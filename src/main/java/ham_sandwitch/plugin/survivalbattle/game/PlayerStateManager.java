package ham_sandwitch.plugin.survivalbattle.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.GameMode;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerStateManager {
    private final JavaPlugin plugin;

    public static class SavedPlayerState {
        public ItemStack[] contents;
        public ItemStack[] armor;
        public ItemStack offhand;
        public int foodLevel;
        public float saturation;
        public double health;
        public GameMode gameMode;
    }

    private final Map<UUID, SavedPlayerState> saved = new HashMap<>();

    public PlayerStateManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasSavedState(UUID uuid) {
        return saved.containsKey(uuid);
    }

    public void savePreMatchState(Player p) {
        if (p == null || !p.isOnline()) return;
        try {
            SavedPlayerState s = new SavedPlayerState();
            ItemStack[] contents = p.getInventory().getContents();
            ItemStack[] armor = p.getInventory().getArmorContents();
            s.contents = contents != null ? contents.clone() : new ItemStack[0];
            s.armor = armor != null ? armor.clone() : new ItemStack[0];
            s.offhand = p.getInventory().getItemInOffHand();
            s.foodLevel = p.getFoodLevel();
            s.saturation = p.getSaturation();
            s.health = p.getHealth();
            s.gameMode = p.getGameMode();
            saved.put(p.getUniqueId(), s);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save pre-match state for " + (p == null ? "<null>" : p.getName()) + ": " + e.getMessage());
        }
    }

    public void restorePostMatchState(Player p) {
        if (p == null) return;
        SavedPlayerState s = saved.get(p.getUniqueId());
        if (s == null) return;
        if (!p.isOnline()) {
            plugin.getLogger().info("Player " + p.getName() + " is offline; deferring state restore.");
            return;
        }
        try {
            p.getInventory().clear();
            if (s.contents != null) p.getInventory().setContents(s.contents);
            if (s.armor != null) p.getInventory().setArmorContents(s.armor);
            if (s.offhand != null) p.getInventory().setItemInOffHand(s.offhand);
            p.setFoodLevel(s.foodLevel);
            p.setSaturation(s.saturation);
            double health = Math.max(0.0, Math.min(p.getMaxHealth(), s.health));
            p.setHealth(health);
            if (s.gameMode != null) p.setGameMode(s.gameMode);
            p.updateInventory();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to restore post-match state for " + p.getName() + ": " + e.getMessage());
        } finally {
            saved.remove(p.getUniqueId());
        }
    }

    public void remove(UUID uuid) {
        saved.remove(uuid);
    }
}

