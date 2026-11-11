package trainboy15.smpplugin.database;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import trainboy15.smpplugin.commands.Auction;
import trainboy15.smpplugin.commands.Orders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTables();
    }

    private void connect() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File dbFile = new File(plugin.getDataFolder(), "smpdata.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not connect to smpdata.db: " + e.getMessage());
        }
    }

    private void createTables() {
        String auctionsTable = "CREATE TABLE IF NOT EXISTS auctions (" +
                "listing_id VARCHAR(36) PRIMARY KEY, " +
                "seller_uuid VARCHAR(36) NOT NULL, " +
                "seller_name VARCHAR(16) NOT NULL, " +
                "item_data TEXT NOT NULL, " +
                "price DOUBLE NOT NULL, " +
                "timestamp BIGINT NOT NULL" +
                ");";

        String settingsTable = "CREATE TABLE IF NOT EXISTS player_settings (" +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "setting_key VARCHAR(64) NOT NULL, " +
                "setting_value BOOLEAN NOT NULL, " +
                "PRIMARY KEY (player_uuid, setting_key)" +
                ");";

        String ordersTable = "CREATE TABLE IF NOT EXISTS orders (" +
                "order_id VARCHAR(36) PRIMARY KEY, " +
                "requester_uuid VARCHAR(36) NOT NULL, " +
                "requester_name VARCHAR(16) NOT NULL, " +
                "item_data TEXT NOT NULL, " +
                "amount_requested INT NOT NULL, " +
                "amount_delivered INT NOT NULL DEFAULT 0, " +
                "unclaimed_items INT NOT NULL DEFAULT 0, " +
                "price_per_item DOUBLE NOT NULL, " +
                "timestamp BIGINT NOT NULL" +
                ");";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(auctionsTable);
            stmt.execute(settingsTable);
            stmt.execute(ordersTable);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create database tables: " + e.getMessage());
        }
    }

    // ==================== AUCTIONS ====================

    public List<Auction.AuctionItem> loadAllAuctions() {
        List<Auction.AuctionItem> loaded = new ArrayList<>();
        String sql = "SELECT * FROM auctions;";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                UUID listingId = UUID.fromString(rs.getString("listing_id"));
                UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
                String sellerName = rs.getString("seller_name");
                ItemStack item = itemFromBase64(rs.getString("item_data"));
                double price = rs.getDouble("price");
                long timestamp = rs.getLong("timestamp");

                if (item != null) {
                    loaded.add(new Auction.AuctionItem(listingId, sellerUuid, sellerName, item, price, timestamp));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load auctions from database: " + e.getMessage());
        }
        return loaded;
    }

    public void insertAuctionAsync(Auction.AuctionItem item) {
        String base64Item = itemToBase64(item.getItem());
        if (base64Item == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO auctions (listing_id, seller_uuid, seller_name, item_data, price, timestamp) VALUES (?, ?, ?, ?, ?, ?);";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, item.getListingId().toString());
                pstmt.setString(2, item.getSellerUUID().toString());
                pstmt.setString(3, item.getSellerName());
                pstmt.setString(4, base64Item);
                pstmt.setDouble(5, item.getPrice());
                pstmt.setLong(6, item.getTimestamp());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to insert auction into database: " + e.getMessage());
            }
        });
    }

    public void deleteAuctionAsync(UUID listingId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM auctions WHERE listing_id = ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, listingId.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete auction from database: " + e.getMessage());
            }
        });
    }

    // ==================== SETTINGS ====================

    public Map<String, Boolean> loadPlayerSettings(UUID playerUuid) {
        Map<String, Boolean> settingsMap = new HashMap<>();
        String sql = "SELECT setting_key, setting_value FROM player_settings WHERE player_uuid = ?;";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    settingsMap.put(rs.getString("setting_key"), rs.getBoolean("setting_value"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load settings for " + playerUuid + ": " + e.getMessage());
        }
        return settingsMap;
    }

    public void savePlayerSettingAsync(UUID playerUuid, String key, boolean value) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO player_settings (player_uuid, setting_key, setting_value) " +
                         "VALUES (?, ?, ?) " +
                         "ON CONFLICT(player_uuid, setting_key) DO UPDATE SET " +
                         "setting_value = excluded.setting_value;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.setString(2, key);
                pstmt.setBoolean(3, value);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to save setting for " + playerUuid + ": " + e.getMessage());
            }
        });
    }

    // ==================== ORDERS ====================

    public List<Orders.OrderRequest> loadAllOrders() {
        List<Orders.OrderRequest> loaded = new ArrayList<>();
        String sql = "SELECT * FROM orders;";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                UUID orderId = UUID.fromString(rs.getString("order_id"));
                UUID requesterUuid = UUID.fromString(rs.getString("requester_uuid"));
                String requesterName = rs.getString("requester_name");
                ItemStack templateItem = itemFromBase64(rs.getString("item_data"));
                int amountRequested = rs.getInt("amount_requested");
                int amountDelivered = rs.getInt("amount_delivered");
                int unclaimedItems = rs.getInt("unclaimed_items");
                double pricePerItem = rs.getDouble("price_per_item");
                long timestamp = rs.getLong("timestamp");

                if (templateItem != null) {
                    loaded.add(new Orders.OrderRequest(
                            orderId, requesterUuid, requesterName, templateItem,
                            amountRequested, amountDelivered, unclaimedItems, pricePerItem, timestamp
                    ));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load orders from database: " + e.getMessage());
        }
        return loaded;
    }

    public void insertOrderAsync(Orders.OrderRequest order) {
        String base64Item = itemToBase64(order.getTemplateItem());
        if (base64Item == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO orders (order_id, requester_uuid, requester_name, item_data, amount_requested, amount_delivered, unclaimed_items, price_per_item, timestamp) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, order.getOrderId().toString());
                pstmt.setString(2, order.getRequesterUUID().toString());
                pstmt.setString(3, order.getRequesterName());
                pstmt.setString(4, base64Item);
                pstmt.setInt(5, order.getAmountRequested());
                pstmt.setInt(6, order.getAmountDelivered());
                pstmt.setInt(7, order.getUnclaimedItems());
                pstmt.setDouble(8, order.getPricePerItem());
                pstmt.setLong(9, order.getTimestamp());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to insert order into database: " + e.getMessage());
            }
        });
    }

    public void updateOrderProgressAsync(UUID orderId, int amountDelivered, int unclaimedItems) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "UPDATE orders SET amount_delivered = ?, unclaimed_items = ? WHERE order_id = ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, amountDelivered);
                pstmt.setInt(2, unclaimedItems);
                pstmt.setString(3, orderId.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update order progress in database: " + e.getMessage());
            }
        });
    }

    public void deleteOrderAsync(UUID orderId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM orders WHERE order_id = ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, orderId.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete order from database: " + e.getMessage());
            }
        });
    }

    // ==================== SERIALIZATION & CLEANUP ====================

    private String itemToBase64(ItemStack item) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(item);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to serialize ItemStack: " + e.getMessage());
            return null;
        }
    }

    private ItemStack itemFromBase64(String data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack) dataInput.readObject();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to deserialize ItemStack: " + e.getMessage());
            return null;
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error closing smpdata.db: " + e.getMessage());
        }
    }
}
