package behaviours;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.Commons;
import constants.enums.Day;
import constants.enums.TipoContrato;
import debugscreens.ProfessorDebugViewer;
import df.DFCache;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import objetos.Asignatura;
import objetos.BloqueInfo;
import objetos.AssignationData;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
import objetos.helper.BatchProposal;
import service.TimetablingEvaluator;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class NegotiationStateBehaviour extends TickerBehaviour {
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<BatchProposal> propuestas;
    private NegotiationState currentState;
    private long proposalTimeout;
    private int retryCount = 0;
    //private static final int MAX_RETRIES = 10;
    private boolean proposalReceived = false;
    private final AssignationData assignationData;
    private int bloquesPendientes = 0;
    private static final long TIMEOUT_PROPUESTA = 1000; // 5 seconds

    private long negotiationStartTime;
    private final Map<String, Long> subjectNegotiationTimes = new HashMap<>();

    public enum NegotiationState {
        SETUP,
        COLLECTING_PROPOSALS,
        EVALUATING_PROPOSALS,
        FINISHED
    }

    public NegotiationStateBehaviour(AgenteProfesor profesor, long period, ConcurrentLinkedQueue<BatchProposal> propuestas) {
        super(profesor, period);
        this.profesor = profesor;
        this.propuestas = propuestas;
        this.currentState = NegotiationState.SETUP;
        this.assignationData = new AssignationData();
        this.negotiationStartTime = System.currentTimeMillis();
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
        AtomicReference<ProfessorDebugViewer> debugWindow = new AtomicReference<>(profesor.getDebugWindow());
        if (debugWindow.get() == null) {
            /*
            SwingUtilities.invokeLater(() -> {
                ProfessorDebugViewer newWindow = new ProfessorDebugViewer(profesor.getNombre());
                debugWindow.set(newWindow);
                profesor.setDebugWindow(newWindow);  // Update the agent's reference
                newWindow.setVisible(true);
            });*/
        }
        if (!profesor.canUseMoreSubjects()) {
            currentState = NegotiationState.FINISHED;
            long totalTime = System.currentTimeMillis() - negotiationStartTime;
            System.out.printf("[TIMING] Professor %s completed all negotiations in %d ms%n",
                    profesor.getNombre(), totalTime);

            // Print individual subject times
            subjectNegotiationTimes.forEach((subject, time) ->
                    System.out.printf("[TIMING] Subject %s negotiation took %d ms%n", subject, time));

            profesor.finalizarNegociaciones();
            return;
        }

        Asignatura currentSubject = profesor.getCurrentSubject();
        if (currentSubject != null) {
            bloquesPendientes = currentSubject.getHoras();
            assignationData.clear();

            negotiationStartTime = System.currentTimeMillis();

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
        List<BatchProposal> currentBatchProposals = new ArrayList<>();
        while (!propuestas.isEmpty()) {
            BatchProposal bp = propuestas.poll();
            if (bp != null) {
                currentBatchProposals.add(bp);
            }
        }

        List<BatchProposal> validProposals = filterAndSortProposals(currentBatchProposals);

        if (!validProposals.isEmpty() && tryAssignBatchProposals(validProposals)) {
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

    private List<BatchProposal> filterAndSortProposals(List<BatchProposal> proposals) {
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

        ArrayList<BatchProposalScore> scoredProposals = new ArrayList<>();

        // Filter and score proposals
        for (BatchProposal proposal : proposals) {
            if (!isValidProposalFast(proposal, currentSubject, isOddYear, currentAsignaturaNombre)) {
                continue;
            }

            // Calculate satisfaction for each block in the proposal
            for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry : proposal.getDayProposals().entrySet()) {
                for (BatchProposal.BlockProposal blockProposal : entry.getValue()) {
                    // Calculate satisfaction using TimetablingEvaluator
                    int satisfaction = TimetablingEvaluator.calculateSatisfaction(
                            proposal.getCapacity(),
                            currentSubject.getVacantes(),
                            currentNivel,
                            proposal.getCampus(),
                            currentCampus,
                            blockProposal.getBlock(),
                            currentSchedule,
                            profesor.getTipoContrato()
                    );
                    proposal.setSatisfactionScore(satisfaction);
                }
            }

            Map<Day, List<BatchProposal.BlockProposal>> dayProposals = proposal.getDayProposals();
            int totalScore = calculateProposalScore(proposal, currentCampus, currentNivel, currentSubject);

            for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry : dayProposals.entrySet()) {
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

                if (!proposal.getRoomCode().startsWith(currentCampus.substring(0, 1))) {
                    totalScore -= 10000;
                }

                // Penalize room changes
                int roomCount = roomUsage.getOrDefault(proposal.getRoomCode(), 0);
                totalScore -= roomCount * 1500;

                // Campus transition penalty
                if (!proposal.getRoomCode().startsWith(currentCampus.substring(0, 1))) {
                    for (BatchProposal.BlockProposal block : entry.getValue()) {
                        BloqueInfo prevBlock = profesor.getBloqueInfo(proposalDay, block.getBlock() - 1);
                        BloqueInfo nextBlock = profesor.getBloqueInfo(proposalDay, block.getBlock() + 1);

                        if ((prevBlock != null && !prevBlock.getCampus().equals(currentCampus)) ||
                                (nextBlock != null && !nextBlock.getCampus().equals(currentCampus))) {
                            totalScore -= 8000;
                        }
                    }
                }

                // Penalty for too many blocks in one day
                if (dayUsage >= 2) {
                    totalScore -= 6000;
                }
            }

            scoredProposals.add(new BatchProposalScore(proposal, totalScore));
        }

        if (scoredProposals.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort by final scores
        scoredProposals.sort((ps1, ps2) -> ps2.score - ps1.score);

        // Convert to List<BatchProposal>
        return scoredProposals.stream()
                .map(ps -> ps.proposal)
                .collect(Collectors.toList());
    }

    private static class BatchProposalScore {
        final BatchProposal proposal;
        final int score;

        BatchProposalScore(BatchProposal proposal, int score) {
            this.proposal = proposal;
            this.score = score;
        }
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

            // Check block limit per day
            Map<String, List<Integer>> asignaturasEnDia = profesor.getBlocksByDay(day);
            List<Integer> existingBlocks = asignaturasEnDia.get(asignaturaNombre);
            if (existingBlocks != null && existingBlocks.size() >= 2) {
                continue;
            }

            // Validate each block in the day
            for (BatchProposal.BlockProposal block : blocks) {
                int bloque = block.getBlock();

                // Basic time constraints
                if (bloque < 1 || bloque > Commons.MAX_BLOQUE_DIURNO) {
                    continue;
                }

                // Block 9 constraint
                if (bloque == Commons.MAX_BLOQUE_DIURNO && bloquesPendientes % 2 == 0) {
                    continue;
                }

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
            for (BatchProposal.BlockProposal block : entry.getValue()) {
                if (isOddYear) {
                    if (block.getBlock() <= 4) score += 3000;
                } else {
                    if (block.getBlock() >= 5) score += 3000;
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

    private boolean tryAssignBatchProposals(List<BatchProposal> batchProposals) {
        Asignatura currentSubject = profesor.getCurrentSubject();
        int requiredHours = currentSubject.getHoras();
        long batchStartTime = System.currentTimeMillis();
        if (bloquesPendientes <= 0 || bloquesPendientes > requiredHours) {
            System.out.printf("Invalid pending hours state: %d/%d for %s%n",
                    bloquesPendientes, requiredHours, currentSubject.getNombre());
            return false;
        }

        Map<Day, Integer> dailyAssignments = new HashMap<>();
        int totalAssigned = 0;

        // Process each batch proposal (which represents one room's available blocks)
        for (BatchProposal batchProposal : batchProposals) {
            long proposalStartTime = System.currentTimeMillis();
            List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();

            // Process each day's blocks in this room
            for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                    batchProposal.getDayProposals().entrySet()) {
                Day day = entry.getKey();

                // Skip if day already has 2 blocks
                if (dailyAssignments.getOrDefault(day, 0) >= 2) continue;

                // Process blocks for this day
                for (BatchProposal.BlockProposal block : entry.getValue()) {
                    // Stop if we've assigned all needed blocks
                    if (totalAssigned >= bloquesPendientes) break;

                    // Skip if block not available
                    if (!profesor.isBlockAvailable(day, block.getBlock())) continue;

                    requests.add(new BatchAssignmentRequest.AssignmentRequest(
                            day,
                            block.getBlock(),
                            currentSubject.getNombre(),
                            batchProposal.getSatisfactionScore(),
                            batchProposal.getRoomCode(),
                            currentSubject.getVacantes()
                    ));

                    totalAssigned++;
                    dailyAssignments.merge(day, 1, Integer::sum);
                }
            }

            // Send batch assignment if we have requests
            if (!requests.isEmpty()) {
                try {
                    if (sendBatchAssignment(requests, batchProposal.getOriginalMessage())) {
                        System.out.printf("Successfully assigned %d blocks in room %s for %s%n",
                                requests.size(), batchProposal.getRoomCode(),
                                currentSubject.getNombre());

                        long proposalTime = System.currentTimeMillis() - proposalStartTime;
                        System.out.printf("[TIMING] Room %s assignment took %d ms - Assigned %d blocks for %s%n",
                                batchProposal.getRoomCode(), proposalTime, requests.size(),
                                currentSubject.getNombre());
                    }
                } catch (Exception e) {
                    System.err.println("Error in batch assignment: " + e.getMessage());
                    return false;
                }
            }
        }

        long totalBatchTime = System.currentTimeMillis() - batchStartTime;
        System.out.printf("[TIMING] Total batch assignment time for %s: %d ms - Total blocks assigned: %d%n",
                currentSubject.getNombre(), totalBatchTime, totalAssigned);

        return totalAssigned > 0;
    }

    private boolean sendBatchAssignment(List<BatchAssignmentRequest.AssignmentRequest> requests,
                                        ACLMessage originalMsg) throws IOException {
        // Create batch request
        BatchAssignmentRequest batchRequest = new BatchAssignmentRequest(requests);

        // Send acceptance message
        ACLMessage batchAccept = originalMsg.createReply();
        batchAccept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        batchAccept.setContentObject(batchRequest);
        profesor.send(batchAccept);

        // Wait for confirmation
        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchSender(originalMsg.getSender()),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
        );

        return waitForConfirmation(mt, requests);
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

    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    private void sendProposalRequests() {
        try {
            List<DFAgentDescription> results = DFCache.search(profesor, AgenteSala.SERVICE_NAME);
            if (results.isEmpty()) {
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            if (currentSubject == null) {
                System.err.println("Warning: No current subject available for professor " + profesor.getNombre());
                return;
            }

            // Create CFP message once
            ACLMessage cfp = createCFPMessage(currentSubject);
            // Filter rooms before adding receivers
            results.stream()
                    .filter(room -> !canQuickReject(currentSubject, room))
                    .forEach(room -> cfp.addReceiver(room.getName()));

            profesor.send(cfp);

            // Send the message
            profesor.send(cfp);

        } catch (Exception e) {
            System.err.println("Error sending proposal requests: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private final Map<String, DFAgentDescription> roomCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> quickRejectCache = new ConcurrentHashMap<>();

    // Cache key generator
    private String getCacheKey(Asignatura subject, String roomId) {
        return subject.getCodigoAsignatura() + "-" + roomId;
    }

    //FIXME: La capacidad está como "turno" en el json de salas
    private boolean canQuickReject(Asignatura subject, DFAgentDescription room) {
        String cacheKey = getCacheKey(subject, room.getName().getLocalName());

        return quickRejectCache.computeIfAbsent(cacheKey, k -> {
            // Basic room requirements
            ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
            List<Property> props = new ArrayList<>();
            sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

            //first 0 is the campus, and 1 is the capacity
            Property roomCampusProp = props.get(0);
            String roomCampus = (String) roomCampusProp.getValue();

            Property roomCapacityProp = props.get(2);
            int roomCapacity = Integer.parseInt((String) roomCapacityProp.getValue());

            // Quick reject conditions
            if (!roomCampus.equals(subject.getCampus())) {
                return true;
            }

            if (roomCapacity < subject.getVacantes()) {
                return true; // Room too small
            }

            return false; // Passed basic checks
        });
    }

    // Separate method for creating the CFP message to improve readability
    private ACLMessage createCFPMessage(Asignatura currentSubject) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);

        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

        // Build request info
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

        return cfp;
    }
}