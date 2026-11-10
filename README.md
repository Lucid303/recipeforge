# Recipe Forge (version 0.1.0)

Creative-mode, in-game recipe editor for NeoForge 1.21.1. Works with recipes from any mod
(Create, Mekanism, Ars Nouveau, ...). Edits are saved as an overlay datapack in
`config/recipeforge/pack` and applied instantly with a reload (same as /reload).

## How to use in game
1. Load a world in CREATIVE mode.
2. Press F8 (change it in Controls > Recipe Forge).
3. Type part of a recipe name, click one of the matches, press Load.
4. Edit the JSON text, press "Save & Reload". JEI should update after the reload.
5. Disable = turn a recipe off. Restore = remove your edit and bring the original back.
   Template = a starter for a brand new recipe (give it a new id such as recipeforge:my_recipe).

Saving under the SAME id replaces that recipe. Saving under a NEW id adds another recipe.

## How to build (GitHub does it for you)
See the instructions in the chat. Short version: upload all these files to a new GitHub
repository, open the Actions tab, wait for the green check, download "recipeforge-jar".

## If the Actions tab shows no "Build mod" workflow
The hidden folder .github did not upload. On GitHub: Add file > Create new file, type the name
.github/workflows/build.yml (typing the slashes makes the folders) and paste this:

name: Build mod
on:
  push:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.14.3
      - name: Build
        run: gradle build --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: recipeforge-jar
          path: build/libs/*.jar

## Known limits of v0.1
- It is a JSON text editor, not a slot-based picture GUI (that comes in later versions).
- Recipes that a mod creates purely in code (not from data files) cannot be disabled or replaced.
- Singleplayer works with cheats on or off. On a dedicated server you must be an operator and in creative.
