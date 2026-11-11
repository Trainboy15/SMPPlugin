package trainboy15.smpplugin.commands;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.BanList;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import trainboy15.smpplugin.SMPPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Admin implements CommandExecutor, Listener {

    private final SMPPlugin plugin;
    private final Auction auction;
    private final Orders orders;
    private final Settings settings;
    private Permission vaultPerms = null;

    private final Map<UUID, AdminSession> sessions = new HashMap<>();
    private final Set<UUID> awaitingPlayerSearch = ConcurrentHashMap.newKeySet();

    public Admin(SMPPlugin plugin, Auction auction, Orders orders, Settings settings) {
        this.plugin = plugin;
        this.auction = auction;
        this.orders = orders;
        this.settings = settings;
        setupPermissions();
    }

    private void setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = plugin.getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp != null) {
            vaultPerms = rsp.getProvider();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use the admin GUI.");
            return true;
        }

        Player admin = (Player) sender;
        if (!admin.hasPermission("smp.admin")) {
            admin.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        AdminSession session = sessions.computeIfAbsent(admin.getUniqueId(), k -> new AdminSession());
        session.page = 1;
        session.currentMenu = MenuType.MAIN;
        openMainGui(admin);
        return true;
    }

    // ==================== CONFIG-DRIVEN GUI BUILDERS ====================

    public void openMainGui(Player admin) {
        AdminSession session = sessions.computeIfAbsent(admin.getUniqueId(), k -> new AdminSession());
        session.currentMenu = MenuType.MAIN;

        int size = normalizeSize(plugin.getConfig().getInt("admin.size", 27), 9);
        String title = color(plugin.getConfig().getString("admin.title", "&8Admin Panel"));
        Inventory inv = Bukkit.createInventory(null, size, title);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%auctions_count%", String.valueOf(auction.getListings().size()));
        placeholders.put("%orders_count%", String.valueOf(orders.getOrders().size()));

        populateConfiguredItems(inv, "admin.items", placeholders, null);
        admin.openInventory(inv);
    }

    public void openPlayersGui(Player admin, AdminSession session) {
        session.currentMenu = MenuType.PLAYERS;

        int size = normalizeSize(plugin.getConfig().getInt("admin.players.size", 54), 18);
        int itemSlots = size - 9;

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int maxPages = Math.max(1, (int) Math.ceil((double) online.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("admin.players.title", "&8Admin -> Players (Page %page%/%maxpage%)");
        String title = color(applyPagePlaceholders(rawTitle, session.page, maxPages));

        Inventory inv = Bukkit.createInventory(null, size, title);
        session.displayedPlayers.clear();

        int start = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (start + i) < online.size(); i++) {
            Player target = online.get(start + i);
            inv.setItem(i, createPlayerHead(target));
            session.displayedPlayers.put(i, target.getUniqueId());
        }

        Map<String, String> placeholders = pagePlaceholderMap(session.page, maxPages);
        populateConfiguredItems(inv, "admin.players.items", placeholders, null);
        admin.openInventory(inv);
    }

    public void openPlayerDetailGui(Player admin, OfflinePlayer target) {
        AdminSession session = sessions.computeIfAbsent(admin.getUniqueId(), k -> new AdminSession());
        session.currentMenu = MenuType.MANAGE_PLAYER;
        session.targetPlayer = target.getUniqueId();

        String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString().substring(0, 8);
        boolean isBanned = Bukkit.getBanList(BanList.Type.NAME).isBanned(targetName);

        Economy econ = plugin.getEconomy();
        double bal = (econ != null) ? econ.getBalance(target) : 0.0;

        String primaryGroup = "Unknown";
        if (vaultPerms != null && target.isOnline() && target.getPlayer() != null) {
            primaryGroup = vaultPerms.getPrimaryGroup(target.getPlayer());
        }

        boolean isPremium = target.isOnline() && target.getPlayer() != null && target.getPlayer().hasPermission("smp.premium");
        boolean isAdmin = target.isOnline() && target.getPlayer() != null && target.getPlayer().hasPermission("smp.admin");
        boolean ahNotif = settings.hasAhNotifications(target.getUniqueId());
        boolean ahConfirm = settings.hasAhConfirm(target.getUniqueId());

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%player%", targetName);
        placeholders.put("%online_status%", target.isOnline() ? "&aOnline" : "&7Offline");
        placeholders.put("%ban_status%", isBanned ? "&cBanned" : "&aNot Banned");
        placeholders.put("%balance%", String.format("%.2f", bal));
        placeholders.put("%group%", primaryGroup);
        placeholders.put("%is_premium%", isPremium ? "&aYes" : "&cNo");
        placeholders.put("%is_admin%", isAdmin ? "&aYes" : "&cNo");
        placeholders.put("%ah_notifications%", ahNotif ? "&aON" : "&cOFF");
        placeholders.put("%ah_confirm%", ahConfirm ? "&aON" : "&cOFF");

        int size = normalizeSize(plugin.getConfig().getInt("admin.managePlayer.size", 36), 9);
        String rawTitle = plugin.getConfig().getString("admin.managePlayer.title", "&8Admin -> %player%");
        String title = color(replacePlaceholders(rawTitle, placeholders));

        Inventory inv = Bukkit.createInventory(null, size, title);
        populateConfiguredItems(inv, "admin.managePlayer.items", placeholders, target);
        admin.openInventory(inv);
    }

    public void openAuctionsModGui(Player admin, AdminSession session) {
        session.currentMenu = MenuType.AUCTIONS;

        int size = normalizeSize(plugin.getConfig().getInt("admin.auctions.size", 54), 18);
        int itemSlots = size - 9;

        List<Auction.AuctionItem> all = auction.getListings();
        int maxPages = Math.max(1, (int) Math.ceil((double) all.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("admin.auctions.title", "&8Admin -> Auctions (Page %page%/%maxpage%)");
        String title = color(applyPagePlaceholders(rawTitle, session.page, maxPages));

        Inventory inv = Bukkit.createInventory(null, size, title);
        session.displayedAuctions.clear();

        int start = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (start + i) < all.size(); i++) {
            Auction.AuctionItem item = all.get(start + i);
            ItemStack display = item.getItem().clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(color("&7Seller: &e" + item.getSellerName()));
                lore.add(color("&7Price: &a$" + item.getPrice()));
                lore.add(color("&cLeft-Click: &fDelete listing permanently"));
                lore.add(color("&eRight-Click: &fCancel & return item to seller"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i, display);
            session.displayedAuctions.put(i, item);
        }

        Map<String, String> placeholders = pagePlaceholderMap(session.page, maxPages);
        populateConfiguredItems(inv, "admin.auctions.items", placeholders, null);
        admin.openInventory(inv);
    }

    public void openOrdersModGui(Player admin, AdminSession session) {
        session.currentMenu = MenuType.ORDERS;

        int size = normalizeSize(plugin.getConfig().getInt("admin.orders.size", 54), 18);
        int itemSlots = size - 9;

        List<Orders.OrderRequest> all = orders.getOrders();
        int maxPages = Math.max(1, (int) Math.ceil((double) all.size() / itemSlots));
        session.page = Math.max(1, Math.min(session.page, maxPages));

        String rawTitle = plugin.getConfig().getString("admin.orders.title", "&8Admin -> Orders (Page %page%/%maxpage%)");
        String title = color(applyPagePlaceholders(rawTitle, session.page, maxPages));

        Inventory inv = Bukkit.createInventory(null, size, title);
        session.displayedOrders.clear();

        int start = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (start + i) < all.size(); i++) {
            Orders.OrderRequest order = all.get(start + i);
            ItemStack display = order.getTemplateItem().clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(color("&7Buyer: &e" + order.getRequesterName()));
                lore.add(color("&7Price/Item: &a$" + order.getPricePerItem()));
                lore.add(color("&7Progress: &b" + order.getAmountDelivered() + "&7/&b" + order.getAmountRequested()));
                lore.add(color("&cLeft-Click: &fDelete order permanently"));
                lore.add(color("&eRight-Click: &fCancel & refund remaining escrow"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i, display);
            session.displayedOrders.put(i, order);
        }

        Map<String, String> placeholders = pagePlaceholderMap(session.page, maxPages);
        populateConfiguredItems(inv, "admin.orders.items", placeholders, null);
        admin.openInventory(inv);
    }

    // ==================== CONFIG ITEM POPULATION ====================

    private void populateConfiguredItems(Inventory inv, String sectionPath, Map<String, String> placeholders, OfflinePlayer skullOwner) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionPath);
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                if (slot >= 0 && slot < inv.getSize()) {
                    inv.setItem(slot, buildConfigItem(sectionPath + "." + key, placeholders, skullOwner));
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Invalid slot number in " + sectionPath + ": " + key);
            }
        }
    }

    private ItemStack buildConfigItem(String path, Map<String, String> placeholders, OfflinePlayer skullOwner) {
        String matConfig = plugin.getConfig().getString(path + ".material", "STONE");
        ItemStack item;

        if (matConfig.equalsIgnoreCase("%player_head%") || matConfig.equalsIgnoreCase("SKULL")) {
            item = new ItemStack(Material.PLAYER_HEAD);
            if (skullOwner != null && item.getItemMeta() instanceof SkullMeta) {
                SkullMeta skullMeta = (SkullMeta) item.getItemMeta();
                skullMeta.setOwningPlayer(skullOwner);
                item.setItemMeta(skullMeta);
            }
        } else {
            Material mat = Material.matchMaterial(matConfig);
            if (mat == null) mat = Material.STONE;
            item = new ItemStack(mat);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String rawName = plugin.getConfig().getString(path + ".name", "");
            meta.setDisplayName(color(replacePlaceholders(rawName, placeholders)));

            List<String> lore = new ArrayList<>();
            if (plugin.getConfig().isList(path + ".lore")) {
                for (String line : plugin.getConfig().getStringList(path + ".lore")) {
                    lore.add(color(replacePlaceholders(line, placeholders)));
                }
            } else if (plugin.getConfig().isString(path + ".lore")) {
                lore.add(color(replacePlaceholders(plugin.getConfig().getString(path + ".lore", ""), placeholders)));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ==================== EVENT HANDLERS ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();

        AdminSession session = sessions.get(admin.getUniqueId());
        if (session == null || session.currentMenu == null) return;

        // Verify the open inventory title matches the expected configured title
        if (!matchesActiveMenuTitle(event.getView().getTitle(), session)) {
            return;
        }

        event.setCancelled(true);
        if (!admin.hasPermission("smp.admin")) {
            admin.closeInventory();
            return;
        }

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int slot = event.getSlot();

        // 1. Main Menu
        if (session.currentMenu == MenuType.MAIN) {
            String action = plugin.getConfig().getString("admin.items." + slot + ".action", "").toLowerCase();
            switch (action) {
                case "players":
                    session.page = 1;
                    openPlayersGui(admin, session);
                    break;
                case "auctions":
                    session.page = 1;
                    openAuctionsModGui(admin, session);
                    break;
                case "orders":
                    session.page = 1;
                    openOrdersModGui(admin, session);
                    break;
                case "close":
                    admin.closeInventory();
                    break;
            }
            return;
        }

        // 2. Players List
        if (session.currentMenu == MenuType.PLAYERS) {
            int itemSlots = event.getView().getTopInventory().getSize() - 9;
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("admin.players.items." + slot + ".action", "").toLowerCase();
                switch (action) {
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openPlayersGui(admin, session);
                        break;
                    case "search":
                        admin.closeInventory();
                        awaitingPlayerSearch.add(admin.getUniqueId());
                        admin.sendMessage(color("&eType the player's exact username in chat (or &ccancel&e):"));
                        break;
                    case "back":
                        openMainGui(admin);
                        break;
                    case "next":
                        session.page++;
                        openPlayersGui(admin, session);
                        break;
                }
                return;
            }

            if (session.displayedPlayers.containsKey(slot)) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(session.displayedPlayers.get(slot));
                openPlayerDetailGui(admin, target);
            }
            return;
        }

        // 3. Manage Individual Player
        if (session.currentMenu == MenuType.MANAGE_PLAYER) {
            String action = plugin.getConfig().getString("admin.managePlayer.items." + slot + ".action", "").toLowerCase();
            if (action.equals("back")) {
                session.page = 1;
                openPlayersGui(admin, session);
                return;
            }

            if (session.targetPlayer == null) return;
            OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetPlayer);
            String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();

            switch (action) {
                case "toggle_ban":
                    BanList banList = Bukkit.getBanList(BanList.Type.NAME);
                    if (banList.isBanned(targetName)) {
                        banList.pardon(targetName);
                        admin.sendMessage(color("&aUnbanned " + targetName + "."));
                    } else {
                        banList.addBan(targetName, "Banned by an administrator.", (java.time.Instant) null, admin.getName());
                        if (target.isOnline() && target.getPlayer() != null) {
                            target.getPlayer().kickPlayer(color("&cYou have been banned by an administrator."));
                        }
                        admin.sendMessage(color("&cBanned " + targetName + "."));
                    }
                    openPlayerDetailGui(admin, target);
                    break;

                case "kick":
                    if (target.isOnline() && target.getPlayer() != null) {
                        target.getPlayer().kickPlayer(color("&cYou were kicked by an administrator."));
                        admin.sendMessage(color("&eKicked " + targetName + "."));
                    } else {
                        admin.sendMessage(color("&cThat player is offline."));
                    }
                    openPlayerDetailGui(admin, target);
                    break;

                case "economy":
                    Economy econ = plugin.getEconomy();
                    if (econ != null) {
                        if (event.isShiftClick() && event.isRightClick()) {
                            double current = econ.getBalance(target);
                            if (current > 0) econ.withdrawPlayer(target, current);
                            admin.sendMessage(color("&eReset " + targetName + "'s balance to $0."));
                        } else if (event.isRightClick()) {
                            econ.withdrawPlayer(target, 1000);
                            admin.sendMessage(color("&cRemoved $1,000 from " + targetName + "."));
                        } else {
                            econ.depositPlayer(target, 1000);
                            admin.sendMessage(color("&aAdded $1,000 to " + targetName + "."));
                        }
                    }
                    openPlayerDetailGui(admin, target);
                    break;

                case "settings":
                    if (event.isRightClick()) {
                        boolean cur = settings.hasAhConfirm(target.getUniqueId());
                        settings.setSetting(target.getUniqueId(), "ah_confirm", !cur);
                    } else {
                        boolean cur = settings.hasAhNotifications(target.getUniqueId());
                        settings.setSetting(target.getUniqueId(), "ah_notifications", !cur);
                    }
                    openPlayerDetailGui(admin, target);
                    break;
            }
            return;
        }

        // 4. Auctions Moderation
        if (session.currentMenu == MenuType.AUCTIONS) {
            int itemSlots = event.getView().getTopInventory().getSize() - 9;
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("admin.auctions.items." + slot + ".action", "").toLowerCase();
                switch (action) {
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openAuctionsModGui(admin, session);
                        break;
                    case "back":
                        openMainGui(admin);
                        break;
                    case "next":
                        session.page++;
                        openAuctionsModGui(admin, session);
                        break;
                }
                return;
            }

            if (session.displayedAuctions.containsKey(slot)) {
                Auction.AuctionItem targetListing = session.displayedAuctions.get(slot);
                boolean refund = event.isRightClick();
                if (auction.adminRemoveListing(targetListing, refund)) {
                    admin.sendMessage(color(refund
                            ? "&eCancelled listing and returned item to " + targetListing.getSellerName() + "."
                            : "&cPermanently deleted " + targetListing.getSellerName() + "'s listing."));
                }
                openAuctionsModGui(admin, session);
            }
            return;
        }

        // 5. Orders Moderation
        if (session.currentMenu == MenuType.ORDERS) {
            int itemSlots = event.getView().getTopInventory().getSize() - 9;
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("admin.orders.items." + slot + ".action", "").toLowerCase();
                switch (action) {
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openOrdersModGui(admin, session);
                        break;
                    case "back":
                        openMainGui(admin);
                        break;
                    case "next":
                        session.page++;
                        openOrdersModGui(admin, session);
                        break;
                }
                return;
            }

            if (session.displayedOrders.containsKey(slot)) {
                Orders.OrderRequest targetOrder = session.displayedOrders.get(slot);
                boolean refund = event.isRightClick();
                if (orders.adminRemoveOrder(targetOrder, refund)) {
                    admin.sendMessage(color(refund
                            ? "&eCancelled order and refunded remaining escrow to " + targetOrder.getRequesterName() + "."
                            : "&cPermanently deleted " + targetOrder.getRequesterName() + "'s order."));
                }
                openOrdersModGui(admin, session);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player admin = event.getPlayer();
        if (!awaitingPlayerSearch.remove(admin.getUniqueId())) return;

        event.setCancelled(true);
        String name = event.getMessage().trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin.isOnline()) return;
            AdminSession session = sessions.computeIfAbsent(admin.getUniqueId(), k -> new AdminSession());
            if (name.equalsIgnoreCase("cancel")) {
                openPlayersGui(admin, session);
                return;
            }
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            openPlayerDetailGui(admin, target);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        awaitingPlayerSearch.remove(id);
    }

    // ==================== UTILITY HELPERS ====================

    private boolean matchesActiveMenuTitle(String viewTitle, AdminSession session) {
        switch (session.currentMenu) {
            case MAIN:
                return viewTitle.equals(color(plugin.getConfig().getString("admin.title", "&8Admin Panel")));
            case PLAYERS:
                String pPrefix = color(plugin.getConfig().getString("admin.players.title", "&8Admin -> Players").split("%page%")[0]);
                return viewTitle.startsWith(pPrefix);
            case MANAGE_PLAYER:
                String mPrefix = color(plugin.getConfig().getString("admin.managePlayer.title", "&8Admin -> %player%").split("%player%")[0]);
                return viewTitle.startsWith(mPrefix);
            case AUCTIONS:
                String aPrefix = color(plugin.getConfig().getString("admin.auctions.title", "&8Admin -> Auctions").split("%page%")[0]);
                return viewTitle.startsWith(aPrefix);
            case ORDERS:
                String oPrefix = color(plugin.getConfig().getString("admin.orders.title", "&8Admin -> Orders").split("%page%")[0]);
                return viewTitle.startsWith(oPrefix);
            default:
                return false;
        }
    }

    private ItemStack createPlayerHead(OfflinePlayer target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            String name = target.getName() != null ? target.getName() : "Unknown";
            meta.setDisplayName(color("&b" + name));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Status: " + (target.isOnline() ? "&aOnline" : "&7Offline")));
            lore.add(color("&7Banned: " + (Bukkit.getBanList(BanList.Type.NAME).isBanned(name) ? "&cYes" : "&aNo")));
            lore.add("");
            lore.add(color("&eClick to manage player!"));
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private Map<String, String> pagePlaceholderMap(int page, int maxPages) {
        Map<String, String> map = new HashMap<>();
        map.put("%page%", String.valueOf(page));
        map.put("%maxpage%", String.valueOf(maxPages));
        map.put("%prevpage%", String.valueOf(Math.max(1, page - 1)));
        map.put("%nextpage%", String.valueOf(Math.min(maxPages, page + 1)));
        return map;
    }

    private String applyPagePlaceholders(String text, int page, int maxPages) {
        return replacePlaceholders(text, pagePlaceholderMap(page, maxPages));
    }

    private String replacePlaceholders(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private int normalizeSize(int size, int min) {
        return Math.max(min, Math.min(54, size - (size % 9)));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private enum MenuType {
        MAIN, PLAYERS, MANAGE_PLAYER, AUCTIONS, ORDERS
    }

    private static class AdminSession {
        MenuType currentMenu = MenuType.MAIN;
        int page = 1;
        UUID targetPlayer = null;
        final Map<Integer, UUID> displayedPlayers = new HashMap<>();
        final Map<Integer, Auction.AuctionItem> displayedAuctions = new HashMap<>();
        final Map<Integer, Orders.OrderRequest> displayedOrders = new HashMap<>();
    }
}
