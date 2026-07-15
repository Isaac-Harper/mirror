package io.monogram.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pure-logic unit tests for {@link MirrorConfig}: defaults and the {@code clamp()} bounds that keep a
 * hand-edited or corrupt config from feeding out-of-range values into the renderer. No Minecraft bootstrap
 * needed - the config path is resolved lazily, so the class loads and clamps off-game.
 */
class MirrorConfigTest {

    @Test
    void defaultsAreInRange() {
        MirrorConfig c = new MirrorConfig();
        // Defaults must already satisfy their own bounds (clamp is a no-op on a fresh config).
        MirrorConfig same = c.clamp();
        assertEquals(6, c.maxReflections);
        assertEquals(3, c.recursionDepth);
        assertEquals(2, c.nestedPerParent);
        assertEquals(12, c.maxRenderPasses);
        assertEquals(0, c.reflectionDistanceChunks);
        assertEquals(32, c.fogStartBlocks);
        assertEquals(1.0f, c.resolutionScale);
        assertSame(c, same, "clamp() should return the same instance for fluent use");
    }

    @Test
    void clampRaisesValuesBelowTheirMinimum() {
        MirrorConfig c = new MirrorConfig();
        c.maxReflections = 0;
        c.recursionDepth = 0;
        c.maxRenderPasses = 0;
        c.reflectionDistanceChunks = -5;
        c.fogStartBlocks = -1;
        c.resolutionScale = 0.0f;
        c.clamp();
        assertEquals(1, c.maxReflections);
        assertEquals(1, c.recursionDepth);
        assertEquals(1, c.maxRenderPasses);
        assertEquals(0, c.reflectionDistanceChunks);
        assertEquals(0, c.fogStartBlocks);
        assertEquals(0.25f, c.resolutionScale);
    }

    @Test
    void clampLowersValuesAboveTheirMaximum() {
        MirrorConfig c = new MirrorConfig();
        c.maxReflections = 999;
        c.recursionDepth = 999;
        c.nestedPerParent = 999;
        c.maxRenderPasses = 9999;
        c.reflectionDistanceChunks = 999;
        c.fogStartBlocks = 99999;
        c.resolutionScale = 4.0f;
        c.clamp();
        assertEquals(16, c.maxReflections);
        assertEquals(8, c.recursionDepth);
        assertEquals(8, c.nestedPerParent);
        assertEquals(128, c.maxRenderPasses);
        assertEquals(64, c.reflectionDistanceChunks);
        assertEquals(512, c.fogStartBlocks);
        assertEquals(1.0f, c.resolutionScale);
    }
}
