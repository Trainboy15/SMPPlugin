package trainboy15.smpplugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class AuctionDatabase {

    private final JavaPlugin plugin;
    private Connection connection;

    public AuctionDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTable();
    }

    private void connect() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File dbFile = new File(plugin.getDataFolder(), "auctions.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not connect to auctions.db: " + e.getMessage());
        }
    }

    private void createTable() {
        if (connection == null) return;
        String sql = "CREATE TABLE IF NOT EXISTS auctions (" +
                     "listing_id VARCHAR(36) PRIMARY KEY, " +
                     "seller_uuid VARCHAR(36) NOT NULL, " +
                     "seller_name VARCHAR(16) NOT NULL, " +
                     "item_data TEXT NOT NULL, " +
                     "price DOUBLE NOT NULL, " +
                     "timestamp BIGINT NOT NULL" +
                     ");";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create auctions table: " + e.getMessage());
        }
    }

    public List<Auction.AuctionItem> loadAll() {
        List<Auction.AuctionItem> loaded = new ArrayList<>();
        if (connection == null) return loaded;

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

    public void insertAsync(Auction.AuctionItem auctionItem) {
        if (connection == null) return;
        String base64Item = itemToBase64(auctionItem.getItem());
        if (base64Item == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO auctions (listing_id, seller_uuid, seller_name, item_data, price, timestamp) VALUES (?, ?, ?, ?, ?, ?);";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, auctionItem.getListingId().toString());
                pstmt.setString(2, auctionItem.getSellerUUID().toString());
                pstmt.setString(3, auctionItem.getSellerName());
                pstmt.setString(4, base64Item);
                pstmt.setDouble(5, auctionItem.getPrice());
                pstmt.setLong(6, auctionItem.getTimestamp());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to save listing to database: " + e.getMessage());
            }
        });
    }

    public void deleteAsync(UUID listingId) {
        if (connection == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM auctions WHERE listing_id = ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, listingId.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete listing from database: " + e.getMessage());
            }
        });
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error closing auctions.db: " + e.getMessage());
        }
    }

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
}
