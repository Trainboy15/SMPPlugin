package trainboy15.smpplugin.commands;

import trainboy15.smpplugin.SMPPlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class Tpa implements CommandExecutor, Listener, TabCompleter {

    private final SMPPlugin plugin;

    // Tracks pending GUI confirmations: Sender UUID -> PendingRequest
    private final Map<UUID, PendingRequest> pendingGuiConfirmations = new HashMap<>();

    // Tracks active incoming TPA requests: Target UUID -> PendingRequest
    private final Map<UUID, PendingRequest> activeRequests = new HashMap<>();

    private static final long REQUEST_TIMEOUT_MS = 60_000L; // 60 seconds

    public Tpa(SMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        String cmdName = command.getName().toLowerCase();

        // 1. /tpa <player> or /tpahere <player>
        if (cmdName.equals("tpa") || cmdName.equals("tpahere")) {
            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /" + cmdName + " <player>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "Player not found or offline.");
                return true;
            }

            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You cannot send a teleport request to yourself.");
                return true;
            }

            boolean isTpaHere = cmdName.equals("tpahere");
            PendingRequest pending = new PendingRequest(player.getUniqueId(), target.getUniqueId(), isTpaHere, System.currentTimeMillis());
            pendingGuiConfirmations.put(player.getUniqueId(), pending);

            openConfirmGui(player, target);
            return true;
        }

        // 2. /tpaccept
        if (cmdName.equals("tpaccept")) {
            PendingRequest request = activeRequests.remove(player.getUniqueId());
            if (request == null || isExpired(request)) {
                player.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
                return true;
            }

            Player requester = Bukkit.getPlayer(request.senderUUID);
            if (requester == null || !requester.isOnline()) {
                player.sendMessage(ChatColor.RED + "The player who sent the request is no longer online.");
                return true;
            }

            player.sendMessage(ChatColor.GREEN + "Accepted teleport request from " + requester.getName() + ".");
            requester.sendMessage(ChatColor.GREEN + player.getName() + " accepted your teleport request!");

            if (request.isTpaHere) {
                player.teleport(requester.getLocation());
            } else {
                requester.teleport(player.getLocation());
            }
            return true;
        }

        // 3. /tpadeny
        if (cmdName.equals("tpadeny")) {
            PendingRequest request = activeRequests.remove(player.getUniqueId());
            if (request == null || isExpired(request)) {
                player.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
                return true;
            }

            Player requester = Bukkit.getPlayer(request.senderUUID);
            player.sendMessage(ChatColor.YELLOW + "Denied teleport request.");
            if (requester != null && requester.isOnline()) {
                requester.sendMessage(ChatColor.RED + player.getName() + " denied your teleport request.");
            }
            return true;
        }

        return true;
    }

    private void openConfirmGui(Player sender, Player target) {
        String title = color(plugin.getConfig().getString("tpa.confirmRequest.title", "&8Confirm Request"));
        Inventory inventory = Bukkit.createInventory(null, 27, title);

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("tpa.confirmRequest");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key.equalsIgnoreCase("title")) continue;

                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < 27) {
                        inventory.setItem(slot, createGuiItem(key, target));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        sender.openInventory(inventory);
    }

    private ItemStack createGuiItem(String slotKey, Player target) {
        String path = "tpa.confirmRequest." + slotKey;
        String matName = plugin.getConfig().getString(path + ".material", "STONE");

        Material material;
        if (matName.equalsIgnoreCase("HEAD") || matName.equalsIgnoreCase("PLAYER_HEAD")) {
            material = Material.PLAYER_HEAD;
        } else {
            material = Material.matchMaterial(matName);
            if (material == null) material = Material.STONE;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            if (meta instanceof SkullMeta) {
                ((SkullMeta) meta).setOwningPlayer(target);
            }

            String name = plugin.getConfig().getString(path + ".name", "").replace("%player%", target.getName());
            meta.setDisplayName(color(name));

            List<String> lore = new ArrayList<>();
            if (plugin.getConfig().isList(path + ".lore")) {
                for (String line : plugin.getConfig().getStringList(path + ".lore")) {
                    lore.add(color(line.replace("%player%", target.getName())));
                }
            } else if (plugin.getConfig().isString(path + ".lore")) {
                String line = plugin.getConfig().getString(path + ".lore", "");
                lore.add(color(line.replace("%player%", target.getName())));
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String expectedTitle = color(plugin.getConfig().getString("tpa.confirmRequest.title", "&8Confirm Request"));
        if (!event.getView().getTitle().equals(expectedTitle)) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        String action = plugin.getConfig().getString("tpa.confirmRequest." + slot + ".action", "");
        if (action.isEmpty()) return;

        PendingRequest pending = pendingGuiConfirmations.remove(player.getUniqueId());
        player.closeInventory();

        if (action.equalsIgnoreCase("cancel") || pending == null) {
            player.sendMessage(ChatColor.RED + "Teleport request cancelled.");
            return;
        }

        if (action.equalsIgnoreCase("confirm")) {
            Player target = Bukkit.getPlayer(pending.targetUUID);
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "That player is no longer online.");
                return;
            }

            // Store the active request with a fresh timestamp
            PendingRequest active = new PendingRequest(player.getUniqueId(), target.getUniqueId(), pending.isTpaHere, System.currentTimeMillis());
            activeRequests.put(target.getUniqueId(), active);

            String typeLabel = pending.isTpaHere ? "TP here" : "TPA";
            String rawMsg = plugin.getConfig().getString(
                    "tpa.incomingRequest.message",
                    "&b%player% &fsent you a &f%type% &frequest. &fType &b/tpaccept &for &b/tpadeny"
            );

            String formattedMsg = color(rawMsg
                    .replace("%player%", player.getName())
                    .replace("%type%", typeLabel));

            target.sendMessage(formattedMsg);
            player.sendMessage(ChatColor.GREEN + "Teleport request sent to " + target.getName() + "!");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();
        if ((cmdName.equals("tpa") || cmdName.equals("tpahere")) && args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> !name.equalsIgnoreCase(sender.getName()))
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean isExpired(PendingRequest request) {
        return (System.currentTimeMillis() - request.timestamp) > REQUEST_TIMEOUT_MS;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static class PendingRequest {
        final UUID senderUUID;
        final UUID targetUUID;
        final boolean isTpaHere;
        final long timestamp;

        PendingRequest(UUID senderUUID, UUID targetUUID, boolean isTpaHere, long timestamp) {
            this.senderUUID = senderUUID;
            this.targetUUID = targetUUID;
            this.isTpaHere = isTpaHere;
            this.timestamp = timestamp;
        }
    }
}
