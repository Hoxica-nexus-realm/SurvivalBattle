package ham_sandwitch.plugin.survivalbattle.game;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Random;

public class WorldManager {
    
    private final JavaPlugin plugin;
    private World battleWorld;
    
    // 収集フェーズのデフォルト大幅に拡張
    private static final int COLLECTION_BORDER_SIZE = 100000;
    // PVP フェーズの初期ボーダー（デフォルト）
    private static final int DEFAULT_PVP_INITIAL_BORDER = 150;
    private static final double BORDER_DAMAGE = 0.5;
    
    public WorldManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * 新しいバトルワールドを生成
     */
    public boolean createBattleWorld() {
        deleteBattleWorld();
        
        WorldCreator wc = new WorldCreator("survivalbattle_battle_" + System.currentTimeMillis());
        wc.environment(World.Environment.NORMAL);
        wc.seed(new Random().nextLong());
        this.battleWorld = wc.createWorld();
        
        if (this.battleWorld == null) {
            return false;
        }
        
        setupBattleWorld();
        return true;
    }
    
    /**
     * バトルワールドの初期設定
     */
    private void setupBattleWorld() {
        if (battleWorld == null) return;
        
        battleWorld.setTime(0);
        battleWorld.setDifficulty(Difficulty.NORMAL);
        
        // ゲームルール設定
        battleWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        battleWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);  // ★ MOB 無効化
        battleWorld.setGameRule(GameRule.KEEP_INVENTORY, false);
        battleWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        battleWorld.setGameRule(GameRule.DO_FIRE_TICK, true);
        battleWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);  // ★ 天候無効化
        battleWorld.setGameRule(GameRule.DO_MOB_LOOT, true);
        battleWorld.setGameRule(GameRule.DO_TILE_DROPS, true);
        battleWorld.setGameRule(GameRule.RANDOM_TICK_SPEED, 3);

        WorldBorder border = battleWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(COLLECTION_BORDER_SIZE);
        border.setWarningDistance(10);
        border.setDamageAmount(BORDER_DAMAGE);
        
        plugin.getLogger().info("Battle world created: " + battleWorld.getName());
    }
    
    /**
     * PVPフェーズ用にボーダーを初期サイズに設定（可変版）
     */
    public void setPvpBorder(double size) {
        if (battleWorld == null) return;
        
        WorldBorder border = battleWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(size);
        
        plugin.getLogger().info("PVP border set: " + size);
    }

    /**
     * 収集フェーズ用のボーダーサイズを設定
     */
    public void setCollectionBorder(int size) {
        if (battleWorld == null) return;
        WorldBorder border = battleWorld.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(size);
        plugin.getLogger().info("Collection border set: " + size);
    }
    
    /**
     * ワールドボーダーを縮小開始（最終サイズと秒数を指定）
     */
    public void startBorderShrink(double finalSize, int durationSeconds) {
        if (battleWorld == null) return;
        
        WorldBorder border = battleWorld.getWorldBorder();
        // 設定されている現在サイズから finalSize に duration で縮小
        border.setSize(finalSize, durationSeconds);
        
        plugin.getLogger().info("Border shrinking to " + finalSize + " over " + durationSeconds + "s");
    }
    
    /**
     * バトルワールドのスポーン地点を取得
     */
    public Location getBattleSpawnLocation() {
        if (battleWorld == null) return null;
        
        Location spawn = battleWorld.getSpawnLocation().clone();
        spawn.add(0.5, 0.5, 0.5);
        spawn.add(0.5, 1.0, 0.5);
        return TeleportUtil.findSafeLocation(spawn);
    }
    
    /**
     * 中央から半径50以内のランダムな安全位置を取得
     */
    public Location getRandomSafeLocation() {
        if (battleWorld == null) return null;
        
        Random random = new Random();
        int maxAttempts = 50;
        
        for (int i = 0; i < maxAttempts; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = random.nextDouble() * 50;
            
            int x = (int) (distance * Math.cos(angle));
            int z = (int) (distance * Math.sin(angle));
            
            Location loc = new Location(battleWorld, x + 0.5, 100, z + 0.5);
            Location safeLoc = TeleportUtil.findSafeLocation(loc);
            
            if (safeLoc != null) {
                return safeLoc;
            }
        }
        
        Location fallback = new Location(battleWorld, 0.5, 100, 0.5);
        return TeleportUtil.findSafeLocation(fallback);
    }
    
    /**
     * バトルワールドをアンロードし、フォルダを削除
     */
    public void deleteBattleWorld() {
        if (battleWorld == null) return;
        
        String worldName = battleWorld.getName();
        
        for (Player p : battleWorld.getPlayers()) {
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        
        boolean unloaded = Bukkit.unloadWorld(battleWorld, false);
        if (unloaded) {
            File worldFolder = battleWorld.getWorldFolder();
            battleWorld = null;
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (worldFolder.exists()) {
                    deleteFolder(worldFolder);
                    plugin.getLogger().info("World folder deleted: " + worldName);
                }
            }, 20L);
        } else {
            plugin.getLogger().warning("Failed to unload world: " + worldName);
        }
    }
    
    /**
     * フォルダを再帰的に削除
     */
    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }
        folder.delete();
    }
    
    public World getBattleWorld() {
        return battleWorld;
    }
    
    public boolean hasBattleWorld() {
        return battleWorld != null;
    }
}
