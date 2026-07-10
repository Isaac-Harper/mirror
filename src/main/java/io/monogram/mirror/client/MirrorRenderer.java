package io.monogram.mirror.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
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
import io.monogram.mirror.client.mixin.CloudRendererAccessor;
import io.monogram.mirror.client.mixin.GameRendererAccessor;
import io.monogram.mirror.client.mixin.LevelExtractorAccessor;
import io.monogram.mirror.client.mixin.LevelRendererAccessor;
import io.monogram.mirror.client.mixin.SkyRendererAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Native planar reflection. Coplanar same-facing mirror cells are grouped onto a shared plane; per
 * plane we render the reflected world (incl. the player) from a reflected virtual camera into an
 * offscreen FBO once, then composite it onto each cell's quad by projective texturing (correct at any
 * viewing angle). The pass is split across the frame: {@link MirrorFbo#captureSceneDepth} snapshots the
 * scene depth mid-frame (while the deferred pipeline still has it), and {@link #renderAll} renders and
 * composites at the end of the world render - after the third-person player is drawn - depth-testing
 * against that snapshot so reflections stay occluded. See the mixins for the exact hook points.
 */
public final class MirrorRenderer {
    private MirrorRenderer() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    // Mirrors are found via a client-side registry kept in sync by block-entity load/unload events
    // (see MirrorClient), so discovery is O(mirrors) with no chunk scan. The budget knobs (planes /
    // depth / nested / passes / distance / fog / resolution) are user-tunable via MirrorConfig
    // (Mod Menu settings screen, persisted to config/mirror.json); read fresh each frame so edits apply live.
    private static final int MAX_MIRRORS = 256; // mirror CELLS collected per scan (a merged mirror is many cells)
    private static final int RESCAN_INTERVAL = 40;
    /** Planes whose cells cover fewer screen pixels than this are skipped: at that size the reflection
     *  is a handful of pixels of frame texture, not worth a full world re-render. */
    private static final int MIN_SCREEN_AREA = 32 * 32;
    private static final long ERROR_LOG_INTERVAL_MS = 10_000;

    private static int frame = 0;
    private static int passes = 0;              // full reflection renders so far this frame (budget guard)
    private static List<MirrorSurface> cached = List.of();
    /** Positions of every loaded mirror block entity, maintained by MirrorClient's load/unload hooks.
     *  Client-thread only. */
    private static final Set<BlockPos> knownMirrors = new HashSet<>();
    private static Level lastLevel;             // detects world/dimension changes (drop the stale cache)
    private static long lastErrorLogMs = 0;     // rate-limits reflection-pass error logging
    private static boolean loggedNoDepth = false;
    private static boolean depthExpected = false; // a pass already ran, so a depth snapshot should exist by now
    // Volatile: read by mixins that sit on public API (mainRenderTarget, repositionCamera) which other
    // mods may touch off the render thread; a stale read there must not see mid-pass state.
    private static volatile boolean rendering = false;
    private static volatile boolean virtualCull = false;

    /** True while a reflection is being rendered into an FBO - the mirror's own block-entity renderer skips
     *  drawing during this so the mirror never appears in its own reflection (no clip-plane precision games). */
    public static boolean isRendering() {
        return rendering;
    }

    /** True only while a VIRTUAL camera's cull frustum is in play (the reflection's extract/render), not
     *  during the end-of-pass restore of the MAIN frustum - FrustumMixin keys its freeze-guard off this. */
    public static boolean isVirtualCull() {
        return virtualCull;
    }

    private static volatile boolean skyRedirected = false;

    /** True while SkyRenderer's captured render target has been swapped onto the pass's reflection FBO,
     *  so SkyRendererMixin lets the sky draws through (they land in the mirror's buffer and give the
     *  reflection a real sky) instead of cancelling them to protect the main target. */
    public static boolean isSkyRedirected() {
        return skyRedirected;
    }

    private static volatile boolean cloudsRedirected = false;

    /** True while the dedicated reflection {@link CloudRenderer} is swapped into LevelRenderer, so
     *  LevelRendererCloudMixin lets the cloud pass run for the reflection instead of cancelling it to
     *  protect vanilla's cross-frame cloud state. */
    public static boolean isCloudsRedirected() {
        return cloudsRedirected;
    }

    /** Reflection-only CloudRenderer: own camera memos and ring buffers (so reflections never corrupt
     *  vanilla's), sharing the vanilla instance's loaded cloud texture per pass. */
    private static CloudRenderer reflectionClouds;

    private static CloudRenderer reflectionClouds() {
        if (reflectionClouds == null) {
            reflectionClouds = new CloudRenderer();
        }
        return reflectionClouds;
    }

    /** Whether any mirror cells were found near the camera (the composite/depth-seed only run if so). */
    public static boolean hasMirrors() {
        return !cached.isEmpty();
    }

    /** A mirror block entity appeared in the client level (placed or its chunk loaded). */
    public static void onMirrorLoaded(BlockPos pos) {
        knownMirrors.add(pos.immutable());
    }

    /** A mirror block entity left the client level (broken or its chunk unloaded). */
    public static void onMirrorUnloaded(BlockPos pos) {
        knownMirrors.remove(pos);
    }

    /** Disconnect: drop every world-tied reference and free the screen-sized GPU targets - without this
     *  the last ClientLevel and several full-resolution buffers survive into the menus. */
    public static void onClientDisconnect() {
        cached = List.of();
        knownMirrors.clear();
        lastLevel = null;
        depthExpected = false;
        MirrorFbo.releaseAll();
        if (reflectionClouds != null) {
            reflectionClouds.close(); // its ring buffers; recreated on demand next time
            reflectionClouds = null;
        }
    }

    /** Reflection-only fog scratch data + its UBO - separate from the main FogRenderer so its per-frame
     *  ring is never touched (writing the shared ring extra times wraps it and bleeds the close reflection
     *  fog into the world). Recreated with its contents per pass: Apple's GL layer has unreliable ordering
     *  for buffer rewrites between draws, so an immutable create-with-data is the safe transport. */
    private static final FogData reflectionFogData = new FogData();
    private static GpuBuffer fogBuffer;

    /** Build {@code d} into a fresh reflection fog UBO and return the slice to feed the level render.
     *  Layout mirrors {@code FogRenderer.updateBuffer} (vec4 colour + 6 floats). */
    private static GpuBufferSlice writeReflectionFog(FogData d) {
        if (fogBuffer != null) {
            fogBuffer.close(); // deferred by the driver until the previous pass's draws are done
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE)
                .putVec4(d.color)
                .putFloat(d.environmentalStart)
                .putFloat(d.environmentalEnd)
                .putFloat(d.renderDistanceStart)
                .putFloat(d.renderDistanceEnd)
                .putFloat(d.skyEnd)
                .putFloat(d.cloudEnd)
                .get();
            fogBuffer = RenderSystem.getDevice().createBuffer(() -> "mirror_fog", GpuBuffer.USAGE_UNIFORM, data);
        }
        return fogBuffer.slice();
    }
    private static RenderPipeline projectPipeline;
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
                // No blending: the shader pins alpha to 1, so a blend state would be a per-pixel no-op that
                // still costs ROP blend bandwidth on every composited quad.
                .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
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

        if (level != lastLevel) {
            // New world or dimension: the cached positions and the captured scene depth belong to the
            // old one, and a fresh world deserves fresh reflection-error diagnostics.
            lastLevel = level;
            cached = List.of();
            MirrorFbo.discardSceneDepth();
            lastErrorLogMs = 0;
            loggedNoDepth = false;
            depthExpected = false;
            frame = 0; // rescan immediately below
        }
        if (Math.floorMod(frame++, RESCAN_INTERVAL) == 0) {
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
            // Nausea/portal distortion is ALSO folded into the level projection (rotate/scale/rotate after
            // the bob), so replicate it too or the quad slides off the mirror block while the screen warps.
            // Same math as renderLevel's spin block, driven by the same fields.
            LocalPlayer player = mc.player;
            float effectScale = mc.options.screenEffectScale().get().floatValue();
            float portal = Mth.lerp(partial, player.oPortalEffectIntensity, player.portalEffectIntensity);
            float nausea = player.getEffectBlendFactor(MobEffects.NAUSEA, partial);
            float intensity = Math.max(portal, nausea) * effectScale * effectScale;
            if (intensity > 0.0f) {
                float squeeze = 5.0f / (intensity * intensity + 5.0f) - intensity * 0.04f;
                squeeze *= squeeze;
                Vector3f spinAxis = new Vector3f(0.0f, Mth.SQRT_OF_TWO / 2.0f, Mth.SQRT_OF_TWO / 2.0f);
                float spin = (gra.mirror$spinningEffectTime() + partial * gra.mirror$spinningEffectSpeed())
                    * Mth.DEG_TO_RAD;
                mainProjBob.rotate(spin, spinAxis);
                mainProjBob.scale(1.0f / squeeze, 1.0f, 1.0f);
                mainProjBob.rotate(-spin, spinAxis);
            }

            // Group coplanar, same-facing mirror cells onto a shared plane. Adjacent mirrors merge into one
            // larger mirror, and every cell on a plane shows the SAME reflection - so we render that
            // reflection ONCE per plane and composite each cell from it, instead of one pass per block.
            // (fastutil map: primitive long keys, no per-lookup boxing at 60 calls/second.)
            Long2ObjectMap<List<MirrorSurface>> groups = new Long2ObjectLinkedOpenHashMap<>();
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
            // Filter to the on-screen planes BEFORE rendering, so each plane can reserve one pass for every
            // plane still waiting behind it - otherwise a near mirror's recursion eats the whole pass budget
            // and farther mirrors get no level-1 render at all, flickering in and out as the camera moves.
            List<List<MirrorSurface>> onScreen = new ArrayList<>();
            int cloudsIndex = -1; // the on-screen plane covering the most pixels gets the clouds
            long cloudsBest = 0;
            for (List<MirrorSurface> group : ordered) {
                if (onScreen.size() >= cfg.maxReflections) {
                    break; // bound top-level planes per frame
                }
                // Test with the BOBBED projection - the quad is placed with it (place0), so testing the
                // unbobbed one can disagree at the screen edge for a frame while walking. Sum the cells'
                // pixel rects (cells don't overlap, so the sum is the plane's coverage): a plane covering
                // almost no screen isn't worth a full world re-render.
                long area = 0;
                for (MirrorSurface s : group) {
                    int[] rect = mirrorScreenRect(s, eye, mainView, mainProjBob, mainTarget.width, mainTarget.height);
                    if (rect != null) {
                        area += (long) rect[2] * rect[3];
                    }
                }
                if (area >= MIN_SCREEN_AREA) {
                    if (area > cloudsBest) {
                        cloudsBest = area;
                        cloudsIndex = onScreen.size();
                    }
                    onScreen.add(group);
                }
            }
            if (onScreen.isEmpty()) {
                return; // no pass will run, so there is no renderer state to restore
            }
            if (depthExpected && MirrorFbo.sceneDepth == null && !loggedNoDepth) {
                // The depth-capture injection is fail-soft (require = 0); say so once per world instead of
                // silently compositing reflections that show through walls. Gated on depthExpected: the FIRST
                // frame with mirrors legitimately has no snapshot yet (the capture runs mid-frame, the scan
                // that enables it at the end), so only a still-missing snapshot afterwards means the
                // injection didn't apply.
                loggedNoDepth = true;
                LOGGER.warn("[mirror] no scene-depth snapshot available (depth-capture injection missing?) - "
                    + "reflections may show through closer geometry");
            }
            depthExpected = true;
            // The partial tick vanilla uses for camera extraction (tracks the camera ENTITY's tick rate,
            // which can diverge from the level's - frozen entities, dying mounts).
            float camPartial = camera.getCameraEntityPartialTicks(dt);
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
                    Reflected r1 = renderReflection(mc, gra, lrs, eye, camera.yRot(), camera.xRot(), rep, mainProj, 1, dt, partial, reserved,
                        i == cloudsIndex);
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
                    compositeGroup(mainTarget.getColorTextureView(), r1.fbo(), place0, sample1, group,
                        eye, r1.cam().position(), mainTarget.getDepthTextureView());
                }
            } finally {
                try {
                    // renderWorldToFbo leaves the extractor on the last virtual camera - re-extract for the main
                    // view once so the rest of the frame is correct. Keep `rendering` true across this restore
                    // extract so the consumeFrustumUpdate suppression still covers it (else this 3rd extract eats
                    // the main view's pending per-frame frustum-update signal and the main view goes a frame stale,
                    // cutting off as you turn). extract()'s own applyFrustum is gated on that consumed signal, so
                    // force the MAIN frustum back explicitly or the main view stays culled to the reflected frustum.
                    mc.levelExtractor.extract(dt, camera, partial);
                    ((LevelExtractorAccessor) mc.levelExtractor).mirror$applyFrustum(camera.getCullFrustum());
                    // extract() does NOT re-extract the camera (vanilla does that once pre-frame), so undo the
                    // last virtual camera's extractRenderState and oblique projection by hand - anything after
                    // this hook that reads cameraRenderState (the 3D debug crosshair) sees the main camera again.
                    camera.extractRenderState(lrs.cameraRenderState, camPartial);
                    lrs.cameraRenderState.projectionMatrix.set(mainProj);
                    // repositionCamera pointed the section dispatcher's translucency-sort origin at the last
                    // virtual camera; point it back, or async resorts queued before next frame sort for the mirror.
                    mc.levelRenderer.sectionRenderDispatcher().setCameraPosition(lrs.cameraRenderState.pos);
                } finally {
                    // Unconditional, and LAST: if any restore step above throws, a stuck `rendering` flag
                    // would suppress sky, clouds, and frustum updates for the rest of the session.
                    rendering = false;
                }
            }
        } catch (Throwable t) {
            // Rate-limited (not latched): a new failure minutes later should still reach the log, but a
            // failure every frame must not flood it.
            long now = System.currentTimeMillis();
            if (now - lastErrorLogMs >= ERROR_LOG_INTERVAL_MS) {
                lastErrorLogMs = now;
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
            int depth, DeltaTracker dt, float partial, int reserved, boolean clouds) {
        Reflected r = renderWorldToFbo(mc, gra, lrs, viewerEye, viewerYaw, viewerPitch, rep, mainProj, depth, dt, partial, clouds);
        if (r == null) {
            return null; // pass budget exhausted
        }
        MirrorConfig cfg = MirrorConfig.get();
        if (depth < cfg.recursionDepth) {
            // Place nested quads with this FBO's EXACT projection (oblique * its view) so each lands where this
            // level drew the nested mirror, and occlude them against this FBO's own rendered depth buffer.
            Matrix4f place = new Matrix4f(r.obliqueProj()).mul(r.fboViewRot());
            var nestedDepth = r.fbo().getDepthTextureView();
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
                    r.cam().yRot(), r.cam().xRot(), nrep, mainProj, depth + 1, dt, partial, reserved,
                    false); // nested bounces skip clouds: one cloud render per frame, on the dominant plane
                if (child == null) {
                    break; // budget exhausted - stop descending this branch
                }
                Matrix4f sample = new Matrix4f(mainProj).mul(child.fboViewRot());
                compositeGroup(r.fbo().getColorTextureView(), child.fbo(), place, sample, ng,
                    r.cam().position(), child.cam().position(), nestedDepth); // occlude against this FBO's depth
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
            int depth, DeltaTracker dt, float partial, boolean clouds) {
        MirrorConfig cfg = MirrorConfig.get();
        if (passes >= cfg.maxRenderPasses) {
            return null; // hard cap on full world re-renders per frame
        }
        passes++;
        RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
        // Reflections may render below screen resolution (config): the composite samples the FBO
        // projectively, so its size is a quality knob, not a correctness one - and the fragment cost of a
        // full world re-render scales with it.
        int fboW = Math.max(1, Math.round(mainTarget.width * cfg.resolutionScale));
        int fboH = Math.max(1, Math.round(mainTarget.height * cfg.resolutionScale));
        TextureTarget fbo = MirrorFbo.level(depth, fboW, fboH);

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
        // Save EVERYTHING the pass mutates BEFORE mutating anything, then do all the mutations inside one
        // try: a throw anywhere in the setup (the extracts, the buffer writes, the FBO clear) must not leak
        // the oblique projection, a pushed model-view stack, or the redirect into the rest of the frame -
        // renderAll's catch swallows the throw, so an unrestored mutation here corrupts every later frame.
        long prevPlaneKey = reflectingPlaneKey;
        boolean prevVirtualCull = virtualCull;
        GpuBufferSlice savedProj = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedType = RenderSystem.getProjectionType();
        GpuBufferSlice savedShaderFog = RenderSystem.getShaderFog(); // renderLevel overwrites this global slice
        GpuBuffer savedGlobals = RenderSystem.getGlobalSettingsUniform();
        TextureTarget prevTarget = MirrorFbo.target;
        boolean prevRedirect = MirrorFbo.redirect;
        // The sky renderer's real target, saved before the swap (and before the try: the restore must
        // know it even if the pass throws mid-setup).
        var prevSkyRenderer = mc.levelRenderer.skyRenderer();
        RenderTarget prevSkyTarget = prevSkyRenderer != null
            ? ((SkyRendererAccessor) (Object) prevSkyRenderer).mirror$renderTarget() : null;
        // Vanilla's cloud renderer, saved for the same reason.
        CloudRenderer prevClouds = mc.levelRenderer.cloudRenderer();
        boolean cloudsSwapped = false;
        var mvStack = RenderSystem.getModelViewStack();
        boolean pushedMv = false;
        Matrix4f fboViewRot;
        Matrix4f oblique;
        try {
            // Mark this plane as the one being reflected, so its OWN block-entity model is skipped during the
            // extract+render below (a mirror must not draw inside its own reflection) while OTHER mirrors still
            // draw. virtualCull arms FrustumMixin's freeze-guard for the virtual frustum work below.
            reflectingPlaneKey = planeKey(plane);
            virtualCull = true;
            // Inside the guard: prepareCullFrustum can reach offsetToFullyIncludeCameraCube, which hangs
            // on a virtual frustum unless FrustumMixin (armed by virtualCull) short-circuits it.
            inv.mirror$prepareCullFrustum(vView, reflProj, virtual.position());
            mc.levelExtractor.extract(dt, virtual, partial);
            // extract()'s own applyFrustum is gated on the per-frame occlusion signal we suppress during the pass,
            // so the reflection's extract skips it and the section set stays on the MAIN frustum. Force the virtual
            // frustum so the reflection culls its OWN sections (else distant sections pop as the main view turns).
            ((LevelExtractorAccessor) mc.levelExtractor).mirror$applyFrustum(virtual.getCullFrustum());
            // No weather in reflections: WeatherEffectRenderer's fixed instance buffer overflows when several
            // passes share it, and a mid-renderLevel throw then leaks the model-view stack into a fatal crash.
            lrs.weatherRenderState.reset();
            CameraRenderState cam = lrs.cameraRenderState;
            // Extract with the camera-entity partial tick (what vanilla feeds extractRenderState), else
            // entities draw relative to the main camera / sub-frame interpolation drifts.
            virtual.extractRenderState(cam, virtual.getCameraEntityPartialTicks(dt));
            // The composite MUST sample with this exact matrix (not getViewRotationMatrix(), which can differ).
            fboViewRot = new Matrix4f(cam.viewRotationMatrix);
            // Reflection distance fog: clear out to fogStart (config, capped at fogEnd so the range can't
            // invert), then fade to the fog colour by fogEnd (≈ render distance) so reflections show depth out
            // to where the real world fades. CRUCIAL: never write the main FogRenderer's ring here - extra
            // writes wrap it and clobber the main view's fog slice (whole-world fog + flicker).
            float fogEnd = cfg.fogEndBlocks();
            float fogStart = Math.min(cfg.fogStartBlocks, fogEnd);
            FogData rf = reflectionFogData;
            rf.color = cam.fogData.color;
            rf.renderDistanceStart = fogStart;
            rf.renderDistanceEnd = fogEnd;
            rf.environmentalStart = Math.min(cam.fogData.environmentalStart, fogStart);
            rf.environmentalEnd = Math.min(cam.fogData.environmentalEnd, fogEnd);
            // Match the SKY's horizon fog to the FLOOR fog so they reach the fog colour at the same distance -
            // that blends the floor-to-sky seam at the horizon. The upper sky, nearer the zenith, stays clear.
            rf.skyEnd = fogEnd;
            // Clouds sit ~120 blocks ABOVE the camera: capping their fog at the reflection distance would
            // fog out even the overhead ones entirely. Use the main view's cloud fog reach so reflected
            // clouds fade exactly like the real ones.
            rf.cloudEnd = Math.max(fogEnd, cam.fogData.cloudEnd);
            GpuBufferSlice terrainFog = writeReflectionFog(rf);

            // Oblique near-plane clip: bend the projection so its near plane IS the mirror plane, clipping the WALL
            // the mirror is mounted on (and anything behind the mirror) out of the reflection. The deferred level
            // render reads its projection from cam.projectionMatrix (NOT RenderSystem's), so it MUST be set there.
            // The mirror's OWN block no longer needs clipping here - it's drawn by a block-entity renderer that
            // skips the reflection pass entirely (MirrorBlockEntityRenderer), so it's never in this FBO to begin with.
            // Bend reflProj (built above, with the generous far) into the oblique near-plane clip at the mirror plane.
            oblique = obliqueProjection(reflProj, cam.viewRotationMatrix, plane.normal(), plane.center(), virtual.position(), zZeroToOne);
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
            RenderSystem.setProjectionMatrix(projBuffer().getBuffer(oblique), ProjectionType.PERSPECTIVE);

            // Clear the FBO to the fog colour (the redirected render leaves stale garbage where geometry misses).
            // 26.2 takes a Vector4fc clear colour; force alpha 1 so the clear is opaque. Depth clears to 0.0, not
            // 1.0: reverse-Z puts the far plane at 0, so terrain's GREATER_THAN_OR_EQUAL test passes against it.
            Vector4f cc = cam.fogData.color;
            Vector4f clearColor = new Vector4f(cc.x, cc.y, cc.z, 1.0f);
            RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(fbo.getColorTexture(), clearColor, fbo.getDepthTexture(), 0.0);

            // Terrain reads the camera position from the Globals UBO (not cam.pos) - rewrite it for the virtual
            // camera so terrain renders camera-relative to the reflection, matching the entities.
            RenderSystem.setGlobalSettingsUniform(writeGlobals(virtual.position(), fboW, fboH, mc, partial));

            // Reset the model-view stack to identity (renderLevel multiplies the redirected render by the main
            // camera's rotation otherwise, double-transforming entities). Redirect the world render into our FBO.
            mvStack.pushMatrix();
            pushedMv = true;
            mvStack.identity();
            MirrorFbo.target = fbo;
            MirrorFbo.redirect = true;

            // Give the reflection a real sky: SkyRenderer draws into the RenderTarget it captured at
            // construction (the REAL main target - the mainRenderTarget() redirect never reaches it), so
            // swap that field onto this pass's FBO and let the sky draws run (SkyRendererMixin allows them
            // while isSkyRedirected). Without this the reflected sky is just the fog-coloured clear, which
            // reads as "the sky below the horizon" - glaringly wrong at dawn and dusk. The virtual camera's
            // rotation is already on the model-view stack for these draws: render() multiplies its view
            // rotation onto the (identity) stack itself.
            var skyRenderer = mc.levelRenderer.skyRenderer();
            if (skyRenderer != null) {
                ((SkyRendererAccessor) (Object) skyRenderer).mirror$setRenderTarget(fbo);
                skyRedirected = true;
            }

            // Clouds too, or a mirror showing clear sky reads as a hole in the wall. Vanilla's
            // CloudRenderer keeps cross-frame camera memos + ring buffers that a virtual-camera pass
            // would corrupt (jittering the MAIN clouds), so a dedicated reflection instance is swapped
            // in, borrowing the vanilla instance's loaded texture. Only ONE pass per frame renders them
            // (the plane covering the most screen, where clouds matter): one instance, one mesh memo,
            // one ring write per frame.
            if (clouds && prevClouds != null) {
                CloudRenderer refl = reflectionClouds();
                ((CloudRendererAccessor) (Object) refl).mirror$setTexture(
                    ((CloudRendererAccessor) (Object) prevClouds).mirror$texture());
                ((LevelRendererAccessor) (Object) mc.levelRenderer).mirror$setCloudRenderer(refl);
                cloudsRedirected = true;
                cloudsSwapped = true;
            }

            // 26.2 consolidated bookkeeping + draw into LevelRenderer.render (no precomputed section list arg).
            // It re-runs the once-per-frame chunk bookkeeping; the mixins (SectionOcclusionGraph/ViewArea) cancel
            // the parts that would corrupt the main view, gated on MirrorFbo.redirect.
            mc.levelRenderer.render(gra.mirror$resourcePool(), dt, false, cam, cam.viewRotationMatrix,
                terrainFog, cam.fogData.color, true);
        } finally {
            skyRedirected = false;
            // Fetch the sky renderer FRESH: if vanilla recreated it mid-pass (shouldResetSkyRenderer), the
            // new instance captured OUR redirected FBO from mainRenderTarget() - point it back at the real
            // main target either way.
            var skyRestore = mc.levelRenderer.skyRenderer();
            if (skyRestore != null && prevSkyTarget != null) {
                ((SkyRendererAccessor) (Object) skyRestore).mirror$setRenderTarget(prevSkyTarget);
            }
            cloudsRedirected = false;
            if (cloudsSwapped) {
                ((LevelRendererAccessor) (Object) mc.levelRenderer).mirror$setCloudRenderer(prevClouds);
            }
            MirrorFbo.redirect = prevRedirect;
            MirrorFbo.target = prevTarget;
            RenderSystem.setGlobalSettingsUniform(savedGlobals);
            RenderSystem.setProjectionMatrix(savedProj, savedType);
            RenderSystem.setShaderFog(savedShaderFog); // restore the main view's fog slice (renderLevel clobbered it)
            if (pushedMv) {
                mvStack.popMatrix();
            }
            reflectingPlaneKey = prevPlaneKey; // restore (handles the nested depth-2 render too)
            virtualCull = prevVirtualCull;
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
    private static Long2ObjectMap<List<MirrorSurface>> visibleGroups(Level level, Vec3 eye, MirrorSurface parent) {
        long excludeKey = planeKey(parent);
        Long2ObjectMap<List<MirrorSurface>> g = new Long2ObjectLinkedOpenHashMap<>();
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

    /** Plane key of the mirror currently being reflected into an FBO (so its OWN block-entity model is skipped
     *  during that render - a mirror must not appear in its own reflection). NO_PLANE when not rendering. */
    private static final long NO_PLANE = Long.MIN_VALUE;
    private static long reflectingPlaneKey = NO_PLANE;

    /**
     * Whether {@link MirrorBlockEntityRenderer} should skip drawing the mirror at {@code pos}/{@code facing}
     * right now. Only the plane being reflected is skipped (it would self-reflect); OTHER mirrors are drawn so
     * they appear (frame + glass) inside this reflection, and their own reflections composite via recursion.
     */
    public static boolean skipInReflection(BlockPos pos, Direction facing) {
        if (!rendering) {
            return false;
        }
        return planeKey(pos, facing) == reflectingPlaneKey;
    }

    /** {@link #planeKey(MirrorSurface)} from a bare pos/facing, allocation-free - this runs per mirror
     *  block entity per reflection pass. Exact same arithmetic as normal·center over a built surface
     *  (the axis component of the cell centre, plus the glass offset along the normal). */
    private static long planeKey(BlockPos pos, Direction facing) {
        Vec3 n = facing.getUnitVec3();
        double d = n.x * (pos.getX() + 0.5) + n.y * (pos.getY() + 0.5) + n.z * (pos.getZ() + 0.5)
            + MirrorSurface.GLASS_OFFSET;
        return ((long) facing.ordinal() << 32) | (Math.round(d * 16.0) & 0xFFFFFFFFL);
    }

    /** Globals UBO for the virtual camera, recreated with its contents per pass (see {@link #fogBuffer}
     *  for why rewrite-in-place is not safe on this driver). */
    private static GpuBuffer globalsBuffer;

    /**
     * Globals UBO rebuilt for the virtual camera (CameraBlockPos/CameraOffset + screen/time fields),
     * so terrain renders camera-relative to the reflection - matching the entities. The non-camera
     * fields (glint/time/blur) are best-effort; only the camera position must be exact.
     * Layout mirrors {@link GlobalSettingsUniform#update}.
     */
    private static GpuBuffer writeGlobals(Vec3 camPos, int w, int h, Minecraft mc, float partial) {
        if (globalsBuffer != null) {
            globalsBuffer.close(); // deferred by the driver until the previous pass's draws are done
        }
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
            globalsBuffer = RenderSystem.getDevice().createBuffer(() -> "mirror_globals", GpuBuffer.USAGE_UNIFORM, data);
        }
        return globalsBuffer;
    }

    /** UBO holding every cell's composite data for the group being drawn (one 256-byte slot per cell),
     *  recreated with its contents per group (see {@link #fogBuffer} for why rewrite-in-place is not safe
     *  on this driver). */
    private static GpuBuffer compositeUbo;
    /** Std140 size of one cell's MirrorProj block (2 mat4 + 8 vec4 = 256 bytes) - conveniently also a
     *  universally valid UBO offset alignment, so slot i can sit at exactly i * stride. */
    private static final int COMPOSITE_STRIDE = 256;

    /**
     * Composite the reflection onto every cell of a plane via projective-textured quads (correct at any
     * viewing angle), in ONE render pass for the whole group: per-cell passes with throwaway UBOs made a
     * merged mirror pay real encoder overhead per block. Each cell gets a slot in {@link #compositeUbo}
     * and its own draw with that slot bound.
     */
    private static void compositeGroup(GpuTextureView targetColor, TextureTarget sampleFbo, Matrix4f placeVP,
                                       Matrix4f sampleVP, List<MirrorSurface> cells, Vec3 placeEye,
                                       Vec3 sampleEye, GpuTextureView depthView) {
        int bytes = cells.size() * COMPOSITE_STRIDE;
        ByteBuffer data = MemoryUtil.memAlloc(bytes); // heap-direct: a merged plane can exceed the 64K stack
        try {
            for (int i = 0; i < cells.size(); i++) {
                Vec3[] corners = cellCorners(cells.get(i));
                data.position(i * COMPOSITE_STRIDE);
                Std140Builder b = Std140Builder.intoBuffer(data)
                    .putMat4f(placeVP)
                    .putMat4f(sampleVP);
                for (Vec3 c : corners) { // corners relative to the placement (viewer) camera - double-precision subtract
                    b.putVec4((float) (c.x - placeEye.x), (float) (c.y - placeEye.y), (float) (c.z - placeEye.z), 1.0f);
                }
                for (Vec3 c : corners) { // corners relative to the virtual (sampled) camera
                    b.putVec4((float) (c.x - sampleEye.x), (float) (c.y - sampleEye.y), (float) (c.z - sampleEye.z), 1.0f);
                }
            }
            data.position(0);
            data.limit(bytes);
            if (compositeUbo != null) {
                compositeUbo.close(); // deferred by the driver until the previous group's draws are done
            }
            compositeUbo = RenderSystem.getDevice().createBuffer(() -> "mirror_proj_ubo", GpuBuffer.USAGE_UNIFORM, data);
        } finally {
            MemoryUtil.memFree(data);
        }
        // Depth-test (no write) so the surface is occluded by closer geometry: the top level tests against
        // the main target's re-seeded scene depth, a nested composite against the parent FBO's own depth
        // (still valid - the FBO is mod-owned, so the framegraph never discards it).
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(() -> "mirror_composite", targetColor,
                Optional.<Vector4fc>empty(), depthView, OptionalDouble.empty())) {
            pass.setPipeline(projectPipeline());
            RenderSystem.bindDefaultUniforms(pass);
            // LINEAR (not NEAREST): when the mirror is distant it covers few screen pixels, so each pixel
            // minifies many FBO texels. NEAREST picks one at random and turns MC's dithered sky into
            // shimmering diagonal bands; bilinear averages them into a smooth reflection.
            pass.bindTexture("InSampler", sampleFbo.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (int i = 0; i < cells.size(); i++) {
                pass.setUniform("MirrorProj", compositeUbo.slice((long) i * COMPOSITE_STRIDE, COMPOSITE_STRIDE));
                pass.draw(6, 1, 0, 0); // 26.2: draw(vertexCount, instanceCount, firstVertex, firstInstance)
            }
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
     * Collect every registered mirror cell within render distance. The registry ({@link #knownMirrors}) is
     * kept in sync by the client block-entity load/unload events (see MirrorClient), so this is O(mirrors)
     * with no chunk scan - a mirror-free world pays nothing. Stale entries (block changed under a live
     * block entity) are dropped here. Capped at MAX_MIRRORS; actual reflection work is separately bounded
     * by the configured max reflections / render passes.
     */
    private static List<MirrorSurface> findNearbyMirrors(Level level, Vec3 camPos) {
        List<MirrorSurface> out = new ArrayList<>();
        if (knownMirrors.isEmpty()) {
            return out;
        }
        double maxDist = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0 + 16.0;
        double maxDistSq = maxDist * maxDist;
        for (Iterator<BlockPos> it = knownMirrors.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (camPos.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > maxDistSq) {
                continue; // beyond render distance (possibly an unloaded chunk - leave the entry alone)
            }
            BlockState state = level.getBlockState(p);
            if (!(state.getBlock() instanceof MirrorBlock)) {
                it.remove(); // stale entry: the block changed without an unload event
                continue;
            }
            out.add(MirrorSurface.single(p, state.getValue(MirrorBlock.FACING),
                state.getValue(MirrorBlock.UP), state.getValue(MirrorBlock.DOWN),
                state.getValue(MirrorBlock.LEFT), state.getValue(MirrorBlock.RIGHT)));
        }
        if (out.size() > MAX_MIRRORS) {
            // Keep the NEAREST cells, not whichever the registry iterated first: a first-met cap reshuffles
            // the survivors (and can keep only PART of a merged plane) every rescan - distant mirrors pop
            // in and out in mirror-dense worlds.
            out.sort(java.util.Comparator.comparingDouble(m -> m.center().distanceToSqr(camPos)));
            out = new ArrayList<>(out.subList(0, MAX_MIRRORS));
        }
        return out;
    }
}
