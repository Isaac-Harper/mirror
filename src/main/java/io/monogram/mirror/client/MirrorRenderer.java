package io.monogram.mirror.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.monogram.mirror.MirrorBlock;
import io.monogram.mirror.client.mixin.CameraInvoker;
import io.monogram.mirror.client.mixin.GameRendererAccessor;
import io.monogram.mirror.client.mixin.LevelRendererAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Native planar reflection. Coplanar same-facing mirror cells are grouped onto a shared plane; per
 * plane we render the reflected world (incl. the player) from a reflected virtual camera into an
 * offscreen FBO once, then composite it onto each cell's quad by projective texturing (correct at any
 * viewing angle). The pass is split across the frame: {@link #captureSceneDepth} snapshots the scene
 * depth mid-frame (while the deferred pipeline still has it), and {@link #renderAll} renders and
 * composites at the end of the world render - after the third-person player is drawn - depth-testing
 * against that snapshot so reflections stay occluded. See the mixins for the exact hook points.
 */
public final class MirrorRenderer {
    private MirrorRenderer() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    // Mirrors are found by iterating loaded chunks' block entities out to the render distance (findNearbyMirrors)
    // - cheap at any range (no cubic block scan), so reflections work as far as the chunks are loaded. The
    // budget knobs (planes / depth / nested / passes / distance / fog) are user-tunable via MirrorConfig
    // (Mod Menu settings screen, persisted to config/mirror.json); read fresh each frame so edits apply live.
    private static final int MAX_MIRRORS = 256; // mirror CELLS collected per scan (a merged mirror is many cells)
    private static final int RESCAN_INTERVAL = 40;

    private static int frame = 0;
    private static int passes = 0;              // full reflection renders so far this frame (budget guard)
    private static int renderingDepth = 0;      // recursion depth of the reflection currently being rendered
    private static List<MirrorSurface> cached = List.of();
    private static Level lastLevel;             // detects world/dimension changes (drop the stale cache)
    private static boolean loggedError = false;
    private static boolean rendering = false;

    /** True while a reflection is being rendered into an FBO - the mirror's own block-entity renderer skips
     *  drawing during this so the mirror never appears in its own reflection (no clip-plane precision games). */
    public static boolean isRendering() {
        return rendering;
    }

    /** True when the last scan found mirrors nearby - gates the hand-depth seed in GameRendererMixin. */
    public static boolean hasMirrors() {
        return !cached.isEmpty();
    }

    /** Reflection-only fog renderer + scratch data - separate from the main view's so its ring buffer is never
     *  touched (writing the shared ring extra times wraps it and bleeds the close reflection fog into the world). */
    private static FogRenderer reflectionFog;
    private static final FogData reflectionFogData = new FogData();

    private static FogRenderer reflectionFog() {
        if (reflectionFog == null) {
            reflectionFog = new FogRenderer();
        }
        return reflectionFog;
    }
    private static RenderPipeline projectPipeline;
    private static RenderPipeline projectPipelineNoDepth;
    private static ProjectionMatrixBuffer projBuffer;

    /**
     * Projective-textured mirror: draws the mirror face as a quad in the main view and samples the
     * reflection FBO at each surface point's projection through the virtual camera. Correct at any
     * viewing angle (unlike a fixed screen-space flip, which is only right head-on).
     */
    private static RenderPipeline projectPipeline() {
        if (projectPipeline == null) {
            projectPipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("mirror", "pipeline/mirror_project"))
                .withVertexShader(Identifier.fromNamespaceAndPath("mirror", "core/mirror_project"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("mirror", "core/mirror_project"))
                .withSampler("InSampler")
                .withUniform("MirrorProj", UniformType.UNIFORM_BUFFER)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT), 7))
                // Depth-test (LEQUAL, no write) against the scene so the mirror is occluded by blocks
                // and entities in front of it; the quad is nudged a hair toward the camera to win over
                // the mirror block's own front face without z-fighting.
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withCull(false)
                .build();
        }
        return projectPipeline;
    }

    /**
     * Same as {@link #projectPipeline()} but with NO depth test - fallback for compositing a nested
     * reflection onto a parent reflection's FBO when that parent's depth capture is missing (its own
     * depth buffer the framegraph has discarded, so there's nothing to occlude against).
     */
    private static RenderPipeline projectPipelineNoDepth() {
        if (projectPipelineNoDepth == null) {
            projectPipelineNoDepth = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("mirror", "pipeline/mirror_project_nodepth"))
                .withVertexShader(Identifier.fromNamespaceAndPath("mirror", "core/mirror_project"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("mirror", "core/mirror_project"))
                .withSampler("InSampler")
                .withUniform("MirrorProj", UniformType.UNIFORM_BUFFER)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT), 7))
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withCull(false)
                .build();
        }
        return projectPipelineNoDepth;
    }

    private static ProjectionMatrixBuffer projBuffer() {
        if (projBuffer == null) {
            projBuffer = new ProjectionMatrixBuffer("mirror");
        }
        return projBuffer;
    }

    /** Oblique near-plane projection (Lengyel): bends `proj` so its near plane is the mirror plane. */
    private static Matrix4f obliqueProjection(Matrix4f proj, Matrix4f viewRot, Vec3 n, Vec3 p, Vec3 v,
                                              boolean zZeroToOne) {
        // Clip plane in the virtual camera's view space; kept side = the mirror's front (player) side.
        Vector3f nView = viewRot.transformDirection(new Vector3f((float) n.x, (float) n.y, (float) n.z));
        float cw = -(float) (n.x * (p.x - v.x) + n.y * (p.y - v.y) + n.z * (p.z - v.z));
        float cx = nView.x, cy = nView.y, cz = nView.z;

        Matrix4f ob = new Matrix4f(proj);
        // Q = the far-plane corner nearest the clip plane, recovered through P's inverse. The far corner is
        // clip (±1, ±1, 1, 1) in BOTH depth conventions (z_ndc = 1 at far either way), so qx/qy/qw hold too.
        float qx = (Math.signum(cx) + ob.m20()) / ob.m00();
        float qy = (Math.signum(cy) + ob.m21()) / ob.m11();
        float qw = (1.0f + ob.m22()) / ob.m32();
        float cq = cx * qx + cy * qy - cz + cw * qw; // C·Q
        // Replace the z row so the near plane IS the clip plane while the far plane still touches Q.
        // [-1,1]: near is z_clip = -w, so row2' = s*C + w-row gives C·v = 0 -> z_ndc = -1, with s = 2/(C·Q).
        // [0,1]:  near is z_clip = 0,  so row2' = s*C alone gives C·v = 0 -> z_ndc = 0,  with s = 1/(C·Q).
        float s = (zZeroToOne ? 1.0f : 2.0f) / cq;
        ob.m02(cx * s);
        ob.m12(cy * s);
        ob.m22(zZeroToOne ? cz * s : cz * s + 1.0f);
        ob.m32(cw * s);
        return ob;
    }

    /**
     * Copies a render target's depth while it's still valid - during its framegraph, via the
     * AFTER_TRANSLUCENT_FEATURES event (the framegraph discards the target's depth once it resolves).
     * For the MAIN pass this is the scene depth the top-level composite tests against; while rendering a
     * reflection (the redirect target IS the reflection FBO here) it's that level's depth, which the
     * next-deeper composite tests against. The deepest level's depth isn't consumed, so it's skipped.
     */
    public static void captureSceneDepth(LevelRenderContext ctx) {
        if (cached.isEmpty()) {
            return; // no mirrors nearby - nothing will use a captured depth this frame
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        RenderTarget tgt = mc.getMainRenderTarget(); // == the redirect FBO while a reflection is rendering
        if (!rendering) {
            MirrorFbo.getOrCreateSceneDepth(tgt.width, tgt.height).copyDepthFrom(tgt);
        } else if (renderingDepth < MirrorConfig.get().recursionDepth) {
            MirrorFbo.getOrCreateReflectionDepth(renderingDepth, tgt.width, tgt.height).copyDepthFrom(tgt);
        }
    }

    /**
     * Renders all visible mirror reflections. Called right after {@code renderAllFeatures} in
     * {@code GameRenderer.renderLevel}: the third-person player and held item are now drawn (so our
     * world re-render no longer steals them), and we depth-test the composite against the scene depth
     * captured earlier by {@link #captureSceneDepth} (the live depth buffer is already gone by here).
     */
    public static void renderAll() {
        if (rendering) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (level == null || camera == null || mc.player == null) {
            return;
        }

        if (level != lastLevel) {
            // New world or dimension: the cached positions belong to the old one, and a fresh world
            // deserves fresh reflection-error diagnostics.
            lastLevel = level;
            cached = List.of();
            loggedError = false;
            frame = 0; // rescan immediately below
        }
        if (frame++ % RESCAN_INTERVAL == 0) {
            cached = findNearbyMirrors(level, camera.position());
        }
        if (cached.isEmpty()) {
            return;
        }

        DeltaTracker dt = mc.getDeltaTracker();
        float partial = dt.getGameTimeDeltaPartialTick(false);
        MirrorConfig cfg = MirrorConfig.get();
        // Free level buffers beyond the configured depth (no-op unless the user lowered recursionDepth),
        // here because destroying GPU buffers must happen on the render thread.
        MirrorFbo.trim(cfg.recursionDepth);

        try {
            Vec3 camPos = camera.position();

            // Capture the MAIN camera's matrices ONCE, here, before any group's reflection overwrites
            // cameraRenderState with its virtual camera (via extractRenderState; extractLevel does NOT restore
            // it). Otherwise the 2nd+ plane reads the previous group's virtual state and composites its quad
            // off-position. cameraRenderState.pos is the terrain-matching eye (not getMainCamera().position()).
            GameRenderer gr = mc.gameRenderer;
            GameRendererAccessor gra = (GameRendererAccessor) (Object) gr;
            LevelRenderState lrs = gr.getGameRenderState().levelRenderState;
            Vec3 cs = lrs.cameraRenderState.pos;
            Vec3 eye = new Vec3(cs.x, cs.y, cs.z);
            Matrix4f mainView = new Matrix4f(lrs.cameraRenderState.viewRotationMatrix);
            Matrix4f mainProj = new Matrix4f(lrs.cameraRenderState.projectionMatrix);
            // View-bobbing is folded into the projection as projection*bobPose; recompute it so the quad bobs
            // in lockstep with the terrain (see the composite). bobHurt always, bobView when the option is on.
            Matrix4f mainProjBob = new Matrix4f(mainProj);
            PoseStack bobPose = new PoseStack();
            gra.mirror$bobHurt(lrs.cameraRenderState, bobPose);
            if (mc.options.bobView().get()) {
                gra.mirror$bobView(lrs.cameraRenderState, bobPose);
            }
            mainProjBob.mul(bobPose.last().pose());

            // Group coplanar, same-facing mirror cells onto a shared plane. Adjacent mirrors merge into one
            // larger mirror, and every cell on a plane shows the SAME reflection - so we render that
            // reflection ONCE per plane and composite each cell from it, instead of one pass per block.
            Map<Long, List<MirrorSurface>> groups = new LinkedHashMap<>();
            for (MirrorSurface m : cached) {
                BlockState bs = level.getBlockState(m.pos());
                if (!(bs.getBlock() instanceof MirrorBlock) || bs.getValue(MirrorBlock.FACING) != m.facing()) {
                    continue; // block removed/replaced since the last scan - don't render a ghost reflection
                }
                if (camPos.subtract(m.center()).dot(m.normal()) <= 0.0) {
                    continue; // face points away
                }
                groups.computeIfAbsent(planeKey(m), k -> new ArrayList<>()).add(m);
            }
            RenderTarget mainTarget = mc.getMainRenderTarget();
            // Render the NEAREST on-screen planes first so the closest mirrors always get the budget
            // (cfg.maxReflections), not whichever the scan happened to list first - matters now that mirrors are
            // found out to render distance and many can be on screen at once.
            List<List<MirrorSurface>> ordered = new ArrayList<>(groups.values());
            ordered.sort(java.util.Comparator.comparingDouble(g -> g.get(0).center().distanceToSqr(eye)));
            // Filter to the on-screen planes BEFORE rendering, so each plane can reserve one pass for every
            // plane still waiting behind it - otherwise a near mirror's recursion eats the whole pass budget
            // and farther mirrors get no level-1 render at all, flickering in and out as the camera moves.
            List<List<MirrorSurface>> onScreen = new ArrayList<>();
            for (List<MirrorSurface> group : ordered) {
                if (onScreen.size() >= cfg.maxReflections) {
                    break; // bound top-level planes per frame
                }
                for (MirrorSurface s : group) {
                    // Test with the BOBBED projection - the quad is placed with it (place0), so testing the
                    // unbobbed one can disagree at the screen edge for a frame while walking.
                    if (mirrorScreenRect(s, eye, mainView, mainProjBob, mainTarget.width, mainTarget.height) != null) {
                        onScreen.add(group);
                        break;
                    }
                }
            }
            rendering = true;
            passes = 0;
            try {
                for (int i = 0; i < onScreen.size(); i++) {
                    List<MirrorSurface> group = onScreen.get(i);
                    MirrorSurface rep = group.get(0); // any cell defines the shared plane
                    // Render the world this mirror reflects (level 1) and recursively decorate it with any
                    // mirror-in-mirror bounces down to cfg.recursionDepth (see renderReflection). Recursion may
                    // only spend passes the planes still waiting after this one won't need for their level 1.
                    int reserved = onScreen.size() - i - 1;
                    Reflected r1 = renderReflection(mc, gra, lrs, eye, camera.yRot(), camera.xRot(), rep, mainProj, 1, dt, partial, reserved);
                    if (r1 == null) {
                        continue; // pass budget exhausted
                    }

                    // Composite r1's (now possibly nested-decorated) reflection onto the screen.
                    Matrix4f place0 = new Matrix4f(mainProjBob).mul(mainView);
                    Matrix4f sample1 = new Matrix4f(mainProj).mul(r1.fboViewRot());
                    // Occlude against the main target's depth: GameRendererMixin re-seeded it with the captured
                    // world depth before the hand drew, so by now it holds world + held-item depth - the
                    // reflection is hidden behind both walls and the item in hand. (sceneDepth alone lacks the
                    // hand, which is drawn after the framegraph that sceneDepth was copied from.)
                    var depthView = mainTarget.getDepthTextureView();
                    for (MirrorSurface s : group) {
                        composite(mainTarget.getColorTextureView(), r1.fbo(), place0, sample1, cellCorners(s),
                            eye, r1.cam().position(), depthView);
                    }
                }
            } finally {
                rendering = false;
                // renderWorldToFbo leaves LevelRenderer on the last virtual camera - restore the main view
                // (visible-section set + camera render state) once, so the rest of the frame is correct.
                ((LevelRendererAccessor) mc.levelRenderer).mirror$applyFrustum(camera.getCullFrustum());
                mc.levelRenderer.extractLevel(dt, camera, partial);
                if (reflectionFog != null) {
                    reflectionFog.endFrame(); // recycle our fog ring buffer for next frame
                }
            }
        } catch (Throwable t) {
            if (!loggedError) {
                loggedError = true;
                LOGGER.error("[mirror] reflection pass threw", t);
            }
        }
    }

    /**
     * Render {@code rep}'s reflection at recursion {@code depth} into its level FBO, then for each mirror
     * visible INSIDE that reflection (up to {@code cfg.nestedPerParent}) recurse one level deeper and
     * composite the child's reflection onto this one - so a mirror seen in a mirror shows its own reflection,
     * out to {@code cfg.recursionDepth} bounces (a hall of mirrors). Depth-first: each level's FBO is fully
     * composited upward before a sibling at the same level reuses it. Returns null when the per-frame pass
     * budget ({@code cfg.maxRenderPasses}) is exhausted, which also bounds the total recursion.
     * {@code reserved} passes are kept back for the top-level planes still waiting behind this one, so deep
     * recursion on a near mirror can't starve a farther mirror of its level-1 render (visible flicker).
     */
    private static Reflected renderReflection(Minecraft mc, GameRendererAccessor gra, LevelRenderState lrs,
            Vec3 viewerEye, float viewerYaw, float viewerPitch, MirrorSurface rep, Matrix4f mainProj,
            int depth, DeltaTracker dt, float partial, int reserved) {
        Reflected r = renderWorldToFbo(mc, gra, lrs, viewerEye, viewerYaw, viewerPitch, rep, mainProj, depth, dt, partial);
        if (r == null) {
            return null; // pass budget exhausted
        }
        MirrorConfig cfg = MirrorConfig.get();
        if (depth < cfg.recursionDepth) {
            // Place nested quads with this FBO's EXACT projection (oblique * its view) so each lands where this
            // level drew the nested mirror, and occlude them against this level's captured depth (the FBO's
            // own depth is discarded once its framegraph resolves - see captureSceneDepth).
            Matrix4f place = new Matrix4f(r.obliqueProj()).mul(r.fboViewRot());
            TextureTarget levelDepth = MirrorFbo.reflectionDepth(depth);
            var nestedDepth = levelDepth != null ? levelDepth.getDepthTextureView() : null;
            // Nearest-first from the virtual eye (like the top level), not cache scan order - scan order
            // changes on every rescan, making WHICH nested mirrors get the bounces flicker every ~40 frames.
            List<List<MirrorSurface>> nested = new ArrayList<>(visibleGroups(mc.level, r.cam().position(), rep).values());
            nested.sort(java.util.Comparator.comparingDouble(g -> g.get(0).center().distanceToSqr(r.cam().position())));
            int nplanes = 0;
            for (List<MirrorSurface> ng : nested) {
                if (nplanes++ >= cfg.nestedPerParent) {
                    break;
                }
                if (passes >= cfg.maxRenderPasses - reserved) {
                    break; // the remaining budget belongs to the top-level planes still waiting
                }
                MirrorSurface nrep = ng.get(0);
                Reflected child = renderReflection(mc, gra, lrs, r.cam().position(),
                    r.cam().yRot(), r.cam().xRot(), nrep, mainProj, depth + 1, dt, partial, reserved);
                if (child == null) {
                    break; // budget exhausted - stop descending this branch
                }
                Matrix4f sample = new Matrix4f(mainProj).mul(child.fboViewRot());
                for (MirrorSurface s : ng) {
                    composite(r.fbo().getColorTextureView(), child.fbo(), place, sample, cellCorners(s),
                        r.cam().position(), child.cam().position(), nestedDepth); // occlude against this FBO's depth
                }
            }
        }
        return r;
    }

    /**
     * One mirror plane's reflected world rendered into an FBO: the virtual camera, the exact view matrix
     * used, and the oblique projection used (needed to place a nested quad with depth that matches this
     * FBO's depth buffer).
     */
    private record Reflected(Camera cam, Matrix4f fboViewRot, Matrix4f obliqueProj, TextureTarget fbo) {}

    /**
     * Render the world reflected across {@code plane} (as seen from a viewer at eye/yaw/pitch) into the
     * depth-{@code depth} reflection FBO; return the virtual camera + the exact view matrix it used.
     * Does NOT restore the main view - {@link #renderAll} does that once at the end, so successive and
     * nested calls just overwrite the transient LevelRenderer state. Returns null if the pass budget is hit.
     */
    private static Reflected renderWorldToFbo(Minecraft mc, GameRendererAccessor gra, LevelRenderState lrs,
            Vec3 viewerEye, float viewerYaw, float viewerPitch, MirrorSurface plane, Matrix4f mainProj,
            int depth, DeltaTracker dt, float partial) {
        MirrorConfig cfg = MirrorConfig.get();
        if (passes >= cfg.maxRenderPasses) {
            return null; // hard cap on full world re-renders per frame
        }
        passes++;
        RenderTarget mainTarget = mc.getMainRenderTarget();
        TextureTarget fbo = MirrorFbo.level(depth, mainTarget.width, mainTarget.height);

        // Reflected virtual camera across this plane (position + yaw mirrored, pitch unchanged).
        Camera virtual = new Camera();
        virtual.setLevel(mc.level);
        virtual.setEntity(mc.player);
        CameraInvoker inv = (CameraInvoker) (Object) virtual;
        inv.mirror$setPosition(MirrorReflection.reflectPoint(viewerEye, plane));
        inv.mirror$setRotation(MirrorReflection.reflectYaw(viewerYaw, plane.facing()), viewerPitch);
        inv.mirror$setDetached(true); // render the player in the reflection (third-person)
        virtual.tick();

        Matrix4f vView = new Matrix4f();
        virtual.getViewRotationMatrix(vView);
        // Reflection projection: main view's FoV/aspect with a render-distance far plane so reflections show
        // depth out to where the world fades. Built here (not later) so the SAME far reach also drives the chunk
        // cull frustum below - otherwise the far floor is culled and the reflected floor stops short.
        float far = cfg.farBlocks();
        boolean zZeroToOne = RenderSystem.getDevice().isZZeroToOne();
        Matrix4f reflProj = new Matrix4f().setPerspective(
            2.0f * (float) Math.atan(1.0 / mainProj.m11()), mainProj.m11() / mainProj.m00(),
            0.05f, far, zZeroToOne);
        inv.mirror$prepareCullFrustum(vView, reflProj, virtual.position());
        // Visible-section set for the VIRTUAL frustum (else sections pop as the MAIN view moves/turns).
        ((LevelRendererAccessor) mc.levelRenderer).mirror$applyFrustum(virtual.getCullFrustum());

        mc.levelRenderer.extractLevel(dt, virtual, partial);
        // No weather in reflections: WeatherEffectRenderer's fixed instance buffer overflows when several
        // passes share it, and a mid-renderLevel throw then leaks the model-view stack into a fatal crash.
        lrs.weatherRenderState.reset();
        CameraRenderState cam = lrs.cameraRenderState;
        virtual.extractRenderState(cam, partial); // else entities draw relative to the main camera
        // The composite MUST sample with this exact matrix (not getViewRotationMatrix(), which can differ).
        Matrix4f fboViewRot = new Matrix4f(cam.viewRotationMatrix);
        // Reflection distance fog: clear out to fogStart (config), then fade to the fog colour by fogEnd
        // (≈ render distance) so reflections show depth out to where the real world fades. CRUCIAL: use our OWN
        // FogRenderer, never the main one - its buffer is a per-frame ring, and writing the shared ring extra
        // times here wraps it and clobbers the main view's fog slice (whole-world fog + flicker).
        float fogStart = cfg.fogStartBlocks;
        float fogEnd = cfg.fogEndBlocks();
        FogData rf = reflectionFogData;
        rf.color = cam.fogData.color;
        rf.renderDistanceStart = fogStart;
        rf.renderDistanceEnd = fogEnd;
        rf.environmentalStart = Math.min(cam.fogData.environmentalStart, fogStart);
        rf.environmentalEnd = Math.min(cam.fogData.environmentalEnd, fogEnd);
        // Match the SKY's horizon fog to the FLOOR fog so they reach the fog colour at the same distance - that
        // blends the floor-to-sky seam at the horizon. The upper sky/clouds, being nearer the zenith, stay clear.
        rf.skyEnd = fogEnd;
        rf.cloudEnd = fogEnd;
        FogRenderer reflFog = reflectionFog();
        reflFog.updateBuffer(rf);
        GpuBufferSlice terrainFog = reflFog.getBuffer(FogRenderer.FogMode.WORLD);

        // Oblique near-plane clip: bend the projection so its near plane IS the mirror plane, clipping the WALL
        // the mirror is mounted on (and anything behind the mirror) out of the reflection. The deferred level
        // render reads its projection from cam.projectionMatrix (NOT RenderSystem's), so it MUST be set there.
        // The mirror's OWN block no longer needs clipping here - it's drawn by a block-entity renderer that
        // skips the reflection pass entirely (MirrorBlockEntityRenderer), so it's never in this FBO to begin with.
        // Bend reflProj (built above, with the generous far) into the oblique near-plane clip at the mirror plane.
        Matrix4f oblique = obliqueProjection(reflProj, cam.viewRotationMatrix, plane.normal(), plane.center(), virtual.position(), zZeroToOne);
        cam.projectionMatrix.set(oblique);
        GpuBufferSlice savedProj = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedType = RenderSystem.getProjectionType();
        GpuBufferSlice savedShaderFog = RenderSystem.getShaderFog(); // renderLevel overwrites this global slice; restore it
        RenderSystem.setProjectionMatrix(projBuffer().getBuffer(oblique), ProjectionType.PERSPECTIVE);

        // Clear the FBO to the fog colour (the redirected render leaves stale garbage where geometry misses).
        Vector4f fc = cam.fogData.color;
        int clearArgb = 0xFF000000
            | (Math.round(Math.min(1f, Math.max(0f, fc.x)) * 255f) << 16)
            | (Math.round(Math.min(1f, Math.max(0f, fc.y)) * 255f) << 8)
            | Math.round(Math.min(1f, Math.max(0f, fc.z)) * 255f);
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(fbo.getColorTexture(), clearArgb, fbo.getDepthTexture(), 1.0);

        // Terrain reads the camera position from the Globals UBO (not cam.pos) - rebuild it for the virtual
        // camera so terrain renders camera-relative to the reflection, matching the entities.
        GpuBuffer savedGlobals = RenderSystem.getGlobalSettingsUniform();
        GpuBuffer mirrorGlobals = buildGlobals(virtual.position(), mainTarget.width, mainTarget.height, mc, partial);

        // Reset the model-view stack to identity (renderLevel multiplies the redirected render by the main
        // camera's rotation otherwise, double-transforming entities). Redirect the world render into our FBO.
        var mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.identity();
        TextureTarget prevTarget = MirrorFbo.target;
        boolean prevRedirect = MirrorFbo.redirect;
        int prevDepth = renderingDepth;
        MirrorFbo.target = fbo;
        MirrorFbo.redirect = true;
        renderingDepth = depth; // captureSceneDepth uses this to snapshot the right FBO's depth mid-render
        RenderSystem.setGlobalSettingsUniform(mirrorGlobals);
        try {
            mc.levelRenderer.renderLevel(gra.mirror$resourcePool(), dt, false, cam, cam.viewRotationMatrix,
                terrainFog, cam.fogData.color, true, lrs.chunkSectionsToRender);
        } finally {
            MirrorFbo.redirect = prevRedirect;
            MirrorFbo.target = prevTarget;
            renderingDepth = prevDepth;
            RenderSystem.setGlobalSettingsUniform(savedGlobals);
            mirrorGlobals.close();
            RenderSystem.setProjectionMatrix(savedProj, savedType);
            RenderSystem.setShaderFog(savedShaderFog); // restore the main view's fog slice (renderLevel clobbered it)
            mvStack.popMatrix();
        }
        return new Reflected(virtual, fboViewRot, oblique, fbo);
    }

    /**
     * Mirror cells that can actually appear inside {@code parent}'s reflection, grouped by plane: facing
     * toward {@code eye} (the virtual camera), in FRONT of the parent's plane (anything behind it is
     * removed by the oblique near-plane clip, so rendering it would only waste recursion budget), still
     * present in the world (the cache refreshes every {@link #RESCAN_INTERVAL} frames, so a broken mirror
     * would otherwise keep reflecting inside other mirrors), and not the parent's own plane.
     */
    private static Map<Long, List<MirrorSurface>> visibleGroups(Level level, Vec3 eye, MirrorSurface parent) {
        long excludeKey = planeKey(parent);
        Map<Long, List<MirrorSurface>> g = new LinkedHashMap<>();
        for (MirrorSurface m : cached) {
            long k = planeKey(m);
            if (k == excludeKey) {
                continue; // don't reflect the parent mirror in itself
            }
            if (eye.subtract(m.center()).dot(m.normal()) <= 0.0) {
                continue; // faces away from this viewer
            }
            if (m.center().subtract(parent.center()).dot(parent.normal()) <= 0.0) {
                continue; // behind the parent's plane - the oblique clip cuts it out of this reflection
            }
            BlockState bs = level.getBlockState(m.pos());
            if (!(bs.getBlock() instanceof MirrorBlock) || bs.getValue(MirrorBlock.FACING) != m.facing()) {
                continue; // block removed/replaced since the last scan - don't render a ghost reflection
            }
            g.computeIfAbsent(k, key -> new ArrayList<>()).add(m);
        }
        return g;
    }

    /**
     * The four world-space corners of a mirror cell's glass (nudged a hair toward its front face).
     * Insets per-edge so the quad covers only the glass - it stops at the frame on framed edges and
     * reaches the cell boundary on connected edges (joining the neighbour), so it never leaks onto wood.
     */
    private static Vec3[] cellCorners(MirrorSurface s) {
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 horiz = s.normal().cross(up);
        // Tiny placement offset toward the viewer, just enough to keep the quad off the exact glass plane. The
        // real depth-test win over the coplanar glass face is a constant NDC-space bias in the vertex shader -
        // a world-space offset here would perspective-compress to nothing far out and the z-fight would return.
        Vec3 ctr = s.center().add(s.normal().scale(0.001));
        Vec3 r = horiz.scale(s.rightOff()), l = horiz.scale(-s.leftOff());
        Vec3 u = up.scale(s.upOff()), d = up.scale(-s.downOff());
        return new Vec3[]{
            ctr.add(r).add(u),
            ctr.add(r).add(d),
            ctr.add(l).add(u),
            ctr.add(l).add(d),
        };
    }

    /** Map a surface to its mirror plane: same facing + same plane distance → same key → one render. */
    private static long planeKey(MirrorSurface m) {
        long d = Math.round(m.normal().dot(m.center()) * 16.0); // 1/16-block precision along the normal
        return ((long) m.facing().ordinal() << 32) | (d & 0xFFFFFFFFL);
    }

    /**
     * Globals UBO rebuilt for the virtual camera (CameraBlockPos/CameraOffset + screen/time fields),
     * so terrain renders camera-relative to the reflection - matching the entities. The non-camera
     * fields (glint/time/blur) are best-effort; only the camera position must be exact.
     * Layout mirrors {@link GlobalSettingsUniform#update}.
     */
    private static GpuBuffer buildGlobals(Vec3 camPos, int w, int h, Minecraft mc, float partial) {
        int bx = Mth.floor(camPos.x), by = Mth.floor(camPos.y), bz = Mth.floor(camPos.z);
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, GlobalSettingsUniform.UBO_SIZE)
                .putIVec3(bx, by, bz)
                .putVec3((float) (bx - camPos.x), (float) (by - camPos.y), (float) (bz - camPos.z))
                .putVec2((float) w, (float) h)
                .putFloat(1.0f)
                .putFloat(((float) (gameTime % 24000L) + partial) / 24000.0f)
                .putInt(0)  // MenuBlurRadius
                // UseRgss=1: terrain.fsh anti-aliases its atlas minification with Rotated-Grid Super-Sampling,
                // gated on this flag. With it 0, reflected terrain nearest-samples the atlas and the razor-sharp
                // texel grid beats against the mirror's grazing projective sampling into a regular diagonal stipple.
                // Matching the main view (1) makes reflected terrain filter the same way and kills the stipple.
                .putInt(1)
                .get();
            return RenderSystem.getDevice().createBuffer(() -> "mirror_globals", GpuBuffer.USAGE_UNIFORM, data);
        }
    }

    /** Composite the reflection via a projective-textured mirror quad (correct at any viewing angle). */
    private static void composite(GpuTextureView targetColor, TextureTarget sampleFbo, Matrix4f placeVP,
                                  Matrix4f sampleVP, Vec3[] corners, Vec3 placeEye, Vec3 sampleEye,
                                  GpuTextureView depthView) {
        GpuBuffer ubo;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder b = Std140Builder.onStack(stack, 256)
                .putMat4f(placeVP)
                .putMat4f(sampleVP);
            for (Vec3 c : corners) { // corners relative to the placement (viewer) camera - double-precision subtract
                b.putVec4((float) (c.x - placeEye.x), (float) (c.y - placeEye.y), (float) (c.z - placeEye.z), 1.0f);
            }
            for (Vec3 c : corners) { // corners relative to the virtual (sampled) camera
                b.putVec4((float) (c.x - sampleEye.x), (float) (c.y - sampleEye.y), (float) (c.z - sampleEye.z), 1.0f);
            }
            ubo = RenderSystem.getDevice().createBuffer(() -> "mirror_proj_ubo", GpuBuffer.USAGE_UNIFORM, b.get());
        }
        // Depth-test (no write) so the surface is occluded by closer geometry: the top level tests against
        // the main target's captured scene depth, a nested composite against the parent level's captured
        // depth (the framegraph discards each FBO's live depth once its render resolves).
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (ubo; RenderPass pass = depthView != null
                ? enc.createRenderPass(() -> "mirror_composite", targetColor, OptionalInt.empty(),
                    depthView, OptionalDouble.empty())
                : enc.createRenderPass(() -> "mirror_composite", targetColor, OptionalInt.empty())) {
            pass.setPipeline(depthView != null ? projectPipeline() : projectPipelineNoDepth());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("MirrorProj", ubo.slice());
            // LINEAR (not NEAREST): when the mirror is distant it covers few screen pixels, so each pixel
            // minifies many FBO texels. NEAREST picks one at random and turns MC's dithered sky into
            // shimmering diagonal bands; bilinear averages them into a smooth reflection.
            pass.bindTexture("InSampler", sampleFbo.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 6);
        }
    }

    /** Screen-space pixel bbox (GL bottom-left origin) of the mirror's front face; null = skip. */
    private static int[] mirrorScreenRect(MirrorSurface m, Vec3 camPos, Matrix4f view, Matrix4f proj, int w, int h) {
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 horiz = m.normal().cross(up);
        if (horiz.lengthSqr() < 1e-6) {
            return null;
        }
        Vec3 c = m.center();
        Vec3 r = horiz.scale(m.rightOff()), l = horiz.scale(-m.leftOff());
        Vec3 u = up.scale(m.upOff()), d = up.scale(-m.downOff());
        Vec3[] corners = {
            c.add(r).add(u),
            c.add(r).add(d),
            c.add(l).add(u),
            c.add(l).add(d),
        };
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean anyInFront = false, anyBehind = false;
        for (Vec3 corner : corners) {
            Vector4f v = new Vector4f((float) (corner.x - camPos.x), (float) (corner.y - camPos.y),
                (float) (corner.z - camPos.z), 1.0f);
            view.transform(v);
            proj.transform(v);
            if (v.w <= 1e-4f) {
                anyBehind = true; // this corner is behind the camera - its projection is meaningless
                continue;
            }
            anyInFront = true;
            float sx = (v.x / v.w * 0.5f + 0.5f) * w;
            float sy = (v.y / v.w * 0.5f + 0.5f) * h;
            minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
        }
        if (!anyInFront) {
            return null; // fully behind the camera
        }
        if (anyBehind) {
            // The quad crosses the camera plane (walking close past a mirror's edge): the bbox of the
            // remaining corners is unreliable, so conservatively call it fully visible rather than let the
            // whole plane pop out of existence while most of it is still on screen.
            return new int[]{0, 0, w, h};
        }
        int x = Math.max(0, (int) Math.floor(minX));
        int y = Math.max(0, (int) Math.floor(minY));
        int rw = Math.min(w, (int) Math.ceil(maxX)) - x;
        int rh = Math.min(h, (int) Math.ceil(maxY)) - y;
        if (rw <= 0 || rh <= 0) {
            return null;
        }
        return new int[]{x, y, rw, rh};
    }

    /**
     * Collect every loaded mirror cell out to the render distance by iterating each loaded chunk's block
     * entities (mirrors carry a {@link io.monogram.mirror.MirrorBlockEntity}). This is O(loaded chunks), not
     * O(radius^3) like a block scan, so it stays cheap even at full render distance. Capped at MAX_MIRRORS;
     * actual reflection work is separately bounded by the configured max reflections / render passes.
     */
    private static List<MirrorSurface> findNearbyMirrors(Level level, Vec3 camPos) {
        List<MirrorSurface> out = new ArrayList<>();
        if (!(level.getChunkSource() instanceof net.minecraft.client.multiplayer.ClientChunkCache cache)) {
            return out;
        }
        int ccx = net.minecraft.core.SectionPos.blockToSectionCoord(camPos.x);
        int ccz = net.minecraft.core.SectionPos.blockToSectionCoord(camPos.z);
        int r = Minecraft.getInstance().options.getEffectiveRenderDistance(); // in chunks
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = cache.getChunk(
                    ccx + dx, ccz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue; // not loaded
                }
                for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof io.monogram.mirror.MirrorBlockEntity)) {
                        continue;
                    }
                    BlockState state = be.getBlockState();
                    if (!(state.getBlock() instanceof MirrorBlock)) {
                        continue; // block changed since the BE was created
                    }
                    BlockPos p = be.getBlockPos();
                    out.add(MirrorSurface.single(p, state.getValue(MirrorBlock.FACING),
                        state.getValue(MirrorBlock.UP), state.getValue(MirrorBlock.DOWN),
                        state.getValue(MirrorBlock.LEFT), state.getValue(MirrorBlock.RIGHT)));
                }
            }
        }
        if (out.size() > MAX_MIRRORS) {
            // Keep the NEAREST cells, not whichever the chunk scan met first: the scan order shifts with
            // the camera's chunk, so a first-met cap reshuffles the survivors (and can keep only PART of a
            // merged plane) every rescan - distant mirrors pop in and out in mirror-dense worlds.
            out.sort(java.util.Comparator.comparingDouble(m -> m.center().distanceToSqr(camPos)));
            out = new ArrayList<>(out.subList(0, MAX_MIRRORS));
        }
        return out;
    }
}
