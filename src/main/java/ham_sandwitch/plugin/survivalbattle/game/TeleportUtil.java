package ham_sandwitch.plugin.survivalbattle.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public class TeleportUtil {
    
    private static final int MAX_FALL_DISTANCE = 3;
    private static final int MAX_SEARCH_RADIUS = 5;
    private static final int MAX_VERTICAL_SEARCH = 10;
    
    /**
     * 安全な位置を探す
     * @param loc 基準位置
     * @return 安全な位置
     */
    public static Location findSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return loc;
        
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        
        // まず現在位置をチェック
        Location currentLoc = new Location(world, x + 0.5, y, z + 0.5, loc.getYaw(), loc.getPitch());
        if (isSafeLocation(currentLoc)) {
            return currentLoc;
        }
        
        // 上下に探索
        for (int dy = 0; dy <= MAX_VERTICAL_SEARCH; dy++) {
            // 下方向
            if (dy > 0) {
                Location below = new Location(world, x + 0.5, y - dy, z + 0.5, loc.getYaw(), loc.getPitch());
                if (isSafeLocation(below)) {
                    return below;
                }
            }
            
            // 上方向
            Location above = new Location(world, x + 0.5, y + dy, z + 0.5, loc.getYaw(), loc.getPitch());
            if (isSafeLocation(above)) {
                return above;
            }
        }
        
        // 水平方向に探索
        for (int radius = 1; radius <= MAX_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    
                    Location searchLoc = new Location(world, x + dx + 0.5, y, z + dz + 0.5, loc.getYaw(), loc.getPitch());
                    Location safe = findSafeLocationVertical(searchLoc);
                    if (safe != null) {
                        return safe;
                    }
                }
            }
        }
        
        // 安全な場所が見つからない場合は最高地点+1を返す
        int highestY = world.getHighestBlockYAt(x, z);
        return new Location(world, x + 0.5, highestY + 1, z + 0.5, loc.getYaw(), loc.getPitch());
    }
    
    /**
     * 垂直方向に安全な位置を探す
     */
    private static Location findSafeLocationVertical(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        
        for (int y = loc.getBlockY(); y >= loc.getBlockY() - MAX_VERTICAL_SEARCH && y >= 0; y--) {
            Location checkLoc = new Location(world, x + 0.5, y, z + 0.5, loc.getYaw(), loc.getPitch());
            if (isSafeLocation(checkLoc)) {
                return checkLoc;
            }
        }
        
        for (int y = loc.getBlockY() + 1; y <= loc.getBlockY() + MAX_VERTICAL_SEARCH && y < world.getMaxHeight(); y++) {
            Location checkLoc = new Location(world, x + 0.5, y, z + 0.5, loc.getYaw(), loc.getPitch());
            if (isSafeLocation(checkLoc)) {
                return checkLoc;
            }
        }
        
        return null;
    }
    
    /**
     * 位置が安全かチェック
     */
    private static boolean isSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        
        // ワールドの範囲外チェック
        if (y < 0 || y >= world.getMaxHeight() - 1) return false;
        
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        
        // 足元と頭の位置が空気（または通過可能）であること
        if (!isPassable(feet) || !isPassable(head)) {
            return false;
        }
        
        // 地面が固体であること
        if (!ground.getType().isSolid()) {
            return false;
        }
        
        // 危険なブロックチェック
        if (isDangerousBlock(ground)) {
            return false;
        }
        
        // 落下距離チェック
        int fallDistance = calculateFallDistance(loc);
        if (fallDistance > MAX_FALL_DISTANCE) {
            return false;
        }
        
        return true;
    }
    
    /**
     * ブロックが通過可能かチェック
     */
    private static boolean isPassable(Block block) {
        Material type = block.getType();
        return type.isAir() || 
               !type.isSolid() || 
               type == Material.WATER ||
               type == Material.LAVA;
    }
    
    /**
     * 危険なブロックかチェック
     */
    private static boolean isDangerousBlock(Block block) {
        Material type = block.getType();
        return type == Material.LAVA ||
               type == Material.FIRE ||
               type == Material.MAGMA_BLOCK ||
               type == Material.CACTUS ||
               type == Material.SWEET_BERRY_BUSH;
    }
    
    /**
     * 落下距離を計算
     */
    private static int calculateFallDistance(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        
        for (int dy = 0; dy <= MAX_FALL_DISTANCE + 5; dy++) {
            int checkY = y - dy - 1;
            if (checkY < 0) return MAX_FALL_DISTANCE + 1;
            
            Block block = world.getBlockAt(x, checkY, z);
            if (block.getType().isSolid()) {
                return dy;
            }
        }
        
        return MAX_FALL_DISTANCE + 1;
    }
}
