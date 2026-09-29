package org.zalava.modules.shoppinglist;

import org.zalava.ProviderFactory;
import org.zalava.ProviderFactoryContext;
import org.zalava.ProviderFactoryDescriptor;
import org.zalava.ZalavaProvider;

import java.util.List;

final class ShoppingListProviderFactory implements ProviderFactory {

    @Override
    public ProviderFactoryDescriptor descriptor() {
        return new ProviderFactoryDescriptor(
                "shopping-list-household",
                ShoppingListSeaModule.MODULE_ID,
                "shopping-list",
                "Household shopping list factory",
                "Creates the private household shopping-list provider"
        );
    }

    @Override
    public List<ZalavaProvider> createProviders(ProviderFactoryContext context) {
        return List.of(new ShoppingListProvider(new SqliteShoppingListRepository(ShoppingListStorage.path())));
    }
}
