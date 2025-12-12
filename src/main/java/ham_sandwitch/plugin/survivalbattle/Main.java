package ham_sandwitch.plugin.survivalbattle;

import ham_sandwitch.plugin.survivalbattle.commands.SbCommand;
import ham_sandwitch.plugin.survivalbattle.commands.SbTabCompleter;
import ham_sandwitch.plugin.survivalbattle.game.GameManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    private GameManager gameManager;
    private static Main instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        
        World defaultWorld = getServer().getWorlds().get(0);
        
        double x = getConfig().getDouble("teleport.lobby.x", defaultWorld.getSpawnLocation().getX());
        double y = getConfig().getDouble("teleport.lobby.y", defaultWorld.getSpawnLocation().getY());
        double z = getConfig().getDouble("teleport.lobby.z", defaultWorld.getSpawnLocation().getZ());
        float yaw = (float) getConfig().getDouble("teleport.lobby.yaw", defaultWorld.getSpawnLocation().getYaw());
        float pitch = (float) getConfig().getDouble("teleport.lobby.pitch", defaultWorld.getSpawnLocation().getPitch());
        String worldName = getConfig().getString("teleport.lobby.world", defaultWorld.getName());
        
        World lobbyWorld = getServer().getWorld(worldName);
        
        if (lobbyWorld == null) {
            getLogger().severe("Lobby world '" + worldName + "' not found! Using default world: " + defaultWorld.getName());
            lobbyWorld = defaultWorld;
        }

        Location lobbyLocation = new Location(lobbyWorld, x, y, z, yaw, pitch);
        
        double waitX = getConfig().getDouble("teleport.waiting.x", x);
        double waitY = getConfig().getDouble("teleport.waiting.y", y);
        double waitZ = getConfig().getDouble("teleport.waiting.z", z);
        float waitYaw = (float) getConfig().getDouble("teleport.waiting.yaw", yaw);
        float waitPitch = (float) getConfig().getDouble("teleport.waiting.pitch", pitch);
        String waitWorldName = getConfig().getString("teleport.waiting.world", "survivalbattle_wait");
        
        World waitingWorld = getServer().getWorld(waitWorldName);
        if (waitingWorld == null) {
            getLogger().warning("Waiting world '" + waitWorldName + "' not found! Using lobby world.");
            waitingWorld = lobbyWorld;
        }
        
        Location waitingLocation = new Location(waitingWorld, waitX, waitY, waitZ, waitYaw, waitPitch);
        
        this.gameManager = new GameManager(this, lobbyLocation, waitingLocation); 
        
        getServer().getPluginManager().registerEvents(gameManager, this);
        
        getCommand("sb").setExecutor(new SbCommand(gameManager, this));
        getCommand("sb").setTabCompleter(new SbTabCompleter());
        
        getLogger().info("SurvivalBattle Plugin enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.cleanup();
        }
        getLogger().info("SurvivalBattle Plugin disabled!");
    }
    
    public static Main getInstance() {
        return instance;
    }
    
    public GameManager getGameManager() {
        return gameManager;
    }
}