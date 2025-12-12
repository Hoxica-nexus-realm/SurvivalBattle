package ham_sandwitch.plugin.survivalbattle.game;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class GameManager implements Listener {

    private final JavaPlugin plugin;
    private final Location lobbyLocation;
    private final Location waitingLocation;
    private final WorldManager worldManager;
    private final PlayerSetupManager playerSetupManager;
    private final GameTimer gameTimer;

    private Phase currentPhase = Phase.IDLE;
    private final Set<UUID> alivePlayers = new HashSet<>();
    private final Set<UUID> idleSpectators = new HashSet<>();
    private final Set<UUID> gameSpectators = new HashSet<>();
    private final Map<UUID, PlayerStats> playerStats = new HashMap<>();

    // 再接続時アクション管理
    private final Set<UUID> sendToLobbyOnReconnect = new HashSet<>();
    private final Set<UUID> awaitingReconnectDuringCollection = new HashSet<>();

    private int minPlayersToStart = 2;
    private boolean debugMode = false;
    private boolean logDebugMode = false;
    private int fakePlayers = 0;

    // 投票管理: / GUI の「ゲーム開始」投票
    private final Set<UUID> startVotes = new HashSet<>();

    // --- チーム管理追加 ---
    public enum TeamColor {
        RED("赤", ChatColor.RED, Material.RED_WOOL),
        BLUE("青", ChatColor.BLUE, Material.BLUE_WOOL),
        YELLOW("黄", ChatColor.YELLOW, Material.YELLOW_WOOL),
        BLACK("黒", ChatColor.DARK_GRAY, Material.BLACK_WOOL),
        WHITE("白", ChatColor.WHITE, Material.WHITE_WOOL);

        public final String displayJa;
        public final ChatColor chatColor;
        public final Material material;
        TeamColor(String displayJa, ChatColor chatColor, Material material) {
            this.displayJa = displayJa;
            this.chatColor = chatColor;
            this.material = material;
        }
    }

    private final EnumMap<TeamColor, Set<UUID>> teamMembers = new EnumMap<>(TeamColor.class);
    private final EnumMap<TeamColor, Boolean> teamEnabled = new EnumMap<>(TeamColor.class);
    private boolean teamsEnabled;

    public enum Phase {
        IDLE, COUNTDOWN, COLLECTION, PVP, ENDED
    }

    public GameManager(JavaPlugin plugin, Location lobbyLocation, Location waitingLocation) {
        this.plugin = plugin;
        this.lobbyLocation = lobbyLocation;
        this.waitingLocation = waitingLocation;
        this.worldManager = new WorldManager(plugin);
        this.playerSetupManager = new PlayerSetupManager(plugin, lobbyLocation, waitingLocation);
        this.gameTimer = new GameTimer(plugin, this);

        // チーム初期化
        for (TeamColor tc : TeamColor.values()) {
            teamMembers.put(tc, new HashSet<>());
            boolean enabled = plugin.getConfig().getBoolean("teams." + tc.name().toLowerCase(), true);
            teamEnabled.put(tc, enabled);
        }
        teamsEnabled = plugin.getConfig().getBoolean("teams.enabled", true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        World battleWorld = worldManager.getBattleWorld();

        // 再接続フラグ: PvP中に退出していた -> ロビーへ
        if (sendToLobbyOnReconnect.remove(uuid)) {
            p.teleport(lobbyLocation);
            playerSetupManager.setupLobbyPlayer(p);
            p.sendMessage("§e離脱時がPvPだったためロビーに戻されました。");
            return;
        }

        // 収集フェーズ中に退出していたプレイヤーの復帰処理
        if (awaitingReconnectDuringCollection.remove(uuid)) {
            if (currentPhase == Phase.COLLECTION && battleWorld != null && worldManager.hasBattleWorld()) {
                Location spawn = worldManager.getRandomSafeLocation();
                if (spawn == null) spawn = worldManager.getBattleSpawnLocation();
                if (spawn != null) {
                    p.teleport(spawn);
                    addAlivePlayer(uuid); // 参加者に復帰
                    playerSetupManager.setupParticipant(p);
                    p.sendMessage("§a収集フェーズに復帰しました。がんばってください！");
                    return;
                }
            }
            // 収集フェーズが終わっていた場合はロビーへ
            p.teleport(lobbyLocation);
            playerSetupManager.setupLobbyPlayer(p);
            p.sendMessage("§e収集フェーズは終了していたためロビーに戻されました。");
            return;
        }

        // 通常の振る舞い（IDLE時ロビーへ）
        if (currentPhase == Phase.IDLE) {
            playerSetupManager.setupLobbyPlayer(p);
            p.teleport(lobbyLocation);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Player p = event.getPlayer();
        World battleWorld = worldManager.getBattleWorld();
        boolean inBattleWorld = (battleWorld != null && p.getWorld().equals(battleWorld));
        boolean wasParticipant = alivePlayers.contains(uuid);

        // プラグイン管理対象外ワールドかつ未参加なら通常のクリーンアップだけ
        if (!inBattleWorld && !wasParticipant) {
            idleSpectators.remove(uuid);
            gameSpectators.remove(uuid);
            return;
        }

        // PvPフェーズ中に退出 -> 再接続時ロビーへ戻す（負け扱い）
        if (currentPhase == Phase.PVP) {
            alivePlayers.remove(uuid);
            sendToLobbyOnReconnect.add(uuid);
            PlayerStats stats = playerStats.computeIfAbsent(uuid, k -> new PlayerStats());
            stats.incrementLosses();
            // プレイヤーが切断時は即座に観戦化しておく
            addGameSpectator(uuid);
            return;
        }

        // 収集フェーズ中に退出 -> 収集が継続していれば復帰待ち、既に集Phaseが終わっていればロビー扱い
        if (currentPhase == Phase.COLLECTION) {
            // 実行中の収集フェーズなら復帰待ち（alivePlayers に残すことで勝利判定の対象になる）
            if (worldManager.hasBattleWorld()) {
                // alivePlayers に残して復帰待ちフラグを立てる
                if (alivePlayers.contains(uuid)) {
                    awaitingReconnectDuringCollection.add(uuid);
                } else {
                    // 観戦者が収集中に離脱した場合は普通に削除
                    idleSpectators.remove(uuid);
                    gameSpectators.remove(uuid);
                }
                return;
            } else {
                // 既にバトルワールドが存在しないならロビーへ
                alivePlayers.remove(uuid);
                idleSpectators.remove(uuid);
                gameSpectators.remove(uuid);
                sendToLobbyOnReconnect.add(uuid);
                return;
            }
        }

        // その他（IDLE/ENDEDなど）は従来通り削除
        alivePlayers.remove(uuid);
        idleSpectators.remove(uuid);
        gameSpectators.remove(uuid);
    }

    // 追加: PvP開始時刻（秒）, キルログ, 生存時間, 被キル情報
    private long pvpStartEpochSec = 0L;
    private final List<KillEntry> killLog = new ArrayList<>();
    private final Map<UUID, Long> survivalSeconds = new HashMap<>();
    private final Map<UUID, UUID> killedBy = new HashMap<>();

    // キルログエントリ
    private static class KillEntry {
        public final UUID victim;
        public final UUID killer; //  null ならbanana
        public final long epochSec;
        public final long survivalSec; // 0 ならbanana

        public KillEntry(UUID victim, UUID killer, long epochSec, long survivalSec) {
            this.victim = victim;
            this.killer = killer;
            this.epochSec = epochSec;
            this.survivalSec = survivalSec;
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // バトルワールド以外での死亡は無視
        Player victim = event.getEntity();
        World battleWorld = worldManager.getBattleWorld();
        if (battleWorld == null || !victim.getWorld().equals(battleWorld)) return;

        // デフォルトの死亡メッセージを抑止
        event.setDeathMessage(null);

        UUID victimUuid = victim.getUniqueId();

        if (currentPhase != Phase.COLLECTION && currentPhase != Phase.PVP) {
            return;
        }

        if (!alivePlayers.contains(victimUuid)) {
            return;
        }

        Player killer = victim.getKiller();

        // 統計更新
        PlayerStats victimStats = playerStats.computeIfAbsent(victimUuid, k -> new PlayerStats());
        victimStats.incrementDeaths();

        if (killer != null) {
            UUID killerUuid = killer.getUniqueId();
            PlayerStats killerStats = playerStats.computeIfAbsent(killerUuid, k -> new PlayerStats());
            killerStats.incrementKills();
            killedBy.put(victimUuid, killerUuid);
        } else {
            killedBy.put(victimUuid, null);
        }

        // 生存時間を記録（pvp開始時刻が有効な場合）
        long nowSec = System.currentTimeMillis() / 1000L;
        long surv = (pvpStartEpochSec > 0) ? Math.max(0L, nowSec - pvpStartEpochSec) : 0L;
        survivalSeconds.put(victimUuid, surv);

        // キルログに追加（出力は試合終了時まで保留）
        killLog.add(new KillEntry(victimUuid, (killer != null ? killer.getUniqueId() : null), nowSec, surv));

        // プレイヤーを生存リストから除外して観戦状態へ
        removeAlivePlayer(victimUuid);

        // 試合終了判定
        checkGameEnd();
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // 両者がプレイヤーで、かつバトルワールド内でのみPvP制御を行う
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            Player damager = (Player) event.getDamager();
            Player target = (Player) event.getEntity();
            World battleWorld = worldManager.getBattleWorld();
            
            // ★ バトルワールド外では常に無効化（ロビー・待機場での誤爆防止）
            if (battleWorld == null || !damager.getWorld().equals(battleWorld) || !target.getWorld().equals(battleWorld)) {
                event.setCancelled(true);  // ← ここを重要視
                return;
            }
            
            // バトルワールド内で、PVPフェーズ以外は無効化
            if (currentPhase != Phase.PVP) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        World pw = p.getWorld();
        World battleWorld = worldManager.getBattleWorld();

        // ロビー/待機/バトルワールド以外では処理しない
        if (!pw.equals(lobbyLocation.getWorld()) && !pw.equals(waitingLocation.getWorld())
                && (battleWorld == null || !pw.equals(battleWorld))) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String displayName = clicked.getItemMeta().getDisplayName();

        // チーム選択アイテムが押された（待機インベントリ内）
        if (displayName != null && displayName.contains("チーム選択")) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player) {
                Player p1 = (Player) event.getWhoClicked();
                openTeamMenu(p1);
            }
            return;
        }

        // チームメニュー内での操作
        String title = event.getView().getTitle();
        if (title != null && title.equals("§bチーム選択")) {
            event.setCancelled(true);
            Player p2 = (Player) event.getWhoClicked();
            if (displayName == null) return;

            // OP + shift-click で有効/無効トグル
            for (TeamColor tc : TeamColor.values()) {
                String teamName = tc.chatColor + tc.displayJa + "チーム";
                if (displayName.contains(teamName)) {
                    // OP + shift -> トグル有効/無効
                    if (p2.isOp() && event.isShiftClick()) {
                        boolean now = teamEnabled.getOrDefault(tc, true);
                        teamEnabled.put(tc, !now);
                        Bukkit.broadcastMessage("§6[Teams] " + tc.chatColor + tc.displayJa + " チームを " + (now ? "無効化" : "有効化") + " しました。");
                        // 更新されたメニューを再表示
                        openTeamMenu(p2);
                        return;
                    }

                    // 通常クリック -> チーム参加（有効なチームのみ）
                    if (!teamsEnabled) {
                        p2.sendMessage("§cチーム機能は無効化されています。");
                        return;
                    }
                    if (!teamEnabled.getOrDefault(tc, true)) {
                        p2.sendMessage("§cそのチームは現在参加できません。");
                        return;
                    }
                    // 他チームから除外し、選択チームに追加
                    removeFromAllTeams(p2.getUniqueId());
                    teamMembers.get(tc).add(p2.getUniqueId());
                    p2.closeInventory();
                    p2.sendMessage(tc.chatColor + tc.displayJa + " チームに参加しました。");
                    Bukkit.broadcastMessage("§7[Teams] " + p2.getName() + " が " + tc.chatColor + tc.displayJa + " チームに参加しました。");
                    return;
                }
            }
            return;
        }

        // ロビー・待機場のアイテムクリック処理
        if (displayName.contains("ロビーに戻る")) {
            event.setCancelled(true);
            p.closeInventory();
            teleportToLobby(p);
        } else if (displayName.contains("ゲーム開始")) {
            // 投票方式に変更（OP も一票として扱う）
            event.setCancelled(true);
            p.closeInventory();

            UUID uuid = p.getUniqueId();

            // 投票可能なプレイヤー集合は waiting (alivePlayers) とする
            int eligible = Math.max(1, alivePlayers.size()); // 0 対策
            int required = (eligible / 2) + 1; // 過半数

            // トグル投票
            if (startVotes.contains(uuid)) {
                startVotes.remove(uuid);
                p.sendMessage(ChatColor.YELLOW + "投票を取り消しました。現在の投票数: " + startVotes.size() + "/" + required);
                Bukkit.broadcastMessage("§7[投票] " + p.getName() + " が投票を取り消しました. (" + startVotes.size() + "/" + required + ")");
            } else {
                startVotes.add(uuid);
                Bukkit.broadcastMessage("§a[投票] " + p.getName() + " がゲーム開始に投票しました. (" + startVotes.size() + "/" + required + ")");
            }

            // 投票到達でゲーム開始
            if (startVotes.size() >= required) {
                // リセット前に通知
                Bukkit.broadcastMessage("§6投票が過半数に達しました。ゲームを開始します。");
                // 管理者コマンドと同様に開始（ConsoleSender を CommandSender として渡す）
                startGame(Bukkit.getConsoleSender());
                resetStartVotes();
            }
        } else if (displayName.contains("観戦モード")) {
            event.setCancelled(true);
            if (currentPhase == Phase.IDLE) {
                addIdleSpectator(p.getUniqueId());
                playerSetupManager.setupIdleSpectator(p);
            } else {
                addGameSpectator(p.getUniqueId());
                playerSetupManager.setupGameSpectator(p, worldManager.getBattleWorld());
            }
        } else if (displayName.contains("観戦モード解除")) {
            event.setCancelled(true);
            addAlivePlayer(p.getUniqueId());
            playerSetupManager.setupWaitingPlayer(p);
            p.sendMessage(ChatColor.GREEN + "参加者に戻りました。");
        }
    }

    // チームメニューを生成して表示
    public void openTeamMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 9, "§bチーム選択");
        int slot = 0;
        for (TeamColor tc : TeamColor.values()) {
            ItemStack item = new ItemStack(tc.material);
            var meta = item.getItemMeta();
            if (meta != null) {
                String name = tc.chatColor + tc.displayJa + "チーム";
                meta.setDisplayName(name);
                List<String> lore = new ArrayList<>();
                lore.add("§7人数: §e" + teamMembers.get(tc).size());
                lore.add("§7状態: " + (teamEnabled.getOrDefault(tc, true) ? "§a有効" : "§c無効"));
                lore.add("");
                lore.add("§eクリック: 参加");
                lore.add("§6OP + Shiftクリック: 有効/無効 切替");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }
        p.openInventory(inv);
    }

    private void removeFromAllTeams(UUID uuid) {
        for (TeamColor tc : TeamColor.values()) {
            teamMembers.get(tc).remove(uuid);
        }
    }

    public void startCountdown(int seconds) {
        resetStartVotes();
        setPhase(Phase.COUNTDOWN);
        gameTimer.setCountdownTime(seconds);
        gameTimer.start();
        gameTimer.updateBossBar("§6ゲーム開始まで " + seconds + "秒", BarColor.YELLOW, true);
    }

    public void startCollectionPhase() {
        setPhase(Phase.COLLECTION);
        int collectionTime = plugin.getConfig().getInt("game.collection_time_seconds", 300);
        gameTimer.setGameTime(collectionTime);

        // 収集用ボーダーサイズ（config があれば反映）
        int collectionBorder = plugin.getConfig().getInt("game.collection_border_size", 10000);
        worldManager.setCollectionBorder(collectionBorder);

        gameTimer.start();
        gameTimer.updateBossBar("§a資源収集中 §f- §e" + (collectionTime / 60) + ":00", BarColor.GREEN, true);

        World battleWorld = worldManager.getBattleWorld();
        // 参加者をバトルワールドの安全位置へテレポート（必要なら）
        for (UUID uuid : new HashSet<>(alivePlayers)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                // 既にバトルワールドにいればセットアップだけ、違えば安全位置へテレポート
                if (battleWorld != null && !p.getWorld().equals(battleWorld)) {
                    Location safe = worldManager.getRandomSafeLocation();
                    if (safe == null) safe = worldManager.getBattleSpawnLocation();
                    if (safe != null) {
                        p.teleport(safe);
                    }
                }
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                playerSetupManager.setupParticipant(p);
                p.sendMessage("§a§lゲームが開始されました！資源を集めましょう！");
            } else {
                // オフラインだが alivePlayers にいる場合は復帰待ち扱い (既存のフラグ処理に委ねる)
            }
        }

        Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
        Bukkit.broadcastMessage("§a§l  📦 資源収集フェーズ開始!");
        Bukkit.broadcastMessage("§a§l  残り時間: " + (collectionTime / 60) + "分");
        Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
    }

    public void startPvpPhase() {
        setPhase(Phase.PVP);
        int pvpTime = plugin.getConfig().getInt("game.pvp_time_seconds", 900);
        gameTimer.setGameTime(pvpTime);

        // config から初期ボーダー / 最終サイズ / 縮小時間を取得
        double initialBorder = plugin.getConfig().getDouble("game.pvp_initial_border", 150.0);
        double finalBorder = plugin.getConfig().getDouble("game.border_final_size", 5.0);
        int shrinkDuration = plugin.getConfig().getInt("game.border_shrink_duration_seconds", 300);

        // 初期ボーダーを設定してから縮小を開始
        worldManager.setPvpBorder(initialBorder);
        worldManager.startBorderShrink(finalBorder, shrinkDuration);

        // GameTimer に縮小カウントダウンを伝える
        gameTimer.setBorderShrinkDuration(shrinkDuration);

        // PvP開始時刻を記録し既存ログをクリア
        this.pvpStartEpochSec = System.currentTimeMillis() / 1000L;
        this.killLog.clear();
        this.survivalSeconds.clear();
        this.killedBy.clear();

        gameTimer.start();
        gameTimer.updateBossBar("§cPVP中 §f- §e" + (pvpTime / 60) + ":00", BarColor.RED, true);

        Bukkit.broadcastMessage("§c§l━━━━━━━━━━━━━━━━━━━━");
        Bukkit.broadcastMessage("§c§l  ⚔️ PVP開始! ⚔️");
        Bukkit.broadcastMessage("§c§l  ワールドボーダーが縮小を開始します！");
        Bukkit.broadcastMessage("§c§l━━━━━━━━━━━━━━━━━━━━");

        // テレポート: チーム戦時は同チームを同位置へ、それ以外は通常ランダム
        World battleWorld = worldManager.getBattleWorld();
        if (battleWorld == null) return;

        if (teamsEnabled) {
            // チームごとに一つのスポーン位置を決める（チームに生存者がいる場合のみ）
            Map<TeamColor, Location> teamSpawns = new EnumMap<>(TeamColor.class);
            for (TeamColor tc : TeamColor.values()) {
                // チーム有効かつメンバーかつ生存者がいるかを確認
                if (!teamEnabled.getOrDefault(tc, true)) continue;
                long aliveCountInTeam = teamMembers.get(tc).stream().filter(alivePlayers::contains).count();
                if (aliveCountInTeam <= 0) continue;
                Location spawn = worldManager.getRandomSafeLocation();
                if (spawn == null) spawn = worldManager.getBattleSpawnLocation();
                if (spawn != null) {
                    teamSpawns.put(tc, spawn);
                }
            }

            // 生存プレイヤーをそのチームの spawn にまとめてテレポート
            for (UUID uuid : new HashSet<>(alivePlayers)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                // 所属チームを探す
                TeamColor memberTeam = null;
                for (TeamColor tc : TeamColor.values()) {
                    if (teamMembers.get(tc).contains(uuid)) {
                        memberTeam = tc;
                        break;
                    }
                }
                if (memberTeam != null && teamSpawns.containsKey(memberTeam)) {
                    p.teleport(teamSpawns.get(memberTeam));
                } else {
                    // チームに属していない/スポーンが決まっていない場合はランダムに
                    Location spawnLoc = worldManager.getRandomSafeLocation();
                    if (spawnLoc == null) spawnLoc = worldManager.getBattleSpawnLocation();
                    if (spawnLoc != null) p.teleport(spawnLoc);
                }
                playerSetupManager.setupParticipant(p);
            }
        } else {
            // 通常のランダムテレポート
            for (UUID uuid : new HashSet<>(alivePlayers)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                Location spawnLoc = worldManager.getRandomSafeLocation();
                if (spawnLoc == null) spawnLoc = worldManager.getBattleSpawnLocation();
                if (spawnLoc != null) p.teleport(spawnLoc);
                playerSetupManager.setupParticipant(p);
            }
        }
    }

    public void finalizeGameAndTeleportAll(UUID winner) {
        setPhase(Phase.ENDED);
        gameTimer.stop();

        // 追加: 試合結果を表示（teleport/cleanup 前）
        try {
            broadcastMatchResults();
        } catch (Exception e) {
            plugin.getLogger().warning("Error broadcasting match results: " + e.getMessage());
        }

        // reset tab names before teleport
        resetAllPlayerListNames();

        for (UUID uuid : alivePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.teleport(lobbyLocation);
                playerSetupManager.setupLobbyPlayer(p);
            }
        }

        for (UUID uuid : idleSpectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.teleport(lobbyLocation);
                playerSetupManager.setupLobbyPlayer(p);
            }
        }

        for (UUID uuid : gameSpectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.teleport(lobbyLocation);
                playerSetupManager.setupLobbyPlayer(p);
            }
        }

        alivePlayers.clear();
        idleSpectators.clear();
        gameSpectators.clear();
        worldManager.deleteBattleWorld();

        if (winner != null) {
            Player winnerPlayer = Bukkit.getPlayer(winner);
            if (winnerPlayer != null) {
                Bukkit.broadcastMessage("§a§l" + winnerPlayer.getName() + "が勝利しました！");
            }
        }
    }

    // 変更: 試合結果を集計して表示する（チーム戦対応）
    private void broadcastMatchResults() {
        long nowSec = System.currentTimeMillis() / 1000L;

        // 生存者の生存時間を補完
        for (UUID uuid : new HashSet<>(alivePlayers)) {
            if (!survivalSeconds.containsKey(uuid)) {
                long surv = (pvpStartEpochSec > 0) ? Math.max(0L, nowSec - pvpStartEpochSec) : 0L;
                survivalSeconds.put(uuid, surv);
            }
        }

        // 追加: このゲームのキル数を集計（killLog から）
        Map<UUID, Integer> gameKills = new HashMap<>();
        for (KillEntry entry : killLog) {
            if (entry.killer != null) {
                gameKills.put(entry.killer, gameKills.getOrDefault(entry.killer, 0) + 1);
            }
        }

        // ヘッダ（勝者情報）
        if (teamsEnabled) {
            // チーム戦判定
            int survivingTeams = 0;
            TeamColor last = null;
            for (TeamColor tc : TeamColor.values()) {
                if (!teamEnabled.getOrDefault(tc, true)) continue;
                long cnt = teamMembers.get(tc).stream().filter(alivePlayers::contains).count();
                if (cnt > 0) { survivingTeams++; last = tc; }
            }
            if (survivingTeams == 1 && last != null) {
                Bukkit.broadcastMessage("§6🏆 Winner Team: " + last.chatColor + last.displayJa + " チーム");
            } else {
                Bukkit.broadcastMessage("§6🏆 Winner: なし（引き分け）");
            }
            
            // チーム戦結果
            Bukkit.broadcastMessage("§6Result");
            for (TeamColor tc : TeamColor.values()) {
                Set<UUID> teamPlayers = teamMembers.get(tc);
                if (teamPlayers.isEmpty()) continue;
                
                Bukkit.broadcastMessage(tc.chatColor + "━━━ " + tc.displayJa + " チーム ━━━");
                
                for (UUID uuid : teamPlayers) {
                    String name = Bukkit.getOfflinePlayer(uuid).getName();
                    if (name == null) name = uuid.toString();

                    int kills = gameKills.getOrDefault(uuid, 0);
                    long survSec = survivalSeconds.getOrDefault(uuid, 0L);
                    String survStr = formatSecondsHMS(survSec);

                    UUID killer = killedBy.get(uuid);
                    String killedByStr = (killer == null) ? "—" : (Bukkit.getOfflinePlayer(killer).getName() != null ? Bukkit.getOfflinePlayer(killer).getName() : killer.toString());

                    boolean alive = alivePlayers.contains(uuid);

                    Bukkit.broadcastMessage(name);
                    Bukkit.broadcastMessage("  - キル数: " + kills);
                    Bukkit.broadcastMessage("  - 最終: " + (alive ? "生存" : "死亡"));
                    Bukkit.broadcastMessage("  - 生存時間: " + survStr);
                    if (!alive) {
                        Bukkit.broadcastMessage("  - Eliminated by: " + killedByStr);
                    }
                }
            }
        } else {
            // 個人戦結果
            Bukkit.broadcastMessage("§6🏆 Winner: " + (alivePlayers.size() == 1 ? Bukkit.getOfflinePlayer(alivePlayers.iterator().next()).getName() : "なし"));
            Bukkit.broadcastMessage("§6Result");

            // プレイヤー一覧を決定（参加者 / 統計にある全員）
            Set<UUID> allPlayers = new LinkedHashSet<>();
            allPlayers.addAll(playerStats.keySet());
            allPlayers.addAll(alivePlayers);
            allPlayers.addAll(idleSpectators);
            allPlayers.addAll(gameSpectators);

            // 出力: 各プレイヤーについて整形表示
            for (UUID uuid : allPlayers) {
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                if (name == null) name = uuid.toString();

                int kills = gameKills.getOrDefault(uuid, 0);
                long survSec = survivalSeconds.getOrDefault(uuid, 0L);
                String survStr = formatSecondsHMS(survSec);

                UUID killer = killedBy.get(uuid);
                String killedByStr = (killer == null) ? "—" : (Bukkit.getOfflinePlayer(killer).getName() != null ? Bukkit.getOfflinePlayer(killer).getName() : killer.toString());

                boolean alive = alivePlayers.contains(uuid);

                Bukkit.broadcastMessage(name);
                Bukkit.broadcastMessage("  - キル数: " + kills);
                Bukkit.broadcastMessage("  - 最終: " + (alive ? "生存" : "死亡"));
                Bukkit.broadcastMessage("  - 生存時間: " + survStr);
                if (!alive) {
                    Bukkit.broadcastMessage("  - Eliminated by: " + killedByStr);
                }
            }
        }

        Bukkit.broadcastMessage("§6============================");
    }

    private String formatSecondsHMS(long totalSec) {
        long m = totalSec / 60;
        long s = totalSec % 60;
        if (m > 0) {
            return m + "m" + s + "s";
        }
        return s + "s";
    }

    public void addAlivePlayer(UUID uuid) {
        alivePlayers.add(uuid);
        idleSpectators.remove(uuid);
        gameSpectators.remove(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            // 初回追加時に tab 表示をセット（即時反映）
            applyTabHpForPlayer(p);
        }
    }

    public void addIdleSpectator(UUID uuid) {
        idleSpectators.add(uuid);
        alivePlayers.remove(uuid);
        gameSpectators.remove(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            // 観戦/ロビーでは tab をリセット
            try { p.setPlayerListName(null); } catch (Exception ignored) {}
        }
    }

    public void addGameSpectator(UUID uuid) {
        gameSpectators.add(uuid);
        alivePlayers.remove(uuid);
        idleSpectators.remove(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            try { p.setPlayerListName(null); } catch (Exception ignored) {}
        }
    }

    public void removePlayer(UUID uuid) {
        alivePlayers.remove(uuid);
        idleSpectators.remove(uuid);
        gameSpectators.remove(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            try { p.setPlayerListName(null); } catch (Exception ignored) {}
        }
    }

    private void setPhase(Phase phase) {
        this.currentPhase = phase;
    }

    public Phase getCurrentPhase() {
        return currentPhase;
    }

    public World getBattleWorld() {
        return worldManager.getBattleWorld();
    }

    public void cleanup() {
        gameTimer.stop();
        worldManager.deleteBattleWorld();
    }

    public Set<UUID> getAlivePlayers() {
        return new HashSet<>(alivePlayers);
    }

    public GameTimer getGameTimer() {
        return gameTimer;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }

    public void startGame(CommandSender sender) {
        // reset votes to avoid stale votes
        resetStartVotes();

        if (currentPhase != Phase.IDLE) {
            sender.sendMessage(ChatColor.RED + "ゲームは既に開始されています。");
            return;
        }

        if (alivePlayers.size() + fakePlayers < minPlayersToStart) {
            sender.sendMessage(ChatColor.RED + "最小プレイヤー数(" + minPlayersToStart + ")に達していません。");
            sender.sendMessage(ChatColor.GRAY + "現在: " + (alivePlayers.size() + fakePlayers) + "人");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "ゲームを開始します！");
        if (logDebugMode) {
            plugin.getLogger().info("Game started with " + alivePlayers.size() + " players + " + fakePlayers + " fake players");
        }

        worldManager.createBattleWorld();

        for (UUID uuid : alivePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                Location spawnLoc = worldManager.getRandomSafeLocation();
                if (spawnLoc == null) {
                    spawnLoc = worldManager.getBattleSpawnLocation();
                }
                p.teleport(spawnLoc);
                playerSetupManager.setupParticipant(p);
            }
        }

        int countdownTime = plugin.getConfig().getInt("game.countdown_time_seconds", 30);
        startCountdown(countdownTime);
    }

    public void stopGame(CommandSender sender) {
        if (currentPhase == Phase.IDLE) {
            sender.sendMessage(ChatColor.YELLOW + "ゲームは実行されていません。");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "ゲームを強制終了します...");
        finalizeGameAndTeleportAll(null);
        Bukkit.broadcastMessage(ChatColor.RED + "ゲームが管理者により強制終了されました。");
    }

    // getGameStats にチーム情報を追加表示
    public String getGameStats() {
        StringBuilder stats = new StringBuilder();
        stats.append(ChatColor.BLUE).append("=== ゲーム状態 ===\n");
        stats.append(ChatColor.GREEN).append("フェーズ: ").append(currentPhase).append("\n");
        stats.append(ChatColor.GREEN).append("参加者: ").append(alivePlayers.size()).append("人").append("\n");
        stats.append(ChatColor.GREEN).append("待機観戦: ").append(idleSpectators.size()).append("人").append("\n");
        stats.append(ChatColor.GREEN).append("ゲーム観戦: ").append(gameSpectators.size()).append("人").append("\n");

        if (teamsEnabled) {
            stats.append(ChatColor.GOLD).append("=== チーム状況 ===\n");
            for (TeamColor tc : TeamColor.values()) {
                stats.append(tc.chatColor).append(tc.displayJa).append("チーム: ")
                    .append(teamMembers.get(tc).size()).append("人")
                    .append(teamEnabled.getOrDefault(tc, true) ? " (有効)\n" : " (無効)\n");
            }
        }

        if (currentPhase != Phase.IDLE) {
            GameTimer.GameTime gameTime = gameTimer.getRemainingTime();
            stats.append(ChatColor.YELLOW).append("残り時間: ")
                .append(gameTime.minutes).append("分").append(gameTime.seconds).append("秒\n");
        }
        return stats.toString();
    }

    public void teleportToWaiting(Player player) {
        if (currentPhase != Phase.IDLE) {
            player.sendMessage(ChatColor.RED + "ゲーム進行中は待機場へ移動できません。");
            return;
        }

        player.teleport(waitingLocation);
        addAlivePlayer(player.getUniqueId());
        playerSetupManager.setupWaitingPlayer(player);
        player.sendMessage(ChatColor.GREEN + "待機場に移動しました。");
    }

    public void teleportToLobby(Player player) {
        player.teleport(lobbyLocation);
        removePlayer(player.getUniqueId());
        playerSetupManager.setupLobbyPlayer(player);
        player.sendMessage(ChatColor.GREEN + "ロビーに戻りました。");
    }

    public Phase getPhase() {
        return currentPhase;
    }

    public void setDebug(boolean debug) {
        this.debugMode = debug;
    }

    public void setLogDebugMode(boolean log) {
        this.logDebugMode = log;
    }

    public void setFakePlayers(int count) {
        this.fakePlayers = count;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * 参加プレイヤーを削除して観戦者に変更
     */
    private void removeAlivePlayer(UUID uuid) {
        alivePlayers.remove(uuid);
        addGameSpectator(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            playerSetupManager.setupGameSpectator(p, worldManager.getBattleWorld());
        }
    }

    /**
     * 毎秒呼ばれる: 生存プレイヤーの tab に現在HPを表示する
     * GameTimer から呼び出されます。
     */
    public void updateTabHpDisplay() {
        if (!plugin.getConfig().getBoolean("display.tab_hp_enabled", true)) return;

        String format = plugin.getConfig().getString("display.tab_hp_format", "{name} §7[{hp}❤]");

        for (UUID uuid : alivePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            double hp = Math.max(0.0, Math.min(p.getMaxHealth(), p.getHealth()));
            String hpStr;
            // 表示は小数1桁まで、10以上は整数表示にする（任意）
            if (hp >= 10.0) {
                hpStr = String.format("%.0f", hp);
            } else {
                hpStr = String.format("%.1f", hp);
            }
            String name = p.getName();
            String text = format.replace("{name}", name).replace("{hp}", hpStr);

            // 長さ制限を超えないように切る（安全策）
            if (text.length() > 40) text = text.substring(0, 40);

            try {
                p.setPlayerListName(text);
            } catch (Exception ignored) {}
        }
    }

    private void applyTabHpForPlayer(Player p) {
        if (!plugin.getConfig().getBoolean("display.tab_hp_enabled", true)) return;
        // すぐに1回更新（updateTabHpDisplay が次tickに反映する前に）
        double hp = Math.max(0.0, Math.min(p.getMaxHealth(), p.getHealth()));
        String hpStr = hp >= 10.0 ? String.format("%.0f", hp) : String.format("%.1f", hp);
        String format = plugin.getConfig().getString("display.tab_hp_format", "{name} §7[{hp} ❤]");
        String text = format.replace("{name}", p.getName()).replace("{hp}", hpStr);
        if (text.length() > 40) text = text.substring(0, 40);
        try { p.setPlayerListName(text); } catch (Exception ignored) {}
    }

    // finalize / cleanup などで tab 表示をリセットすることを保証する
    public void resetAllPlayerListNames() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.setPlayerListName(null); } catch (Exception ignored) {}
        }
    }

    /**
     * ゲーム終了判定
     */
    private void checkGameEnd() {
        // COLLECTION/PVPフェーズでのみ確認
        if (currentPhase != Phase.COLLECTION && currentPhase != Phase.PVP) {
            return;
        }

        if (teamsEnabled) {
            // チーム戦判定: 生存者がいるチーム数を数える
            int survivingTeams = 0;
            TeamColor lastSurvivingTeam = null;
            for (TeamColor tc : TeamColor.values()) {
                if (!teamEnabled.getOrDefault(tc, true)) continue;
                long aliveInTeam = teamMembers.get(tc).stream().filter(alivePlayers::contains).count();
                if (aliveInTeam > 0) {
                    survivingTeams++;
                    lastSurvivingTeam = tc;
                }
            }

            if (survivingTeams == 1 && lastSurvivingTeam != null) {
                // そのチームの勝利とする
                Set<UUID> winners = new HashSet<>();
                for (UUID uuid : teamMembers.get(lastSurvivingTeam)) {
                    if (alivePlayers.contains(uuid)) winners.add(uuid);
                }

                // 勝利処理: 勝者全員に勝利カウントを付与し、全体に通知
                for (UUID u : winners) {
                    PlayerStats stats = playerStats.computeIfAbsent(u, k -> new PlayerStats());
                    stats.incrementWins();
                }

                StringBuilder names = new StringBuilder();
                for (UUID u : winners) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) {
                        if (names.length() > 0) names.append(", ");
                        names.append(p.getName());
                    }
                }

                Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
                Bukkit.broadcastMessage("§a§l🏆 チーム戦 勝利: " + lastSurvivingTeam.chatColor + lastSurvivingTeam.displayJa + " チーム!");
                if (names.length() > 0) {
                    Bukkit.broadcastMessage("§a§l  生存者: " + names.toString());
                }
                Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");

                // 敗者統計を更新（チームに属さない生存者も含める）
                for (UUID uuid : idleSpectators) {
                    PlayerStats stats = playerStats.computeIfAbsent(uuid, k -> new PlayerStats());
                    stats.incrementLosses();
                }
                for (UUID uuid : gameSpectators) {
                    PlayerStats stats = playerStats.computeIfAbsent(uuid, k -> new PlayerStats());
                    stats.incrementLosses();
                }

                finalizeGameAndTeleportAll(winners.isEmpty() ? null : winners.iterator().next());
                return;
            }
            // 2チーム以上残っている -> 続行
            return;
        }

        // 個人戦判定（従来の処理）
        if (alivePlayers.size() <= 1) {
            UUID winner = alivePlayers.isEmpty() ? null : alivePlayers.iterator().next();

            if (winner != null) {
                Player winnerPlayer = Bukkit.getPlayer(winner);
                if (winnerPlayer != null) {
                    PlayerStats winnerStats = playerStats.computeIfAbsent(winner, k -> new PlayerStats());
                    winnerStats.incrementWins();

                    Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
                    Bukkit.broadcastMessage("§a§l🏆 " + winnerPlayer.getName() + " が優勝しました！");
                    Bukkit.broadcastMessage("§a§l━━━━━━━━━━━━━━━━━━━━");
                }
            } else {
                Bukkit.broadcastMessage("§c§l引き分けです。全員が倒されました。");
            }

            // 敗者統計を更新
            for (UUID uuid : idleSpectators) {
                PlayerStats stats = playerStats.computeIfAbsent(uuid, k -> new PlayerStats());
                stats.incrementLosses();
            }
            for (UUID uuid : gameSpectators) {
                PlayerStats stats = playerStats.computeIfAbsent(uuid, k -> new PlayerStats());
                stats.incrementLosses();
            }

            finalizeGameAndTeleportAll(winner);
        }
    }

    /**
     * プレイヤーの統計情報を取得
     */
    public PlayerStats getPlayerStats(UUID uuid) {
        return playerStats.computeIfAbsent(uuid, k -> new PlayerStats());
    }

    private void resetStartVotes() {
        startVotes.clear();
    }

    @EventHandler
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItem();
        
        // アイテムがない場合はスキップ
        if (item == null || !item.hasItemMeta()) return;
        
        String displayName = item.getItemMeta().getDisplayName();
        if (displayName == null) return;
        
        // GUI アイテム（表示名に特定の文字列が含まれる）の場合、使用を禁止
        if (displayName.contains("ロビーに戻る") ||
            displayName.contains("ゲーム開始") ||
            displayName.contains("観戦モード") ||
            displayName.contains("チーム選択")) {
            event.setCancelled(true);
            return;
        }
        
        // ロビー・待機場でのバニラアイテム使用を禁止
        World battleWorld = worldManager.getBattleWorld();
        World pw = p.getWorld();
        
        boolean inLobby = pw.equals(lobbyLocation.getWorld());
        boolean inWaiting = pw.equals(waitingLocation.getWorld());
        boolean inBattle = (battleWorld != null && pw.equals(battleWorld));
        
        // PVPフェーズ以外でのアイテム使用を禁止
        if (inLobby || inWaiting) {
            event.setCancelled(true);
            return;
        }
        
        if (inBattle && currentPhase != Phase.PVP) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        World battleWorld = worldManager.getBattleWorld();
        World pw = p.getWorld();
        
        boolean inLobby = pw.equals(lobbyLocation.getWorld());
        boolean inWaiting = pw.equals(waitingLocation.getWorld());
        boolean inBattle = (battleWorld != null && pw.equals(battleWorld));
        
        // ロビー・待機場での動物/NPC 操作を禁止
        if (inLobby || inWaiting) {
            event.setCancelled(true);
            return;
        }
        
        // PVPフェーズ以外での操作を禁止
        if (inBattle && currentPhase != Phase.PVP) {
            event.setCancelled(true);
        }
    }
}