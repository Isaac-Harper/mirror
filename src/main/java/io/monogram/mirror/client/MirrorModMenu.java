package io.monogram.mirror.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu integration: registers the in-game "config" button for this mod (the {@code modmenu} entrypoint in
 * fabric.mod.json). The screen itself lives in {@link MirrorConfigScreen}, which holds every Cloth Config
 * reference; this class must not import {@code me.shedaniel} classes so it still loads when Cloth Config is
 * missing. In that case the factory returns null and Mod Menu shows the button as unavailable, rather than
 * throwing {@code NoClassDefFoundError}.
 *
 * <p>This class is only loaded when Mod Menu is installed (Mod Menu invokes the entrypoint), so the mod's core
 * has no hard dependency on Mod Menu or Cloth Config - they're {@code suggests} in fabric.mod.json.
 */
public class MirrorModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return parent -> null;
        }
        return MirrorConfigScreen::create;
    }
}
