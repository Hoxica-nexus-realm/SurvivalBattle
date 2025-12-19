package ham_sandwitch.plugin.survivalbattle.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SbTabCompleter implements TabCompleter {

    private final List<String> MAIN_COMMANDS = Arrays.asList("game", "join", "lobby", "stats", "debug", "reload", "help");
    private final List<String> GAME_COMMANDS = Arrays.asList("start", "stats", "stop");
    private final List<String> DEBUG_COMMANDS = Arrays.asList("on", "off", "fake");
    private final List<String> FAKE_COUNT_ARGS = Arrays.asList("1", "2", "3", "5", "10");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("sb")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            // OP 権限なしの場合、OP コマンドを除外
            if (!sender.isOp()) {
                List<String> nonOpCommands = new ArrayList<>(MAIN_COMMANDS);
                nonOpCommands.removeAll(Arrays.asList("debug", "reload"));
                return filterList(nonOpCommands, args[0]);
            }
            return filterList(MAIN_COMMANDS, args[0]);
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "game":
                    return filterList(GAME_COMMANDS, args[1]);
                case "debug":
                    if (!sender.isOp()) {
                        return Collections.emptyList();
                    }
                    return filterList(DEBUG_COMMANDS, args[1]);
                default:
                    return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("fake")) {
                if (!sender.isOp()) {
                    return Collections.emptyList();
                }
                return filterList(FAKE_COUNT_ARGS, args[2]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterList(List<String> originalList, String currentArg) {
        return originalList.stream()
                .filter(s -> s.toLowerCase().startsWith(currentArg.toLowerCase()))
                .collect(Collectors.toList());
    }
}
