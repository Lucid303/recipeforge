# World Forge (version 0.5.0)

A creative-mode toolbox for designing modpacks in game. NeoForge 1.21.1.
Press LEFT ALT in a creative world to open the hub (change the key in Controls > World Forge).

## Modules
- Recipe Forge: crafting table, furnace, blast furnace, smoker, campfire, stonecutter, smithing table
  (picture editors), plus a text editor for other mods' machines (Create, Mekanism, Ars Nouveau...).
- Loot Forge: block drops, mob drops, chest loot. Works with every mod's loot tables (they are data files).
- Structure Forge: choose which biomes a structure spawns in (needs re-entering the world), and replace
  blocks inside structure files.
- Biome Forge: change grass, leaf, water, underwater, sky and fog colours of any biome with a colour wheel.
- Station Forge: anvil and grindstone combinations, loom pattern items, and (brewing build) brewing stand.
- History: every change, with before / after pictures.

## Two builds
- worldforge-0.5.0.jar: everything except the brewing stand.
- worldforge-brewing-0.5.0.jar: everything including the brewing stand. Install only ONE of them.

## Where settings are stored
config/worldforge/ inside the Minecraft instance: pack/ (recipes, loot, tags, structures),
stations.json, brewing.json, biome_colors.json, history.json.
Your old config/recipeforge folder is copied there automatically the first time.
Copy config/worldforge into a modpack to share your changes.

See CHECKLIST.md for the full list of features and known limits.
