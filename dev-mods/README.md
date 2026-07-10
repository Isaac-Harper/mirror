# Dev-only test mods

Jars dropped here are added to the `runClient` / `runTestWorld` runtime only (never to the published
mod). Used to verify compatibility. The jars themselves are gitignored; only this note is tracked.

## Sodium

The reflection pass is validated against Sodium. To test, download the matching build and drop it here:

    curl -sL "https://cdn.modrinth.com/data/AANobbMI/versions/2Yom1N68/sodium-fabric-0.9.1%2Bmc26.2.jar" \
      -o dev-mods/sodium-fabric-0.9.1+mc26.2.jar

`build.gradle` picks up any `sodium-*.jar` here automatically. Mirrors reflect correctly culled terrain
under Sodium because both drive terrain culling from `LevelExtractor.extract`; the mod's per-camera
extract already sets up Sodium's chunk list for the mirror camera.
