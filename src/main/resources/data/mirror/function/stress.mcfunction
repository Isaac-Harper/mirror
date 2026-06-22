# Stress-arena entry - force-load, then build once chunks are live.
forceload add 196 -28 228 16
tellraw @a {"text":"Building stress arena (chunks loading)...","color":"gray"}
schedule function mirror:stress_build 40t
