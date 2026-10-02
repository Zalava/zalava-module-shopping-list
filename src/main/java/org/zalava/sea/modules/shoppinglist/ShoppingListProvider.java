package org.zalava.modules.shoppinglist;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.zalava.InvocationContext;
import org.zalava.ProviderCapabilities;
import org.zalava.ProviderDescriptor;
import org.zalava.ZalavaOperationResult;
import org.zalava.ZalavaProvider;
import org.zalava.ZalavaToolDescriptor;
import org.zalava.ZalavaToolInputSchemas;
import tools.jackson.databind.JsonNode;

final class ShoppingListProvider implements ZalavaProvider {

  static final String PROVIDER_ID = "shopping-list-household";

  private static final List<String> POLICY_TAGS =
      List.of("sea_backed", "shopping-list", "household-data", "local-storage");

  private static final List<ZalavaToolDescriptor> TOOLS =
      List.of(
          tool(
              "add_item",
              "Add or update an active shopping-list item.",
              true,
              Map.of(
                  "name",
                  ZalavaToolInputSchemas.string(),
                  "quantity",
                  ZalavaToolInputSchemas.string()),
              "name"),
          tool("list_items", "Return the active household shopping list.", false, Map.of()),
          tool(
              "mark_bought",
              "Remove an active shopping-list item and record a purchase event.",
              true,
              Map.of("name", ZalavaToolInputSchemas.string()),
              "name"),
          tool(
              "remove_item",
              "Remove an active item; reason defaults to bought and discarded avoids purchase history.",
              true,
              Map.of(
                  "name",
                  ZalavaToolInputSchemas.string(),
                  "reason",
                  ZalavaToolInputSchemas.string()),
              "name"),
          tool(
              "purchase_summary",
              "Summarize purchased item counts over recent days.",
              false,
              Map.of("days", ZalavaToolInputSchemas.integer())));

  private final ShoppingListRepository repository;
  private final ProviderDescriptor descriptor;

  ShoppingListProvider(ShoppingListRepository repository) {
    this.repository = repository;
    this.descriptor =
        new ProviderDescriptor(
            PROVIDER_ID,
            ShoppingListSeaModule.MODULE_ID,
            "shopping-list",
            "Household Shopping List",
            "One private household shopping list backed by local SQLite storage.",
            ShoppingListSeaModule.version(),
            ProviderCapabilities.toolsOnly(),
            POLICY_TAGS,
            Map.of("storage", "sqlite", "list", "household"));
  }

  @Override
  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderCapabilities capabilities() {
    return descriptor.capabilities();
  }

  @Override
  public List<ZalavaToolDescriptor> listTools() {
    return TOOLS;
  }

  @Override
  public ZalavaOperationResult callTool(
      String toolName, JsonNode arguments, InvocationContext context) {
    return switch (toolName) {
      case "add_item" -> addItem(arguments);
      case "list_items" -> listItems();
      case "mark_bought" -> markBought(arguments);
      case "remove_item" -> removeItem(arguments);
      case "purchase_summary" -> purchaseSummary(arguments);
      default -> throw new IllegalArgumentException("Unknown shopping-list tool: " + toolName);
    };
  }

  private ZalavaOperationResult addItem(JsonNode arguments) {
    ShoppingListItem item =
        repository.addItem(requiredText(arguments, "name"), optionalText(arguments, "quantity"));
    return success("Added " + item.name(), Map.of("item", itemContent(item)));
  }

  private ZalavaOperationResult listItems() {
    List<ShoppingListItem> items = repository.listActiveItems();
    String compact =
        items.isEmpty()
            ? "List is empty"
            : "List: "
                + String.join(", ", items.stream().map(ShoppingListProvider::compactItem).toList());
    return success(
        compact, Map.of("items", items.stream().map(ShoppingListProvider::itemContent).toList()));
  }

  private ZalavaOperationResult markBought(JsonNode arguments) {
    return remove(requiredText(arguments, "name"), RemovalReason.BOUGHT);
  }

  private ZalavaOperationResult removeItem(JsonNode arguments) {
    RemovalReason reason = RemovalReason.from(optionalText(arguments, "reason").orElse("bought"));
    return remove(requiredText(arguments, "name"), reason);
  }

  private ZalavaOperationResult remove(String name, RemovalReason reason) {
    Optional<ShoppingListItem> removed = repository.removeItem(name, reason);
    if (removed.isEmpty()) {
      return ZalavaOperationResult.failure(
          "Item not found: " + ShoppingListNames.requireDisplayName(name));
    }
    String verb = reason == RemovalReason.BOUGHT ? "Bought " : "Removed ";
    return success(
        verb + removed.get().name(),
        Map.of(
            "item", itemContent(removed.get()),
            "reason", reason.value()));
  }

  private ZalavaOperationResult purchaseSummary(JsonNode arguments) {
    int days =
        arguments == null || !arguments.hasNonNull("days") ? 30 : arguments.path("days").asInt();
    List<PurchaseSummaryItem> summary = repository.purchaseSummary(days);
    String compact =
        summary.isEmpty()
            ? "No purchases"
            : "Purchases: "
                + String.join(
                    ", ", summary.stream().map(item -> item.name() + " x" + item.count()).toList());
    return success(
        compact,
        Map.of(
            "days",
            days,
            "items",
            summary.stream()
                .map(item -> Map.of("name", item.name(), "count", item.count()))
                .toList()));
  }

  private static ZalavaOperationResult success(String text, Map<String, Object> content) {
    return new ZalavaOperationResult(
        true, Map.of("text", text, "data", content), Map.of("providerId", PROVIDER_ID));
  }

  private static String requiredText(JsonNode arguments, String name) {
    String value = arguments == null ? null : arguments.path(name).asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static Optional<String> optionalText(JsonNode arguments, String name) {
    if (arguments == null || !arguments.hasNonNull(name)) {
      return Optional.empty();
    }
    String value = arguments.path(name).asString(null);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }

  private static Map<String, Object> itemContent(ShoppingListItem item) {
    return Map.of(
        "name", item.name(),
        "quantity", item.quantity().orElse(""));
  }

  private static String compactItem(ShoppingListItem item) {
    return item.quantity().map(quantity -> item.name() + " (" + quantity + ")").orElse(item.name());
  }

  private static ZalavaToolDescriptor tool(
      String name,
      String description,
      boolean sideEffecting,
      Map<String, Object> properties,
      String... required) {
    return new ZalavaToolDescriptor(
        name,
        description,
        sideEffecting,
        POLICY_TAGS,
        ZalavaToolInputSchemas.object(properties, required));
  }
}
