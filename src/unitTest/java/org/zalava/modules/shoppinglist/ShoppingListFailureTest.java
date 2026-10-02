package org.zalava.modules.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zalava.api.InvocationContext;
import tools.jackson.databind.json.JsonMapper;

class ShoppingListFailureTest {
  @TempDir Path directory;

  @Test
  void normalizesNamesAndUpdatesExistingQuantitiesWithoutDuplicatingRows() {
    var repository = new SqliteShoppingListRepository(directory.resolve("shopping.sqlite"));
    assertThat(repository.listActiveItems()).isEmpty();
    repository.addItem(" Milk ", Optional.empty());
    repository.addItem("MILK", Optional.empty());
    repository.addItem("milk", Optional.of(" 2 bottles "));
    assertThat(repository.listActiveItems())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.name()).isEqualTo("Milk");
              assertThat(item.quantity()).contains("2 bottles");
            });
    assertThat(repository.removeItem("missing", RemovalReason.BOUGHT)).isEmpty();
    assertThatThrownBy(() -> repository.purchaseSummary(0)).hasMessageContaining("at least 1");
    assertThat(RemovalReason.from(null)).isEqualTo(RemovalReason.BOUGHT);
    assertThat(RemovalReason.from(" ")).isEqualTo(RemovalReason.BOUGHT);
    assertThat(RemovalReason.from(" BOUGHT ")).isEqualTo(RemovalReason.BOUGHT);
    assertThat(RemovalReason.from(" discarded ")).isEqualTo(RemovalReason.DISCARDED);
    assertThatThrownBy(() -> RemovalReason.from("invalid"))
        .isInstanceOf(IllegalArgumentException.class);
    for (String name : new String[] {null, " "}) {
      assertThatThrownBy(() -> repository.addItem(name, Optional.empty()))
          .hasMessage("name is required");
    }
  }

  @Test
  void rollsBackRemovalWhenPurchasePersistenceFails() throws Exception {
    Path database = directory.resolve("rollback.sqlite");
    var repository = new SqliteShoppingListRepository(database);
    repository.addItem("Milk", Optional.empty());
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute("drop table purchase_events");
    }
    assertThatThrownBy(() -> repository.removeItem("Milk", RemovalReason.BOUGHT))
        .hasMessageContaining("Unable to remove");
    assertThat(repository.listActiveItems())
        .singleElement()
        .satisfies(item -> assertThat(item.name()).isEqualTo("Milk"));
    assertThatThrownBy(() -> repository.purchaseSummary(30))
        .hasMessageContaining("Unable to summarize");
  }

  @Test
  void surfacesStorageFailuresAndRejectsInvalidProviderInputs() throws Exception {
    Path database = directory.resolve("failure.sqlite");
    var repository = new SqliteShoppingListRepository(database);
    var provider = new ShoppingListProvider(repository);
    var json = new JsonMapper();
    assertThat(provider.capabilities().supportsTools()).isTrue();
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "unknown",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            json.createObjectNode(),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    InvocationContext.system()))
        .hasMessageContaining("Unknown shopping-list tool");
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "add_item",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            null,
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    InvocationContext.system()))
        .hasMessage("name is required");
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "add_item",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            json.createObjectNode().put("name", " "),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    InvocationContext.system()))
        .hasMessage("name is required");
    provider.callTool(
        "add_item",
        new tools.jackson.databind.json.JsonMapper()
            .convertValue(
                json.createObjectNode().put("name", "Milk").put("quantity", " "),
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
        InvocationContext.system());
    provider.callTool(
        "add_item",
        new tools.jackson.databind.json.JsonMapper()
            .convertValue(
                json.createObjectNode().put("name", "Eggs").putNull("quantity"),
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
        InvocationContext.system());
    assertThat(
            provider
                .callTool(
                    "purchase_summary",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            null,
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    InvocationContext.system())
                .success())
        .isTrue();
    Files.delete(database);
    Files.createDirectory(database);
    assertThatThrownBy(repository::listActiveItems).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.addItem("Milk", Optional.empty()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.removeItem("Milk", RemovalReason.DISCARDED))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.purchaseSummary(7))
        .isInstanceOf(IllegalStateException.class);
    Path blocker = directory.resolve("not-a-directory");
    Files.writeString(blocker, "fixture");
    assertThatThrownBy(() -> new SqliteShoppingListRepository(blocker.resolve("database")))
        .isInstanceOf(IllegalStateException.class);
  }
}
