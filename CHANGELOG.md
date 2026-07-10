# Changelog

Jar versions carry the Minecraft version as build metadata (for example `0.1.2+26.2`); this file tracks the mod version for the 26.2 line (branch `main`). The `publishMods` task publishes the matching `## <version>` section below as the Modrinth changelog.

## 0.2.1

- Compatible with Sodium. Mirrors reflect correctly culled terrain and the main view is unaffected, because the reflection pass drives Sodium's own terrain culling. Iris is still unsupported.

## 0.2.0

- Reflections now show the real sky: the sky gradient, sun, moon, stars, and clouds, all matching the time of day. Mirrors used to show a flat fog colour where the sky belonged, which looked orange at dawn and dusk.
- Mirrors are now waterloggable, so placing one underwater no longer leaves an air pocket.
- New "Reflection resolution" setting renders reflections below screen resolution for a large performance gain with little visible difference. Very small on-screen mirrors are now skipped entirely.
- The mirror recipe now shows up in the recipe book once you have the ingredients.
- Reflection memory is freed when you leave a world, and the mirror pass costs nothing in worlds without mirrors.
- The mod now declares itself incompatible with Sodium and Iris, which replace the renderer this mod hooks into.
- The mod icon and issue links now appear in Mod Menu.
- Many stability and correctness fixes: mirrors no longer corrupt the sky in the End, the view no longer stalls if a reflection errors mid-frame, and reflections restore the main view cleanly.

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
