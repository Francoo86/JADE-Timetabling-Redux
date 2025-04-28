package evaluators;

import agentes.AgenteProfesor;
import constants.Commons;
import constants.enums.Actividad;
import constants.enums.Day;
import constants.enums.TipoContrato;
import objetos.Asignatura;
import objetos.BloqueInfo;
import objetos.helper.BatchProposal;

import java.util.*;
import java.util.stream.Collectors;

public class ConstraintEvaluator {
    private static class BatchProposalScore {
        final BatchProposal proposal;
        final int score;

        BatchProposalScore(BatchProposal proposal, int score) {
            this.proposal = proposal;
            this.score = score;
        }
    }

    private AgenteProfesor profesor;
    private final int MEETING_ROOM_THRESHOLD = 10;

    //leave it empty for now
    public ConstraintEvaluator(AgenteProfesor profesor) {
        this.profesor = profesor;
    }

    public List<BatchProposal> filterAndSortProposals(List<BatchProposal> proposals) {
        if (proposals.isEmpty()) {
            return Collections.emptyList();
        }

        Asignatura currentSubject = profesor.getCurrentSubject();
        String currentCampus = currentSubject.getCampus();
        int currentNivel = currentSubject.getNivel();
        String currentAsignaturaNombre = currentSubject.getNombre();
        boolean needsMeetingRoom = currentSubject.getVacantes() < MEETING_ROOM_THRESHOLD;

        // Get current schedule info
        Map<Day, List<Integer>> currentSchedule = profesor.getBlocksBySubject(currentAsignaturaNombre);
        Map<String, Integer> roomUsage = new HashMap<>();
        Map<Day, Integer> blocksPerDay = new HashMap<>();
        String mostUsedRoom = calculateMostUsedRoom(currentSchedule, blocksPerDay, roomUsage);

        ArrayList<BatchProposalScore> scoredProposals = new ArrayList<>();

        // Process each proposal
        for (BatchProposal proposal : proposals) {
            if (!isValidProposal(proposal, currentSubject, currentNivel, needsMeetingRoom, currentAsignaturaNombre)) {
                continue;
            }

            int totalScore = calculateTotalScore(
                    proposal, currentSubject, currentCampus, currentNivel,
                    needsMeetingRoom, blocksPerDay, mostUsedRoom, roomUsage,
                    currentSchedule
            );

            if (totalScore > 0) {
                scoredProposals.add(new BatchProposalScore(proposal, totalScore));
            }
        }

        if (scoredProposals.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort by final scores
        scoredProposals.sort((ps1, ps2) -> ps2.score - ps1.score);

        return scoredProposals.stream()
                .map(ps -> ps.proposal)
                .collect(Collectors.toList());
    }

    private String calculateMostUsedRoom(
            Map<Day, List<Integer>> currentSchedule,
            Map<Day, Integer> blocksPerDay,
            Map<String, Integer> roomUsage) {

        String mostUsedRoom = null;

        for (Map.Entry<Day, List<Integer>> entry : currentSchedule.entrySet()) {
            Day day = entry.getKey();
            List<Integer> blocks = entry.getValue();
            blocksPerDay.put(day, blocks.size());

            for (int block : blocks) {
                BloqueInfo info = profesor.getBloqueInfo(day, block);
                if (info != null) {
                    String room = info.getCampus();
                    int count = roomUsage.merge(room, 1, Integer::sum);
                    if (mostUsedRoom == null || count > roomUsage.getOrDefault(mostUsedRoom, 0)) {
                        mostUsedRoom = room;
                    }
                }
            }
        }
        return mostUsedRoom;
    }

    private boolean isValidProposal(
            BatchProposal proposal,
            Asignatura currentSubject,
            int currentNivel,
            boolean needsMeetingRoom,
            String currentAsignaturaNombre) {

        boolean isMeetingRoom = proposal.getCapacity() < MEETING_ROOM_THRESHOLD;

        // More flexible room assignment strategy
        if (needsMeetingRoom) {
            // Small class case
            if (!isMeetingRoom) {
                // Allow regular rooms if they're not extremely oversized
                if (proposal.getCapacity() > currentSubject.getVacantes() * 4) {
                    return false; // Only reject extremely oversized rooms
                }
            }
        } else {
            // Regular class case
            if (isMeetingRoom) {
                return false; // Protect meeting rooms for small classes
            }
        }

        return isValidProposalFast(proposal, currentSubject,
                currentNivel % 2 == 1, currentAsignaturaNombre) &&
                validateGapsForProposal(proposal);
    }

    private boolean isValidProposalFast(BatchProposal proposal, Asignatura asignatura,
                                        boolean isOddYear, String asignaturaNombre) {
        // Basic room validation
        if (!checkCampusConstraints(proposal, asignatura.getCampus())) {
            return false;
        }

        // Check each day's blocks
        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry : proposal.getDayProposals().entrySet()) {
            Day day = entry.getKey();
            List<BatchProposal.BlockProposal> blocks = entry.getValue();

            // Get all blocks for this subject on this day
            Map<String, List<Integer>> asignaturasEnDia = profesor.getBlocksByDay(day);
            List<Integer> existingBlocks = asignaturasEnDia.get(asignaturaNombre);

            // Check block limit per day
            if (existingBlocks != null && existingBlocks.size() >= 2) {
                continue;
            }

            // Extract proposed block numbers for validation
            List<Integer> proposedBlocks = blocks.stream()
                    .map(BatchProposal.BlockProposal::getBlock)
                    .collect(Collectors.toList());

            // Skip if activity duration constraint would be violated
            // (Unless it's a lab or workshop)
            if (asignatura.getActividad() != Actividad.LABORATORIO &&
                    asignatura.getActividad() != Actividad.TALLER) {

                // Check for continuous blocks in proposed blocks
                List<Integer> sortedBlocks = new ArrayList<>(proposedBlocks);
                if (existingBlocks != null) {
                    sortedBlocks.addAll(existingBlocks);
                }
                Collections.sort(sortedBlocks);

                int continuousCount = 1;
                for (int i = 1; i < sortedBlocks.size(); i++) {
                    if (sortedBlocks.get(i) == sortedBlocks.get(i-1) + 1) {
                        continuousCount++;
                        if (continuousCount > 2) {
                            continue;  // Skip this day if it would create >2 continuous hours
                        }
                    } else {
                        continuousCount = 1;
                    }
                }
            }

            // Validate each block in the day
            for (BatchProposal.BlockProposal block : blocks) {
                int bloque = block.getBlock();

                // Basic time constraints
                if (bloque < 1 || bloque > Commons.MAX_BLOQUE_DIURNO) {
                    continue;
                }

                // Block 9 constraint
                if (bloque == Commons.MAX_BLOQUE_DIURNO && profesor.getBloquesPendientesInNegotiation() % 2 == 0) {
                    continue;
                }
                /*
                if (bloque == Commons.MAX_BLOQUE_DIURNO && behaviour.getBloquesPendientes() % 2 == 0) {
                    continue;
                }*/

                // Year-based constraints
                if (isOddYear) {
                    if (bloque > 4 && bloque != Commons.MAX_BLOQUE_DIURNO) {
                        continue;
                    }
                } else {
                    if (bloque < 5 && proposal.getSatisfactionScore() < 8) {
                        continue;
                    }
                }

                // If we find at least one valid block, proposal is valid
                return true;
            }
        }

        return false;
    }

