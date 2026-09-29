package org.zalava.modules.shoppinglist;

import org.zalava.ModuleDescriptor;
import org.zalava.ProviderFactory;
import org.zalava.SeaModule;
import org.zalava.web.SeaWebExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

public final class ShoppingListSeaModule implements SeaModule {

    static final String MODULE_ID = "zalava-module-shopping-list";

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(
                MODULE_ID,
                version(),
                "Shopping List",
                "Private household shopping-list tools backed by local SQLite storage"
        );
    }

    @Override
    public List<ProviderFactory> providerFactories() {
        return List.of(new ShoppingListProviderFactory());
    }

    @Override
    public List<SeaWebExtension> webExtensions() {
        return List.of(new ShoppingListWebExtension(new SqliteShoppingListRepository(ShoppingListStorage.path())));
    }

    static String version() {
        Properties properties = new Properties();
        try (InputStream input = ShoppingListSeaModule.class.getResourceAsStream("/module.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing module version metadata");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read module version metadata", exception);
        }
        return properties.getProperty("module.version");
    }
}
