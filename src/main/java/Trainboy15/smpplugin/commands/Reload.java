package trainboy15.smpplugin.commands;
import trainboy15.smpplugin.SMPPlugin;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class Reload implements CommandExecutor {

    private final JavaPlugin plugin;

    public Reload(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check if the sender has permission
        if (!sender.hasPermission("smp.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        // Reload the config.yml file
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully!");
        
        return true;
    }
}