    private int calculateProposalScore(BatchProposal proposal, String currentCampus,
                                       int nivel, Asignatura subject) {
        int score = 0;

        // Campus consistency (high priority)
        if (proposal.getCampus().equals(currentCampus)) {
            score += 10000;
        } else {
            score -= 10000;
        }

        // Time preference based on year
        boolean isOddYear = nivel % 2 == 1;
        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry : proposal.getDayProposals().entrySet()) {
            List<BatchProposal.BlockProposal> blocks = entry.getValue();
            for (BatchProposal.BlockProposal block : blocks) {
                if (isOddYear) {
                    if (block.getBlock() <= 4) score += 3000;
                } else {
                    if (block.getBlock() >= 5) score += 3000;
                }
            }

            if (profesor.getTipoContrato() != TipoContrato.JORNADA_PARCIAL) {
                if (blocks.size() > 1) {
                    blocks.sort(Comparator.comparingInt(BatchProposal.BlockProposal::getBlock));
                    for (int i = 1; i < blocks.size(); i++) {
                        int gap = blocks.get(i).getBlock() - blocks.get(i-1).getBlock();
                        if (gap <= 2) { // Bloques consecutivos o con 1 bloque libre
                            score += 5000;  // Alto bonus para favorecer horarios compactos
                        } else {
                            score -= 8000;  // Penalización por gaps grandes
                        }
                    }
                }
            }
        }

        // Base satisfaction score
        score += proposal.getSatisfactionScore() * 10;

        // Capacity score - prefer rooms that closely match needed capacity
        int capacityDiff = Math.abs(proposal.getCapacity() - subject.getVacantes());
        score -= capacityDiff * 100;

