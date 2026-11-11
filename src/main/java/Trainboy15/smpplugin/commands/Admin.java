package trainboy15.smpplugin.commands;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.BanList;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import trainboy15.smpplugin.SMPPlugin;

import java.time.Instant;
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

    private static final String TITLE_BAN_REASON = ChatColor.DARK_GRAY + "Admin -> Ban Reason";

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

    // ==================== GUI BUILDERS ====================

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
        String title = color(replacePagePlaceholders(rawTitle, session.page, maxPages));
        Inventory inv = Bukkit.createInventory(null, size, title);
        session.displayedPlayers.clear();

        int start = (session.page - 1) * itemSlots;
        for (int i = 0; i < itemSlots && (start + i) < online.size(); i++) {
            Player target = online.get(start + i);
            inv.setItem(i, createPlayerHead(target));
            session.displayedPlayers.put(i, target.getUniqueId());
        }

        Map<String, String> placeholders = pagePlaceholders(session.page, maxPages);
        populateConfiguredItems(inv, "admin.players.items", placeholders, null);
        admin.openInventory(inv);
    }

    public void openPlayerDetailGui(Player admin, OfflinePlayer target) {
        AdminSession session = sessions.computeIfAbsent(admin.getUniqueId(), k -> new AdminSession());
        session.currentMenu = MenuType.MANAGE_PLAYER;
        session.targetPlayer = target.getUniqueId();

        int size = normalizeSize(plugin.getConfig().getInt("admin.managePlayer.size", 36), 9);
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString().substring(0, 8);
        boolean isBanned = target.isBanned();

        String rawTitle = plugin.getConfig().getString("admin.managePlayer.title", "&8Admin -> %player%");
        String title = color(rawTitle.replace("%player%", name));
        Inventory inv = Bukkit.createInventory(null, size, title);

        Economy econ = plugin.getEconomy();
        double bal = (econ != null) ? econ.getBalance(target) : 0.0;

        String primaryGroup = "Unknown";
        boolean isPremium = false;
        boolean isAdmin = false;
        if (target.isOnline() && target.getPlayer() != null) {
            Player onlineTarget = target.getPlayer();
            if (vaultPerms != null) {
                primaryGroup = vaultPerms.getPrimaryGroup(onlineTarget);
            }
            isPremium = onlineTarget.hasPermission("smp.premium");
            isAdmin = onlineTarget.hasPermission("smp.admin");
        }

        boolean ahNotif = settings.hasAhNotifications(target.getUniqueId());
        boolean ahConfirm = settings.hasAhConfirm(target.getUniqueId());

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%player%", name);
        placeholders.put("%online_status%", target.isOnline() ? "&aOnline" : "&7Offline");
        placeholders.put("%ban_status%", isBanned ? "&cBanned" : "&aNot Banned");
        placeholders.put("%balance%", String.format("%.2f", bal));
        placeholders.put("%group%", primaryGroup);
        placeholders.put("%is_premium%", isPremium ? "&aYes" : "&cNo");
        placeholders.put("%is_admin%", isAdmin ? "&aYes" : "&cNo");
        placeholders.put("%ah_notifications%", ahNotif ? "&aON" : "&cOFF");
        placeholders.put("%ah_confirm%", ahConfirm ? "&aON" : "&cOFF");

        populateConfiguredItems(inv, "admin.managePlayer.items", placeholders, target);
        admin.openInventory(inv);
    }

    public void openBanReasonAnvil(Player admin, OfflinePlayer target) {
        AdminSession session = sessions.computeIfAbsent(admin.getUniqueId(), k -> new AdminSession());
        session.currentMenu = MenuType.BAN_REASON;
        session.targetPlayer = target.getUniqueId();

        Inventory inv = Bukkit.createInventory(admin, InventoryType.ANVIL, TITLE_BAN_REASON);

        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Rule Violation");
            meta.setLore(Arrays.asList(
                    color("&7Type the ban reason above,"),
                    color("&7then click the output item to confirm.")
            ));
            paper.setItemMeta(meta);
        }

        inv.setItem(0, paper);
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
        String title = color(replacePagePlaceholders(rawTitle, session.page, maxPages));
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

        Map<String, String> placeholders = pagePlaceholders(session.page, maxPages);
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
        String title = color(replacePagePlaceholders(rawTitle, session.page, maxPages));
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

        Map<String, String> placeholders = pagePlaceholders(session.page, maxPages);
        populateConfiguredItems(inv, "admin.orders.items", placeholders, null);
        admin.openInventory(inv);
    }

    // ==================== EVENT HANDLERS ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();

        AdminSession session = sessions.get(admin.getUniqueId());
        if (session == null || session.currentMenu == MenuType.NONE) return;

        String viewTitle = event.getView().getTitle();
        if (!isAdminInventoryTitle(viewTitle)) {
            session.currentMenu = MenuType.NONE;
            return;
        }

        event.setCancelled(true);
        if (!admin.hasPermission("smp.admin")) {
            admin.closeInventory();
            return;
        }

        // Handle Anvil Ban Reason GUI
        if (session.currentMenu == MenuType.BAN_REASON || viewTitle.equals(TITLE_BAN_REASON)) {
            if (event.getRawSlot() != 2) return;

            ItemStack result = event.getCurrentItem();
            if (result == null || result.getType() == Material.AIR) return;

            String reason = "Banned by an administrator.";
            if (result.hasItemMeta() && result.getItemMeta().hasDisplayName()) {
                String typed = ChatColor.stripColor(result.getItemMeta().getDisplayName()).trim();
                if (!typed.isEmpty()) {
                    reason = typed;
                }
            }

            event.getView().getTopInventory().clear();
            admin.closeInventory();

            if (session.targetPlayer != null) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetPlayer);
                String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();

                target.ban(reason, (Instant) null, admin.getName());
                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().kickPlayer(color("&cYou have been banned.\n&7Reason: &f" + reason));
                }
                admin.sendMessage(color("&cBanned " + targetName + " &7for: &f" + reason));
                openPlayerDetailGui(admin, target);
            }
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
            String action = plugin.getConfig().getString("admin.items." + slot + ".action", "");
            switch (action.toLowerCase()) {
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
                    session.currentMenu = MenuType.NONE;
                    admin.closeInventory();
                    break;
            }
            return;
        }

        int itemSlots = event.getView().getTopInventory().getSize() - 9;

        // 2. Players List
        if (session.currentMenu == MenuType.PLAYERS) {
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("admin.players.items." + slot + ".action", "");
                switch (action.toLowerCase()) {
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openPlayersGui(admin, session);
                        break;
                    case "next":
                        session.page++;
                        openPlayersGui(admin, session);
                        break;
                    case "search":
                        session.currentMenu = MenuType.NONE;
                        admin.closeInventory();
                        awaitingPlayerSearch.add(admin.getUniqueId());
                        admin.sendMessage(color("&eType the player's exact username in chat (or &ccancel&e):"));
                        break;
                    case "back":
                        openMainGui(admin);
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

        // 3. Player Detail View
        if (session.currentMenu == MenuType.MANAGE_PLAYER) {
            String action = plugin.getConfig().getString("admin.managePlayer.items." + slot + ".action", "");
            if (action.equalsIgnoreCase("back")) {
                session.page = 1;
                openPlayersGui(admin, session);
                return;
            }

            if (session.targetPlayer == null) return;
            OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetPlayer);
            String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();

            switch (action.toLowerCase()) {
                case "toggle_ban":
                    if (target.isBanned()) {
                        @SuppressWarnings("unchecked")
BanList<PlayerProfile> banList = (BanList<PlayerProfile>) Bukkit.getBanList(BanList.Type.NAME);
banList.pardon(target.getPlayerProfile());
                        admin.sendMessage(color("&aUnbanned " + targetName + "."));
                        openPlayerDetailGui(admin, target);
                    } else {
                        openBanReasonAnvil(admin, target);
                    }
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
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("admin.auctions.items." + slot + ".action", "");
                switch (action.toLowerCase()) {
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openAuctionsModGui(admin, session);
                        break;
                    case "next":
                        session.page++;
                        openAuctionsModGui(admin, session);
                        break;
                    case "back":
                        openMainGui(admin);
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
            if (slot >= itemSlots) {
                String action = plugin.getConfig().getString("admin.orders.items." + slot + ".action", "");
                switch (action.toLowerCase()) {
                    case "previous":
                        session.page = Math.max(1, session.page - 1);
                        openOrdersModGui(admin, session);
                        break;
                    case "next":
                        session.page++;
                        openOrdersModGui(admin, session);
                        break;
                    case "back":
                        openMainGui(admin);
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
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(TITLE_BAN_REASON)) {
            event.getInventory().clear();
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

    // ==================== CONFIG & ITEM HELPERS ====================

    private void populateConfiguredItems(Inventory inv, String sectionPath, Map<String, String> placeholders, OfflinePlayer headOwner) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionPath);
        if (section == null) return;

        int size = inv.getSize();
        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, buildConfigItem(sectionPath + "." + slot, placeholders, headOwner));
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Invalid slot number in " + sectionPath + ": " + key);
            }
        }
    }

    private ItemStack buildConfigItem(String path, Map<String, String> placeholders, OfflinePlayer headOwner) {
        String matConfig = plugin.getConfig().getString(path + ".material", "STONE");
        ItemStack item;

        if ((matConfig.equalsIgnoreCase("%player_head%") || matConfig.equalsIgnoreCase("PLAYER_HEAD")) && headOwner != null) {
            item = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) item.getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(headOwner);
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
            meta.setDisplayName(color(applyPlaceholders(rawName, placeholders)));

            List<String> lore = new ArrayList<>();
            if (plugin.getConfig().isList(path + ".lore")) {
                for (String line : plugin.getConfig().getStringList(path + ".lore")) {
                    lore.add(color(applyPlaceholders(line, placeholders)));
                }
            } else if (plugin.getConfig().isString(path + ".lore")) {
                lore.add(color(applyPlaceholders(plugin.getConfig().getString(path + ".lore", ""), placeholders)));
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
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
            lore.add(color("&7Banned: " + (target.isBanned() ? "&cYes" : "&aNo")));
            lore.add("");
            lore.add(color("&eClick to manage player!"));
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private boolean isAdminInventoryTitle(String viewTitle) {
        if (viewTitle.equals(TITLE_BAN_REASON)) return true;
        String mainTitle = color(plugin.getConfig().getString("admin.title", "&8Admin Panel"));
        String playersPrefix = color(plugin.getConfig().getString("admin.players.title", "&8Admin -> Players").split("%page%")[0]);
        String managePrefix = color(plugin.getConfig().getString("admin.managePlayer.title", "&8Admin -> %player%").split("%player%")[0]);
        String auctionsPrefix = color(plugin.getConfig().getString("admin.auctions.title", "&8Admin -> Auctions").split("%page%")[0]);
        String ordersPrefix = color(plugin.getConfig().getString("admin.orders.title", "&8Admin -> Orders").split("%page%")[0]);

        return viewTitle.equals(mainTitle)
                || viewTitle.startsWith(playersPrefix)
                || viewTitle.startsWith(managePrefix)
                || viewTitle.startsWith(auctionsPrefix)
                || viewTitle.startsWith(ordersPrefix);
    }

    private Map<String, String> pagePlaceholders(int page, int maxPages) {
        Map<String, String> map = new HashMap<>();
        map.put("%page%", String.valueOf(page));
        map.put("%maxpage%", String.valueOf(maxPages));
        map.put("%prevpage%", String.valueOf(Math.max(1, page - 1)));
        map.put("%nextpage%", String.valueOf(Math.min(maxPages, page + 1)));
        return map;
    }

    private String replacePagePlaceholders(String text, int page, int maxPages) {
        return applyPlaceholders(text, pagePlaceholders(page, maxPages));
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) return text;
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private int normalizeSize(int raw, int min) {
        return Math.max(min, Math.min(54, raw - (raw % 9)));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private enum MenuType {
        NONE, MAIN, PLAYERS, MANAGE_PLAYER, BAN_REASON, AUCTIONS, ORDERS
    }

    private static class AdminSession {
        MenuType currentMenu = MenuType.NONE;
        int page = 1;
        UUID targetPlayer = null;
        final Map<Integer, UUID> displayedPlayers = new HashMap<>();
        final Map<Integer, Auction.AuctionItem> displayedAuctions = new HashMap<>();
        final Map<Integer, Orders.OrderRequest> displayedOrders = new HashMap<>();
    }
}
