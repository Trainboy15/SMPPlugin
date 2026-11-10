package trainboy15.smpplugin.commands;

import trainboy15.smpplugin.SMPPlugin;
import trainboy15.smpplugin.commands.Sell;

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
import java.util.Collections;

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
        int size = plugin.getConfig().getInt("shop.size", 27);
        size = Math.max(9, Math.min(54, size - size % 9));

        String title = color(plugin.getConfig().getString("shop.title", "&8Shop"));
        Inventory inventory = Bukkit.createInventory(null, size, title);

        ConfigurationSection pagesSection = plugin.getConfig().getConfigurationSection("shop.pages");
        if (pagesSection != null) {
            for (String pageKey : pagesSection.getKeys(false)) {
                String path = "shop.pages." + pageKey;
                int slot = plugin.getConfig().getInt(path + ".pageItemSlot", -1);
                Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".pageItem", "STONE"));

                if (material != null && slot >= 0 && slot < size) {
                    ItemStack item = new ItemStack(material);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        String name = plugin.getConfig().getString(path + ".pageItemName");
                        if (name != null) {
                            meta.setDisplayName(color(name));
                        }

                        String loreLine = plugin.getConfig().getString(path + ".pageItemLore");
                        if (loreLine != null && !loreLine.isEmpty()) {
                            meta.setLore(Collections.singletonList(color(loreLine)));
                        }

                        item.setItemMeta(meta);
                    }
                    inventory.setItem(slot, item);
                }
            }
        }

        player.openInventory(inventory);
    }
        private void openPage(Player player, String pageKey) {
        String pagePath = "shop.pages." + pageKey;
        ConfigurationSection pageSection = plugin.getConfig().getConfigurationSection(pagePath);
        if (pageSection == null) return;

        int size = plugin.getConfig().getInt("shop.size", 27);
        size = Math.max(9, Math.min(54, size - size % 9));

        String rawTitle = pageSection.getString("pageTitle", plugin.getConfig().getString("shop.title", "&8Shop"));
        Inventory inventory = Bukkit.createInventory(null, size, color(rawTitle));

        // Add Back / Exit Button
        int exitSlot = plugin.getConfig().getInt("shop.exitButtonSlot", -1);
        Material exitMat = Material.matchMaterial(plugin.getConfig().getString("shop.exitButtonItem", "RED_STAINED_GLASS"));
        if (exitMat != null && exitSlot >= 0 && exitSlot < size) {
            ItemStack exitItem = new ItemStack(exitMat);
            ItemMeta exitMeta = exitItem.getItemMeta();
            if (exitMeta != null) {
                exitMeta.setDisplayName(color(plugin.getConfig().getString("shop.exitButtonItemName", "&4Back")));
                String exitLore = plugin.getConfig().getString("shop.exitButtonItemLore");
                if (exitLore != null && !exitLore.isEmpty()) {
                    exitMeta.setLore(Collections.singletonList(color(exitLore)));
                }
                exitItem.setItemMeta(exitMeta);
            }
            inventory.setItem(exitSlot, exitItem);
        }

        // Add Category Shop Items
        ConfigurationSection itemsSection = pageSection.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String slotKey : itemsSection.getKeys(false)) {
                int slot;
                try {
                    slot = Integer.parseInt(slotKey);
                } catch (NumberFormatException e) {
                    continue;
                }

                String itemPath = pagePath + ".items." + slotKey;
                Material material = Material.matchMaterial(plugin.getConfig().getString(itemPath + ".material", "STONE"));
                int amount = plugin.getConfig().getInt(itemPath + ".amount", 1);
                double buyPrice = plugin.getConfig().getDouble(itemPath + ".buy", -1);
                double sellPrice = plugin.getConfig().getDouble(itemPath + ".sell", -1);

                if (material != null && slot >= 0 && slot < size) {
                    ItemStack item = new ItemStack(material, amount);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
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
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String viewTitle = event.getView().getTitle();
        String mainTitle = color(plugin.getConfig().getString("shop.title", "&8Shop"));
        ConfigurationSection pagesSection = plugin.getConfig().getConfigurationSection("shop.pages");

        // 1. Check if the player clicked inside the Main Shop Menu
        if (viewTitle.equals(mainTitle)) {
            event.setCancelled(true);
            int clickedSlot = event.getRawSlot();
            if (clickedSlot < 0 || clickedSlot >= event.getInventory().getSize()) return;

            if (pagesSection != null) {
                for (String pageKey : pagesSection.getKeys(false)) {
                    int pageSlot = plugin.getConfig().getInt("shop.pages." + pageKey + ".pageItemSlot", -1);
                    if (clickedSlot == pageSlot) {
                        openPage(player, pageKey); // Opens the matching category page
                        return;
                    }
                }
            }
            return;
        }

        // 2. Check if the player clicked inside one of the Category Pages
        if (pagesSection != null) {
            for (String pageKey : pagesSection.getKeys(false)) {
                String pagePath = "shop.pages." + pageKey;
                String pageTitle = color(plugin.getConfig().getString(pagePath + ".pageTitle", mainTitle));

                if (viewTitle.equals(pageTitle)) {
                    event.setCancelled(true);
                    int clickedSlot = event.getRawSlot();
                    if (clickedSlot < 0 || clickedSlot >= event.getInventory().getSize()) return;

                    // Check if they clicked the Back / Exit button
                    int exitSlot = plugin.getConfig().getInt("shop.exitButtonSlot", -1);
                    if (clickedSlot == exitSlot) {
                        openInventory(player); // Returns to the main shop menu
                        return;
                    }

                    // Check if they clicked a purchasable/sellable item
                    String itemPath = pagePath + ".items." + clickedSlot;
                    if (plugin.getConfig().contains(itemPath)) {
                        handleTransaction(player, itemPath, event.getClick());
                    }
                    return;
                }
            }
        }
    }


    private void handleTransaction(Player player, String path, ClickType click) {
        Economy econ = plugin.getEconomy();
        if (econ == null) {
            player.sendMessage(ChatColor.RED + "Economy system is not available.");
            return;
        }

        String matName = plugin.getConfig().getString(path + ".material", "STONE");
        Material material = Material.matchMaterial(matName);
        if (material == null) return;

        int amount = plugin.getConfig().getInt(path + ".amount", 1);
        double buyPrice = plugin.getConfig().getDouble(path + ".buy", -1);
        double sellPrice = plugin.getConfig().getDouble(path + ".sell", -1);

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
