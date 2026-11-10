package trainboy15.smpplugin.commands;

import trainboy15.smpplugin.SMPPlugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

public class Sell implements CommandExecutor, Listener {

    private final SMPPlugin plugin;

    public Sell(SMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (args.length > 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /sell");
            return true;
        }

        openInventory((Player) sender);
        return true;
    }

    private void openInventory(Player player) {
        int size = plugin.getConfig().getInt("sellgui.size", 27);
        size = Math.max(9, Math.min(54, size - (size % 9)));

        String title = color(plugin.getConfig().getString("sellgui.title", "&8Sell Items"));
        Inventory inventory = Bukkit.createInventory(null, size, title);

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        String expectedTitle = color(plugin.getConfig().getString("sellgui.title", "&8Sell Items"));
        if (!event.getView().getTitle().equals(expectedTitle)) return;

        Economy econ = plugin.getEconomy();
        if (econ == null) {
            player.sendMessage(ChatColor.RED + "Economy system is not available.");
            returnItems(player, event.getInventory().getContents());
            return;
        }

        double totalEarned = 0.0;
        int totalItemsSold = 0;

        for (ItemStack item : event.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;

            // Look up the unit sell price from shop.pages.<page>.items.<slot>
            double unitPrice = getShopUnitSellPrice(item.getType());

            if (unitPrice > 0) {
                int amount = item.getAmount();
                totalEarned += unitPrice * amount;
                totalItemsSold += amount;
            } else {
                // Return unsellable items to the player
                returnSingleItem(player, item);
            }
        }

        event.getInventory().clear();

        if (totalItemsSold > 0) {
            econ.depositPlayer(player, totalEarned);
            player.sendMessage(ChatColor.GREEN + "Sold " + totalItemsSold + " item(s) for $" + String.format("%.2f", totalEarned) + "!");
        } else {
            player.sendMessage(ChatColor.YELLOW + "No sellable items were placed in the menu.");
        }
    }

    /**
     * Searches shop.pages.<pageKey>.items.<slot> for a matching material and returns its unit sell price.
     */
    private double getShopUnitSellPrice(Material targetMaterial) {
        ConfigurationSection pages = plugin.getConfig().getConfigurationSection("shop.pages");
        if (pages == null) return -1.0;

        for (String pageKey : pages.getKeys(false)) {
            ConfigurationSection items = pages.getConfigurationSection(pageKey + ".items");
            double price = findPriceInSection(items, targetMaterial);
            if (price > 0) return price;
        }

        return -1.0;
    }

    private double findPriceInSection(ConfigurationSection itemsSection, Material targetMaterial) {
        if (itemsSection == null) return -1.0;

        for (String key : itemsSection.getKeys(false)) {
            String matName = itemsSection.getString(key + ".material", "");
            Material mat = Material.matchMaterial(matName);

            if (mat == targetMaterial) {
                double sellPrice = itemsSection.getDouble(key + ".sell", -1.0);
                int amount = Math.max(1, itemsSection.getInt(key + ".amount", 1));
                if (sellPrice > 0) {
                    return sellPrice / amount;
                }
            }
        }
        return -1.0;
    }

    private void returnItems(Player player, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                returnSingleItem(player, item);
            }
        }
    }

    private void returnSingleItem(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
