package org.zalava.modules.shoppinglist;

import java.util.List;
import org.zalava.web.WebExtensionDescriptor;
import org.zalava.web.WebExtensionRegistry;
import org.zalava.web.ZalavaWebExtension;
import org.zalava.web.ZalavaWebRequest;
import org.zalava.web.ZalavaWebResponse;

final class ShoppingListWebExtension implements ZalavaWebExtension {

  private final ShoppingListRepository repository;

  ShoppingListWebExtension(ShoppingListRepository repository) {
    this.repository = repository;
  }

  @Override
  public WebExtensionDescriptor descriptor() {
    return new WebExtensionDescriptor(
        ShoppingListSeaModule.MODULE_ID,
        "shopping-list",
        "Shopping List",
        "View the household shopping list and mark items bought.");
  }

  @Override
  public void register(WebExtensionRegistry registry) {
    registry
        .page("shopping-list")
        .title("Shopping List")
        .description("Household shopping list")
        .navSection("apps")
        .get("/", this::render)
        .post("/items/bought", this::markBought);
  }

  private ZalavaWebResponse render(ZalavaWebRequest request) {
    return ZalavaWebResponse.html(page(null));
  }

  private ZalavaWebResponse markBought(ZalavaWebRequest request) {
    String itemName = request.firstFormParameter("name").orElse("");
    if (itemName.isBlank()) {
      return ZalavaWebResponse.html(400, page("Missing item name."));
    }
    return repository
        .removeItem(itemName, RemovalReason.BOUGHT)
        .map(item -> ZalavaWebResponse.html(page("Bought " + item.name() + ".")))
        .orElseGet(
            () ->
                ZalavaWebResponse.html(
                    404,
                    page(
                        "Item not found: "
                            + ShoppingListNames.requireDisplayName(itemName)
                            + ".")));
  }

  private String page(String message) {
    List<ShoppingListItem> items = repository.listActiveItems();
    StringBuilder html = new StringBuilder();
    html.append(
        """
                <header class="dashboard-heading mb-6">
                    <p class="has-text-primary has-text-weight-bold is-uppercase is-size-7 mb-2">Household</p>
                    <h1 class="title is-2 mb-3">Shopping List</h1>
                    <p class="subtitle is-5 has-text-grey">Check an item when it has been bought.</p>
                </header>
                """);
    if (message != null && !message.isBlank()) {
      html.append("<article class=\"message is-info\"><div class=\"message-body\">")
          .append(escape(message))
          .append("</div></article>");
    }
    html.append("<section class=\"box dashboard-activity\">");
    if (items.isEmpty()) {
      html.append(
          """
                    <div class="notification is-light has-text-centered py-6">
                        <p class="has-text-weight-semibold mb-1">The shopping list is empty.</p>
                        <p class="has-text-grey">Add items through chat or another SEA channel.</p>
                    </div>
                    """);
    } else {
      html.append("<div class=\"activity-list\">");
      for (ShoppingListItem item : items) {
        html.append(
            """
                        <form class="activity-entry" method="post" action="/apps/zalava-module-shopping-list/shopping-list/items/bought">
                            <label class="checkbox activity-copy">
                                <input type="checkbox" name="bought" value="true" onchange="this.form.submit()">
                                <input type="hidden" name="name" value="%s">
                                <span class="has-text-weight-semibold">%s</span>
                                %s
                            </label>
                            <button class="button is-small is-light" type="submit">Bought</button>
                        </form>
                        """
                .formatted(
                    escape(item.name()),
                    escape(item.name()),
                    item.quantity()
                        .map(
                            quantity ->
                                "<span class=\"has-text-grey is-size-7 ml-2\">"
                                    + escape(quantity)
                                    + "</span>")
                        .orElse("")));
      }
      html.append("</div>");
    }
    html.append("</section>");
    return html.toString();
  }

  private static String escape(String value) {
    return value == null
        ? ""
        : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }
}
