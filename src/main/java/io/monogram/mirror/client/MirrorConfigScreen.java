package io.monogram.mirror.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the settings screen with Cloth Config. Editing a slider and saving writes {@link MirrorConfig} to
 * disk; {@link MirrorRenderer} reads it each frame, so changes apply immediately.
 *
 * <p>Every {@code me.shedaniel} reference lives in this class, and nothing else: {@link MirrorModMenu} only
 * touches it after checking that Cloth Config is loaded, so the config button degrades gracefully (instead
 * of throwing {@code NoClassDefFoundError}) when Cloth Config is absent.
 */
public final class MirrorConfigScreen {
    private MirrorConfigScreen() {
    }

    /** Builds the Cloth Config screen; only call when the {@code cloth-config} mod is loaded. */
    public static Screen create(Screen parent) {
        MirrorConfig c = MirrorConfig.get();
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.literal("Mirror Settings"))
            .setSavingRunnable(c::save);
        ConfigCategory cat = builder.getOrCreateCategory(Component.literal("Reflections"));
        ConfigEntryBuilder e = builder.entryBuilder();

        cat.addEntry(e.startIntSlider(Component.literal("Max reflections on screen"), c.maxReflections, 1, 16)
            .setDefaultValue(6)
            .setTooltip(Component.literal("How many mirror planes render at once (nearest first). Lower = cheaper in mirror-dense scenes."))
            .setSaveConsumer(v -> c.maxReflections = v).build());

        cat.addEntry(e.startIntSlider(Component.literal("Recursion depth"), c.recursionDepth, 1, 8)
            .setDefaultValue(3)
            .setTooltip(Component.literal("1 = no mirror-in-mirror; higher = more nested bounces (a hall of mirrors). Each level re-renders the world, so raise Max world re-renders to match."))
            .setSaveConsumer(v -> c.recursionDepth = v).build());

        cat.addEntry(e.startIntSlider(Component.literal("Nested reflections per mirror"), c.nestedPerParent, 0, 8)
            .setDefaultValue(2)
            .setTooltip(Component.literal("How many mirrors reflected inside one reflection get their own bounce."))
            .setSaveConsumer(v -> c.nestedPerParent = v).build());

        cat.addEntry(e.startIntSlider(Component.literal("Max world re-renders / frame"), c.maxRenderPasses, 1, 128)
            .setDefaultValue(12)
            .setTooltip(Component.literal("Hard cap on total reflection passes per frame (budget guard). Deep recursion needs a higher cap, but each pass costs GPU time."))
            .setSaveConsumer(v -> c.maxRenderPasses = v).build());

        cat.addEntry(e.startIntSlider(Component.literal("Reflection resolution"), Math.round(c.resolutionScale * 100), 25, 100)
            .setDefaultValue(100)
            .setTextGetter(v -> Component.literal(v + "%"))
            .setTooltip(Component.literal("Reflections render at this fraction of screen resolution. Lower is much cheaper, mildly softer."))
            .setSaveConsumer(v -> c.resolutionScale = v / 100.0f).build());

        cat.addEntry(e.startIntSlider(Component.literal("Reflection distance (chunks)"), c.reflectionDistanceChunks, 0, 64)
            .setDefaultValue(0)
            .setTextGetter(v -> Component.literal(v == 0 ? "Auto (render distance)" : v + " chunks"))
            .setTooltip(Component.literal("How far reflections show depth. 0 follows your render distance. Higher = deeper but more expensive."))
            .setSaveConsumer(v -> c.reflectionDistanceChunks = v).build());

        cat.addEntry(e.startIntSlider(Component.literal("Fog start (blocks)"), c.fogStartBlocks, 0, 512)
            .setDefaultValue(32)
            .setTooltip(Component.literal("Reflection stays crisp out to here, then fades to fog by the reflection distance. Values beyond the reflection distance are treated as the reflection distance."))
            .setSaveConsumer(v -> c.fogStartBlocks = v).build());

        return builder.build();
    }
}
