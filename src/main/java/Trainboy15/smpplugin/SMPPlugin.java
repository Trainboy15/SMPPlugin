package trainboy15.smpplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import trainboy15.smpplugin.commands.Auction;
import trainboy15.smpplugin.commands.Shop;

public class SMPPlugin extends JavaPlugin {

    private Economy econ = null;
    private Auction auctionHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Hook into Vault Economy
        if (!setupEconomy()) {
            getLogger().severe("Disabled due to no Vault dependency found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register /shop command and GUI click listener
        Shop shopHandler = new Shop(this);
        if (getCommand("shop") != null) {
            getCommand("shop").setExecutor(shopHandler);
        }
        getServer().getPluginManager().registerEvents(shopHandler, this);

        // Register /ah command and GUI click listener
        auctionHandler = new Auction(this);
        if (getCommand("ah") != null) {
            getCommand("ah").setExecutor(auctionHandler);
        }
        getServer().getPluginManager().registerEvents(auctionHandler, this);

        getLogger().info("SMP Plugin v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SMP Plugin disabled.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    public Economy getEconomy() {
        return econ;
    }
}
