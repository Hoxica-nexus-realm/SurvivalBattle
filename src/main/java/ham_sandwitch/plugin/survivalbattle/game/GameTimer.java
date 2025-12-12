package ham_sandwitch.plugin.survivalbattle.game;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class GameTimer {
    
    private final JavaPlugin plugin;
    private final GameManager gameManager;
    private final BossBar bossBar;
    
    private BukkitTask timerTask;
    private int timeLeft;
    private int countdownTimeLeft;

    // ボーダー縮小表示用
    private int borderShrinkTimeLeft = 0;
    private int borderShrinkDuration = 0;
    
    public GameTimer(JavaPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.bossBar = Bukkit.createBossBar("Survival Battle - IDLE", BarColor.BLUE, BarStyle.SEGMENTED_10);
        this.bossBar.setVisible(false);
    }
    
    public static class GameTime {
        public int minutes;
        public int seconds;
        
        public GameTime(int totalSeconds) {
            this.minutes = totalSeconds / 60;
            this.seconds = totalSeconds % 60;
        }
    }
    
    /**
     * タイマーを開始
     */
    public void start() {
        // 既に実行中のタスクをキャンセル
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        
        // BossBar をすべてのプレイヤーに表示
        bossBar.setVisible(true);
        updateBossBarPlayers();
        
        // タイマータスク開始
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            GameManager.Phase phase = gameManager.getCurrentPhase();
            
            if (phase == GameManager.Phase.COUNTDOWN) {
                handleCountdown();
            } else if (phase == GameManager.Phase.COLLECTION || phase == GameManager.Phase.PVP) {
                handleGameTime();
            }
            
            // 毎秒 BossBar 表示を更新
            updateBossBarPlayers();

            // 追加: 生存プレイヤーの tab HP 更新（GameManager 側で実装）
            try {
                gameManager.updateTabHpDisplay();
            } catch (Exception ignored) {}
        }, 0L, 20L);  // 即座に開始、20tick(1秒)ごと実行
    }
    
    /**
     * BossBar にプレイヤーを追加
     */
    private void updateBossBarPlayers() {
        bossBar.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) {
                bossBar.addPlayer(p);
            }
        }
    }
    
    /**
     * タイマーを停止
     */
    public void stop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }
    
    /**
     * カウントダウンフェーズの処理
     */
    private void handleCountdown() {
        countdownTimeLeft--;
        
        int maxCountdown = plugin.getConfig().getInt("settings.countdown", 30);
        double progress = Math.max(0.0, Math.min(1.0, (double) countdownTimeLeft / maxCountdown));
        bossBar.setProgress(progress);
        bossBar.setTitle("§6Survival Battle: §eゲーム開始まで " + countdownTimeLeft + "秒");
        
        if (countdownTimeLeft <= 0) {
            gameManager.startCollectionPhase();
            Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
            Bukkit.broadcastMessage("§a§l  🎮 GAME START! 🎮");
            Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
            playSound(org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL);
        } else if (countdownTimeLeft <= 5 && countdownTimeLeft > 0) {
            Bukkit.broadcastMessage("§e§l[ゲーム情報] §c" + countdownTimeLeft);
            playSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT);
        } else if (countdownTimeLeft % 10 == 0 && countdownTimeLeft > 0) {
            Bukkit.broadcastMessage("§e§l[ゲーム情報] §aゲーム開始まで残り " + countdownTimeLeft + " 秒");
        }
    }
    
    /**
     * COLLECTION/PVPフェーズの処理
     */
    private void handleGameTime() {
        // 大切: borderShrinkTimeLeft があればそれを優先表示しつつ timeLeft も減らす
        if (borderShrinkTimeLeft > 0 && gameManager.getCurrentPhase() == GameManager.Phase.PVP) {
            borderShrinkTimeLeft--;
            double progress = Math.max(0.0, Math.min(1.0, (double) borderShrinkTimeLeft / Math.max(1, borderShrinkDuration)));
            bossBar.setProgress(progress);
            
            int minutes = borderShrinkTimeLeft / 60;
            int seconds = borderShrinkTimeLeft % 60;
            bossBar.setTitle("§cワールドボーダー縮小まで §e" + minutes + ":" + String.format("%02d", seconds));
            bossBar.setColor(BarColor.RED);

            // なお、通常の PvP 残り時間も減らす（プレイヤーに合計時間を示すため）
            timeLeft--;
            
            if (borderShrinkTimeLeft <= 0) {
                // 縮小終了時は特別効果音
                playSound(org.bukkit.Sound.ENTITY_WITHER_SPAWN);
                // 続行して通常の PvP ボスバー表示に戻る（次 tick）
            }
            // 他の通知は下段の通常処理に委ねる（return して二重処理を防ぐ）
            return;
        }

        // 既存の timeLeft 処理（COLLECTION/PVP 両方で動作）
        timeLeft--;
        
        GameManager.Phase phase = gameManager.getCurrentPhase();
        int initialTime = phase == GameManager.Phase.COLLECTION 
            ? plugin.getConfig().getInt("game.collection_time_seconds", 600)
            : plugin.getConfig().getInt("game.pvp_time_seconds", 900);
        
        double progress = Math.max(0.0, Math.min(1.0, (double) timeLeft / initialTime));
        bossBar.setProgress(progress);
        
        GameTime gameTime = new GameTime(timeLeft);
        String phaseColor = phase == GameManager.Phase.COLLECTION ? "§a" : "§c";
        String phaseName = phase == GameManager.Phase.COLLECTION ? "資源収集" : "PVP";
        bossBar.setTitle(phaseColor + phaseName + " §f- §e" + gameTime.minutes + ":" + String.format("%02d", gameTime.seconds));
        bossBar.setColor(phase == GameManager.Phase.COLLECTION ? BarColor.GREEN : BarColor.RED);
        
        if (timeLeft <= 0) {
            if (phase == GameManager.Phase.COLLECTION) {
                gameManager.startPvpPhase();
                Bukkit.broadcastMessage("§c§l━━━━━━━━━━━━━━━━━━━━");
                Bukkit.broadcastMessage("§c§l  ⚔️ PVP開始! ⚔️");
                Bukkit.broadcastMessage("§c§l━━━━━━━━━━━━━━━━━━━━");
                playSound(org.bukkit.Sound.ENTITY_WITHER_SPAWN);
            } else {
                Bukkit.broadcastMessage("§c§l[ゲーム情報] §4時間切れです！ゲームを終了します。");
                gameManager.finalizeGameAndTeleportAll(null);
            }
            return;
        }
        
        if (phase == GameManager.Phase.COLLECTION && timeLeft == 60) {
            Bukkit.broadcastMessage("§e§l[警告] §6資源収集期間終了まで残り1分です！");
            playSound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING);
        }
        
        if (timeLeft % 300 == 0 && timeLeft > 0) {
            int remainingMinutes = timeLeft / 60;
            Bukkit.broadcastMessage("§b§l[ゲーム情報] §e残り時間: " + remainingMinutes + "分");
        }
        
        if (phase == GameManager.Phase.PVP && timeLeft == 60) {
            Bukkit.broadcastMessage("§c§l[警告] §4残り時間1分です！");
        }
    }
    
    /**
     * 効果音を全プレイヤーに再生
     */
    private void playSound(org.bukkit.Sound sound) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        }
    }
    
    public void setCountdownTime(int seconds) {
        this.countdownTimeLeft = seconds;
    }
    
    public void setGameTime(int seconds) {
        this.timeLeft = seconds;
    }

    public void setBorderShrinkDuration(int seconds) {
        this.borderShrinkDuration = Math.max(0, seconds);
        this.borderShrinkTimeLeft = this.borderShrinkDuration;
    }

    // reset shrink info when stopping or starting new phases
    public void resetBorderShrinkInfo() {
        this.borderShrinkDuration = 0;
        this.borderShrinkTimeLeft = 0;
    }
    
    public BossBar getBossBar() {
        return bossBar;
    }
    
    public void updateBossBar(String title, BarColor color, boolean visible) {
        bossBar.setTitle(title);
        bossBar.setColor(color);
        bossBar.setVisible(visible);
    }
    
    public GameTime getRemainingTime() {
        GameManager.Phase phase = gameManager.getCurrentPhase();
        if (phase == GameManager.Phase.COUNTDOWN) {
            return new GameTime(countdownTimeLeft);
        } else if (phase == GameManager.Phase.COLLECTION || phase == GameManager.Phase.PVP) {
            return new GameTime(timeLeft);
        }
        return new GameTime(0);
    }
}
