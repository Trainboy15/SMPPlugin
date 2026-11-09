package trainboy15.smpplugin;

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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class Shop implements CommandExecutor, Listener {

    private final SMPPlugin plugin;

    public Shop(SMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (args.length > 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /shop");
            return true;
        }

        openInventory((Player) sender);
        return true;
    }

    private void openInventory(Player player) {
        int size = plugin.getConfig().getInt("gui.size", 27);
        size = Math.max(9, Math.min(54, size - size % 9));

        String title = color(plugin.getConfig().getString("shop.title", "&bShop"));
        Inventory inventory = Bukkit.createInventory(null, size, title);

        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("shop.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                String path = "shop.items" + key;
                int slot = plugin.getConfig().getInt(path + ".slot", -1);
                String matName = plugin.getConfig().getString(path + ".material", "STONE");
                Material material = Material.matchMaterial(matName);

                if (material != null && slot >= 0 && slot < size) {
                    int amount = plugin.getConfig().getInt(path + ".amount", 1);
                    double buyPrice = plugin.getConfig().getDouble(path + ".buy", -1);
                    double sellPrice = plugin.getConfig().getDouble(path + ".sell", -1);

                    ItemStack item = new ItemStack(material, amount);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        String displayName = plugin.getConfig().getString(path + ".name");
                        if (displayName != null) {
                            meta.setDisplayName(color(displayName));
                        }

                        List<String> lore = new ArrayList<>();
                        if (buyPrice >= 0) {
                            lore.add(color("&aLeft-Click to Buy: &f$" + buyPrice));
                        }
                        if (sellPrice >= 0) {
                            lore.add(color("&cRight-Click to Sell: &f$" + sellPrice));
                        }
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                    }
                    inventory.setItem(slot, item);
                }
            }
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = color(plugin.getConfig().getString("gui.title", "&bShop"));
        if (!event.getView().getTitle().equals(title)) return;

        event.setCancelled(true); // Prevents players from taking GUI items

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        int clickedSlot = event.getRawSlot();
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("shop-items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
            String path = "shop-items." + key;
            if (plugin.getConfig().getInt(path + ".slot", -1) == clickedSlot) {
                handleTransaction(player, path, event.getClick());
                break;
            }
        }
    }

    private void handleTransaction(Player player, String path, ClickType click) {
        Economy econ = plugin.getEconomy();
        if (econ == null) {
            player.sendMessage(ChatColor.RED + "Economy system is not available.");
            return;
        }

        Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "STONE"));
        int amount = plugin.getConfig().getInt(path + ".amount", 1);
        double buyPrice = plugin.getConfig().getDouble(path + ".buy", -1);
        double sellPrice = plugin.getConfig().getDouble(path + ".sell", -1);

        if (material == null) return;

        // BUYING (Left Click)
        if (click.isLeftClick() && buyPrice >= 0) {
            if (!econ.has(player, buyPrice)) {
                player.sendMessage(ChatColor.RED + "You don't have enough money! Need $" + buyPrice);
                return;
            }
            if (player.getInventory().firstEmpty() == -1) {
                player.sendMessage(ChatColor.RED + "Your inventory is full!");
                return;
            }

            econ.withdrawPlayer(player, buyPrice);
            player.getInventory().addItem(new ItemStack(material, amount));
            player.sendMessage(ChatColor.GREEN + "Bought " + amount + "x " + material.name() + " for $" + buyPrice);
        } 
        // SELLING (Right Click)
        else if (click.isRightClick() && sellPrice >= 0) {
            ItemStack itemToRemove = new ItemStack(material, amount);
            if (!player.getInventory().containsAtLeast(itemToRemove, amount)) {
                player.sendMessage(ChatColor.RED + "You need at least " + amount + "x " + material.name() + " to sell!");
                return;
            }

            player.getInventory().removeItem(itemToRemove);
            econ.depositPlayer(player, sellPrice);
            player.sendMessage(ChatColor.GREEN + "Sold " + amount + "x " + material.name() + " for $" + sellPrice);
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
