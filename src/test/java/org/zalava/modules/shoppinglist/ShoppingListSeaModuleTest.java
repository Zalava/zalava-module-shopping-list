package org.zalava.modules.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zalava.api.ZalavaOperationResult;
import org.zalava.api.ZalavaProvider;
import org.zalava.api.ZalavaToolDescriptor;
import org.zalava.api.extensions.web.ZalavaWebResponse;
import org.zalava.api.testing.ConfigFixture;
import org.zalava.api.testing.ModuleContractKit;
import org.zalava.api.testing.ProviderFixture;
import org.zalava.api.testing.WebExtensionFixture;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the
 * released contract kit. The module owns a local SQLite database, so each test points its
 * module-owned storage property at a {@link TempDir}. Host-owned resolution, validation,
 * permissions and persistence stay covered by SEA.
 */
class ShoppingListSeaModuleTest {

  private static final String MODULE_ID = "zalava-module-shopping-list";
  private static final String FACTORY_ID = "shopping-list-household";
  private static final String PROVIDER_ID = "shopping-list-household";
  private static final String SQLITE_PATH_PROPERTY = "sea.module.shopping-list.sqlite.path";

  @TempDir Path tempDir;

  private ModuleContractKit kit;

  @BeforeEach
  void loadTheBuiltArtifact() {
    System.setProperty(SQLITE_PATH_PROPERTY, tempDir.resolve("shopping-list.sqlite").toString());
    Path artifact = Path.of(System.getProperty("module.artifact"));
    String version = System.getProperty("module.version");
    kit = ModuleContractKit.load(artifact, List.of(), MODULE_ID, version);
  }

  @AfterEach
  void closeTheArtifact() throws Exception {
    try {
      if (kit != null) {
        kit.close();
      }
    } finally {
      System.clearProperty(SQLITE_PATH_PROPERTY);
    }
  }

