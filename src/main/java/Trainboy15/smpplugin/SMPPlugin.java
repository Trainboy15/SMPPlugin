package trainboy15.smpplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import trainboy15.smpplugin.commands.Auction;
import trainboy15.smpplugin.commands.Orders;
import trainboy15.smpplugin.commands.Reload;
import trainboy15.smpplugin.commands.Sell;
import trainboy15.smpplugin.commands.Settings;
import trainboy15.smpplugin.commands.Shop;
import trainboy15.smpplugin.commands.Tpa;
import trainboy15.smpplugin.database.DatabaseManager;

public class SMPPlugin extends JavaPlugin {

    private Economy econ = null;
    private DatabaseManager databaseManager;
    private Settings settingsHandler;
    private Auction auctionHandler;
    private Orders ordersHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Hook into Vault Economy
        if (!setupEconomy()) {
            getLogger().severe("Disabled due to no Vault dependency found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize shared SQLite database manager first
        databaseManager = new DatabaseManager(this);

        // Register /settings command and listener (pass databaseManager)
        settingsHandler = new Settings(this, databaseManager);
        if (getCommand("settings") != null) {
            getCommand("settings").setExecutor(settingsHandler);
        }
        getServer().getPluginManager().registerEvents(settingsHandler, this);

        // Register /shop command and GUI click listener
        Shop shopHandler = new Shop(this);
        if (getCommand("shop") != null) {
            getCommand("shop").setExecutor(shopHandler);
        }
        getServer().getPluginManager().registerEvents(shopHandler, this);

        // Register /sell and /sellgui commands and GUI close listener
        Sell sellCommand = new Sell(this);
        if (getCommand("sell") != null) {
            getCommand("sell").setExecutor(sellCommand);
        }
        if (getCommand("sellgui") != null) {
            getCommand("sellgui").setExecutor(sellCommand);
        }
        getServer().getPluginManager().registerEvents(sellCommand, this);

        // Register /ah command and GUI click listener (pass settingsHandler & databaseManager)
        auctionHandler = new Auction(this, settingsHandler, databaseManager);
        if (getCommand("ah") != null) {
            getCommand("ah").setExecutor(auctionHandler);
        }
        getServer().getPluginManager().registerEvents(auctionHandler, this);

        // Register /orders command and GUI click listener (pass databaseManager)
        ordersHandler = new Orders(this, databaseManager);
        if (getCommand("orders") != null) {
            getCommand("orders").setExecutor(ordersHandler);
        }
        getServer().getPluginManager().registerEvents(ordersHandler, this);

        // Register /smp reload command and tab completer
        Reload reloadHandler = new Reload(this);
        if (getCommand("smp") != null) {
            getCommand("smp").setExecutor(reloadHandler);
            getCommand("smp").setTabCompleter(reloadHandler);
        }

        // Register /tpa, /tpahere, /tpaccept, /tpadeny, GUI listener, and tab completer
        Tpa tpaHandler = new Tpa(this);
        String[] tpaCommands = {"tpa", "tpahere", "tpaccept", "tpadeny"};
        for (String cmd : tpaCommands) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(tpaHandler);
                getCommand(cmd).setTabCompleter(tpaHandler);
            }
        }
        getServer().getPluginManager().registerEvents(tpaHandler, this);

        getLogger().info("SMP Plugin v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
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

    public Settings getSettings() {
        return settingsHandler;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
