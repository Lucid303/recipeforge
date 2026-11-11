# World Forge 0.6.0 - things to check

## General
1. Left Alt opens the hub in creative; in survival it shows "creative mode only".
2. Hub has 5 tiles (Recipe, Loot, Structure, Biome, History); no grey text under them; hovering shows a description.
3. Every screen has a coloured banner, a Back button at the bottom right, and Esc goes back one screen.
4. Page numbers never overlap the < > buttons; the mouse wheel changes pages everywhere.
5. Long status messages wrap onto a second line instead of being cut off.
6. Buttons everywhere are the normal Minecraft grey (no light-grey ones).

## Recipe Forge
7. Search: @minecraft, @create, "@create cogwheel", and @tag (shows item tags like minecraft:planks).
8. Click an item: recipes shown as pictures with the right station icon + name (also anvil/grindstone combos).
9. Crafting table / furnace / blast furnace / smoker / campfire / stonecutter / smithing editors work; stick opens.
10. Tag ingredients: pick a tag with @tag, save; one recipe accepts every item in the tag.
11. Keep: select a square, press Keep (green K), save; the item stays in the grid after crafting (e.g. axe + log -> stripped log).
12. New recipe chooser has Crafting table, Furnace, Blast furnace, Smoker, Campfire, Stonecutter, Smithing table,
    Anvil, Anvil (block), Grindstone, Grindstone (block).
13. Anvil / grindstone combinations appear in the list of the result item (blocked ones under both input items),
    can be edited and removed, and work at the anvil / grindstone.
14. Custom anvil recipes show in JEI (needs the JEI build; see the build log).
15. Remove, Undo my changes, Add new recipe work; text editor works for other mods' machines.

## Loot Forge
16. Categories, search and @mod filter work.
17. Editor: pools, items, weights, counts, rolls (shows 1, not 1.0), Add item, Add pool, Remove pool, X.
18. Hovering any editor button explains it; a legend line explains W / x / X.
19. Disable button toggles to Enable and back; the table really drops nothing while disabled.
20. Restore throws away unsaved edits (deleting an item and pressing Restore brings it back).
21. No text/code button anymore.

## Structure Forge
22. Structure list, search, @mod; tick biomes, All shown / None shown / Never spawn / Save / Restore.
23. After re-entering the world, new chunks use the new biome list.
24. (Block editing inside structures was removed for now.)

## Biome Forge
25. Search works (you can type more than one letter).
26. Choosing a colour and pressing Apply changes the world colours; they stay after leaving the screen and after a restart.
27. If it cannot work, the screen shows the reason in yellow text.
28. Preview shows grass block, leaves, water, tall grass and sky/fog strip.

## History
29. Anvil / grindstone entries show pictures (items), not text.
30. Recipe, loot, biome (colour swatches) and structure (biome squares) entries show before / after pictures.
31. Put back the original / Use this version again work for recipes; Put back the original works for loot, biome, structure.

## Removed on purpose
Brewing stand, loom, structure block editing, Station Forge tile, "Other stations" button.

## Known limits
- No real 3D editing or rotating 3D previews; History pictures are drawn in game (no PNG files).
- Keep works only for the crafting table / Crafter (the game consumes smithing, anvil, grindstone and furnace inputs by itself).
- Keep recipes show in JEI as ordinary crafting recipes without a "kept" marker.
- Structure spawn changes need re-entering the world.
- Anvil/grindstone: custom grindstone recipes use up both input stacks fully; the anvil may not trigger when the first item cannot hold enchantments.