        return score;
    }

    private boolean checkCampusConstraints(BatchProposal proposal, String currentCampus) {
        String proposedCampus = getCampusSala(proposal.getRoomCode());

        // If same campus, always valid
        if (proposedCampus.equals(currentCampus)) {
            return true;
        }

        // Check transitions for each day in the proposal
        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry : proposal.getDayProposals().entrySet()) {
            Day dia = entry.getKey();

            // Check if there's already a campus transition this day
            if (hasExistingTransitionInDay(dia)) {
                return false;
            }

            // Validate buffer blocks for each proposed block
            for (BatchProposal.BlockProposal blockProposal : entry.getValue()) {
                if (!validateTransitionBuffer(dia, blockProposal.getBlock(), proposal.getRoomCode())) {
                    return false;
                }
            }
        }

        return true;
    }

    // Updated to take individual parameters instead of Propuesta
    private boolean validateTransitionBuffer(Day dia, int bloque, String codigoSala) {
        String proposedCampus = getCampusSala(codigoSala);

        BloqueInfo prevBlock = profesor.getBloqueInfo(dia, bloque - 1);
        BloqueInfo nextBlock = profesor.getBloqueInfo(dia, bloque + 1);

        // Check if there's at least one empty block between different campuses
        if (prevBlock != null && !prevBlock.getCampus().equals(proposedCampus)) {
            return profesor.isBlockAvailable(dia, bloque - 1);
        }

        if (nextBlock != null && !nextBlock.getCampus().equals(proposedCampus)) {
            return profesor.isBlockAvailable(dia, bloque + 1);
        }

        return true;
    }

    private boolean hasExistingTransitionInDay(Day dia) {
        String previousCampus = null;
        Map<String, List<Integer>> dayClasses = profesor.getBlocksByDay(dia);

        if (dayClasses == null || dayClasses.isEmpty()) {
            return false;
        }

        List<BloqueInfo> blocks = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : dayClasses.entrySet()) {
            for (Integer bloque : entry.getValue()) {
                BloqueInfo info = profesor.getBloqueInfo(dia, bloque);
                if (info != null) {
                    blocks.add(info);
                }
            }
        }

        blocks.sort(Comparator.comparingInt(BloqueInfo::getBloque));

        for (BloqueInfo block : blocks) {
            if (previousCampus != null && !previousCampus.equals(block.getCampus())) {
                return true;
            }
            previousCampus = block.getCampus();
        }

        return false;
    }

    private String getCampusSala(String codigoSala) {
        return codigoSala.startsWith("KAU") ? "Kaufmann" : "Playa Brava";
    }

    private int calculateTotalScore(
            BatchProposal proposal,
            Asignatura currentSubject,
            String currentCampus,
            int currentNivel,
            boolean needsMeetingRoom,
            Map<Day, Integer> blocksPerDay,
            String mostUsedRoom,
            Map<String, Integer> roomUsage,
            Map<Day, List<Integer>> currentSchedule) {

        // Calculate base scores
        calculateSatisfactionScores(proposal, currentSubject, currentCampus,
                currentNivel, currentSchedule);

        int totalScore = calculateProposalScore(proposal, currentCampus,
                currentNivel, currentSubject);

        // Apply room type scoring with more flexibility
        totalScore = applyMeetingRoomScore(totalScore, proposal, needsMeetingRoom,
                currentSubject);

        // Reduce other penalties to make more assignments viable
        totalScore = applyDayBasedScoring(totalScore, proposal, currentCampus,
                blocksPerDay, mostUsedRoom, roomUsage);

        // Ensure minimum viable score
        return Math.max(totalScore, 1); // Always keep valid proposals
    }

    private int applyMeetingRoomScore(
            int totalScore,
            BatchProposal proposal,
            boolean needsMeetingRoom,
            Asignatura currentSubject) {

        boolean isMeetingRoom = proposal.getCapacity() < MEETING_ROOM_THRESHOLD;

        if (needsMeetingRoom) {
            if (isMeetingRoom) {
                // Perfect match - high bonus
                totalScore += 15000;

                // Additional bonus for optimal size match
                int sizeDiff = Math.abs(proposal.getCapacity() - currentSubject.getVacantes());
                if (sizeDiff <= 2) {
                    totalScore += 5000;
                }
            } else {
                // Using regular room for small class - apply penalty but don't reject
                int oversize = proposal.getCapacity() - currentSubject.getVacantes();
                totalScore -= oversize * 500;  // Progressive penalty for oversized rooms
            }
        }

        return totalScore;
    }
    private int applyDayBasedScoring(
            int totalScore,
            BatchProposal proposal,
            String currentCampus,
            Map<Day, Integer> blocksPerDay,
            String mostUsedRoom,
            Map<String, Integer> roomUsage) {

        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                proposal.getDayProposals().entrySet()) {
            Day proposalDay = entry.getKey();
            int dayUsage = blocksPerDay.getOrDefault(proposalDay, 0);

            // Day-based scoring
            totalScore -= dayUsage * 6000;  // Penalty for same-day assignments

            if (!blocksPerDay.containsKey(proposalDay)) {
                totalScore += 8000;  // Bonus for new days
            }

            // Room consistency scoring
            if (proposal.getRoomCode().equals(mostUsedRoom)) {
                totalScore += 7000;
            }

            // Apply campus and block penalties
            totalScore = applyCampusAndBlockPenalties(
                    totalScore, proposal, proposalDay, currentCampus,
                    dayUsage, roomUsage
            );
        }
        return totalScore;
    }

    private int applyCampusAndBlockPenalties(
            int totalScore,
            BatchProposal proposal,
            Day proposalDay,
            String currentCampus,
            int dayUsage,
            Map<String, Integer> roomUsage) {

        if (!proposal.getRoomCode().startsWith(currentCampus.substring(0, 1))) {
            totalScore -= 10000;

            for (BatchProposal.BlockProposal block : proposal.getDayProposals().get(proposalDay)) {
                BloqueInfo prevBlock = profesor.getBloqueInfo(proposalDay, block.getBlock() - 1);
                BloqueInfo nextBlock = profesor.getBloqueInfo(proposalDay, block.getBlock() + 1);

                if ((prevBlock != null && !prevBlock.getCampus().equals(currentCampus)) ||
                        (nextBlock != null && !nextBlock.getCampus().equals(currentCampus))) {
                    totalScore -= 8000;
                }
            }
        }

        int roomCount = roomUsage.getOrDefault(proposal.getRoomCode(), 0);
        totalScore -= roomCount * 1500;

        if (dayUsage >= 2) {
            totalScore -= 6000;
        }

        return totalScore;
    }

    private boolean validateGapsForProposal(BatchProposal proposal) {
        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                proposal.getDayProposals().entrySet()) {
            if (!validateConsecutiveGaps(entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean validateConsecutiveGaps(Day dia, List<BatchProposal.BlockProposal> proposedBlocks) {
        // Obtener el tipo de contrato
        TipoContrato tipoContrato = profesor.getTipoContrato();

        // Solo aplicar para jornada completa y media jornada
        if (tipoContrato == TipoContrato.JORNADA_PARCIAL) {
            return true;
        }

        // Obtener todos los bloques asignados para este día
        Map<String, List<Integer>> bloquesAsignados = profesor.getBlocksByDay(dia);
        List<Integer> allBlocks = new ArrayList<>();

        // Agregar bloques existentes
        bloquesAsignados.values().forEach(allBlocks::addAll);

        // Agregar bloques propuestos
        proposedBlocks.forEach(block -> allBlocks.add(block.getBlock()));

        // Ordenar bloques
        Collections.sort(allBlocks);

        // Verificar gaps
        int consecutiveGaps = 0;
        for (int i = 1; i < allBlocks.size(); i++) {
            int gap = allBlocks.get(i) - allBlocks.get(i-1) - 1;
            if (gap > 0) {
                consecutiveGaps += gap;
                if (consecutiveGaps > 1) {
                    return false; // Más de un bloque libre consecutivo
                }
            } else {
                consecutiveGaps = 0;
            }
        }

        return true;
    }

    private void calculateSatisfactionScores(
            BatchProposal proposal,
            Asignatura currentSubject,
            String currentCampus,
            int currentNivel,
            Map<Day, List<Integer>> currentSchedule) {

        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                proposal.getDayProposals().entrySet()) {
            for (BatchProposal.BlockProposal blockProposal : entry.getValue()) {
                int satisfaction = TimetablingEvaluator.calculateSatisfaction(
                        proposal.getCapacity(),
                        currentSubject.getVacantes(),
                        currentNivel,
                        proposal.getCampus(),
                        currentCampus,
                        blockProposal.getBlock(),
                        currentSchedule,
                        profesor.getTipoContrato(),
                        currentSubject.getActividad()
                );
                proposal.setSatisfactionScore(satisfaction);
            }
        }
    }
}
