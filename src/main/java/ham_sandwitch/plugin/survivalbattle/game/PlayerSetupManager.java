package ham_sandwitch.plugin.survivalbattle.game;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.List;

public class PlayerSetupManager {
    
    private final JavaPlugin plugin;
    private final Location lobbyLocation;
    private final Location waitingLocation;
    
    public PlayerSetupManager(JavaPlugin plugin, Location lobbyLocation, Location waitingLocation) {
        this.plugin = plugin;
        this.lobbyLocation = lobbyLocation;
        this.waitingLocation = waitingLocation;
    }
    
    /**
     * ロビーにいるプレイヤーのセットアップ
     */
    public void setupLobbyPlayer(Player p) {
        if (p == null || !p.isOnline()) return;
        
        resetPlayer(p);
        p.setGameMode(GameMode.ADVENTURE);
    }
    
    /**
     * 待機場にいるプレイヤーのセットアップ
     */
    public void setupWaitingPlayer(Player p) {
        if (p == null || !p.isOnline()) return;
        
        resetPlayer(p);
        p.setGameMode(GameMode.ADVENTURE);
        
        // インベントリを一度クリア
        p.getInventory().clear();
        
        // アイテムを設定
        p.getInventory().setItem(0, createLobbyItem(Material.RED_BED, "§cロビーに戻る", "ロビーにテレポートします"));
        p.getInventory().setItem(4, createLobbyItem(Material.EMERALD, "§aゲーム開始", "ゲームを開始します"));
        p.getInventory().setItem(8, createLobbyItem(Material.ENDER_EYE, "§e観戦モード", "観戦者になります"));

        // 追加: チーム選択アイテム（config の許可があれば）
        boolean allowTeam = plugin.getConfig().getBoolean("teams.allow_selection_in_waiting", true);
        if (allowTeam) {
            p.getInventory().setItem(2, createLobbyItem(Material.CHEST, "§bチーム選択", "クリックでチームメニューを開きます"));
        }
        
        // 複数回同期
        p.updateInventory();
        plugin.getServer().getScheduler().runTaskLater(plugin, p::updateInventory, 2L);
    }
    
    /**
     * 参加者としてセットアップ
     */
    public void setupParticipant(Player p) {
        if (p == null || !p.isOnline()) return;
        
        resetPlayer(p);
        p.setGameMode(GameMode.SURVIVAL);
        
        giveInitialGear(p);
        applyInitialEffects(p);
        
        p.sendMessage("§a§lゲームに参加しました！頑張ってください！");
    }
    
    /**
     * 観戦者としてセットアップ（IDLE中）
     */
    public void setupIdleSpectator(Player p) {
        if (p == null || !p.isOnline()) return;
        
        resetPlayer(p);
        p.setGameMode(GameMode.ADVENTURE);
        
        p.getInventory().setItem(0, createLobbyItem(Material.RED_BED, "§cロビーに戻る", "ロビーにテレポートします"));
        p.getInventory().setItem(4, createLobbyItem(Material.EMERALD, "§aゲーム開始", "ゲームを開始します"));
        p.getInventory().setItem(8, createLobbyItem(Material.ENDER_EYE, "§e観戦モード解除", "参加者に戻ります"));
        
        p.sendMessage("§7観戦モードになりました。ゲームが開始されるまでお待ちください。");
    }
    
    /**
     * 観戦者としてセットアップ（ゲーム中）
     */
    public void setupGameSpectator(Player p, World battleWorld) {
        if (p == null || !p.isOnline()) return;
        
        resetPlayer(p);
        p.setGameMode(GameMode.SPECTATOR);
        
        if (battleWorld != null) {
            p.teleport(battleWorld.getSpawnLocation());
        }
        
        p.sendMessage("§7あなたは観戦者になりました。");
    }
    
    /**
     * プレイヤーをリセット
     */
    private void resetPlayer(Player p) {
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(5.0f);
        p.getInventory().clear();
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setFireTicks(0);
        p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
        // 追加: tab 表示（player list name）をデフォルトに戻す
        try {
            p.setPlayerListName(null);
        } catch (Exception ignored) {}
    }
    
    /**
     * 初期装備を付与
     */
    private void giveInitialGear(Player p) {
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("items");
        if (items == null) return;
        
        for (String materialName : items.getKeys(false)) {
            try {
                Material material = Material.getMaterial(materialName.toUpperCase());
                int amount = items.getInt(materialName, 1);
                if (material != null) {
                    p.getInventory().addItem(new ItemStack(material, amount));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid item in config: " + materialName);
            }
        }
    }
    
    /**
     * 初期ポーション効果を付与
     */
    private void applyInitialEffects(Player p) {
        ConfigurationSection effects = plugin.getConfig().getConfigurationSection("effects");
        if (effects == null) return;
        
        for (String effectName : effects.getKeys(false)) {
            try {
                PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
                if (type == null) continue;
                
                String value = effects.getString(effectName, "60");
                int duration;
                
                if (value.equalsIgnoreCase("infinite")) {
                    duration = Integer.MAX_VALUE;
                } else {
                    duration = Integer.parseInt(value) * 20;
                }
                
                p.addPotionEffect(new PotionEffect(type, duration, 0, false));
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid effect in config: " + effectName);
            }
        }
    }
    
    /**
     * ロビーアイテムを作成
     */
    private ItemStack createLobbyItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Collections.singletonList(ChatColor.GRAY + lore));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
