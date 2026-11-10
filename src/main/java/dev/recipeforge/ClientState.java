package dev.recipeforge;

/** Counter that goes up every time the client receives a fresh recipe list. */
public final class ClientState {
    private ClientState() {}
    public static volatile int recipeVersion = 0;
}
