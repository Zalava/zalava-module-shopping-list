package org.zalava.modules.shoppinglist;

import java.util.List;
import java.util.Optional;

interface ShoppingListRepository {

  ShoppingListItem addItem(String name, Optional<String> quantity);

  List<ShoppingListItem> listActiveItems();

  Optional<ShoppingListItem> removeItem(String name, RemovalReason reason);

  List<PurchaseSummaryItem> purchaseSummary(int days);
}
