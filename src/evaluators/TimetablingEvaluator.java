package evaluators;

import constants.Commons;
import constants.enums.Day;
import constants.enums.TipoContrato;
import constants.enums.Actividad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TimetablingEvaluator {
    // Constants for room occupancy thresholds
    private static final double OPTIMAL_OCCUPANCY_MIN = 0.75;
    private static final double OPTIMAL_OCCUPANCY_MAX = 0.95;
    private static final int MIN_STUDENTS = 9;
    private static final int MAX_STUDENTS = 70;

    // Weights for different constraints
    private static final double CAPACITY_WEIGHT = 0.25;
    private static final double TIME_SLOT_WEIGHT = 0.20;
    private static final double CAMPUS_WEIGHT = 0.20;
    private static final double CONTINUITY_WEIGHT = 0.15;
    private static final double ACTIVITY_TYPE_WEIGHT = 0.20;

    public static final int MEETING_ROOM_THRESHOLD = 10;

    public static int calculateSatisfaction(
            int roomCapacity,
            int studentsCount,
            int nivel,
            String campus,
            String preferredCampus,
            int block,
            Map<Day, List<Integer>> existingBlocks,
            TipoContrato contrato,
            Actividad activity) {

        // Critical capacity violation - when students exceed room capacity
        if (studentsCount > roomCapacity) {
            return 1; // This is a hard constraint violation
        }

        // Small class handling - return base satisfaction of 3 for appropriate small class placement
        if (studentsCount < MIN_STUDENTS) {
            if (roomCapacity < MEETING_ROOM_THRESHOLD) {
                // Small class in appropriate small room
                double meetingRoomRatio = (double) studentsCount / roomCapacity;
                if (meetingRoomRatio >= 0.5 && meetingRoomRatio <= 0.9) {
                    return 5; // Good fit for small class
                } else {
                    return 3; // Acceptable but not optimal
                }
            } else {
                return 2; // Small class in regular room - not ideal
            }
        }

        // Very large class check
        if (studentsCount > MAX_STUDENTS) {
            return 2; // Should be split into parallel sections
        }

        double capacityScore = evaluateCapacity(roomCapacity, studentsCount);
        double timeSlotScore = evaluateTimeSlot(nivel, block);
        double campusScore = evaluateCampus(campus, preferredCampus, existingBlocks);
        double continuityScore = evaluateContinuity(existingBlocks, contrato);
        double activityScore = evaluateActivityType(activity, block);

        // Calculate weighted average
        double weightedScore = (
                capacityScore * CAPACITY_WEIGHT +
                        timeSlotScore * TIME_SLOT_WEIGHT +
                        campusScore * CAMPUS_WEIGHT +
                        continuityScore * CONTINUITY_WEIGHT +
                        activityScore * ACTIVITY_TYPE_WEIGHT
        ) * 10;

        // Round to nearest integer and ensure score is between 1-10
        return Math.max(1, Math.min(10, (int) Math.round(weightedScore)));
    }

    private static double evaluateCapacity(int roomCapacity, int studentsCount) {
        if (studentsCount < MEETING_ROOM_THRESHOLD) {
            if (roomCapacity < MEETING_ROOM_THRESHOLD) {
                double meetingRoomRatio = (double) studentsCount / roomCapacity;
                if (meetingRoomRatio >= 0.5 && meetingRoomRatio <= 0.9) {
                    return 1.0; // Perfect fit for small class in meeting room
                } else {
                    return 0.8; // Still good for meeting room
                }
            } else {
                if (roomCapacity <= studentsCount * 5) {
                    return 0.7; // Acceptable if room isn't too oversized
                } else {
                    return 0.5; // Penalty for very oversized room
                }
            }
        }

        double occupancyRatio = (double) studentsCount / roomCapacity;

        // Optimal efficiency: 75-95% room capacity
        if (occupancyRatio >= OPTIMAL_OCCUPANCY_MIN && occupancyRatio <= OPTIMAL_OCCUPANCY_MAX) {
            return 1.0;
        }
        // Underutilized: <75% capacity
        else if (occupancyRatio < OPTIMAL_OCCUPANCY_MIN) {
            return 0.7 + (occupancyRatio / OPTIMAL_OCCUPANCY_MIN) * 0.3;
        }
        // Near capacity: >95% but <= 100%
        else if (occupancyRatio <= 1.0) {
            return 0.8;
        }
        // Over capacity should never happen as it's caught earlier
        else {
            return 0.1;
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

    private static double evaluateActivityType(Actividad activity, int block) {
        // Constraint 2: Theory classes in morning blocks (1-4)
        if (activity == Actividad.TEORIA) {
            return block <= 4 ? 1.0 : 0.6;
        }

        // Labs/workshops/practices better in afternoon to allow prep time
        if (activity == Actividad.LABORATORIO ||
                activity == Actividad.TALLER ||
                activity == Actividad.PRACTICA) {
            return block >= 5 ? 1.0 : 0.7;
        }

        // Ayudantias and Tutorias are more flexible
        if (activity == Actividad.AYUDANTIA ||
                activity == Actividad.TUTORIA) {
            return 1.0;
        }

        return 0.8; // Default case
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

    private static double evaluateContinuity(Map<Day, List<Integer>> existingBlocks,
                                             TipoContrato tipoContrato) {
        if (tipoContrato == TipoContrato.JORNADA_PARCIAL) {
            return 1.0; // No continuity restrictions for part-time
        }

        double score = 1.0;
        for (List<Integer> blocks : existingBlocks.values()) {
            if (blocks.size() < 2) continue;

            List<Integer> sortedBlocks = new ArrayList<>(blocks);
            Collections.sort(sortedBlocks);

            // Evaluate gaps between blocks
            for (int i = 1; i < sortedBlocks.size(); i++) {
                int gap = sortedBlocks.get(i) - sortedBlocks.get(i-1) - 1;

                if (gap > 1) {
                    // Penalize more than one free block
                    score *= 0.6;
                } else if (gap == 1) {
                    // One free block is acceptable but not optimal
                    score *= 0.9;
                }
                // Consecutive blocks maintain score = 1.0
            }
        }

        return score;
    }
}