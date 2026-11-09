package trainboy15.smpplugin.commands;

import trainboy15.smpplugin.SMPPlugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class Auction implements CommandExecutor, Listener {

    private final SMPPlugin plugin;
    // Stores active listings in memory (Note: Clears on server restart unless saved to a file/DB)
    private final List<AuctionItem> listings = new ArrayList<>();

    public Auction(SMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        // 1. /ah -> opens AH with all listings
        if (args.length == 0) {
            openInventory(player, 1, null);
            return true;
        }

        // 2. /ah sell <price>
        if (args[0].equalsIgnoreCase("sell")) {
            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "Usage: /ah sell <price>");
                return true;
            }

            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand == null || itemInHand.getType() == Material.AIR) {
                player.sendMessage(ChatColor.RED + "You must be holding an item to sell.");
                return true;
            }

            double price;
            try {
                price = Double.parseDouble(args[1]);
                if (price <= 0) {
                    player.sendMessage(ChatColor.RED + "Price must be greater than 0.");
                    return true;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price. Please enter a valid number.");
                return true;
            }

            // Clone the item before removing it from the player's hand
            listItem(player, itemInHand.clone(), price);
            player.getInventory().setItemInMainHand(null);
            player.sendMessage(ChatColor.GREEN + "Listed your item for $" + price + "!");
            return true;
        }

        // 3. /ah <item> -> searches for that item
        String searchQuery = String.join(" ", args);
        openInventory(player, 1, searchQuery);
        return true;
    }

    private void listItem(Player seller, ItemStack item, double price) {
        listings.add(new AuctionItem(seller.getUniqueId(), seller.getName(), item, price));
    }

    private void openInventory(Player player, int page, String searchQuery) {
        int size = plugin.getConfig().getInt("ah.size", 54);
        size = Math.max(9, Math.min(54, size - (size % 9)));

        // Filter listings if a search query was provided
        List<AuctionItem> filtered = listings;
        if (searchQuery != null && !searchQuery.isEmpty()) {
            String queryLower = searchQuery.toLowerCase();
            filtered = listings.stream()
                    .filter(item -> matchesSearch(item.getItem(), queryLower))
                    .collect(Collectors.toList());
        }

        int maxPages = Math.max(1, (int) Math.ceil((double) filtered.size() / size));
        int currentPage = Math.max(1, Math.min(page, maxPages));

        String rawTitle = plugin.getConfig().getString("ah.title", "&8Auction (%page%/%maxpage%)");
        String title = color(rawTitle
                .replace("%page%", String.valueOf(currentPage))
                .replace("%maxpage%", String.valueOf(maxPages)));

        Inventory inventory = Bukkit.createInventory(null, size, title);

        int startIndex = (currentPage - 1) * size;
        for (int i = 0; i < size && (startIndex + i) < filtered.size(); i++) {
            AuctionItem auctionItem = filtered.get(startIndex + i);
            inventory.setItem(i, createDisplayItem(auctionItem));
        }

        player.openInventory(inventory);
    }

    private boolean matchesSearch(ItemStack item, String queryLower) {
        // Check material name (e.g., "diamond_sword" -> "diamond sword")
        String matName = item.getType().name().toLowerCase().replace("_", " ");
        if (matName.contains(queryLower)) return true;

        // Check custom display name if the item has one
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String displayName = ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase();
            return displayName.contains(queryLower);
        }
        return false;
    }

    private ItemStack createDisplayItem(AuctionItem auctionItem) {
        ItemStack display = auctionItem.getItem().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Seller: &e" + auctionItem.getSellerName()));
            lore.add(color("&7Price: &a$" + auctionItem.getPrice()));
            lore.add(color("&eClick to buy!"));
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player buyer = (Player) event.getWhoClicked();

        // Check if the clicked inventory is the Auction House
        String titlePrefix = color(plugin.getConfig().getString("ah.title", "&8Auction").split("%page%")[0]);
        if (!event.getView().getTitle().startsWith(titlePrefix)) return;

        // Prevent players from taking display items or putting items into the GUI
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Match the clicked display item back to an active listing
        AuctionItem targetListing = findMatchingListing(clicked);
        if (targetListing == null) {
            buyer.sendMessage(ChatColor.RED + "This item is no longer available.");
            buyer.closeInventory();
            return;
        }

        handlePurchase(buyer, targetListing);
    }

    private AuctionItem findMatchingListing(ItemStack displayItem) {
        // Strip the last 4 lore lines we added in createDisplayItem to compare the original item
        ItemStack stripped = displayItem.clone();
        ItemMeta meta = stripped.getItemMeta();
        if (meta != null && meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore.size() >= 4) {
                lore = lore.subList(0, lore.size() - 4);
                meta.setLore(lore.isEmpty() ? null : lore);
                stripped.setItemMeta(meta);
            }
        }

        for (AuctionItem listing : listings) {
            if (listing.getItem().isSimilar(stripped) && listing.getItem().getAmount() == stripped.getAmount()) {
                return listing;
            }
        }
        return null;
    }

    private void handlePurchase(Player buyer, AuctionItem listing) {
        if (buyer.getUniqueId().equals(listing.getSellerUUID())) {
            buyer.sendMessage(ChatColor.RED + "You cannot buy your own listing!");
            return;
        }

        Economy econ = plugin.getEconomy();
        if (econ == null) {
            buyer.sendMessage(ChatColor.RED + "Economy system is not available.");
            return;
        }

        double price = listing.getPrice();
        if (!econ.has(buyer, price)) {
            buyer.sendMessage(ChatColor.RED + "You don't have enough money! Need $" + price);
            return;
        }

        if (buyer.getInventory().firstEmpty() == -1) {
            buyer.sendMessage(ChatColor.RED + "Your inventory is full!");
            return;
        }

        // Process payment and transfer item
        econ.withdrawPlayer(buyer, price);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.getSellerUUID());
        econ.depositPlayer(seller, price);

        listings.remove(listing);
        buyer.getInventory().addItem(listing.getItem());

        buyer.sendMessage(ChatColor.GREEN + "Bought " + listing.getItem().getAmount() + "x "
                + listing.getItem().getType().name() + " for $" + price + "!");

        if (seller.isOnline() && seller.getPlayer() != null) {
            seller.getPlayer().sendMessage(ChatColor.GREEN + buyer.getName() + " bought your "
                    + listing.getItem().getType().name() + " for $" + price + "!");
        }

        // Refresh the GUI
        openInventory(buyer, 1, null);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    // Helper class to represent a single auction listing
    private static class AuctionItem {
        private final UUID sellerUUID;
        private final String sellerName;
        private final ItemStack item;
        private final double price;
        public AuctionItem(UUID sellerUUID, String sellerName, ItemStack item, double price) {
            this.sellerUUID = sellerUUID;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
        }
        public UUID getSellerUUID() { return sellerUUID; }
        public String getSellerName() { return sellerName; }
        public ItemStack getItem() { return item; }
        public double getPrice() { return price; }
    }
}