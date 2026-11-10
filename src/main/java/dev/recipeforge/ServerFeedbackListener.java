package dev.recipeforge;

/** Implemented by screens that want to hear back from the server. */
public interface ServerFeedbackListener {
    default void onServerResult(boolean ok, String message) {}
    default void onRecipeJson(String id, String json) {}
    default void onRecipeError(String id, String message) {}
    default void onHistory(String json) {}
}
