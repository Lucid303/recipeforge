package dev.recipeforge;

import java.util.Collection;
import java.util.Locale;

import net.minecraft.resources.ResourceLocation;

/**
 * Search text parser. "@create" limits results to the mod "create";
 * anything else is matched against the item's id and name. Both can be combined: "@create cogwheel".
 */
public final class SearchFilter {
    private final String mod;      // null when no @mod was typed
    private final boolean exact;   // true when the typed mod name is a real mod id
    private final String text;

    private SearchFilter(String mod, boolean exact, String text) {
        this.mod = mod;
        this.exact = exact;
        this.text = text;
    }

    public static SearchFilter parse(String raw, Collection<String> namespaces) {
        String mod = null;
        StringBuilder text = new StringBuilder();
        if (raw != null) {
            for (String token : raw.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("@") && mod == null) {
                    mod = token.substring(1);
                } else {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(token);
                }
            }
        }
        boolean exact = mod != null && namespaces.contains(mod);
        return new SearchFilter(mod, exact, text.toString());
    }

    public boolean matches(ResourceLocation key, String displayName) {
        if (mod != null && !mod.isEmpty()) {
            String ns = key.getNamespace();
            if (exact ? !ns.equals(mod) : !ns.startsWith(mod)) {
                return false;
            }
        }
        if (text.isEmpty()) {
            return true;
        }
        return key.toString().contains(text) || displayName.toLowerCase(Locale.ROOT).contains(text);
    }
}
