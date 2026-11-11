package trainboy15.smpplugin.commands;

import trainboy15.smpplugin.SMPPlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Settings implements CommandExecutor, Listener {

    private final SMPPlugin plugin;
    private final File playerDataFile;
    private FileConfiguration playerData;

    public Settings(SMPPlugin plugin) {
        this.plugin = plugin;
        this.playerDataFile = new File(plugin.getDataFolder(), "playerdata.yml");
        loadPlayerData();
    }

    private void loadPlayerData() {
        if (!playerDataFile.exists()) {
            try {
                if (plugin.getDataFolder() != null && !plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                playerDataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create playerdata.yml!");
                e.printStackTrace();
            }
        }
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save playerdata.yml!");
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        openSettingsGui(player);
        return true;
    }

    public void openSettingsGui(Player player) {
        int size = plugin.getConfig().getInt("settings.size", 27);
        size = Math.max(9, Math.min(54, size - (size % 9)));

        String rawTitle = plugin.getConfig().getString("settings.title", "&8Settings");
        String title = color(rawTitle);

        Inventory inventory = Bukkit.createInventory(null, size, title);

        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("settings.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < size) {
                        inventory.setItem(slot, createSettingItem(player, slot));
                    }
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid slot number in settings config: " + key);
                }
            }
        }

        player.openInventory(inventory);
    }

    private ItemStack createSettingItem(Player player, int slot) {
        String path = "settings.items." + slot;
        String matName = plugin.getConfig().getString(path + ".material", "STONE");
        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.STONE;

        String action = plugin.getConfig().getString(path + ".action", "");
        boolean enabled = getSetting(player.getUniqueId(), action);

        String enabledPrefix = plugin.getConfig().getString("settings.EnabledPrefix", "&a✔ &fEnabled");
        String disabledPrefix = plugin.getConfig().getString("settings.DisabledPrefix", "&c✘ &fDisabled");
        String statusText = enabled ? disabledPrefix : enabledPrefix;

        ItemStack display = new ItemStack(material);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            String name = plugin.getConfig().getString(path + ".name", "");
            meta.setDisplayName(color(name.replace("%status%", statusText)));

            List<String> lore = new ArrayList<>();
            if (plugin.getConfig().isList(path + ".lore")) {
                for (String line : plugin.getConfig().getStringList(path + ".lore")) {
                    lore.add(color(line.replace("%status%", statusText)));
                }
            } else if (plugin.getConfig().isString(path + ".lore")) {
                lore.add(color(plugin.getConfig().getString(path + ".lore", "").replace("%status%", statusText)));
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

        String settingsTitle = color(plugin.getConfig().getString("settings.title", "&8Settings"));
        if (!event.getView().getTitle().equals(settingsTitle)) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int slot = event.getSlot();
        String path = "settings.items." + slot;
        String action = plugin.getConfig().getString(path + ".action", "");
        if (action.isEmpty()) return;

        if (action.equalsIgnoreCase("close")) {
            player.closeInventory();
            return;
        }

        if (action.toLowerCase().startsWith("toggle_")) {
            boolean current = getSetting(player.getUniqueId(), action);
            setSetting(player.getUniqueId(), action, !current);
            openSettingsGui(player);
        }
    }

    public boolean getSetting(UUID uuid, String settingKey) {
        if (settingKey == null || settingKey.isEmpty()) return true;
        String normalizedKey = settingKey.toLowerCase().replace("toggle_", "");
        return playerData.getBoolean("players." + uuid.toString() + "." + normalizedKey, true);
    }

    public void setSetting(UUID uuid, String settingKey, boolean value) {
        if (uuid == null || settingKey == null || settingKey.isEmpty()) return;

        Player player = Bukkit.getPlayer(uuid);
        String normalizedKey = settingKey.toLowerCase().replace("toggle_", "");

        if (normalizedKey.equals("ah_confirm") && player != null && !player.hasPermission("smp.premium")) {
            player.sendMessage(color("&cYou need Hyper&b+ &cto change this setting!"));
            return;
        }

        playerData.set("players." + uuid.toString() + "." + normalizedKey, value);
        savePlayerData();
    }


    public boolean hasAhNotifications(UUID uuid) {
        return getSetting(uuid, "ah_notifications");
    }

    public boolean hasAhConfirm(UUID uuid) {
        return getSetting(uuid, "ah_confirm");
    }

    public boolean hasPrivateMessages(UUID uuid) {
        return getSetting(uuid, "msg");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
