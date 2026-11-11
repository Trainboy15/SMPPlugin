package trainboy15.smpplugin.commands;

import trainboy15.smpplugin.SMPPlugin;
import trainboy15.smpplugin.database.DatabaseManager;

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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Orders implements CommandExecutor, Listener {

    private final SMPPlugin plugin;
    private final DatabaseManager database;
    private final List<OrderRequest> orders = new ArrayList<>();
    private final Map<UUID, OrderSession> sessions = new HashMap<>();
    private final Set<UUID> awaitingSearchInput = ConcurrentHashMap.newKeySet();

    public Orders(SMPPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        this.orders.addAll(database.loadAllOrders());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        // 1. /orders -> opens Orders GUI with all requests
        if (args.length == 0) {
            OrderSession session = new OrderSession();
            sessions.put(player.getUniqueId(), session);
            openInventory(player, session);
            return true;
        }

        // 2. /orders create <amount> <pricePerItem>
        if (args[0].equalsIgnoreCase("create")) {
            if (args.length != 3) {
                player.sendMessage(ChatColor.RED + "Usage: /orders create <amount> <pricePerItem>");
                return true;
            }

            int maxListings = plugin.getConfig().getInt("orders.maxListings", 18);
            long currentListings = orders.stream()
                    .filter(order -> order.getRequesterUUID().equals(player.getUniqueId()))
                    .count();
            if (currentListings >= maxListings) {
                player.sendMessage(ChatColor.RED + "You have reached your maximum order limit (" + maxListings + ").");
                return true;
            }

            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand == null || itemInHand.getType() == Material.AIR) {
                player.sendMessage(ChatColor.RED + "You must be holding the item you want to request.");
                return true;
            }

            int amount;
            double pricePerItem;
            try {
                amount = Integer.parseInt(args[1]);
                pricePerItem = Double.parseDouble(args[2]);
                if (amount <= 0 || pricePerItem <= 0) {
                    player.sendMessage(ChatColor.RED + "Amount and price must be greater than 0.");
                    return true;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid number. Usage: /orders create <amount> <pricePerItem>");
                return true;
            }

            Economy econ = plugin.getEconomy();
            if (econ == null) {
                player.sendMessage(ChatColor.RED + "Economy system is not available.");
                return true;
            }

            double totalCost = amount * pricePerItem;
            if (!econ.has(player, totalCost)) {
                player.sendMessage(ChatColor.RED + "You don't have enough money! Total escrow required: $" + totalCost);
                return true;
            }

            if (!econ.withdrawPlayer(player, totalCost).transactionSuccess()) {
                player.sendMessage(ChatColor.RED + "Transaction failed. Please try again.");
                return true;
            }

            ItemStack template = itemInHand.clone();
            template.setAmount(1);
            createOrder(player, template, amount, pricePerItem);

            player.sendMessage(ChatColor.GREEN + "Created order for " + amount + "x "
                    + template.getType().name() + " at $" + pricePerItem + " each (Total: $" + totalCost + ")!");
            return true;
        }

        // 3. /orders <item> -> opens Orders GUI filtered by search query
        OrderSession session = new OrderSession();
        session.searchQuery = String.join(" ", args);
        sessions.put(player.getUniqueId(), session);
        openInventory(player, session);
        return true;
    }

    private void createOrder(Player requester, ItemStack template, int amount, double pricePerItem) {
        OrderRequest newOrder = new OrderRequest(
                UUID.randomUUID(),
                requester.getUniqueId(),
                requester.getName(),
                template,
                amount,
                0,
                0,
                pricePerItem,
                System.currentTimeMillis()
        );
        orders.add(newOrder);
        database.insertOrderAsync(newOrder);
    }

    private void openInventory(Player player, OrderSession session) {
        if (session.viewingOwnOrders) {
            openYourOrdersInventory(player, session);
            return;
        }

        int size = plugin.getConfig().getInt("orders.size", 54);
        size = Math.max(18, Math.min(54, size - (size % 9)));
        int itemSlots = size - 9;

        // Only show orders that still need items delivered
        List<OrderRequest> filtered = orders.stream()
                .filter(order -> order.getRemainingAmount() > 0)
                .collect(Collectors.toList());

        // 1. Filter by search query if present
        if (session.searchQuery != null && !session.searchQuery.isEmpty()) {
            String queryLower = session.searchQuery.toLowerCase();
            filtered = filtered.stream()
                    .filter(order -> matchesSearch(order.getTemplateItem(), queryLower))
                    .collect(Collectors.toList());
        }

        // 2. Filter by category (Matches options in slot 47)
        List<String> filterOptions = plugin.getConfig().getStringList("orders.items.47.options");
        if (!filterOptions.isEmpty() && session.filterIndex > 0 && session.filterIndex < filterOptions.size()) {
            String selectedCategory = filterOptions.get(session.filterIndex).toLowerCase();
            filtered = filtered.stream()
                    .filter(order -> matchesCategory(order.getTemplateItem(), selectedCategory))
                    .collect(Collectors.toList());
        }

        // 3. Sort orders (Matches options in slot 48)
        List<String> sortOptions = plugin.getConfig().getStringList("orders.items.48.options");
        if (!sortOptions.isEmpty() && session.sortIndex >= 0 && session.sortIndex < sortOptions.size()) {
            String selectedSort = sortOptions.get(session.sortIndex).toLowerCase();
            if (selectedSort.contains("money per item")) {
                filtered.sort(Comparator.comparingDouble(OrderRequest::getPricePerItem).reversed());
            } else if (selectedSort.contains("deliverd") || selectedSort.contains("delivered")) {
                filtered.sort(Comparator.comparingInt(OrderRequest::getAmountDelivered).reversed());
            } else if (selectedSort.contains("paid")) {
                filtered.sort(Comparator.comparingDouble(OrderRequest::getTotalPaidOut).reversed());
            } else {
                // Default: Recently Listed
                filtered.sort(Comparator.comparingLong(OrderRequest::getTimestamp).reversed());
            }
        }

        int maxPages = Math.max(1, (int) Math.ceil((double) filtered.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("orders.title", "&8Orders (Page %page%/%maxpage%)");
        String title = color(rawTitle
                .replace("%page%", String.valueOf(session.page))
                .replace("%maxpage%", String.valueOf(maxPages)));

        Inventory inventory = Bukkit.createInventory(null, size, title);
        session.displayedSlots.clear();

        // Populate active orders
        int startIndex = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (startIndex + i) < filtered.size(); i++) {
            OrderRequest order = filtered.get(startIndex + i);
            inventory.setItem(i, createDisplayItem(order));
            session.displayedSlots.put(i, order);
        }

        // Populate menu items dynamically from orders.items
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("orders.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < size) {
                        String action = plugin.getConfig().getString("orders.items." + slot + ".action", "");
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

    private void openYourOrdersInventory(Player player, OrderSession session) {
        int size = plugin.getConfig().getInt("orders.yourOrders.size", 27);
        size = Math.max(18, Math.min(54, size - (size % 9)));
        int itemSlots = size - 9;

        List<OrderRequest> ownOrders = orders.stream()
                .filter(order -> order.getRequesterUUID().equals(player.getUniqueId()))
                .collect(Collectors.toList());

        int maxListings = plugin.getConfig().getInt("orders.maxListings", 18);
        int maxPages = Math.max(1, (int) Math.ceil((double) ownOrders.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("orders.yourOrders.title", "&8Orders -> Your Orders");
        String title = color(rawTitle
                .replace("%page%", String.valueOf(session.page))
                .replace("%maxpage%", String.valueOf(maxPages)));

        Inventory inventory = Bukkit.createInventory(null, size, title);
        session.displayedSlots.clear();

        // Populate player's orders
        int startIndex = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (startIndex + i) < ownOrders.size(); i++) {
            OrderRequest order = ownOrders.get(startIndex + i);
            inventory.setItem(i, createOwnDisplayItem(order));
            session.displayedSlots.put(i, order);
        }

        // Populate menu items from orders.yourOrders.items
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("orders.yourOrders.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < size) {
                        inventory.setItem(slot, createYourOrdersMenuItem(slot, ownOrders.size(), maxListings));
                    }
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid slot number in yourOrders config: " + key);
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

    private ItemStack createDisplayItem(OrderRequest order) {
        ItemStack display = order.getTemplateItem().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Buyer: &e" + order.getRequesterName()));
            lore.add(color("&7Price Per Item: &a$" + order.getPricePerItem()));
            lore.add(color("&7Progress: &b" + order.getAmountDelivered() + "&7/&b" + order.getAmountRequested()));
            lore.add(color("&eClick to deliver matching items!"));
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack createOwnDisplayItem(OrderRequest order) {
        ItemStack display = order.getTemplateItem().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7Price Per Item: &a$" + order.getPricePerItem()));
            lore.add(color("&7Delivered: &b" + order.getAmountDelivered() + "&7/&b" + order.getAmountRequested()));
            lore.add(color("&7Unclaimed Items: &e" + order.getUnclaimedItems()));
            if (order.getUnclaimedItems() > 0) {
                lore.add(color("&aLeft-Click to claim delivered items!"));
            }
            lore.add(color("&cRight-Click to cancel & refund remaining!"));
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack createMenuItem(int slot, int selectedIndex) {
        String path = "orders.items." + slot;
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
            String selectedPrefix = plugin.getConfig().getString("orders.OptionSelectedPrefix", "&b• &b");
            String unselectedPrefix = plugin.getConfig().getString("orders.OptionUnselectedPrefix", "&f◦ &f");

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

    private ItemStack createYourOrdersMenuItem(int slot, int currentCount, int maxCount) {
        String path = "orders.yourOrders.items." + slot;
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

        String mainTitlePrefix = color(plugin.getConfig().getString("orders.title", "&8Orders").split("%page%")[0]);
        String ownTitlePrefix = color(plugin.getConfig().getString("orders.yourOrders.title", "&8Orders -> Your Orders").split("%page%")[0]);
        String viewTitle = event.getView().getTitle();

        boolean isMainGui = viewTitle.startsWith(mainTitlePrefix) && !viewTitle.startsWith(ownTitlePrefix);
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
        OrderSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new OrderSession());

        // Handle Your Orders GUI clicks
        if (isOwnGui) {
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("orders.yourOrders.items." + slot + ".action", "");
                switch (action.toLowerCase()) {
                    case "back":
                        session.viewingOwnOrders = false;
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

            OrderRequest targetOrder = session.displayedSlots.get(slot);
            if (targetOrder == null || !orders.contains(targetOrder)) {
                openInventory(player, session);
                return;
            }

            if (event.isRightClick()) {
                handleCancelOrder(player, targetOrder, session);
            } else {
                handleClaimItems(player, targetOrder, session);
            }
            return;
        }

        // Handle Main Orders GUI clicks
        if (slot >= itemSlots) {
            handleMenuClick(player, slot, event.isRightClick());
            return;
        }

        OrderRequest targetOrder = session.displayedSlots.get(slot);
        if (targetOrder == null || !orders.contains(targetOrder) || targetOrder.getRemainingAmount() <= 0) {
            player.sendMessage(ChatColor.RED + "This order is no longer available.");
            openInventory(player, session);
            return;
        }

        handleFulfillOrder(player, targetOrder, session);
    }

    private void handleMenuClick(Player player, int slot, boolean isRightClick) {
        String path = "orders.items." + slot;
        String action = plugin.getConfig().getString(path + ".action");
        if (action == null) return;

        OrderSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new OrderSession());
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
                session.viewingOwnOrders = false;
                openInventory(player, session);
                break;

            case "own":
                session.viewingOwnOrders = true;
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

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            OrderSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new OrderSession());
            if (!message.equalsIgnoreCase("cancel")) {
                session.searchQuery = message;
                session.page = 1;
            }

            openInventory(player, session);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
        awaitingSearchInput.remove(playerId);
    }

    private void handleFulfillOrder(Player supplier, OrderRequest order, OrderSession session) {
        if (supplier.getUniqueId().equals(order.getRequesterUUID())) {
            supplier.sendMessage(ChatColor.RED + "You cannot fulfill your own order!");
            return;
        }

        Economy econ = plugin.getEconomy();
        if (econ == null) {
            supplier.sendMessage(ChatColor.RED + "Economy system is not available.");
            return;
        }

        int needed = order.getRemainingAmount();
        if (needed <= 0) {
            supplier.sendMessage(ChatColor.RED + "This order has already been fulfilled.");
            openInventory(supplier, session);
            return;
        }

        int delivered = removeMatchingItems(supplier, order.getTemplateItem(), needed);

        if (delivered <= 0) {
            supplier.sendMessage(ChatColor.RED + "You don't have any matching items in your inventory!");
            return;
        }

        order.addDelivery(delivered);
        database.updateOrderProgressAsync(order.getOrderId(), order.getAmountDelivered(), order.getUnclaimedItems());

        double payout = delivered * order.getPricePerItem();
        econ.depositPlayer(supplier, payout);

        supplier.sendMessage(ChatColor.GREEN + "Delivered " + delivered + "x "
                + order.getTemplateItem().getType().name() + " and earned $" + payout + "!");

        OfflinePlayer requester = Bukkit.getOfflinePlayer(order.getRequesterUUID());
        if (requester.isOnline() && requester.getPlayer() != null) {
            requester.getPlayer().sendMessage(ChatColor.GREEN + supplier.getName() + " delivered " + delivered + "x "
                    + order.getTemplateItem().getType().name() + " to your order! Claim it in /orders -> Your Items.");
        }

        openInventory(supplier, session);
    }

    private void handleClaimItems(Player owner, OrderRequest order, OrderSession session) {
        int unclaimed = order.getUnclaimedItems();
        if (unclaimed <= 0) {
            owner.sendMessage(ChatColor.YELLOW + "No delivered items waiting to be claimed.");
            return;
        }

        int maxStack = order.getTemplateItem().getMaxStackSize();
        int claimedTotal = 0;

        while (unclaimed > 0) {
            if (owner.getInventory().firstEmpty() == -1) {
                owner.sendMessage(ChatColor.RED + "Your inventory is full! Claimed " + claimedTotal + " items so far.");
                break;
            }
            int giveAmount = Math.min(unclaimed, maxStack);
            ItemStack giveStack = order.getTemplateItem().clone();
            giveStack.setAmount(giveAmount);
            owner.getInventory().addItem(giveStack);

            unclaimed -= giveAmount;
            claimedTotal += giveAmount;
        }

        order.claimItems(claimedTotal);
        if (claimedTotal > 0) {
            owner.sendMessage(ChatColor.GREEN + "Claimed " + claimedTotal + "x " + order.getTemplateItem().getType().name() + "!");
        }

        // Remove completed orders once all items are claimed, otherwise update progress
        if (order.getRemainingAmount() == 0 && order.getUnclaimedItems() == 0) {
            orders.remove(order);
            database.deleteOrderAsync(order.getOrderId());
        } else if (claimedTotal > 0) {
            database.updateOrderProgressAsync(order.getOrderId(), order.getAmountDelivered(), order.getUnclaimedItems());
        }

        openInventory(owner, session);
    }

    private void handleCancelOrder(Player owner, OrderRequest order, OrderSession session) {
        if (order.getUnclaimedItems() > 0) {
            owner.sendMessage(ChatColor.RED + "Left-click to claim your delivered items before canceling!");
            return;
        }

        if (!orders.remove(order)) {
            owner.sendMessage(ChatColor.RED + "This order is no longer active.");
            openInventory(owner, session);
            return;
        }

        database.deleteOrderAsync(order.getOrderId());

        int remaining = order.getRemainingAmount();
        double refund = remaining * order.getPricePerItem();

        Economy econ = plugin.getEconomy();
        if (econ != null && refund > 0) {
            econ.depositPlayer(owner, refund);
        }

        owner.sendMessage(ChatColor.YELLOW + "Canceled order and refunded $" + refund + ".");
        openInventory(owner, session);
    }

    private int removeMatchingItems(Player player, ItemStack template, int maxToRemove) {
        int remainingToRemove = maxToRemove;
        ItemStack[] contents = player.getInventory().getStorageContents();

        for (int i = 0; i < contents.length && remainingToRemove > 0; i++) {
            ItemStack current = contents[i];
            if (current != null && current.isSimilar(template)) {
                int take = Math.min(current.getAmount(), remainingToRemove);
                if (current.getAmount() - take <= 0) {
                    contents[i] = null;
                } else {
                    current.setAmount(current.getAmount() - take);
                }
                remainingToRemove -= take;
            }
        }

        player.getInventory().setStorageContents(contents);
        return maxToRemove - remainingToRemove;
    }
    public List<OrderRequest> getOrders() {
        return new ArrayList<>(orders);
    }

    public boolean adminRemoveOrder(OrderRequest target, boolean refundRemaining) {
        if (!orders.remove(target)) return false;
        database.deleteOrderAsync(target.getOrderId());

        if (refundRemaining) {
            double refund = target.getRemainingAmount() * target.getPricePerItem();
            Economy econ = plugin.getEconomy();
            if (econ != null && refund > 0) {
                OfflinePlayer requester = Bukkit.getOfflinePlayer(target.getRequesterUUID());
                econ.depositPlayer(requester, refund);
                if (requester.isOnline() && requester.getPlayer() != null) {
                    requester.getPlayer().sendMessage(ChatColor.YELLOW + "An admin cancelled your order and refunded $" + refund + ".");
                }
            }
        }
        return true;
    }


    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static class OrderSession {
        int page = 1;
        int filterIndex = 0;
        int sortIndex = 0;
        String searchQuery = "";
        boolean viewingOwnOrders = false;
        final Map<Integer, OrderRequest> displayedSlots = new HashMap<>();
    }

    public static class OrderRequest {
        private final UUID orderId;
        private final UUID requesterUUID;
        private final String requesterName;
        private final ItemStack templateItem;
        private final int amountRequested;
        private int amountDelivered;
        private int unclaimedItems;
        private final double pricePerItem;
        private final long timestamp;

        public OrderRequest(UUID orderId, UUID requesterUUID, String requesterName, ItemStack templateItem,
                            int amountRequested, int amountDelivered, int unclaimedItems,
                            double pricePerItem, long timestamp) {
            this.orderId = orderId;
            this.requesterUUID = requesterUUID;
            this.requesterName = requesterName;
            this.templateItem = templateItem;
            this.amountRequested = amountRequested;
            this.amountDelivered = amountDelivered;
            this.unclaimedItems = unclaimedItems;
            this.pricePerItem = pricePerItem;
            this.timestamp = timestamp;
        }

        public UUID getOrderId() { return orderId; }
        public UUID getRequesterUUID() { return requesterUUID; }
        public String getRequesterName() { return requesterName; }
        public ItemStack getTemplateItem() { return templateItem; }
        public int getAmountRequested() { return amountRequested; }
        public int getAmountDelivered() { return amountDelivered; }
        public int getUnclaimedItems() { return unclaimedItems; }
        public double getPricePerItem() { return pricePerItem; }
        public long getTimestamp() { return timestamp; }

        public int getRemainingAmount() {
            return amountRequested - amountDelivered;
        }

        public double getTotalPaidOut() {
            return amountDelivered * pricePerItem;
        }

        public void addDelivery(int amount) {
            this.amountDelivered += amount;
            this.unclaimedItems += amount;
        }

        public void claimItems(int amount) {
            this.unclaimedItems = Math.max(0, this.unclaimedItems - amount);
        }
    }
}
