package dev.recipeforge;

/** Counters that go up whenever the client receives fresh data. */
public final class ClientState {
    private ClientState() {}
    public static volatile int recipeVersion = 0;
    public static volatile int stationVersion = 0;
}
