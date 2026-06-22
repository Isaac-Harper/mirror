# Mirror demo entry - force-load the plaza, then build it once chunks are live.
forceload add -8 -6 114 16
tellraw @a {"text":"Building mirror demo (chunks loading)...","color":"gray"}
schedule function mirror:build 40t
