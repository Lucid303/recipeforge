package dev.recipeforge.brewing;

import com.google.gson.Gson;

import dev.recipeforge.ClientModules;
import dev.recipeforge.Modules;
import dev.recipeforge.WorldForge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;

/** Client side of the optional brewing stand support. */
public final class BrewingClient {
    private BrewingClient() {}

    public static volatile BrewingData.Store store = new BrewingData.Store();
    public static volatile int version = 0;

    /** Called by Recipe Forge's client when this module is part of the jar. */
    public static void init() {
        ClientModules.addStation(new ClientModules.Extra("Brewing stand", Items.BREWING_STAND, BrewingScreen::new));
        Modules.addClient(BrewingClient::onMessage);
    }

    private static boolean onMessage(String kind, String id, String data) {
        if (!kind.equals("BREW_SYNC")) {
            return false;
        }
        try {
            BrewingData.Store next = new Gson().fromJson(data, BrewingData.Store.class);
            if (next != null) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    BrewingAccess.apply(mc.level.potionBrewing(), store, next);
                }
                store = next;
                version++;
            }
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge could not apply brewing sync", e);
        }
        return true;
    }
}
