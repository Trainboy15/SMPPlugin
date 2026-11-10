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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class Auction implements CommandExecutor, Listener {

    private final SMPPlugin plugin;
    // Stores active listings in memory (Note: Clears on server restart unless saved to a file/DB)
    private final List<AuctionItem> listings = new ArrayList<>();
    private final Map<UUID, AuctionSession> sessions = new HashMap<>();
    private final Set<UUID> awaitingSearchInput = new HashSet<>();

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
            AuctionSession session = new AuctionSession();
            sessions.put(player.getUniqueId(), session);
            openInventory(player, session);
            return true;
        }

        // 2. /ah sell <price>
        if (args[0].equalsIgnoreCase("sell")) {
            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "Usage: /ah sell <price>");
                return true;
            }

            int maxListings = plugin.getConfig().getInt("ah.maxListings", 18);
            long currentListings = listings.stream()
                    .filter(item -> item.getSellerUUID().equals(player.getUniqueId()))
                    .count();
            if (currentListings >= maxListings) {
                player.sendMessage(ChatColor.RED + "You have reached your maximum listing limit (" + maxListings + ").");
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
        AuctionSession session = new AuctionSession();
        session.searchQuery = String.join(" ", args);
        sessions.put(player.getUniqueId(), session);
        openInventory(player, session);
        return true;
    }

    private void listItem(Player seller, ItemStack item, double price) {
        listings.add(new AuctionItem(seller.getUniqueId(), seller.getName(), item, price, System.currentTimeMillis()));
    }

    private void openInventory(Player player, int page, String searchQuery) {
        AuctionSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new AuctionSession());
        session.page = page;
        session.searchQuery = (searchQuery != null) ? searchQuery : "";
        openInventory(player, session);
    }

    private void openInventory(Player player, AuctionSession session) {
        if (session.viewingOwnItems) {
            openYourItemsInventory(player, session);
            return;
        }

        int size = plugin.getConfig().getInt("ah.size", 54);
        size = Math.max(18, Math.min(54, size - (size % 9)));
        int itemSlots = size - 9;

        // Start with all listings
        List<AuctionItem> filtered = new ArrayList<>(listings);

        // 1. Filter by search query if present
        if (session.searchQuery != null && !session.searchQuery.isEmpty()) {
            String queryLower = session.searchQuery.toLowerCase();
            filtered = filtered.stream()
                    .filter(item -> matchesSearch(item.getItem(), queryLower))
                    .collect(Collectors.toList());
        }

        // 2. Filter by category (Matches options in slot 47)
        List<String> filterOptions = plugin.getConfig().getStringList("ah.items.47.options");
        if (!filterOptions.isEmpty() && session.filterIndex > 0 && session.filterIndex < filterOptions.size()) {
            String selectedCategory = filterOptions.get(session.filterIndex).toLowerCase();
            filtered = filtered.stream()
                    .filter(item -> matchesCategory(item.getItem(), selectedCategory))
                    .collect(Collectors.toList());
        }

        // 3. Sort listings (Matches options in slot 48)
        List<String> sortOptions = plugin.getConfig().getStringList("ah.items.48.options");
        if (!sortOptions.isEmpty() && session.sortIndex >= 0 && session.sortIndex < sortOptions.size()) {
            String selectedSort = sortOptions.get(session.sortIndex).toLowerCase();
            if (selectedSort.contains("oldest")) {
                filtered.sort(Comparator.comparingLong(AuctionItem::getTimestamp));
            } else if (selectedSort.contains("expensive")) {
                filtered.sort(Comparator.comparingDouble(AuctionItem::getPrice).reversed());
            } else if (selectedSort.contains("cheapest")) {
                filtered.sort(Comparator.comparingDouble(AuctionItem::getPrice));
            } else {
                // Default: Recently Listed
                filtered.sort(Comparator.comparingLong(AuctionItem::getTimestamp).reversed());
            }
        }

        int maxPages = Math.max(1, (int) Math.ceil((double) filtered.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("ah.title", "&8Auction (%page%/%maxpage%)");
        String title = color(rawTitle
                .replace("%page%", String.valueOf(session.page))
                .replace("%maxpage%", String.valueOf(maxPages)));

        Inventory inventory = Bukkit.createInventory(null, size, title);

        // Populate auction listings
        int startIndex = (session.page - 1) * itemSlots;
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
                        String action = plugin.getConfig().getString("ah.items." + slot + ".action", "");
                        int selectedIndex = 0;
                        if (action.equalsIgnoreCase("filter")) {
                            selectedIndex = session.filterIndex;
                        } else if (action.equalsIgnoreCase("sort")) {
                            selectedIndex = session.sortIndex;
                        }
                        inventory.setItem(slot, createMenuItem(slot, selectedIndex));
                    }
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid slot number in config: " + key);
                }
            }
        }

        player.openInventory(inventory);
    }

    private void openYourItemsInventory(Player player, AuctionSession session) {
        int size = plugin.getConfig().getInt("ah.yourItems.size", 27);
        size = Math.max(18, Math.min(54, size - (size % 9)));
        int itemSlots = size - 9;

        List<AuctionItem> ownListings = listings.stream()
                .filter(item -> item.getSellerUUID().equals(player.getUniqueId()))
                .collect(Collectors.toList());

        int maxListings = plugin.getConfig().getInt("ah.max_listings", 10);
        int maxPages = Math.max(1, (int) Math.ceil((double) ownListings.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("ah.yourItems.title", "&8Your Items (%page%/%maxpage%)");
        String title = color(rawTitle
                .replace("%page%", String.valueOf(session.page))
                .replace("%maxpage%", String.valueOf(maxPages)));

        Inventory inventory = Bukkit.createInventory(null, size, title);

        // Populate player's active listings
        int startIndex = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (startIndex + i) < ownListings.size(); i++) {
            AuctionItem auctionItem = ownListings.get(startIndex + i);
            inventory.setItem(i, createOwnDisplayItem(auctionItem));
        }

        // Populate menu items from ah.yourItems.items
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("ah.yourItems.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < size) {
                        inventory.setItem(slot, createYourItemsMenuItem(slot, ownListings.size(), maxListings));
                    }
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid slot number in yourItems config: " + key);
                }
            }
        }

        player.openInventory(inventory);
    }

    private boolean matchesCategory(ItemStack item, String category) {
        Material mat = item.getType();
        String name = mat.name();

        switch (category) {
            case "blocks":
                return mat.isBlock();
            case "tools":
                return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                        || name.endsWith("_HOE") || mat == Material.FLINT_AND_STEEL || mat == Material.SHEARS
                        || mat == Material.FISHING_ROD;
            case "food":
                return mat.isEdible();
            case "combat":
                return name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                        || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || mat == Material.BOW
                        || mat == Material.CROSSBOW || mat == Material.SHIELD || mat == Material.TRIDENT
                        || mat == Material.ARROW;
            case "potions":
                return mat == Material.POTION || mat == Material.SPLASH_POTION
                        || mat == Material.LINGERING_POTION || mat == Material.GLASS_BOTTLE;
            case "books":
                return mat == Material.BOOK || mat == Material.ENCHANTED_BOOK
                        || mat == Material.WRITABLE_BOOK || mat == Material.WRITTEN_BOOK;
            case "ingredients":
                return mat == Material.DIAMOND || mat == Material.EMERALD || mat == Material.IRON_INGOT
                        || mat == Material.GOLD_INGOT || mat == Material.NETHERITE_INGOT || mat == Material.REDSTONE
                        || mat == Material.LAPIS_LAZULI || mat == Material.COAL || mat == Material.QUARTZ
                        || mat == Material.BLAZE_ROD || mat == Material.GHAST_TEAR || mat == Material.NETHER_WART;
            case "utilities":
                return !mat.isBlock() && !mat.isEdible();
            default:
                return true;
        }
    }

    private boolean matchesSearch(ItemStack item, String queryLower) {
        String matName = item.getType().name().toLowerCase().replace("_", " ");
        if (matName.contains(queryLower)) return true;

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

    private ItemStack createOwnDisplayItem(AuctionItem auctionItem) {
        ItemStack display = auctionItem.getItem().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Seller: &e" + auctionItem.getSellerName()));
            lore.add(color("&7Price: &a$" + auctionItem.getPrice()));
            lore.add(color("&cClick to cancel & reclaim!"));
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

    private ItemStack createYourItemsMenuItem(int slot, int currentCount, int maxCount) {
        String path = "ah.yourItems.items." + slot;
        Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "BOOK"));
        if (material == null) material = Material.BOOK;

        ItemStack display = new ItemStack(material);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(plugin.getConfig().getString(path + ".name", "")));

            List<String> lore = new ArrayList<>();
            if (plugin.getConfig().isList(path + ".lore")) {
                for (String line : plugin.getConfig().getStringList(path + ".lore")) {
                    lore.add(color(line
                            .replace("%listings%", String.valueOf(currentCount))
                            .replace("%max_listings%", String.valueOf(maxCount))));
                }
            } else if (plugin.getConfig().isString(path + ".lore")) {
                lore.add(color(plugin.getConfig().getString(path + ".lore", "")
                        .replace("%listings%", String.valueOf(currentCount))
                        .replace("%max_listings%", String.valueOf(maxCount))));
            }

            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String mainTitlePrefix = color(plugin.getConfig().getString("ah.title", "&8Auction").split("%page%")[0]);
        String ownTitlePrefix = color(plugin.getConfig().getString("ah.yourItems.title", "&8Your Items").split("%page%")[0]);
        String viewTitle = event.getView().getTitle();

        boolean isMainGui = viewTitle.startsWith(mainTitlePrefix);
        boolean isOwnGui = viewTitle.startsWith(ownTitlePrefix);
        if (!isMainGui && !isOwnGui) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int slot = event.getSlot();
        int itemSlots = event.getView().getTopInventory().getSize() - 9;

        // Handle Your Items GUI clicks
        if (isOwnGui) {
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("ah.yourItems.items." + slot + ".action", "");
                AuctionSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new AuctionSession());
                switch (action.toLowerCase()) {
                    case "back":
                        session.viewingOwnItems = false;
                        session.page = 1;
                        openInventory(player, session);
                        break;
                    case "next":
                        session.page++;
                        openInventory(player, session);
                        break;
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openInventory(player, session);
                        break;
                }
                return;
            }

            AuctionItem targetListing = findMatchingListing(clicked);
            if (targetListing != null && targetListing.getSellerUUID().equals(player.getUniqueId())) {
                if (player.getInventory().firstEmpty() == -1) {
                    player.sendMessage(ChatColor.RED + "Your inventory is full!");
                    return;
                }
                listings.remove(targetListing);
                player.getInventory().addItem(targetListing.getItem());
                player.sendMessage(ChatColor.YELLOW + "Removed your listing and returned the item.");
                AuctionSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new AuctionSession());
                openInventory(player, session);
            }
            return;
        }

        // Handle Main Auction House GUI clicks
        if (slot >= itemSlots) {
            handleMenuClick(player, slot, event.isRightClick());
            return;
        }

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
        if (action == null) return;

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
                    int step = isRightClick ? -1 : 1;
                    session.filterIndex = Math.floorMod(session.filterIndex + step, options.size());
                    session.page = 1;
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
                session.searchQuery = "";
                session.viewingOwnItems = false;
                openInventory(player, session);
                break;

            case "own":
                session.viewingOwnItems = true;
                session.page = 1;
                openInventory(player, session);
                break;

            case "search":
                player.closeInventory();
                awaitingSearchInput.add(player.getUniqueId());
                player.sendMessage(color("&eType your search query in chat (or type &ccancel &eto abort):"));
                break;
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingSearchInput.remove(player.getUniqueId())) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();

        AuctionSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new AuctionSession());
        if (!message.equalsIgnoreCase("cancel")) {
            session.searchQuery = message;
            session.page = 1;
        }

        // Open inventory back on the main server thread
        Bukkit.getScheduler().runTask(plugin, () -> openInventory(player, session));
    }

    private AuctionItem findMatchingListing(ItemStack displayItem) {
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

        AuctionSession session = sessions.computeIfAbsent(buyer.getUniqueId(), k -> new AuctionSession());
        openInventory(buyer, session);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static class AuctionSession {
        int page = 1;
        int filterIndex = 0;
        int sortIndex = 0;
        String searchQuery = "";
        boolean viewingOwnItems = false;
    }

    private static class AuctionItem {
        private final UUID sellerUUID;
        private final String sellerName;
        private final ItemStack item;
        private final double price;
        private final long timestamp;

        public AuctionItem(UUID sellerUUID, String sellerName, ItemStack item, double price, long timestamp) {
            this.sellerUUID = sellerUUID;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.timestamp = timestamp;
        }

        public UUID getSellerUUID() { return sellerUUID; }
        public String getSellerName() { return sellerName; }
        public ItemStack getItem() { return item; }
        public double getPrice() { return price; }
        public long getTimestamp() { return timestamp; }
    }
}
