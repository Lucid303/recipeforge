# World Forge (version 0.7.0)

A creative-mode toolbox for designing modpacks in game. NeoForge 1.21.1.
Press LEFT ALT in a creative world to open the hub (change the key in Controls > World Forge).

## Forges
- Recipe Forge: crafting table, furnace, blast furnace, smoker, campfire, stonecutter, smithing table,
  anvil and grindstone (picture editors), plus a text editor for other mods' machines.
  Ingredients can be item TAGS (type @tag in the search box). Crafting recipes can KEEP an item.
- Loot Forge: block drops, mob drops, chest loot (every mod's loot tables work).
- Structure Forge: choose which biomes a structure spawns in (re-enter the world to apply).
- Biome Forge: change grass, leaf, water, underwater, sky and fog colours with a colour wheel.
- Tag Forge: add or remove items, blocks and mobs from tags like planks or ores.
- Enchantment Forge: levels, weight, costs and which items an enchantment applies to (re-enter the world).
- World Gen Forge: ore count per chunk, rarity, height range and vein size (re-enter the world, new chunks only).
- History: every change, with before / after pictures; filter by forge; right-click to jump to the change.
- Pack Export (button in the hub, top right): zips all your changes so you can share them with a modpack.

## Where settings are stored
config/worldforge/ inside the Minecraft instance. Pack Export puts a zip in <game folder>/worldforge-exports/.

## Build variants
The GitHub build tries to include everything (extra forges + JEI plugin + EMI plugin) and falls back to less if a part
cannot be built. The line "BUILD RESULT: ..." in the build log says which one you got.

See CHECKLIST.md for the full list of features and known limits.
