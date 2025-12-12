package ham_sandwitch.plugin.survivalbattle.commands;

import ham_sandwitch.plugin.survivalbattle.game.GameManager;
import ham_sandwitch.plugin.survivalbattle.game.PlayerStats;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class SbCommand implements CommandExecutor {
    private final GameManager gameManager;
    private final JavaPlugin plugin;

    public SbCommand(GameManager gameManager, JavaPlugin plugin) {
        this.gameManager = gameManager;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return sendHelpMessage(sender);
        }

        String mainCommand = args[0].toLowerCase();

        switch (mainCommand) {
            case "game":
                return handleGameCommand(sender, args);
            case "join":
                return handleJoinCommand(sender);
            case "lobby":
                return handleLobbyCommand(sender);
            case "stats":
                return handleStatsCommand(sender, args);
            case "debug":
                return handleDebugCommand(sender, args);
            case "reload":
                return handleReloadCommand(sender);
            case "help":
                return sendHelpMessage(sender);
            default:
                sender.sendMessage(ChatColor.RED + "不明なコマンドです。/sb help を確認してください。");
                return false;
        }
    }

    private boolean handleGameCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使用法: /sb game <start|stats|stop>");
            return false;
        }
        String subCommand = args[1].toLowerCase();

        switch (subCommand) {
            case "start":
                if (gameManager.getPhase() != GameManager.Phase.IDLE) {
                    sender.sendMessage(ChatColor.RED + "❌ ゲームは既に開始されています。");
                    sender.sendMessage(ChatColor.GRAY + "現在のフェーズ: " + gameManager.getPhase());
                    return true;
                }
                gameManager.startGame(sender);
                return true;
                
            case "stats":
                // ゲーム状態を表示
                sender.sendMessage(gameManager.getGameStats());
                return true;
                
            case "stop":
                if (!sender.isOp()) {
                    sender.sendMessage(ChatColor.RED + "❌ 権限がありません。");
                    return true;
                }
                if (gameManager.getPhase() == GameManager.Phase.IDLE) {
                    sender.sendMessage(ChatColor.YELLOW + "⚠️ ゲームは現在実行されていません。");
                    return true;
                }
                gameManager.stopGame(sender);
                return true;
                
            default:
                sender.sendMessage(ChatColor.RED + "❌ 不明なゲームサブコマンドです。");
                sender.sendMessage(ChatColor.GRAY + "使用法: /sb game <start|stats|stop>");
                return false;
        }
    }

    // 追加: プレイヤー個別統計表示コマンド
    private boolean handleStatsCommand(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            // プレイヤー名指定
            String playerName = args[1];
            Player target = plugin.getServer().getPlayer(playerName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "❌ プレイヤー " + playerName + " が見つかりません。");
                return true;
            }
            displayPlayerStats(sender, target.getUniqueId(), target.getName());
        } else if (sender instanceof Player) {
            // 実行者自身の統計
            Player p = (Player) sender;
            displayPlayerStats(sender, p.getUniqueId(), p.getName());
        } else {
            sender.sendMessage(ChatColor.RED + "❌ プレイヤー名を指定してください。");
            return false;
        }
        return true;
    }

    // 追加: プレイヤー統計表示
    private void displayPlayerStats(CommandSender sender, UUID uuid, String playerName) {
        PlayerStats stats = gameManager.getPlayerStats(uuid);
        
        sender.sendMessage(ChatColor.BLUE + "━━━━━ " + playerName + " の統計 ━━━━━");
        sender.sendMessage(ChatColor.GREEN + "総キル数: §f" + stats.getKills());
        sender.sendMessage(ChatColor.GREEN + "総デス数: §f" + stats.getDeaths());
        sender.sendMessage(ChatColor.GREEN + "総勝利数: §f" + stats.getWins());
        sender.sendMessage(ChatColor.GREEN + "総敗北数: §f" + stats.getLosses());
        sender.sendMessage(ChatColor.YELLOW + "勝率: §f" + String.format("%.1f", stats.getWinRate()) + "%");
        
        if (stats.getGamesPlayed() > 0) {
            sender.sendMessage(ChatColor.AQUA + "平均キル/ゲーム: §f" + String.format("%.2f", stats.getAverageKills()));
            sender.sendMessage(ChatColor.AQUA + "平均デス/ゲーム: §f" + String.format("%.2f", stats.getAverageDeaths()));
        }
        
        sender.sendMessage(ChatColor.BLUE + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private boolean handleJoinCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "❌ このコマンドはプレイヤーのみ実行可能です。");
            return true;
        }
        Player player = (Player) sender;
        
        if (gameManager.getPhase() != GameManager.Phase.IDLE) {
            player.sendMessage(ChatColor.RED + "❌ ゲーム進行中は参加できません。");
            player.sendMessage(ChatColor.GRAY + "ゲーム終了後に再度お試しください。");
            return true;
        }
        
        gameManager.teleportToWaiting(player);
        return true;
    }

    private boolean handleLobbyCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "❌ このコマンドはプレイヤーのみ実行可能です。");
            return true;
        }
        Player player = (Player) sender;
        gameManager.teleportToLobby(player);
        return true;
    }

    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "❌ 権限がありません。");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "使用法: /sb debug <on|off|fake> [数値]");
            return false;
        }

        String subCommand = args[1].toLowerCase();
        
        switch (subCommand) {
            case "on":
                gameManager.setDebug(true);
                gameManager.setLogDebugMode(true);
                sender.sendMessage(ChatColor.GREEN + "✅ デバッグモードが有効になりました。");
                return true;
                
            case "off":
                gameManager.setDebug(false);
                gameManager.setLogDebugMode(false);
                sender.sendMessage(ChatColor.GREEN + "✅ デバッグモードが無効になりました。");
                return true;
                
            case "fake":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "使用法: /sb debug fake <数>");
                    return false;
                }
                try {
                    int count = Integer.parseInt(args[2]);
                    if (count < 0) {
                        sender.sendMessage(ChatColor.RED + "❌ 0以上の数値を入力してください。");
                        return false;
                    }
                    gameManager.setFakePlayers(count);
                    sender.sendMessage(ChatColor.GREEN + "✅ ダミープレイヤーを " + count + " に設定しました。");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "❌ 数値を入力してください。");
                }
                return true;
                
            default:
                sender.sendMessage(ChatColor.RED + "❌ 不明なデバッグサブコマンドです。");
                return false;
        }
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "❌ 権限がありません。");
            return true;
        }
        
        try {
            plugin.reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "✅ 設定ファイルを再読み込みしました。");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 設定ファイルの読み込みに失敗しました。");
            plugin.getLogger().severe("Config reload error: " + e.getMessage());
        }
        return true;
    }

    private boolean sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.BLUE + "━━━━━ SurvivalBattle コマンド ━━━━━");
        sender.sendMessage(ChatColor.GREEN + "/sb join" + ChatColor.GRAY + " - 待機場へ移動");
        sender.sendMessage(ChatColor.GREEN + "/sb lobby" + ChatColor.GRAY + " - ロビーにテレポート");
        sender.sendMessage(ChatColor.GREEN + "/sb game start" + ChatColor.GRAY + " - ゲームを開始");
        sender.sendMessage(ChatColor.GREEN + "/sb game stats" + ChatColor.GRAY + " - ゲーム状態表示");
        sender.sendMessage(ChatColor.GREEN + "/sb game stop" + ChatColor.GRAY + " - ゲーム強制終了 (OP)");
        sender.sendMessage(ChatColor.GREEN + "/sb stats [プレイヤー名]" + ChatColor.GRAY + " - プレイヤー統計表示");
        sender.sendMessage(ChatColor.GREEN + "/sb debug on|off" + ChatColor.GRAY + " - デバッグ切替 (OP)");
        sender.sendMessage(ChatColor.GREEN + "/sb debug fake <数>" + ChatColor.GRAY + " - ダミープレイヤー (OP)");
        sender.sendMessage(ChatColor.GREEN + "/sb reload" + ChatColor.GRAY + " - 設定再読み込み (OP)");
        sender.sendMessage(ChatColor.BLUE + "━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return true;
    }
}
