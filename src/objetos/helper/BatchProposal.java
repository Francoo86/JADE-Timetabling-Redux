package objetos.helper;

import constants.enums.Day;
import jade.lang.acl.ACLMessage;
import objetos.ClassroomAvailability;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BatchProposal {
    private final Map<Day, List<BlockProposal>> dayProposals;
    private final String roomCode;
    private final String campus;
    private final int capacity;
    private int satisfactionScore;
    private final ACLMessage originalMessage;

    public BatchProposal(ClassroomAvailability availability, ACLMessage message) {
        this.roomCode = availability.getCodigo();
        this.campus = availability.getCampus();
        this.capacity = availability.getCapacidad();
        this.satisfactionScore = 0;
        this.originalMessage = message;
        this.dayProposals = new HashMap<>();

        // Convert string-based map to proper Day enum map
        availability.getAvailableBlocks().forEach((dayStr, blocks) -> {
            Day day = Day.fromString(dayStr);
            dayProposals.put(day, blocks.stream()
                    .map(block -> new BlockProposal(block, day))
                    .collect(Collectors.toList()));
        });
    }

    public static class BlockProposal {
        private final int block;
        private final Day day;

        public BlockProposal(int block, Day day) {
            this.block = block;
            this.day = day;
        }

        public int getBlock() { return block; }
        public Day getDay() { return day; }
    }

    // Getters
    public Map<Day, List<BlockProposal>> getDayProposals() { return dayProposals; }
    public String getRoomCode() { return roomCode; }
    public String getCampus() { return campus; }
    public int getCapacity() { return capacity; }
    public int getSatisfactionScore() { return satisfactionScore; }
    public ACLMessage getOriginalMessage() { return originalMessage; }
    public void setSatisfactionScore(int satisfactionScore) { this.satisfactionScore = satisfactionScore; }
}