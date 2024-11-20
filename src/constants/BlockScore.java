package constants;

public class BlockScore implements Comparable<BlockScore> {
    private final int score;
    private final String reason;

    public BlockScore(int score, String reason) {
        this.score = score;
        this.reason = reason;
    }

    public int getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public int compareTo(BlockScore other) {
        return Integer.compare(this.score, other.score);
    }
}