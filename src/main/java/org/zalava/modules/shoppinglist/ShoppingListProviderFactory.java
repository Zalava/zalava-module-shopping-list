package org.zalava.modules.shoppinglist;

import java.util.List;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ProviderFactoryContext;
import org.zalava.api.ProviderFactoryDescriptor;
import org.zalava.api.ZalavaProvider;

final class ShoppingListProviderFactory implements ProviderFactory {

  @Override
  public ProviderFactoryDescriptor descriptor() {
    return new ProviderFactoryDescriptor(
        "shopping-list-household",
        ShoppingListSeaModule.MODULE_ID,
        "shopping-list",
        "Household shopping list factory",
        "Creates the private household shopping-list provider");
  }

  @Override
  public List<ZalavaProvider> createProviders(ProviderFactoryContext context) {
    return List.of(
        new ShoppingListProvider(new SqliteShoppingListRepository(ShoppingListStorage.path())));
  }
}
