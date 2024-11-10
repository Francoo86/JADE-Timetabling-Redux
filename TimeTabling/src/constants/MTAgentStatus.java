package constants;

public enum MTAgentStatus {
    ACTIVE(3),
    TERMINATED(4);

    private final int code;

    MTAgentStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
