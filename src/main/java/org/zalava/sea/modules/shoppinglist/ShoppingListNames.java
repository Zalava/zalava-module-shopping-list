package org.zalava.modules.shoppinglist;

import java.util.Locale;

final class ShoppingListNames {

    private ShoppingListNames() {
    }

    static String requireDisplayName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return name.trim();
    }

    static String normalize(String name) {
        return requireDisplayName(name).toLowerCase(Locale.ROOT);
    }
}
