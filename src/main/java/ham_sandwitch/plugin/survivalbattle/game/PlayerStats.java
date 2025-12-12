package ham_sandwitch.plugin.survivalbattle.game;

public class PlayerStats {
    private int kills = 0;
    private int deaths = 0;
    private int wins = 0;
    private int losses = 0;

    public void incrementKills() { kills++; }
    public void incrementDeaths() { deaths++; }
    public void incrementWins() { wins++; }
    public void incrementLosses() { losses++; }

    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }

    // 追加: 統計計算メソッド
    public int getGamesPlayed() {
        return wins + losses;
    }
    
    public double getWinRate() {
        int total = getGamesPlayed();
        if (total == 0) return 0.0;
        return (double) wins / total * 100.0;
    }
    
    public double getAverageKills() {
        int total = getGamesPlayed();
        if (total == 0) return 0.0;
        return (double) kills / total;
    }
    
    public double getAverageDeaths() {
        int total = getGamesPlayed();
        if (total == 0) return 0.0;
        return (double) deaths / total;
    }

    @Override
    public String toString() {
        return "Kills: " + kills +
               ", Deaths: " + deaths +
               ", Wins: " + wins +
               ", Losses: " + losses;
    }
}
