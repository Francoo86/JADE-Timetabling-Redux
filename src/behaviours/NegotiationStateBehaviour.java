package behaviours;

import agentes.AgenteProfesor;
import constants.BlockOptimization;
import constants.BlockScore;
import constants.Commons;
import constants.Messages;
import constants.enums.Day;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import objetos.Asignatura;
import objetos.BloqueInfo;
import objetos.Propuesta;
import objetos.AssignationData;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class NegotiationStateBehaviour extends TickerBehaviour {
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<Propuesta> propuestas;
    private NegotiationState currentState;
    private long proposalTimeout;
    private int retryCount = 0;
    //private static final int MAX_RETRIES = 10;
    private boolean proposalReceived = false;
    private final AssignationData assignationData;
    private int bloquesPendientes = 0;
    private static final long TIMEOUT_PROPUESTA = 500; // 5 seconds

    public enum NegotiationState {
        SETUP,
        COLLECTING_PROPOSALS,
        EVALUATING_PROPOSALS,
        FINISHED
    }

    public NegotiationStateBehaviour(AgenteProfesor profesor, long period, ConcurrentLinkedQueue<Propuesta> propuestas) {
        super(profesor, period);
        this.profesor = profesor;
        this.propuestas = propuestas;
        this.currentState = NegotiationState.SETUP;
        this.assignationData = new AssignationData();
    }

    public synchronized void notifyProposalReceived() {
        this.proposalReceived = true;
    }

    @Override
    protected void onTick() {
        System.out.println(myAgent.getLocalName() + "MSG Pendientes: " + myAgent.getCurQueueSize());
        switch (currentState) {
            case SETUP:
                handleSetupState();
                break;
            case COLLECTING_PROPOSALS:
                handleCollectingState();
                break;
            case EVALUATING_PROPOSALS:
                handleEvaluatingState();
                break;
            case FINISHED:
                stop();
                break;
        }
    }

    private void handleSetupState() {
        if (!profesor.canUseMoreSubjects()) {
            currentState = NegotiationState.FINISHED;
            profesor.finalizarNegociaciones();
            return;
        }

        Asignatura currentSubject = profesor.getCurrentSubject();
        if (currentSubject != null) {
            bloquesPendientes = currentSubject.getHoras();
            assignationData.clear();

            // Add logging here
            System.out.printf("[SETUP] Starting assignment for %s (Code: %s) - Required hours: %d%n",
                    currentSubject.getNombre(),
                    currentSubject.getCodigoAsignatura(),
                    currentSubject.getHoras());

//            System.out.println("Profesor " + profesor.getNombre() + " iniciando negociación para " +
//                    currentSubject.getNombre() + " (" + bloquesPendientes + " horas)");
            sendProposalRequests();
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
            currentState = NegotiationState.COLLECTING_PROPOSALS;
            proposalReceived = false;
        } else {
//            System.out.println("Error: No hay asignatura actual para " + profesor.getNombre());
            currentState = NegotiationState.FINISHED;
        }
    }

    private void handleEvaluatingState() {
        List<Propuesta> currentProposals = new ArrayList<>();
        while (!propuestas.isEmpty()) {
            Propuesta p = propuestas.poll();
            if (p != null) {
                currentProposals.add(p);
            }
        }

        // Filter and sort proposals based on constraints
        //FIXME: Dont use this.
        List<Propuesta> validProposals = filterAndSortProposals(currentProposals);

        //ACA TOCA CAMBIAR EL METODO DE ASIGNACION
        if (!validProposals.isEmpty() && tryAssignBestProposal(validProposals)) {
            retryCount = 0;
            if (bloquesPendientes == 0) {
                profesor.moveToNextSubject();
                currentState = NegotiationState.SETUP;
            } else {
                sendProposalRequests();
                proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
                currentState = NegotiationState.COLLECTING_PROPOSALS;
            }
        } else {
            handleProposalFailure();
        }
    }

    private boolean tryAssignBestProposal(List<Propuesta> validProposals) {
        return tryAssignBatchProposals(validProposals);
    }

    private List<Propuesta> filterAndSortProposals(List<Propuesta> proposals) {
        if (proposals.isEmpty()) {
            return Collections.emptyList();
        }

        Asignatura currentSubject = profesor.getCurrentSubject();
        String currentCampus = currentSubject.getCampus();
        int currentNivel = currentSubject.getNivel();
        String currentAsignaturaNombre = currentSubject.getNombre();

        // Track current schedule state
        Map<Day, List<Integer>> currentSchedule = profesor.getBlocksBySubject(currentAsignaturaNombre);
        Map<String, Integer> roomUsage = new HashMap<>();
        Map<Day, Integer> blocksPerDay = new HashMap<>();
        String mostUsedRoom = null;

        // Calculate current room and day usage
        for (Map.Entry<Day, List<Integer>> entry : currentSchedule.entrySet()) {
            Day day = entry.getKey();
            List<Integer> blocks = entry.getValue();
            blocksPerDay.put(day, blocks.size());

            // Get room usage and track most used room
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

        // Pre-calculate common values
        boolean isOddYear = currentNivel % 2 == 1;
        int targetSize = proposals.size();
        int daysUsed = blocksPerDay.size();

        ArrayList<ProposalScore> scoredProposals = new ArrayList<>(targetSize);

        // Filter and score proposals
        for (Propuesta proposal : proposals) {
            if (!isValidProposalFast(proposal, currentSubject, isOddYear, currentAsignaturaNombre)) {
                continue;
            }

            // Calculate base score
            int score = calculateProposalScore(proposal, currentCampus, currentNivel, currentSubject);

            Day proposalDay = proposal.getDia();
            String proposalRoom = proposal.getCodigo();
            int proposalBlock = proposal.getBloque();

            // Strong penalty for same-day assignments
            int dayUsage = blocksPerDay.getOrDefault(proposalDay, 0);
            score -= dayUsage * 6000;  // Increased penalty

            // Bonus for distributing across week
            if (!blocksPerDay.containsKey(proposalDay)) {
                score += 8000;  // Encourage using new days up to 3 days
            }

            // Consecutive block handling
            List<Integer> dayBlocks = currentSchedule.getOrDefault(proposalDay, Collections.emptyList());
            boolean hasConsecutive = dayBlocks.stream()
                    .anyMatch(block -> Math.abs(block - proposalBlock) == 1);
            if (hasConsecutive && dayUsage < 2) {  // Only reward consecutive if not too many blocks that day
                score += 5000;
            }

            // Room consistency bonus
            if (proposalRoom.equals(mostUsedRoom)) {
                score += 7000;
            }

            if (!proposalRoom.startsWith(currentCampus.substring(0, 1))) {
                score -= 10000;  // Increase from 8000
            }

            // Penalize room changes
            int roomCount = roomUsage.getOrDefault(proposalRoom, 0);
            score -= roomCount * 1500;  // Increased penalty

            // Time of day preference
            if (isOddYear && proposalBlock <= 4) {
                score += 3500;
            } else if (!isOddYear && proposalBlock >= 5) {
                score += 3500;
            }

            // Stronger penalty for block 9
            if (proposalBlock == Commons.MAX_BLOQUE_DIURNO) {
                score -= 3000;
            }

            // Stronger campus transition penalty
            if (!proposalRoom.startsWith(currentCampus.substring(0, 1))) {
                BloqueInfo prevBlock = profesor.getBloqueInfo(proposalDay, proposalBlock - 1);
                BloqueInfo nextBlock = profesor.getBloqueInfo(proposalDay, proposalBlock + 1);

                if ((prevBlock != null && !prevBlock.getCampus().equals(currentCampus)) ||
                        (nextBlock != null && !nextBlock.getCampus().equals(currentCampus))) {
                    score -= 8000;  // Doubled penalty
                }
            }

            // Penalty for too many blocks in one day
            if (dayUsage >= 2) {
                score -= 6000;  // Strong penalty for more than 2 blocks per day
            }

            scoredProposals.add(new ProposalScore(proposal, score));
        }

        if (scoredProposals.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort by final scores
        scoredProposals.sort((ps1, ps2) -> ps2.score - ps1.score);

        // Convert to List<Propuesta>
        return scoredProposals.stream()
                .map(ps -> ps.proposal)
                .collect(Collectors.toList());
    }

    // Lightweight class to hold proposal and its score
    private static class ProposalScore {
        final Propuesta proposal;
        final int score;

        ProposalScore(Propuesta proposal, int score) {
            this.proposal = proposal;
            this.score = score;
        }
    }

    // Optimized validation method combining multiple checks
    private boolean isValidProposalFast(Propuesta propuesta, Asignatura asignatura,
                                        boolean isOddYear, String asignaturaNombre) {
        // Check basic time constraints first (fastest checks)
        int bloque = propuesta.getBloque();
        if (bloque < 1 || bloque > Commons.MAX_BLOQUE_DIURNO) {
            return false;
        }

        // Check block 9 constraint
        if (bloque == Commons.MAX_BLOQUE_DIURNO && bloquesPendientes % 2 == 0) {
            return false;
        }

        // Check year-based constraints
        if (isOddYear) {
            if (bloque > 4 && bloque != Commons.MAX_BLOQUE_DIURNO) {
                return false;
            }
        } else {
            if (bloque < 5 && propuesta.getSatisfaccion() < 8) {
                return false;
            }
        }

        // Check block limit per day
        Map<String, List<Integer>> asignaturasEnDia = profesor.getBlocksByDay(propuesta.getDia());
        List<Integer> bloques = asignaturasEnDia.get(asignaturaNombre);
        if (bloques != null && bloques.size() >= 2) {
            return false;
        }

        // More expensive checks
        if (!checkCampusConstraints(propuesta, asignatura.getCampus())) {
            return false;
        }

        // Check consecutive blocks only if needed
        return bloquesPendientes < 2 || hasConsecutiveBlockAvailable(propuesta);
    }

    private int calculateProposalScore(Propuesta propuesta, String preferredRoom,
                                       String preferredCampus, boolean isOddYear) {
        int score = 0;

        // Preferred room bonus (highest priority)
        if (preferredRoom != null && propuesta.getCodigo().equals(preferredRoom)) {
            score += 10000;
        }

        // Campus consistency (high priority)
        if (!getCampusSala(propuesta.getCodigo()).equals(preferredCampus)) {
            score -= 10000;
        }

        // Time preference based on year
        if (isOddYear) {
            if (propuesta.getBloque() <= 4) score += 3000;
        } else {
            if (propuesta.getBloque() >= 5) score += 3000;
        }

        // Room availability bonus (can be calculated outside and passed in if performance is a concern)
        // score += roomAvailability * 100;

        // Satisfaction score
        score += propuesta.getSatisfaccion() * 10;

        return score;
    }

    // Optimized score calculation
    private int calculateProposalScore(Propuesta proposal, String currentCampus,
                                       int nivel, Asignatura subject) {
        int score = 0;

        // Highest priority: Consecutive block bonus
        List<Integer> existingBlocks = profesor.getBlocksBySubject(subject.getNombre())
                .getOrDefault(proposal.getDia(), Collections.emptyList());
        if (!existingBlocks.isEmpty()) {
            for (int existingBlock : existingBlocks) {
                if (Math.abs(existingBlock - proposal.getBloque()) == 1) {
                    score += 20000; // Increased weight for consecutive blocks
                    break;
                }
            }
        }

        // Second priority: Time of day preference based on year
        boolean isOddYear = nivel % 2 == 1;
        int bloque = proposal.getBloque();
        if (isOddYear) {
            score += (bloque <= 4) ? 10000 : 0; // Morning preference
        } else {
            score += (bloque >= 5) ? 10000 : 0; // Afternoon preference
        }

        // Third priority: Campus consistency
        if (getCampusSala(proposal.getCodigo()).equals(currentCampus)) {
            score += 5000;
        }

        // Fourth priority: Even distribution across days
        Map<Day, List<Integer>> currentSchedule = profesor.getBlocksBySubject(subject.getNombre());
        int blocksOnDay = currentSchedule.getOrDefault(proposal.getDia(), Collections.emptyList()).size();
        score -= blocksOnDay * 1000; // Penalty for concentrating too many blocks on same day

        // Fifth priority: Block 9 avoidance
        if (proposal.getBloque() == Commons.MAX_BLOQUE_DIURNO) {
            score -= 3000; // Penalty for using block 9
        }

        // Sixth priority: Room consistency
        String currentRoom = null;
        for (Map.Entry<Day, List<Integer>> entry : currentSchedule.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                currentRoom = getCampusSala(proposal.getCodigo());
                break;
            }
        }
        if (currentRoom != null && currentRoom.equals(proposal.getCodigo())) {
            score += 2000; // Bonus for using same room
        }

        // Base satisfaction score
        score += proposal.getSatisfaccion() * 100;

        return score;
    }

    private boolean isValidProposal(Propuesta propuesta, Asignatura asignatura) {
        // Check basic time constraints
        if (!checkTimeConstraints(propuesta)) {
            return false;
        }

        // Check campus constraints
        if (!checkCampusConstraints(propuesta, asignatura.getCampus())) {
            return false;
        }

        // Check year-based constraints
        if (!checkYearBasedConstraints(propuesta, asignatura.getNivel())) {
            return false;
        }

        // Check block 9 constraint
        if (propuesta.getBloque() == Commons.MAX_BLOQUE_DIURNO && bloquesPendientes % 2 == 0) {
            return false;
        }

        // Check block limit per day
        if (countBlocksPerDay(propuesta.getDia(), asignatura.getNombre()) >= 2) {
            return false;
        }

        // Check consecutive blocks availability if needed
        if (bloquesPendientes >= 2 && !hasConsecutiveBlockAvailable(propuesta)) {
            return false;
        }

        return true;
    }

    private int countBlocksPerDay(Day dia, String nombreAsignatura) {
        Map<String, List<Integer>> asignaturasEnDia = profesor.getBlocksByDay(dia);
        List<Integer> bloques = asignaturasEnDia.getOrDefault(nombreAsignatura, new ArrayList<>());
        return bloques.size();
    }

    private boolean checkTimeConstraints(Propuesta propuesta) {
        int bloque = propuesta.getBloque();
        return bloque >= 1 && bloque <= Commons.MAX_BLOQUE_DIURNO;
    }

    private boolean checkCampusConstraints(Propuesta propuesta, String currentCampus) {
        Day dia = propuesta.getDia();
        String proposedCampus = getCampusSala(propuesta.getCodigo());

        // If same campus, always valid
        if (proposedCampus.equals(currentCampus)) {
            return true;
        }

        // Check if there's already a campus transition this day
        if (hasExistingTransitionInDay(dia)) {
            return false;
        }

        // Validate buffer block for campus transition
        return validateTransitionBuffer(propuesta);
    }

    private boolean checkYearBasedConstraints(Propuesta propuesta, int nivel) {
        // First, third, and fifth years prefer morning blocks (1-4)
        // Second, fourth, and sixth years prefer afternoon blocks (5-9)
        boolean isOddYear = nivel % 2 == 1;
        int bloque = propuesta.getBloque();

        if (isOddYear) {
            return bloque <= 4 || bloque == Commons.MAX_BLOQUE_DIURNO;
        } else {
            return bloque >= 5 || propuesta.getSatisfaccion() >= 8;
        }
    }

    private boolean hasConsecutiveBlockAvailable(Propuesta propuesta) {
        Day dia = propuesta.getDia();
        int bloque = propuesta.getBloque();

        boolean previousBlockAvailable = bloque > 1 &&
                profesor.isBlockAvailable(dia, bloque - 1);
        boolean nextBlockAvailable = bloque < Commons.MAX_BLOQUE_DIURNO &&
                profesor.isBlockAvailable(dia, bloque + 1);

        return previousBlockAvailable || nextBlockAvailable;
    }

    private int compareProposals(Propuesta p1, Propuesta p2,
                                 Asignatura subject, String currentCampus, int nivel) {
        // First priority: Block optimization score
        BlockScore score1 = BlockOptimization.getInstance().evaluateBlock(
                currentCampus, nivel, p1.getBloque(), p1.getDia(),
                profesor.getBlocksBySubject(subject.getNombre())
        );
        BlockScore score2 = BlockOptimization.getInstance().evaluateBlock(
                currentCampus, nivel, p2.getBloque(), p2.getDia(),
                profesor.getBlocksBySubject(subject.getNombre())
        );

        if (score1.getScore() != score2.getScore()) {
            return score2.getScore() - score1.getScore();
        }

        // Second priority: Campus transition score
        int transitionScore1 = evaluateCampusTransition(p1, currentCampus);
        int transitionScore2 = evaluateCampusTransition(p2, currentCampus);

        if (transitionScore1 != transitionScore2) {
            return transitionScore2 - transitionScore1;
        }

        // Third priority: Professor satisfaction
        return p2.getSatisfaccion() - p1.getSatisfaccion();
    }

    private int evaluateCampusTransition(Propuesta propuesta, String currentCampus) {
        String proposedCampus = getCampusSala(propuesta.getCodigo());

        if (proposedCampus.equals(currentCampus)) {
            return 100;
        }

        if (hasExistingTransitionInDay(propuesta.getDia())) {
            return 0;
        }

        // Check surrounding blocks for transitions
        BloqueInfo prevBlock = profesor.getBloqueInfo(propuesta.getDia(), propuesta.getBloque() - 1);
        BloqueInfo nextBlock = profesor.getBloqueInfo(propuesta.getDia(), propuesta.getBloque() + 1);

        if (prevBlock != null && !prevBlock.getCampus().equals(proposedCampus)) {
            return 25;
        }

        if (nextBlock != null && !nextBlock.getCampus().equals(proposedCampus)) {
            return 25;
        }

        return 75;
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

        Collections.sort(blocks, Comparator.comparingInt(BloqueInfo::getBloque));

        for (BloqueInfo block : blocks) {
            if (previousCampus != null && !previousCampus.equals(block.getCampus())) {
                return true;
            }
            previousCampus = block.getCampus();
        }

        return false;
    }

    private boolean validateTransitionBuffer(Propuesta propuesta) {
        Day dia = propuesta.getDia();
        int bloque = propuesta.getBloque();
        String proposedCampus = getCampusSala(propuesta.getCodigo());

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

    private String getCampusSala(String codigoSala) {
        return codigoSala.startsWith("KAU") ? "Kaufmann" : "Playa Brava";
    }

    private static final int MAX_RETRIES = 3;

    private void handleNoProposals() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (bloquesPendientes == profesor.getCurrentSubject().getHoras()) {
                // If no blocks assigned yet for this subject, move to next subject
                profesor.moveToNextSubject();
            } else {
                // If some blocks assigned, try different room
                assignationData.setSalaAsignada(null);
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
            // Add exponential backoff to avoid overwhelming the system
            long backoffTime = (long) Math.pow(2, retryCount) * 1000; // 2^retry seconds
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
            sendProposalRequests();
        }
    }

    private void handleProposalFailure() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (assignationData.hasSalaAsignada()) {
                // Try different room if current one isn't working
                assignationData.setSalaAsignada(null);
            } else {
                // If we've tried different rooms without success, move on
                profesor.moveToNextSubject();
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
            currentState = NegotiationState.COLLECTING_PROPOSALS;
            // Add exponential backoff here too
            long backoffTime = (long) Math.pow(2, retryCount) * 1000;
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
            sendProposalRequests();
        }
    }

    private void handleCollectingState() {
        // If we received proposals, evaluate immediately
        if (proposalReceived) {
            if (!propuestas.isEmpty()) {
                currentState = NegotiationState.EVALUATING_PROPOSALS;
                return;
            }
        }

        // If we hit timeout
        if (System.currentTimeMillis() > proposalTimeout) {
            if (!propuestas.isEmpty()) {
                currentState = NegotiationState.EVALUATING_PROPOSALS;
            } else {
                handleNoProposals();
            }
        }
    }

    private Set<String> assignedSlots = new HashSet<>();

    private boolean tryAssignBatchProposals(List<Propuesta> validProposals) {
        // Validate current state
        Asignatura currentSubject = profesor.getCurrentSubject();
        int requiredHours = profesor.getCurrentSubjectRequiredHours();

        // Early exit if no more hours needed
        if (bloquesPendientes <= 0) {
            System.out.println("No more hours needed for current subject: " + currentSubject.getNombre());
            return true;
        }

        // Group proposals by day
        Map<Day, List<Propuesta>> proposalsByDay = validProposals.stream()
                .collect(Collectors.groupingBy(Propuesta::getDia));

        // Sort and limit days
        List<Day> sortedDays = proposalsByDay.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().size() - e1.getValue().size())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        boolean anySuccess = false;
        int assignedHours = 0;
        int maxHoursForInstance = requiredHours;

        // Track preferences
        Map<Day, Integer> blocksPerDay = new HashMap<>();
        String preferredRoom = null;
        String preferredCampus = currentSubject.getCampus();
        boolean isOddYear = currentSubject.getNivel() % 2 == 1;

        // Process each day
        for (Day day : sortedDays) {
            // Stop if we've assigned all needed hours
            if (assignedHours >= maxHoursForInstance) {
                break;
            }

            List<Propuesta> dayProposals = proposalsByDay.get(day);
            if (dayProposals.isEmpty()) continue;

            // Enforce 2 blocks per day limit
            if (blocksPerDay.getOrDefault(day, 0) >= 2) continue;

            Set<String> availableRooms = dayProposals.stream()
                    .map(Propuesta::getCodigo)
                    .collect(Collectors.toSet());

            // Sort proposals by priority
            String finalPreferredRoom = preferredRoom;
            dayProposals.sort((p1, p2) -> {
                int score1 = calculateProposalScore(p1, finalPreferredRoom, preferredCampus, isOddYear);
                int score2 = calculateProposalScore(p2, finalPreferredRoom, preferredCampus, isOddYear);
                return Integer.compare(score2, score1);
            });

            List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();
            int lastBlock = -1;
            String lastRoom = null;

            for (Propuesta propuesta : dayProposals) {
                // Check if we've reached the required hours
                if (assignedHours >= maxHoursForInstance ||
                        blocksPerDay.getOrDefault(day, 0) >= 2) {
                    break;
                }

                // Check if slot is already assigned
                String slotKey = day.toString() + "-" + propuesta.getBloque();
                if (assignedSlots.contains(slotKey)) {
                    continue;
                }

                // Ensure consecutive blocks
                if (lastBlock != -1) {
                    if (Math.abs(propuesta.getBloque() - lastBlock) != 1) continue;
                    if (!propuesta.getCodigo().equals(lastRoom) &&
                            !propuesta.getCodigo().substring(0, 1).equals(lastRoom.substring(0, 1))) {
                        continue;
                    }
                }

                // Room consistency check
                if (preferredRoom == null) {
                    preferredRoom = propuesta.getCodigo();
                } else if (!propuesta.getCodigo().equals(preferredRoom)) {
                    if (!propuesta.getCodigo().substring(0, 1).equals(preferredRoom.substring(0, 1)) ||
                            availableRooms.contains(preferredRoom)) {
                        continue;
                    }
                }

                // Block 9 handling
                if (propuesta.getBloque() == Commons.MAX_BLOQUE_DIURNO &&
                        bloquesPendientes % 2 == 0) {
                    continue;
                }

                // Final availability check
                if (profesor.isBlockAvailable(propuesta.getDia(), propuesta.getBloque())) {
                    requests.add(new BatchAssignmentRequest.AssignmentRequest(
                            propuesta.getDia(),
                            propuesta.getBloque(),
                            currentSubject.getNombre(),
                            propuesta.getSatisfaccion(),
                            propuesta.getCodigo(),
                            currentSubject.getVacantes()
                    ));

                    assignedSlots.add(slotKey);
                    lastBlock = propuesta.getBloque();
                    lastRoom = propuesta.getCodigo();
                    assignedHours++;
                    blocksPerDay.merge(day, 1, Integer::sum);
                }
            }

            // Process batch assignment
            if (!requests.isEmpty()) {
                try {
                    // Validate we won't exceed required hours
                    if (bloquesPendientes - requests.size() < 0) {
                        System.out.println("WARNING: Would exceed required hours - skipping batch");
                        continue;
                    }

                    ACLMessage batchAccept = dayProposals.get(0).getMensaje().createReply();
                    batchAccept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    batchAccept.setContentObject(new BatchAssignmentRequest(requests));
                    profesor.send(batchAccept);

                    MessageTemplate mt = MessageTemplate.and(
                            MessageTemplate.MatchSender(dayProposals.get(0).getMensaje().getSender()),
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                    );

                    if (waitForConfirmation(mt, requests)) {
                        anySuccess = true;
                        System.out.printf("[BATCH] Subject %s (Code: %s): Assigned %d/%d hours on %s (Pending: %d)%n",
                                currentSubject.getNombre(),
                                currentSubject.getCodigoAsignatura(),
                                requests.size(),
                                requiredHours,
                                day,
                                bloquesPendientes);
                    }
                } catch (IOException e) {
                    System.err.printf("Error processing batch assignment for %s: %s%n",
                            currentSubject.getNombre(), e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // Success only if we assigned exactly what was needed
        return anySuccess && assignedHours <= requiredHours && bloquesPendientes == 0;
    }

    // Helper method to handle confirmation waiting
    private boolean waitForConfirmation(MessageTemplate mt, List<BatchAssignmentRequest.AssignmentRequest> requests) {
        if (bloquesPendientes - requests.size() < 0) {
            System.out.println("WARNING: Assignment would exceed required hours");
            return false;
        }

        long startTime = System.currentTimeMillis();
        long timeout = 1000;

        while (System.currentTimeMillis() - startTime < timeout) {
            ACLMessage confirm = myAgent.receive(mt);
            if (confirm != null) {
                try {
                    BatchAssignmentConfirmation confirmation =
                            (BatchAssignmentConfirmation) confirm.getContentObject();

                    for (BatchAssignmentConfirmation.ConfirmedAssignment assignment :
                            confirmation.getConfirmedAssignments()) {
                        profesor.updateScheduleInfo(
                                assignment.getDay(),
                                assignment.getClassroomCode(),
                                assignment.getBlock(),
                                profesor.getCurrentSubject().getNombre(),
                                assignment.getSatisfaction()
                        );

                        bloquesPendientes--;
                        assignationData.assign(
                                assignment.getDay(),
                                assignment.getClassroomCode(),
                                assignment.getBlock()
                        );
                    }
                    return true;
                } catch (UnreadableException e) {
                    System.err.println("Error reading confirmation: " + e.getMessage());
                    e.printStackTrace();
                    return false;
                }
            }
            block(50);
        }
        return false;
    }
    //FIXME: Esto envia no por bloques, si no TODAS las propuestas aceptadas
    /*private boolean tryAssignProposal(Propuesta propuesta) {
        Day dia = propuesta.getDia();
        int bloque = propuesta.getBloque();

        if (!profesor.isBlockAvailable(dia, bloque)) {
            return false;
        }

        // Send acceptance message
        ACLMessage accept = propuesta.getMensaje().createReply();
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);

        Asignatura currentSubject = profesor.getCurrentSubject();
        accept.setContent(String.format("%s,%d,%s,%d,%s,%d",
                dia,
                bloque,
                currentSubject.getNombre(),
                propuesta.getSatisfaccion(),
                propuesta.getCodigo(),
                currentSubject.getVacantes()));

        profesor.send(accept);

        // Wait for confirmation with retry
        long startTime = System.currentTimeMillis();
        long timeout = 1000; // 1 second timeout for confirmation

        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchSender(propuesta.getMensaje().getSender()),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
        );

        while (System.currentTimeMillis() - startTime < timeout) {
            ACLMessage confirm = myAgent.receive(mt);
            if (confirm != null) {
                //Aca le pasamos asignacionsala?
                profesor.updateScheduleInfo(
                        dia,
                        propuesta.getCodigo(),
                        bloque,
                        currentSubject.getNombre(),
                        propuesta.getSatisfaccion()
                );

                // Update negotiation state
                bloquesPendientes--;
                assignationData.assign(dia, propuesta.getCodigo(), bloque);

//                System.out.printf("Profesor %s: Asignado bloque %d del día %s en sala %s para %s (quedan %d horas)%n",
//                        profesor.getNombre(), bloque, dia, propuesta.getCodigo(),
//                        currentSubject.getNombre(), bloquesPendientes);

                return true;
            }
            block(100); // Block for 100ms between checks
        }

//        System.out.println("Timeout esperando confirmación de sala " + propuesta.getCodigo());
        return false;
    }*/

    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    private void sendProposalRequests() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("sala");
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(profesor, template);
            if (result.length == 0) {
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            for (DFAgentDescription dfd : result) {
                cfp.addReceiver(dfd.getName());
            }

            // Enhanced CFP content with more context
            String solicitudInfo = String.format("%s,%d,%d,%s,%d,%s,%s,%d",
                    sanitizeSubjectName(currentSubject.getNombre()),
                    currentSubject.getVacantes(),
                    currentSubject.getNivel(),
                    currentSubject.getCampus(),
                    bloquesPendientes,
                    assignationData.getSalaAsignada(),
                    assignationData.getUltimoDiaAsignado() != null ?
                            assignationData.getUltimoDiaAsignado().toString() : "",
                    assignationData.getUltimoBloqueAsignado());

            cfp.setContent(solicitudInfo);
            cfp.setConversationId("neg-" + profesor.getNombre() + "-" + bloquesPendientes);
            profesor.send(cfp);

        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}