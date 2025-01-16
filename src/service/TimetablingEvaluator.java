package service;

import constants.Commons;
import constants.enums.Day;
import java.util.List;
import java.util.Map;

public class TimetablingEvaluator {
    // Constants for room occupancy thresholds
    private static final double OPTIMAL_OCCUPANCY_MIN = 0.75;
    private static final double OPTIMAL_OCCUPANCY_MAX = 0.95;
    private static final int MIN_STUDENTS = 9;
    private static final int MAX_STUDENTS = 70;

    // Weights for different constraints
    private static final double CAPACITY_WEIGHT = 0.35;
    private static final double TIME_SLOT_WEIGHT = 0.25;
    private static final double CAMPUS_WEIGHT = 0.25;
    private static final double CONTINUITY_WEIGHT = 0.15;

    public static int calculateSatisfaction(
            int roomCapacity,
            int studentsCount,
            int nivel,
            String campus,
            String preferredCampus,
            int block,
            Map<Day, List<Integer>> existingBlocks) {

        // Constraint 8: Contar los limites de estudiantes.
        // Se considerara el uso de las salas de clases para aquellas actividades curriculares que contemplen de nueve hasta setenta estudiantes.
        // Aquellas que tengan un numero inferior a nueve, deberan utilizar salas de reuniones.
        // Aquellas actividades curriculares que tengan un numero de estudaintes que sobrepase la capacidad de las aulas dispuestas
        // en el campus (70), deberan dividirse en paralelos equivalentes

        //FIXME: Esto tiene un problema con las salas de reuniones, ya que no se consideran en el calculo de la satisfaccion.
        if (studentsCount < MIN_STUDENTS || studentsCount > MAX_STUDENTS) {
            return 1;
        }

        double capacityScore = evaluateCapacity(roomCapacity, studentsCount);
        double timeSlotScore = evaluateTimeSlot(nivel, block);
        double campusScore = evaluateCampus(campus, preferredCampus, existingBlocks);
        double continuityScore = evaluateContinuity(existingBlocks);

        // Calculate weighted average
        double weightedScore = (
                capacityScore * CAPACITY_WEIGHT +
                        timeSlotScore * TIME_SLOT_WEIGHT +
                        campusScore * CAMPUS_WEIGHT +
                        continuityScore * CONTINUITY_WEIGHT
        ) * 10;

        // Round to nearest integer and ensure score is between 1-10
        return Math.max(1, Math.min(10, (int) Math.round(weightedScore)));
    }

    private static double evaluateCapacity(int roomCapacity, int studentsCount) {
        double occupancyRatio = (double) studentsCount / roomCapacity;

        // Optimal efficiency: 75-95% room capacity
        if (occupancyRatio >= OPTIMAL_OCCUPANCY_MIN && occupancyRatio <= OPTIMAL_OCCUPANCY_MAX) {
            return 1.0;
        }
        // Overcrowded: >95% capacity
        else if (occupancyRatio > OPTIMAL_OCCUPANCY_MAX) {
            return 0.6;
        }
        // Underutilized: <75% capacity
        else {
            return 0.7 + (occupancyRatio / OPTIMAL_OCCUPANCY_MIN) * 0.3;
        }
    }

    private static double evaluateTimeSlot(int nivel, int block) {
        // Constraint 1: Only blocks 1-9 (8:00-18:30)
        if (block < 1 || block > Commons.MAX_BLOQUE_DIURNO) {
            return 0.0;
        }

        // Constraints 3 & 7: Level-based time preferences
        boolean isFirstYear = nivel <= 2;
        boolean isOddLevel = nivel % 2 == 1;

        // First year students preferably in morning
        if (isFirstYear) {
            return block <= 4 ? 1.0 : 0.6;
        }

        // Other levels: Odd years morning, Even years afternoon
        if (isOddLevel) {
            return block <= 4 ? 1.0 : 0.7;
        } else {
            return block >= 5 ? 1.0 : 0.7;
        }
    }

    private static double evaluateCampus(
            String campus,
            String preferredCampus,
            Map<Day, List<Integer>> existingBlocks) {

        // Constraint 4: Campus transitions
        if (!campus.equals(preferredCampus)) {
            // Check if there are already classes in different campuses
            boolean hasOtherCampus = existingBlocks.values().stream()
                    .flatMap(List::stream)
                    .count() > 0;

            if (hasOtherCampus) {
                return 0.5; // Penalty for multiple campus transitions
            }
        }

        return campus.equals(preferredCampus) ? 1.0 : 0.7;
    }

    private static double evaluateContinuity(Map<Day, List<Integer>> existingBlocks) {
        // Constraint 6: Avoid large gaps
        boolean hasLargeGaps = existingBlocks.values().stream()
                .anyMatch(blocks -> {
                    if (blocks.size() < 2) return false;
                    blocks.sort(null);
                    for (int i = 1; i < blocks.size(); i++) {
                        if (blocks.get(i) - blocks.get(i-1) > 2) {
                            return true;
                        }
                    }
                    return false;
                });

        return hasLargeGaps ? 0.6 : 1.0;
    }
}