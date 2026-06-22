package io.monogram.mirror.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tunable reflection settings, persisted to {@code config/mirror.json}. Loaded once and edited live by the
 * Mod Menu / Cloth Config screen ({@link MirrorModMenu}); {@link MirrorRenderer} reads {@link #get()} each
 * frame so changes apply immediately on save. This class has NO Mod Menu / Cloth dependency - the mod's core
 * works (with defaults / the file) even when those mods aren't installed.
 */
public final class MirrorConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mirror.json");
    private static MirrorConfig instance;

    // Defaults match the values dialled in during development. Ranges are enforced by clamp() + the GUI sliders.
    public int maxReflections = 6;          // simultaneous reflection planes rendered (nearest-first)
    public int recursionDepth = 2;          // 1 = no recursion; 2 = one mirror-in-mirror bounce
    public int nestedPerParent = 2;         // nested planes reflected inside one reflection
    public int maxRenderPasses = 10;        // hard cap on full world re-renders per frame
    public int reflectionDistanceChunks = 0; // reflection far/fog reach; 0 = follow the render distance
    public int fogStartBlocks = 32;         // reflection stays crisp out to here, then fades to fog

    public static MirrorConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static MirrorConfig load() {
        try {
            if (Files.exists(PATH)) {
                try (Reader r = Files.newBufferedReader(PATH)) {
                    MirrorConfig c = GSON.fromJson(r, MirrorConfig.class);
                    if (c != null) {
                        return c.clamp();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[mirror] couldn't read config, using defaults", e);
        }
        MirrorConfig c = new MirrorConfig();
        c.save();
        return c;
    }

    public void save() {
        clamp();
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            LOGGER.warn("[mirror] couldn't write config", e);
        }
    }

    public MirrorConfig clamp() {
        maxReflections = Math.clamp(maxReflections, 1, 8);
        recursionDepth = Math.clamp(recursionDepth, 1, 2);
        nestedPerParent = Math.clamp(nestedPerParent, 0, 4);
        maxRenderPasses = Math.clamp(maxRenderPasses, 1, 20);
        reflectionDistanceChunks = Math.clamp(reflectionDistanceChunks, 0, 32);
        fogStartBlocks = Math.clamp(fogStartBlocks, 0, 256);
        return this;
    }

    /** Reflection far plane (blocks): chosen distance (or the render distance when 0) plus a margin for the
     *  virtual-camera offset behind the mirror. */
    public float farBlocks() {
        return distanceChunks() * 16.0f + 48.0f;
    }

    /** Distance (blocks) at which the reflection has fully faded to fog - about its render-distance reach. */
    public float fogEndBlocks() {
        return distanceChunks() * 16.0f;
    }

    private int distanceChunks() {
        if (reflectionDistanceChunks > 0) {
            return reflectionDistanceChunks;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc != null ? mc.options.getEffectiveRenderDistance() : 12;
    }
}
