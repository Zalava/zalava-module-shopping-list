package org.zalava.modules.shoppinglist;

import java.time.Instant;
import java.util.Optional;

record ShoppingListItem(String name, Optional<String> quantity, Instant updatedAt) {}
