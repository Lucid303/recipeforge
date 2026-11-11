package dev.recipeforge;

/** Implemented by screens that want to hear back from the server. */
public interface ServerFeedbackListener {
    default void onServerResult(boolean ok, String message) {}
    default void onRecipeJson(String id, String json) {}
    default void onRecipeError(String id, String message) {}
    default void onHistory(String json) {}
    default void onStation(String station, String json) {}
    /** Everything else the server sends (loot, structures, biomes, history details ...). */
    default void onData(String kind, String id, String data) {}
}
