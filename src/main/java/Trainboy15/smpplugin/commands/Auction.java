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
        size = Math.max(18, Math.min(54, size - (size % 9))); // Minimum 18 so there is room for items + menu bar
        int itemSlots = size - 9; // Reserve the bottom row (9 slots) for menu buttons
    
        // Filter listings if a search query was provided
        List<AuctionItem> filtered = listings;
        if (searchQuery != null && !searchQuery.isEmpty()) {
            String queryLower = searchQuery.toLowerCase();
            filtered = listings.stream()
                    .filter(item -> matchesSearch(item.getItem(), queryLower))
                    .collect(Collectors.toList());
        }
    
        int maxPages = Math.max(1, (int) Math.ceil((double) filtered.size() / itemSlots));
        int currentPage = Math.max(1, Math.min(page, maxPages));
    
        String rawTitle = plugin.getConfig().getString("ah.title", "&8Auction (%page%/%maxpage%)");
        String title = color(rawTitle
                .replace("%page%", String.valueOf(currentPage))
                .replace("%maxpage%", String.valueOf(maxPages)));
    
        Inventory inventory = Bukkit.createInventory(null, size, title);
    
        // Populate auction listings (only up to itemSlots)
        int startIndex = (currentPage - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (startIndex + i) < filtered.size(); i++) {
            AuctionItem auctionItem = filtered.get(startIndex + i);
            inventory.setItem(i, createDisplayItem(auctionItem));
        }
    
        // Populate menu items dynamically from config keys
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("ah.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < size) {
                        // Pass index 0 as the default selected option (update this with player's actual state later)
                        inventory.setItem(slot, createMenuItem(slot, 0));
                    }
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid slot number in config: " + key);
                }
            }
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
    private ItemStack createMenuItem(int slot, int selectedIndex) {
        String path = "ah.items." + slot;
        String matName = plugin.getConfig().getString(path + ".material", "STONE");
        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.STONE;

        ItemStack display = new ItemStack(material);
        ItemMeta meta = display.getItemMeta();

        if (meta != null) {
            String name = plugin.getConfig().getString(path + ".name", "");
            meta.setDisplayName(color(name));

            List<String> rawOptions = plugin.getConfig().getStringList(path + ".options");
            String action = plugin.getConfig().getString(path + ".action", "");
            String selectedPrefix = plugin.getConfig().getString("ah.OptionSelectedPrefix", "&b• &b");
            String unselectedPrefix = plugin.getConfig().getString("ah.OptionUnselectedPrefix", "&f◦ &f");

            List<String> lore = new ArrayList<>();
            for (int i = 0; i < rawOptions.size(); i++) {
                String line = rawOptions.get(i);
                // Apply bullet prefixes only to toggleable lists like filter and sort
                if (action.equalsIgnoreCase("filter") || action.equalsIgnoreCase("sort")) {
                    String prefix = (i == selectedIndex) ? selectedPrefix : unselectedPrefix;
                    lore.add(color(prefix + line));
                } else {
                    lore.add(color(line));
                }
            }

            meta.setLore(lore);
            display.setItemMeta(meta);
        }

        return display;
    }

private final Map<UUID, AuctionSession> sessions = new HashMap<>();

@EventHandler
public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player)) return;
    Player player = (Player) event.getWhoClicked();

    // Check if the clicked inventory is the Auction House
    String titlePrefix = color(plugin.getConfig().getString("ah.title", "&8Auction").split("%page%")[0]);
    if (!event.getView().getTitle().startsWith(titlePrefix)) return;

    event.setCancelled(true);

    if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
        return;
    }

    ItemStack clicked = event.getCurrentItem();
    if (clicked == null || clicked.getType() == Material.AIR) return;

    int slot = event.getSlot();
    int inventorySize = event.getView().getTopInventory().getSize();
    int itemSlots = inventorySize - 9;

    // 1. Check if the player clicked the bottom menu bar
    if (slot >= itemSlots) {
        handleMenuClick(player, slot, event.isRightClick());
        return;
    }

    // 2. Otherwise, the player clicked an auction listing
    AuctionItem targetListing = findMatchingListing(clicked);
    if (targetListing == null) {
        player.sendMessage(ChatColor.RED + "This item is no longer available.");
        player.closeInventory();
        return;
    }

    handlePurchase(player, targetListing);
}

    private void handleMenuClick(Player player, int slot, boolean isRightClick) {
        String path = "ah.items." + slot;
        String action = plugin.getConfig().getString(path + ".action");
        if (action == null) return; // Empty glass pane / filler slot

        AuctionSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new AuctionSession());
        List<String> options = plugin.getConfig().getStringList(path + ".options");

        switch (action.toLowerCase()) {
            case "next":
                session.page++;
                openInventory(player, session);
                break;

            case "previous":
                session.page = Math.max(1, session.page - 1);
                openInventory(player, session);
                break;

            case "filter":
                if (!options.isEmpty()) {
                    // Left-click goes forward, right-click goes backward
                    int step = isRightClick ? -1 : 1;
                    session.filterIndex = Math.floorMod(session.filterIndex + step, options.size());
                    session.page = 1; // Reset to page 1 when changing filters
                    openInventory(player, session);
                }
                break;

            case "sort":
                if (!options.isEmpty()) {
                    int step = isRightClick ? -1 : 1;
                    session.sortIndex = Math.floorMod(session.sortIndex + step, options.size());
                    session.page = 1;
                    openInventory(player, session);
                }
                break;

            case "refresh":
                openInventory(player, session);
                break;

            case "own":
                session.viewingOwnItems = !session.viewingOwnItems;
                session.page = 1;
                openInventory(player, session);
                break;

            case "search":
                player.closeInventory();
                player.sendMessage(color("&eType your search query in chat (or type &ccancel &eto abort):"));
                // Flag player for chat input listener here
                break;
        }
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