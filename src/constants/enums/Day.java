package constants.enums;

public enum Day {
    LUNES("Lunes"),
    MARTES("Martes"),
    MIERCOLES("Miercoles"),
    JUEVES("Jueves"),
    VIERNES("Viernes");

    private final String displayName;

    Day(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Day fromString(String day) {
        try {
            return valueOf(day.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Handle display names
            for (Day d : values()) {
                if (d.displayName.equalsIgnoreCase(day)) {
                    return d;
                }
            }
            throw new IllegalArgumentException("No matching day found for: " + day);
        }
    }
}