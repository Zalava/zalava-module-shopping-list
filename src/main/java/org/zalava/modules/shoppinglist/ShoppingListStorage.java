package org.zalava.modules.shoppinglist;

import java.nio.file.Path;

final class ShoppingListStorage {

  private static final String SQLITE_PATH_PROPERTY = "zalava.module.shopping-list.sqlite.path";
  private static final String SQLITE_PATH_ENV = "ZALAVA_MODULE_SHOPPING_LIST_SQLITE_PATH";

  private ShoppingListStorage() {}

  static Path path() {
    String configured = System.getProperty(SQLITE_PATH_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(SQLITE_PATH_ENV);
    }
    if (configured == null || configured.isBlank()) {
      configured = "./workspace/shopping-list/shopping-list.sqlite";
    }
    return Path.of(configured);
  }
}
