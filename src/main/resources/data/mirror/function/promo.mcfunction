# Promo-courtyard entry - force-load + teleport in, then build once chunks are live.
forceload add 388 -14 412 14
tp @p 400 90 2
effect give @p minecraft:slow_falling 10 0 true
tellraw @a {"text":"Building promo courtyard (loading)...","color":"gray"}
schedule function mirror:promo_build 60t
