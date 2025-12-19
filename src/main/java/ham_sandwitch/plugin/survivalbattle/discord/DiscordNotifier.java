package ham_sandwitch.plugin.survivalbattle.discord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ham_sandwitch.plugin.survivalbattle.game.GameManager;


public class DiscordNotifier {
    private final JavaPlugin plugin;
    private final GameManager gameManager;

    public DiscordNotifier(JavaPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    
    public void init() {
        
    }

    
    public void shutdown() {
        
    }

    
    public void sendStartNotification(String message) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
        if (!plugin.getConfig().getBoolean("discord.send_on_start", true)) return;
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                
            } catch (Exception e) {
                plugin.getLogger().warning("Discord sendStartNotification failed: " + e.getMessage());
            }
        });
    }

    
    public void sendEndNotification(String message) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
        if (!plugin.getConfig().getBoolean("discord.send_on_end", true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                
            } catch (Exception e) {
                plugin.getLogger().warning("Discord sendEndNotification failed: " + e.getMessage());
            }
        });
    }

    
    public void sendPlain(String content) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                
            } catch (Exception e) {
                plugin.getLogger().warning("Discord sendPlain failed: " + e.getMessage());
            }
        });
    }
}

