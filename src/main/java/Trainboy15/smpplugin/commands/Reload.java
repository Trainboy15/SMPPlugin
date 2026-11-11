package trainboy15.smpplugin.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;

public class Reload implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    public Reload(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle /smp reload
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("smp.admin")) {
                sender.sendMessage(color(plugin.getConfig().getString("general.noCommand", "&cThis command does not exist.")));
                return true;
            }

            plugin.reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully!");
            return true;
        }

        // Default /smp message if no valid subcommand is given
        sender.sendMessage(ChatColor.YELLOW + "Usage: /smp reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("smp.admin")) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

}
