// BatchAssignmentRequest.java
package objetos.helper;

import constants.enums.Day;

import java.io.Serializable;
import java.util.List;

public class BatchAssignmentRequest implements Serializable {
    private List<AssignmentRequest> assignments;

    public BatchAssignmentRequest(List<AssignmentRequest> assignments) {
        this.assignments = assignments;
    }

    public List<AssignmentRequest> getAssignments() {
        return assignments;
    }

    public static class AssignmentRequest implements Serializable {
        private Day day;
        private int block;
        private String subjectName;
        private int satisfaction;
        private String classroomCode;
        private int vacancy;

        public AssignmentRequest(Day day, int block, String subjectName,
                                 int satisfaction, String classroomCode, int vacancy) {
            this.day = day;
            this.block = block;
            this.subjectName = subjectName;
            this.satisfaction = satisfaction;
            this.classroomCode = classroomCode;
            this.vacancy = vacancy;
        }

        // Add getters
        public Day getDay() { return day; }
        public int getBlock() { return block; }
        public String getSubjectName() { return subjectName; }
        public int getSatisfaction() { return satisfaction; }
        public String getClassroomCode() { return classroomCode; }
        public int getVacancy() { return vacancy; }
    }
}

