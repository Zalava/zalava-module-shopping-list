package org.zalava.modules.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.zalava.ZalavaOperationResult;
import org.zalava.ZalavaProvider;
import org.zalava.ZalavaToolDescriptor;
import org.zalava.testing.ConfigFixture;
import org.zalava.testing.ModuleContractKit;
import org.zalava.testing.ProviderFixture;
import org.zalava.testing.WebExtensionFixture;
import org.zalava.web.ZalavaWebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the released
 * contract kit. The module owns a local SQLite database, so each test points its module-owned storage
 * property at a {@link TempDir}. Host-owned resolution, validation, permissions and persistence stay
 * covered by SEA.
 */
class ShoppingListSeaModuleTest {

    private static final String MODULE_ID = "zalava-module-shopping-list";
    private static final String FACTORY_ID = "shopping-list-household";
    private static final String PROVIDER_ID = "shopping-list-household";
    private static final String SQLITE_PATH_PROPERTY = "sea.module.shopping-list.sqlite.path";

    @TempDir
    Path tempDir;

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
        assertThat(kit.module().getClass().getProtectionDomain().getCodeSource().getLocation().toString())
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
                    .containsExactly("add_item", "list_items", "mark_bought", "remove_item", "purchase_summary");
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
        ConfigFixture configuration = ConfigFixture.empty()
                .factoryConfiguration(MODULE_ID, FACTORY_ID, Map.of("sqlitePath", databasePath()))
                .secrets(MODULE_ID, reference -> Optional.of(reference.toCharArray()));

        try (ProviderFixture providers = kit.providers(configuration)) {
            assertThat(providers.requireProvider(PROVIDER_ID).listTools()).hasSize(5);
        }
    }

    @Test
    void addsListsBuysDiscardsSummarizesAndPersistsInSqlite() {
        try (ProviderFixture providers = kit.providers()) {
            assertThat(text(providers.invoke(PROVIDER_ID, "add_item",
                    arguments().put("name", "eggs").put("quantity", "2 packs")))).isEqualTo("Added eggs");
            assertThat(text(providers.invoke(PROVIDER_ID, "list_items", arguments())))
                    .isEqualTo("List: eggs (2 packs)");
            assertThat(text(providers.invoke(PROVIDER_ID, "mark_bought", arguments().put("name", "EGGS"))))
                    .isEqualTo("Bought eggs");

            providers.invoke(PROVIDER_ID, "add_item", arguments().put("name", "bread"));
            assertThat(text(providers.invoke(PROVIDER_ID, "remove_item",
                    arguments().put("name", "bread").put("reason", "discarded")))).isEqualTo("Removed bread");

            assertThat(text(providers.invoke(PROVIDER_ID, "purchase_summary", arguments().put("days", 7))))
                    .isEqualTo("Purchases: eggs x1");

            providers.invoke(PROVIDER_ID, "add_item", arguments().put("name", "milk"));
        }

        try (ProviderFixture reopened = kit.providers()) {
            assertThat(text(reopened.invoke(PROVIDER_ID, "list_items", arguments()))).isEqualTo("List: milk");
        }
    }

    @Test
    void rejectsMissingItemsInvalidArgumentsAndUnknownTools() {
        try (ProviderFixture providers = kit.providers()) {
            ZalavaOperationResult missing =
                    providers.invoke(PROVIDER_ID, "remove_item", arguments().put("name", "missing"));
            assertThat(missing.success()).isFalse();
            assertThat(missing.content()).isEqualTo("Item not found: missing");

            assertThatThrownBy(() -> providers.invoke(PROVIDER_ID, "add_item", arguments()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name is required");
            assertThatThrownBy(() -> providers.invoke(PROVIDER_ID, "purchase_summary", arguments().put("days", 0)))
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
                .extracting(WebExtensionFixture.RegisteredRoute::method, WebExtensionFixture.RegisteredRoute::path)
                .containsExactly(tuple("GET", "/"), tuple("POST", "/items/bought"));
    }

    @Test
    void rendersItemsAndMarksThemBoughtThroughSyntheticRequests() {
        try (ProviderFixture providers = kit.providers()) {
            providers.invoke(PROVIDER_ID, "add_item",
                    arguments().put("name", "milk").put("quantity", "1 bottle"));
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
            providers.invoke(PROVIDER_ID, "add_item",
                    arguments().put("name", "<milk>").put("quantity", "\"large\""));
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