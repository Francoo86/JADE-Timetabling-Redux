package objetos;

import constants.enums.Day;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SubjectState {
    private int totalAssignedHours = 0;
    private Set<String> assignedSlots = new HashSet<>();
    private Map<String, Integer> roomUsageCount = new HashMap<>();
    private String preferredRoom = null;

    public boolean isSlotAssigned(Day day, int block) {
        return assignedSlots.contains(day.toString() + "-" + block);
    }

    public void markSlotAssigned(Day day, int block) {
        assignedSlots.add(day.toString() + "-" + block);
        totalAssignedHours++;
    }

    public void updateRoomUsage(String room) {
        int count = roomUsageCount.getOrDefault(room, 0) + 1;
        roomUsageCount.put(room, count);
        if (preferredRoom == null || count > roomUsageCount.getOrDefault(preferredRoom, 0)) {
            preferredRoom = room;
        }
    }

    public String getPreferredRoom() {
        return preferredRoom;
    }

    public boolean isComplete(int requiredHours) {
        return totalAssignedHours >= requiredHours;
    }
}