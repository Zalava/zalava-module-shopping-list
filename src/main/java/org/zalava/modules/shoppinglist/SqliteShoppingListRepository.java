package org.zalava.modules.shoppinglist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SqliteShoppingListRepository implements ShoppingListRepository {

  private final Path databasePath;
  private final Clock clock;

  SqliteShoppingListRepository(Path databasePath) {
    this(databasePath, Clock.systemUTC());
  }

  SqliteShoppingListRepository(Path databasePath, Clock clock) {
    this.databasePath = databasePath;
    this.clock = clock;
    loadDriver();
    initialize();
  }

  @Override
  public ShoppingListItem addItem(String name, Optional<String> quantity) {
    String displayName = ShoppingListNames.requireDisplayName(name);
    String normalizedName = ShoppingListNames.normalize(displayName);
    Optional<String> normalizedQuantity =
        quantity.map(String::trim).filter(value -> !value.isBlank());
    Instant now = clock.instant();

    try (Connection connection = connection()) {
      Optional<ShoppingListItem> existing = findActive(connection, normalizedName);
      if (existing.isPresent()) {
        if (normalizedQuantity.isPresent()) {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                            update shopping_list_items
                               set quantity = ?,
                                   updated_at = ?
                             where normalized_name = ? and active = 1
                            """)) {
            statement.setString(1, normalizedQuantity.get());
            statement.setString(2, now.toString());
            statement.setString(3, normalizedName);
            statement.executeUpdate();
          }
          return new ShoppingListItem(existing.get().name(), normalizedQuantity, now);
        }
        return existing.get();
      }

      try (PreparedStatement statement =
          connection.prepareStatement(
              """
                    insert into shopping_list_items(normalized_name, display_name, quantity, active, created_at, updated_at)
                    values (?, ?, ?, 1, ?, ?)
                    """)) {
        statement.setString(1, normalizedName);
        statement.setString(2, displayName);
        statement.setString(3, normalizedQuantity.orElse(null));
        statement.setString(4, now.toString());
        statement.setString(5, now.toString());
        statement.executeUpdate();
      }
      return new ShoppingListItem(displayName, normalizedQuantity, now);
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to add shopping-list item", exception);
    }
  }

  @Override
  public List<ShoppingListItem> listActiveItems() {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                     select display_name, quantity, updated_at
                       from shopping_list_items
                      where active = 1
                      order by display_name collate nocase
                     """);
        ResultSet resultSet = statement.executeQuery()) {
      List<ShoppingListItem> items = new ArrayList<>();
      while (resultSet.next()) {
        items.add(item(resultSet));
      }
      return items;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list shopping-list items", exception);
    }
  }

  @Override
  public Optional<ShoppingListItem> removeItem(String name, RemovalReason reason) {
    String normalizedName = ShoppingListNames.normalize(name);
    Instant now = clock.instant();
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        Optional<ShoppingListItem> existing = findActive(connection, normalizedName);
        if (existing.isEmpty()) {
          connection.rollback();
          return Optional.empty();
        }

        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                        update shopping_list_items
                           set active = 0,
                               removed_reason = ?,
                               updated_at = ?
                         where normalized_name = ? and active = 1
                        """)) {
          statement.setString(1, reason.value());
          statement.setString(2, now.toString());
          statement.setString(3, normalizedName);
          statement.executeUpdate();
        }

        if (reason == RemovalReason.BOUGHT) {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                            insert into purchase_events(normalized_name, display_name, quantity, purchased_at)
                            values (?, ?, ?, ?)
                            """)) {
            ShoppingListItem item = existing.get();
            statement.setString(1, normalizedName);
            statement.setString(2, item.name());
            statement.setString(3, item.quantity().orElse(null));
            statement.setString(4, now.toString());
            statement.executeUpdate();
          }
        }
        connection.commit();
        return existing;
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to remove shopping-list item", exception);
    }
  }

  @Override
  public List<PurchaseSummaryItem> purchaseSummary(int days) {
    if (days < 1) {
      throw new IllegalArgumentException("days must be at least 1");
    }
    Instant since = clock.instant().minusSeconds(days * 24L * 60L * 60L);
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                     select display_name, count(*) as purchases
                       from purchase_events
                      where purchased_at >= ?
                      group by normalized_name
                      order by purchases desc, display_name collate nocase
                     """)) {
      statement.setString(1, since.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        List<PurchaseSummaryItem> summary = new ArrayList<>();
        while (resultSet.next()) {
          summary.add(
              new PurchaseSummaryItem(
                  resultSet.getString("display_name"), resultSet.getInt("purchases")));
        }
        return summary;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to summarize shopping-list purchases", exception);
    }
  }

  private void initialize() {
    try {
      Path parent = databasePath.toAbsolutePath().normalize().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (Connection connection = connection();
          Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            """
                        create table if not exists shopping_list_items (
                            id integer primary key autoincrement,
                            normalized_name text not null,
                            display_name text not null,
                            quantity text,
                            active integer not null,
                            removed_reason text,
                            created_at text not null,
                            updated_at text not null
                        )
                        """);
        statement.executeUpdate(
            """
                        create unique index if not exists active_shopping_list_items_name
                            on shopping_list_items(normalized_name)
                         where active = 1
                        """);
        statement.executeUpdate(
            """
                        create table if not exists purchase_events (
                            id integer primary key autoincrement,
                            normalized_name text not null,
                            display_name text not null,
                            quantity text,
                            purchased_at text not null
                        )
                        """);
      }
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Unable to initialize shopping-list database " + databasePath, exception);
    }
  }

  private Optional<ShoppingListItem> findActive(Connection connection, String normalizedName)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
                select display_name, quantity, updated_at
                  from shopping_list_items
                 where normalized_name = ? and active = 1
                """)) {
      statement.setString(1, normalizedName);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        return Optional.of(item(resultSet));
      }
    }
  }

  private Connection connection() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
  }

  private static void loadDriver() {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("SQLite JDBC driver is not available", exception);
    }
  }

  private static ShoppingListItem item(ResultSet resultSet) throws SQLException {
    return new ShoppingListItem(
        resultSet.getString("display_name"),
        Optional.ofNullable(resultSet.getString("quantity")),
        Instant.parse(resultSet.getString("updated_at")));
  }
}
