# Changelog

Jar versions carry the Minecraft version as build metadata (for example `0.1.2+26.2`); this file tracks the mod version for the 26.2 line (branch `main`). The `publishMods` task publishes the matching `## <version>` section below as the Modrinth changelog.

## 0.1.2

- The mirror has a solid oak back panel instead of a see-through frame when viewed from behind.

## 0.1.1

- Hall of mirrors: recursion depth is now configurable up to 8 bounces (was 2), so two facing mirrors show reflections receding into the distance.
- Distant mirrors no longer flicker when a near mirror spends the frame budget on deep recursion.
- Wider settings ranges: up to 16 mirrors on screen, 128 render passes per frame, 64 chunks of reflection distance.
- The mirror now appears in the Functional Blocks creative tab.
- Reflections reset cleanly when switching worlds or dimensions.

## 0.1.0

Initial release: a craftable mirror block that shows a live reflection of the world, rendered natively on the client with no extra rendering dependencies. Mirrors placed side by side merge into one seamless surface, mirror-in-mirror reflections are supported, and the budget knobs (planes, recursion, distance, fog) are tunable via Mod Menu.
