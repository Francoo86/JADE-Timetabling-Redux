package objetos.helper;

import constants.enums.Day;
import java.io.Serializable;
import java.util.List;

public class BatchAssignmentConfirmation implements Serializable {
    private List<ConfirmedAssignment> confirmedAssignments;

    public BatchAssignmentConfirmation(List<ConfirmedAssignment> confirmedAssignments) {
        this.confirmedAssignments = confirmedAssignments;
    }

    public List<ConfirmedAssignment> getConfirmedAssignments() {
        return confirmedAssignments;
    }

    public static class ConfirmedAssignment implements Serializable {
        private Day day;
        private int block;
        private String classroomCode;
        private int satisfaction;

        public ConfirmedAssignment(Day day, int block, String classroomCode, int satisfaction) {
            this.day = day;
            this.block = block;
            this.classroomCode = classroomCode;
            this.satisfaction = satisfaction;
        }

        // Add getters
        public Day getDay() { return day; }
        public int getBlock() { return block; }
        public String getClassroomCode() { return classroomCode; }
        public int getSatisfaction() { return satisfaction; }
    }
}