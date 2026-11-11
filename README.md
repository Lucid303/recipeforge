# World Forge (version 0.6.0)

A creative-mode toolbox for designing modpacks in game. NeoForge 1.21.1.
Press LEFT ALT in a creative world to open the hub (change the key in Controls > World Forge).

## Modules
- Recipe Forge: crafting table, furnace, blast furnace, smoker, campfire, stonecutter, smithing table,
  anvil and grindstone (picture editors), plus a text editor for other mods' machines.
  Ingredients can be item TAGS (type @tag in the search box). Crafting recipes can KEEP an item (not use it up).
- Loot Forge: block drops, mob drops, chest loot. Works with every mod's loot tables (they are data files).
- Structure Forge: choose which biomes a structure spawns in (needs re-entering the world).
- Biome Forge: change grass, leaf, water, underwater, sky and fog colours of any biome with a colour wheel.
- History: every change, with before / after pictures.

## Where settings are stored
config/worldforge/ inside the Minecraft instance: pack/ (recipes, loot, structures),
stations.json (anvil + grindstone), biome_colors.json, history.json.
Copy config/worldforge into a modpack to share your changes.

## JEI
The build tries to include a JEI plugin that shows custom anvil recipes. If the build log says
"JEI SUPPORT: NOT INCLUDED", the mod still works but anvil recipes will not show in JEI.

See CHECKLIST.md for the full list of features and known limits.
