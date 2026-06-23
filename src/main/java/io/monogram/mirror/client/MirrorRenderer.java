package io.monogram.mirror.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
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
import com.mojang.blaze3d.vertex.PoseStack;
import io.monogram.mirror.MirrorBlock;
import io.monogram.mirror.client.mixin.CameraInvoker;
import io.monogram.mirror.client.mixin.GameRendererAccessor;
import io.monogram.mirror.client.mixin.LevelExtractorAccessor;
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
import org.joml.Vector4fc;
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
    private static boolean loggedError = false;
    private static boolean rendering = false;

    /** True while a reflection is being rendered into an FBO - the mirror's own block-entity renderer skips
     *  drawing during this so the mirror never appears in its own reflection (no clip-plane precision games). */
    public static boolean isRendering() {
        return rendering;
    }

    /** Whether any mirror cells were found near the camera (the composite/depth-seed only run if so). */
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
                // 26.2: samplers + uniforms are declared via a BindGroupLayout, not flat builder calls.
                .withBindGroupLayout(BindGroupLayout.builder()
                    .withSampler("InSampler")
                    .withUniform("MirrorProj", UniformType.UNIFORM_BUFFER)
                    .build())
                .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
                // Depth-test (no write) against the scene so the mirror is occluded by blocks and entities in
                // front of it. 26.2 uses REVERSE-Z depth (near = 1, far = 0), so vanilla world pipelines test
                // GREATER_THAN_OR_EQUAL - match it, or the test is backwards and the quad draws over everything
                // (the cursed-branch over-draw). The quad is biased a hair toward the camera in the vertex
                // shader (gl_Position.z += bias*w, larger depth in reverse-Z) to win over the mirror block's
                // own coplanar glass face without z-fighting.
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                // No vertex bindings: the shader pulls its 6 quad vertices by gl_VertexID.
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        }
        return projectPipeline;
    }

    /**
     * Same as {@link #projectPipeline()} but with NO depth test - used to composite a nested (depth-2)
     * reflection onto a parent reflection's FBO, whose own depth buffer the framegraph has discarded
     * (so there's nothing to occlude against). The nested mirror surface is unobstructed in practice.
     */
    private static RenderPipeline projectPipelineNoDepth() {
        if (projectPipelineNoDepth == null) {
            projectPipelineNoDepth = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("mirror", "pipeline/mirror_project_nodepth"))
                .withVertexShader(Identifier.fromNamespaceAndPath("mirror", "core/mirror_project"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("mirror", "core/mirror_project"))
                .withBindGroupLayout(BindGroupLayout.builder()
                    .withSampler("InSampler")
                    .withUniform("MirrorProj", UniformType.UNIFORM_BUFFER)
                    .build())
                .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
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
    private static Matrix4f obliqueProjection(Matrix4f proj, Matrix4f viewRot, Vec3 n, Vec3 p, Vec3 v) {
        // Clip plane in the virtual camera's view space; kept side = the mirror's front (player) side.
        Vector3f nView = viewRot.transformDirection(new Vector3f((float) n.x, (float) n.y, (float) n.z));
        float cw = -(float) (n.x * (p.x - v.x) + n.y * (p.y - v.y) + n.z * (p.z - v.z));
        float cx = nView.x, cy = nView.y, cz = nView.z;

        Matrix4f ob = new Matrix4f(proj);
        float qx = (Math.signum(cx) + ob.m20()) / ob.m00();
        float qy = (Math.signum(cy) + ob.m21()) / ob.m11();
        float qw = (1.0f + ob.m22()) / ob.m32();
        float s = 2.0f / (cx * qx + cy * qy - cz + cw * qw);
        ob.m02(cx * s);
        ob.m12(cy * s);
        ob.m22(cz * s + 1.0f);
        ob.m32(cw * s);
        return ob;
    }

    /**
     * Renders all visible mirror reflections. Called right after {@code renderAllFeatures} in
     * {@code GameRenderer.renderLevel}: the third-person player and held item are now drawn (so our
     * world re-render no longer steals them), and the top-level composite depth-tests against the main
     * render target's own depth (which still holds the scene at this point) so reflections stay occluded.
     */
    public static void renderAll() {
        if (rendering) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Camera camera = mc.gameRenderer.mainCamera();
        if (level == null || camera == null || mc.player == null) {
            return;
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

        try {
            Vec3 camPos = camera.position();

            // Capture the MAIN camera's matrices ONCE, here, before any group's reflection overwrites
            // cameraRenderState with its virtual camera (via extractRenderState; extractLevel does NOT restore
            // it). Otherwise the 2nd+ plane reads the previous group's virtual state and composites its quad
            // off-position. cameraRenderState.pos is the terrain-matching eye (not getMainCamera().position()).
            GameRenderer gr = mc.gameRenderer;
            GameRendererAccessor gra = (GameRendererAccessor) (Object) gr;
            LevelRenderState lrs = gr.gameRenderState().levelRenderState;
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
            RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
            // Render the NEAREST on-screen planes first so the closest mirrors always get the budget
            // (cfg.maxReflections), not whichever the scan happened to list first - matters now that mirrors are
            // found out to render distance and many can be on screen at once.
            List<List<MirrorSurface>> ordered = new ArrayList<>(groups.values());
            ordered.sort(java.util.Comparator.comparingDouble(g -> g.get(0).center().distanceToSqr(eye)));
            int planesRendered = 0;
            rendering = true;
            passes = 0;
            try {
                for (List<MirrorSurface> group : ordered) {
                    if (planesRendered >= cfg.maxReflections) {
                        break; // bound top-level planes per frame
                    }
                    MirrorSurface rep = group.get(0); // any cell defines the shared plane
                    boolean onScreen = false;
                    for (MirrorSurface s : group) {
                        if (mirrorScreenRect(s, eye, mainView, mainProj, mainTarget.width, mainTarget.height) != null) {
                            onScreen = true;
                            break;
                        }
                    }
                    if (!onScreen) {
                        continue; // the whole merged mirror is off-screen
                    }
                    planesRendered++; // count only on-screen planes that consume the budget
                    // Level 1: render the world this mirror reflects into its FBO.
                    Reflected r1 = renderWorldToFbo(mc, gra, lrs, eye, camera.yRot(), camera.xRot(), rep, mainProj, 1, dt, partial);
                    if (r1 == null) {
                        continue; // pass budget exhausted
                    }

                    // Level 2 (one bounce): for each mirror visible INSIDE r1's reflection, render its own
                    // reflection and composite it onto r1's FBO - so a mirror-in-a-mirror shows a reflection.
                    if (cfg.recursionDepth >= 2) {
                        // Place nested quads with fbo1's EXACT projection (oblique * its view) so the quad
                        // lands where fbo1 drew the nested mirror AND its depth matches fbo1's depth buffer.
                        Matrix4f place1 = new Matrix4f(r1.obliqueProj()).mul(r1.fboViewRot());
                        // Occlude the nested composite against the level-1 FBO's own depth (same reasoning as the
                        // top-level: that FBO holds its rendered depth here, no separate snapshot needed).
                        var nestedDepth = r1.fbo().getDepthTextureView();
                        int nplanes = 0;
                        for (List<MirrorSurface> ng : visibleGroups(r1.cam().position(), planeKey(rep)).values()) {
                            if (nplanes++ >= cfg.nestedPerParent) {
                                break;
                            }
                            MirrorSurface nrep = ng.get(0);
                            Reflected r2 = renderWorldToFbo(mc, gra, lrs, r1.cam().position(),
                                r1.cam().yRot(), r1.cam().xRot(), nrep, mainProj, 2, dt, partial);
                            if (r2 == null) {
                                break;
                            }
                            Matrix4f sample2 = new Matrix4f(mainProj).mul(r2.fboViewRot());
                            for (MirrorSurface s : ng) {
                                composite(r1.fbo().getColorTextureView(), r2.fbo(), place1, sample2, cellCorners(s),
                                    r1.cam().position(), r2.cam().position(), nestedDepth); // occlude against fbo1's depth
                            }
                        }
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
                // renderWorldToFbo leaves the extractor on the last virtual camera - re-extract for the main
                // view once so the rest of the frame is correct. Keep `rendering` true across this restore
                // extract so the consumeFrustumUpdate suppression still covers it (else this 3rd extract eats
                // the main view's pending per-frame frustum-update signal and the main view goes a frame stale,
                // cutting off as you turn). extract()'s own applyFrustum is gated on that consumed signal, so
                // force the MAIN frustum back explicitly or the main view stays culled to the reflected frustum.
                mc.levelExtractor.extract(dt, camera, partial);
                ((LevelExtractorAccessor) mc.levelExtractor).mirror$applyFrustum(camera.getCullFrustum());
                if (reflectionFog != null) {
                    reflectionFog.endFrame(); // recycle our fog ring buffer for next frame
                }
                rendering = false;
            }
        } catch (Throwable t) {
            if (!loggedError) {
                loggedError = true;
                LOGGER.error("[mirror] reflection pass threw", t);
            }
        }
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
        RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
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
        // STANDARD-Z perspective (near, far in normal order). This drives both the cull frustum
        // (prepareCullFrustum needs a normal, non-inverted frustum or offsetToFullyIncludeCameraCube can't
        // include the camera cube → freeze) AND the oblique near-plane clip (Lengyel math is derived for a
        // standard-Z projection). The final render projection is converted to 26.2's reverse-Z below, after
        // the oblique is applied, so the reflection's terrain still depth-sorts under the GREATER_THAN_OR_EQUAL
        // world pipelines. (Feeding a reverse-Z projection here broke both: the freeze and the oblique clip.)
        boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        Matrix4f reflProj = new Matrix4f().setPerspective(
            2.0f * (float) Math.atan(1.0 / mainProj.m11()), mainProj.m11() / mainProj.m00(),
            0.05f, far, zZeroToOne);
        inv.mirror$prepareCullFrustum(vView, reflProj, virtual.position());
        // Mark this plane as the one being reflected, so its OWN block-entity model is skipped during the
        // extract+render below (a mirror must not draw inside its own reflection) while OTHER mirrors still draw.
        long prevPlaneKey = reflectingPlaneKey;
        reflectingPlaneKey = planeKey(plane);
        mc.levelExtractor.extract(dt, virtual, partial);
        // extract()'s own applyFrustum is gated on the per-frame occlusion signal we suppress during the pass,
        // so the reflection's extract skips it and the section set stays on the MAIN frustum. Force the virtual
        // frustum so the reflection culls its OWN sections (else distant sections pop as the main view turns).
        ((LevelExtractorAccessor) mc.levelExtractor).mirror$applyFrustum(virtual.getCullFrustum());
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
        Matrix4f oblique = obliqueProjection(reflProj, cam.viewRotationMatrix, plane.normal(), plane.center(), virtual.position());
        // Convert the standard-Z oblique to 26.2 REVERSE-Z for the actual render, so the reflection's terrain
        // sorts NEAR-over-far under the GREATER_THAN_OR_EQUAL world pipelines (without this the depth stays
        // standard-Z and far draws over near - the "drawing farther things on top of closer things" bug). The
        // conversion depends on the clip-space depth range: for [0,1] (z' = w - z) m22→-1-m22; for [-1,1]
        // (z' = -z) m22→-m22. Both negate m02/m12/m32. (w row of a perspective matrix is (0,0,-1,0).)
        oblique.m02(-oblique.m02());
        oblique.m12(-oblique.m12());
        oblique.m32(-oblique.m32());
        oblique.m22(zZeroToOne ? (-1.0f - oblique.m22()) : -oblique.m22());
        cam.projectionMatrix.set(oblique);
        GpuBufferSlice savedProj = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedType = RenderSystem.getProjectionType();
        GpuBufferSlice savedShaderFog = RenderSystem.getShaderFog(); // renderLevel overwrites this global slice; restore it
        RenderSystem.setProjectionMatrix(projBuffer().getBuffer(oblique), ProjectionType.PERSPECTIVE);

        // Clear the FBO to the fog colour (the redirected render leaves stale garbage where geometry misses).
        // 26.2 takes a Vector4fc clear colour; force alpha 1 so the clear is opaque. Depth clears to 0.0, not
        // 1.0: reverse-Z puts the far plane at 0, so terrain's GREATER_THAN_OR_EQUAL test passes against it.
        Vector4f cc = cam.fogData.color;
        Vector4f clearColor = new Vector4f(cc.x, cc.y, cc.z, 1.0f);
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(fbo.getColorTexture(), clearColor, fbo.getDepthTexture(), 0.0);

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
            // 26.2 consolidated bookkeeping + draw into LevelRenderer.render (no precomputed section list arg).
            // It re-runs the once-per-frame chunk bookkeeping; the mixins (SectionOcclusionGraph/ViewArea) cancel
            // the parts that would corrupt the main view, gated on MirrorFbo.redirect.
            mc.levelRenderer.render(gra.mirror$resourcePool(), dt, false, cam, cam.viewRotationMatrix,
                terrainFog, cam.fogData.color, true);
        } finally {
            MirrorFbo.redirect = prevRedirect;
            MirrorFbo.target = prevTarget;
            renderingDepth = prevDepth;
            RenderSystem.setGlobalSettingsUniform(savedGlobals);
            mirrorGlobals.close();
            RenderSystem.setProjectionMatrix(savedProj, savedType);
            RenderSystem.setShaderFog(savedShaderFog); // restore the main view's fog slice (renderLevel clobbered it)
            mvStack.popMatrix();
            reflectingPlaneKey = prevPlaneKey; // restore (handles the nested depth-2 render too)
        }
        return new Reflected(virtual, fboViewRot, oblique, fbo);
    }

    /** Mirror cells facing toward {@code eye}, grouped by plane; the parent plane is excluded. */
    private static Map<Long, List<MirrorSurface>> visibleGroups(Vec3 eye, long excludeKey) {
        Map<Long, List<MirrorSurface>> g = new LinkedHashMap<>();
        for (MirrorSurface m : cached) {
            long k = planeKey(m);
            if (k == excludeKey) {
                continue; // don't reflect the parent mirror in itself
            }
            if (eye.subtract(m.center()).dot(m.normal()) <= 0.0) {
                continue; // faces away from this viewer
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

    /** Plane key of the mirror currently being reflected into an FBO (so its OWN block-entity model is skipped
     *  during that render - a mirror must not appear in its own reflection). NO_PLANE when not rendering. */
    private static final long NO_PLANE = Long.MIN_VALUE;
    private static long reflectingPlaneKey = NO_PLANE;

    /**
     * Whether {@link MirrorBlockEntityRenderer} should skip drawing the mirror at {@code pos}/{@code facing}
     * right now. Only the plane being reflected is skipped (it would self-reflect); OTHER mirrors are drawn so
     * they appear (frame + glass) inside this reflection, and their own reflections composite via recursion.
     */
    public static boolean skipInReflection(BlockPos pos, net.minecraft.core.Direction facing) {
        if (!rendering) {
            return false;
        }
        return planeKey(MirrorSurface.single(pos, facing, false, false, false, false)) == reflectingPlaneKey;
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
        // With a depthView: depth-test (LEQUAL) so the surface is occluded by closer geometry. Without one
        // (nested composite onto a reflection FBO, whose depth the framegraph discarded): draw unconditionally.
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (ubo; RenderPass pass = depthView != null
                ? enc.createRenderPass(() -> "mirror_composite", targetColor, Optional.<Vector4fc>empty(),
                    depthView, OptionalDouble.empty())
                : enc.createRenderPass(() -> "mirror_composite", targetColor, Optional.<Vector4fc>empty())) {
            pass.setPipeline(depthView != null ? projectPipeline() : projectPipelineNoDepth());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("MirrorProj", ubo.slice());
            // LINEAR (not NEAREST): when the mirror is distant it covers few screen pixels, so each pixel
            // minifies many FBO texels. NEAREST picks one at random and turns MC's dithered sky into
            // shimmering diagonal bands; bilinear averages them into a smooth reflection.
            pass.bindTexture("InSampler", sampleFbo.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(6, 1, 0, 0); // 26.2: draw(vertexCount, instanceCount, firstVertex, firstInstance)
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
        for (Vec3 corner : corners) {
            Vector4f v = new Vector4f((float) (corner.x - camPos.x), (float) (corner.y - camPos.y),
                (float) (corner.z - camPos.z), 1.0f);
            view.transform(v);
            proj.transform(v);
            if (v.w <= 1e-4f) {
                return null;
            }
            float sx = (v.x / v.w * 0.5f + 0.5f) * w;
            float sy = (v.y / v.w * 0.5f + 0.5f) * h;
            minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
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
                    if (out.size() >= MAX_MIRRORS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }
}