  @Test
  void loadsTheModuleFromTheBuiltArtifact() {
    assertThat(kit.module().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
    assertThat(
            kit.module().getClass().getProtectionDomain().getCodeSource().getLocation().toString())
        .endsWith(".jar");
  }

  @Test
  void exposesTheModuleOwnedDescriptorConfigurationAndWebContract() {
    assertThat(kit.moduleId()).isEqualTo(MODULE_ID);
    assertThat(kit.version()).isEqualTo(System.getProperty("module.version"));

    Map<String, Object> schema = kit.module().configuration().jsonSchema();
    assertThat(schema).containsEntry("type", "object");
    assertThat(schema.get("properties")).isInstanceOf(Map.class);
    assertThat((Map<?, ?>) schema.get("properties")).isEmpty();

    assertThat(kit.module().providerFactories()).hasSize(1);
    var factory = kit.module().providerFactories().getFirst().descriptor();
    assertThat(factory.factoryId()).isEqualTo(FACTORY_ID);
    assertThat(factory.moduleId()).isEqualTo(MODULE_ID);
    assertThat(factory.providerType()).isEqualTo("shopping-list");

    assertThat(kit.module().webExtensions()).hasSize(1);
    assertThat(kit.module().webExtensions().getFirst().descriptor().extensionId())
        .isEqualTo("shopping-list");
  }

  @Test
  void createsTheHouseholdProviderAndDeclaresItsTools() {
    try (ProviderFixture providers = kit.providers()) {
      ZalavaProvider provider = providers.requireProvider(PROVIDER_ID);

      assertThat(providers.providers()).hasSize(1);
      assertThat(provider.descriptor().moduleId()).isEqualTo(MODULE_ID);
      assertThat(provider.descriptor().providerType()).isEqualTo("shopping-list");
      assertThat(provider.descriptor().policyTags())
          .contains("sea_backed", "shopping-list", "household-data", "local-storage");
      assertThat(provider.capabilities().supportsTools()).isTrue();
      assertThat(provider.listTools().stream().map(ZalavaToolDescriptor::name))
          .containsExactly(
              "add_item", "list_items", "mark_bought", "remove_item", "purchase_summary");
      assertThat(provider.listTools())
          .filteredOn(ZalavaToolDescriptor::sideEffecting)
          .extracting(ZalavaToolDescriptor::name)
          .containsExactly("add_item", "mark_bought", "remove_item");
      assertThat(providers.requireTool(PROVIDER_ID, "add_item").inputSchema())
          .containsEntry("required", List.of("name"));
    }
  }

  @Test
  void createsItsProviderWhenTheHostSuppliesScopedConfigurationAndSecrets() {
    ConfigFixture configuration =
        ConfigFixture.empty()
            .factoryConfiguration(MODULE_ID, FACTORY_ID, Map.of("sqlitePath", databasePath()))
            .secrets(MODULE_ID, reference -> Optional.of(reference.toCharArray()));

    try (ProviderFixture providers = kit.providers(configuration)) {
      assertThat(providers.requireProvider(PROVIDER_ID).listTools()).hasSize(5);
    }
  }

  @Test
  void addsListsBuysDiscardsSummarizesAndPersistsInSqlite() {
    try (ProviderFixture providers = kit.providers()) {
      assertThat(
              text(
                  providers.invoke(
                      PROVIDER_ID,
                      "add_item",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments().put("name", "eggs").put("quantity", "2 packs"),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .isEqualTo("Added eggs");
      assertThat(
              text(
                  providers.invoke(
                      PROVIDER_ID,
                      "list_items",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .isEqualTo("List: eggs (2 packs)");
      assertThat(
              text(
                  providers.invoke(
                      PROVIDER_ID,
                      "mark_bought",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments().put("name", "EGGS"),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .isEqualTo("Bought eggs");

      providers.invoke(
          PROVIDER_ID,
          "add_item",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  arguments().put("name", "bread"),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
      assertThat(
              text(
                  providers.invoke(
                      PROVIDER_ID,
                      "remove_item",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments().put("name", "bread").put("reason", "discarded"),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .isEqualTo("Removed bread");

      assertThat(
              text(
                  providers.invoke(
                      PROVIDER_ID,
                      "purchase_summary",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments().put("days", 7),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .isEqualTo("Purchases: eggs x1");

      providers.invoke(
          PROVIDER_ID,
          "add_item",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  arguments().put("name", "milk"),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
    }

    try (ProviderFixture reopened = kit.providers()) {
      assertThat(
              text(
                  reopened.invoke(
                      PROVIDER_ID,
                      "list_items",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .isEqualTo("List: milk");
    }
  }

  @Test
  void rejectsMissingItemsInvalidArgumentsAndUnknownTools() {
    try (ProviderFixture providers = kit.providers()) {
      ZalavaOperationResult missing =
          providers.invoke(
              PROVIDER_ID,
              "remove_item",
              new tools.jackson.databind.json.JsonMapper()
                  .convertValue(
                      arguments().put("name", "missing"),
                      new tools.jackson.core.type.TypeReference<
                          java.util.Map<String, Object>>() {}));
      assertThat(missing.success()).isFalse();
      assertThat(missing.content()).isEqualTo("Item not found: missing");

      assertThatThrownBy(
              () ->
                  providers.invoke(
                      PROVIDER_ID,
                      "add_item",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {})))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name is required");
      assertThatThrownBy(
              () ->
                  providers.invoke(
                      PROVIDER_ID,
                      "purchase_summary",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments().put("days", 0),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {})))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("days must be at least 1");
    }
  }

  @Test
  void registersTheShoppingListPageAndItsRoutes() {
    WebExtensionFixture web = kit.webExtensions();

    assertThat(web.pages())
        .extracting(WebExtensionFixture.RegisteredPage::pageId)
        .containsExactly("shopping-list");
    assertThat(web.routesFor("shopping-list"))
        .extracting(
            WebExtensionFixture.RegisteredRoute::method, WebExtensionFixture.RegisteredRoute::path)
        .containsExactly(tuple("GET", "/"), tuple("POST", "/items/bought"));
  }

  @Test
  void rendersItemsAndMarksThemBoughtThroughSyntheticRequests() {
    try (ProviderFixture providers = kit.providers()) {
      providers.invoke(
          PROVIDER_ID,
          "add_item",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  arguments().put("name", "milk").put("quantity", "1 bottle"),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
    }

    WebExtensionFixture web = kit.webExtensions();
    ZalavaWebResponse page = web.invoke("GET", "/");
    assertThat(page.status()).isEqualTo(200);
    assertThat(page.body()).contains("Shopping List", "milk", "1 bottle");

    ZalavaWebResponse bought = web.submit("/items/bought", Map.of("name", List.of("milk")));
    assertThat(bought.status()).isEqualTo(200);
    assertThat(bought.body()).contains("Bought milk.");
    assertThat(web.invoke("GET", "/").body()).doesNotContain("1 bottle");
  }

  @Test
  void escapesItemContentInThePage() {
    try (ProviderFixture providers = kit.providers()) {
      providers.invoke(
          PROVIDER_ID,
          "add_item",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  arguments().put("name", "<milk>").put("quantity", "\"large\""),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
    }

    ZalavaWebResponse page = kit.webExtensions().invoke("GET", "/");
    assertThat(page.body()).contains("&lt;milk&gt;", "&quot;large&quot;");
    assertThat(page.body()).doesNotContain("<milk>");
  }

  private String databasePath() {
    return tempDir.resolve("shopping-list.sqlite").toString();
  }

  private static ObjectNode arguments() {
    return JsonNodeFactory.instance.objectNode();
  }

  @SuppressWarnings("unchecked")
  private static String text(ZalavaOperationResult result) {
    return (String) ((Map<String, Object>) result.content()).get("text");
  }
}
