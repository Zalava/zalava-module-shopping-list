package org.zalava.modules.shoppinglist;

enum RemovalReason {
  BOUGHT("bought"),
  DISCARDED("discarded");

  private final String value;

  RemovalReason(String value) {
    this.value = value;
  }

  static RemovalReason from(String value) {
    if (value == null || value.isBlank() || "bought".equalsIgnoreCase(value.trim())) {
      return BOUGHT;
    }
    if ("discarded".equalsIgnoreCase(value.trim())) {
      return DISCARDED;
    }
    throw new IllegalArgumentException("reason must be bought or discarded");
  }

  String value() {
    return value;
  }
}
