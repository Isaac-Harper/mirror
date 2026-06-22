package io.monogram.mirror.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

/**
 * Mod Menu integration: registers the in-game "config" button for this mod (the {@code modmenu} entrypoint in
 * fabric.mod.json) and builds the settings screen with Cloth Config. Editing a slider and saving writes
 * {@link MirrorConfig} to disk; {@link MirrorRenderer} reads it each frame, so changes apply immediately.
 *
 * <p>This class is only loaded when Mod Menu is installed (Mod Menu invokes the entrypoint), so the mod's core
 * has no hard dependency on Mod Menu or Cloth Config - they're {@code suggests} in fabric.mod.json.
 */
public class MirrorModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            MirrorConfig c = MirrorConfig.get();
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Mirror Settings"))
                .setSavingRunnable(c::save);
            ConfigCategory cat = builder.getOrCreateCategory(Component.literal("Reflections"));
            ConfigEntryBuilder e = builder.entryBuilder();

            cat.addEntry(e.startIntSlider(Component.literal("Max reflections on screen"), c.maxReflections, 1, 8)
                .setDefaultValue(6)
                .setTooltip(Component.literal("How many mirror planes render at once (nearest first). Lower = cheaper in mirror-dense scenes."))
                .setSaveConsumer(v -> c.maxReflections = v).build());

            cat.addEntry(e.startIntSlider(Component.literal("Recursion depth"), c.recursionDepth, 1, 2)
                .setDefaultValue(2)
                .setTooltip(Component.literal("1 = no mirror-in-mirror; 2 = one nested bounce."))
                .setSaveConsumer(v -> c.recursionDepth = v).build());

            cat.addEntry(e.startIntSlider(Component.literal("Nested reflections per mirror"), c.nestedPerParent, 0, 4)
                .setDefaultValue(2)
                .setTooltip(Component.literal("How many mirrors reflected inside one reflection get their own bounce."))
                .setSaveConsumer(v -> c.nestedPerParent = v).build());

            cat.addEntry(e.startIntSlider(Component.literal("Max world re-renders / frame"), c.maxRenderPasses, 1, 20)
                .setDefaultValue(10)
                .setTooltip(Component.literal("Hard cap on total reflection passes per frame (budget guard)."))
                .setSaveConsumer(v -> c.maxRenderPasses = v).build());

            cat.addEntry(e.startIntSlider(Component.literal("Reflection distance (chunks)"), c.reflectionDistanceChunks, 0, 32)
                .setDefaultValue(0)
                .setTextGetter(v -> Component.literal(v == 0 ? "Auto (render distance)" : v + " chunks"))
                .setTooltip(Component.literal("How far reflections show depth. 0 follows your render distance. Higher = deeper but more expensive."))
                .setSaveConsumer(v -> c.reflectionDistanceChunks = v).build());

            cat.addEntry(e.startIntSlider(Component.literal("Fog start (blocks)"), c.fogStartBlocks, 0, 256)
                .setDefaultValue(32)
                .setTooltip(Component.literal("Reflection stays crisp out to here, then fades to fog by the reflection distance."))
                .setSaveConsumer(v -> c.fogStartBlocks = v).build());

            return builder.build();
        };
    }
}
