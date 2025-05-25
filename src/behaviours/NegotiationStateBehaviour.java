package behaviours;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.enums.Day;
import debugscreens.ProfessorDebugViewer;
import df.DFCache;
import evaluators.ConstraintEvaluator;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import objetos.Asignatura;
import objetos.AssignationData;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
import objetos.helper.BatchProposal;
import performance.RTTLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public class NegotiationStateBehaviour extends TickerBehaviour {
    private static final int MEETING_ROOM_THRESHOLD = 10;
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<BatchProposal> propuestas;
    private NegotiationState currentState;
    private long proposalTimeout;
    private int retryCount = 0;
    //private static final int MAX_RETRIES = 10;
    //private boolean proposalReceived = false;
    private final AssignationData assignationData;
    private final ConstraintEvaluator evaluator;
    private int bloquesPendientes = 0;
    private static final long TIMEOUT_PROPUESTA = 5000; // 5 seconds
    private static final long BACKOFF_TIME_OFFSET = 1000; // 1 second

    private long negotiationStartTime;
    private final Map<String, Long> subjectNegotiationTimes = new HashMap<>();

    public enum NegotiationState {
        SETUP,
        COLLECTING_PROPOSALS,
        EVALUATING_PROPOSALS,
        FINISHED
    }

    private RTTLogger rttLogger;

    public NegotiationStateBehaviour(AgenteProfesor profesor, long period, ConcurrentLinkedQueue<BatchProposal> propuestas) {
        super(profesor, period);
        this.profesor = profesor;
        this.propuestas = propuestas;
        this.currentState = NegotiationState.SETUP;
        this.assignationData = new AssignationData();
        this.evaluator = new ConstraintEvaluator(profesor);
        this.negotiationStartTime = System.currentTimeMillis();

        this.rttLogger = RTTLogger.getInstance();
    }

    public int getBloquesPendientes() {
        return bloquesPendientes;
    }

    /*
    public synchronized void notifyProposalReceived() {
        this.proposalReceived = true;
    }*/

    @Override
    protected void onTick() {
        //System.out.println(myAgent.getLocalName() + "MSG Pendientes: " + myAgent.getCurQueueSize());
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
        /*
        AtomicReference<ProfessorDebugViewer> debugWindow = new AtomicReference<>(profesor.getDebugWindow());
        if (debugWindow.get() == null) {
            SwingUtilities.invokeLater(() -> {
                ProfessorDebugViewer newWindow = new ProfessorDebugViewer(profesor.getNombre());
                debugWindow.set(newWindow);
                profesor.setDebugWindow(newWindow);  // Update the agent's reference
                newWindow.setVisible(true);
            });
        }*/
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
        if (currentSubject == null) {
            //System.out.println("Error: No hay asignatura actual para " + profesor.getNombre());
            currentState = NegotiationState.FINISHED;
            return;
        }

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
        //proposalReceived = false;
    }

    private void handleEvaluatingState() {
        List<BatchProposal> currentBatchProposals = new ArrayList<>();
        while (!propuestas.isEmpty()) {
            BatchProposal bp = propuestas.poll();
            if (bp != null) {
                currentBatchProposals.add(bp);
            }
        }

        List<BatchProposal> validProposals = evaluator.filterAndSortProposals(currentBatchProposals);

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
            long backoffTime = (long) Math.pow(2, retryCount) * BACKOFF_TIME_OFFSET; // 2^retry seconds
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
            sendProposalRequests();
        }
    }

    private void handleProposalFailure() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (assignationData.hasSalaAsignada()) {
                assignationData.setSalaAsignada(null);
            } else {
                profesor.moveToNextSubject();
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
            currentState = NegotiationState.COLLECTING_PROPOSALS;
            // Add exponential backoff here too
            long backoffTime = (long) Math.pow(2, retryCount) * BACKOFF_TIME_OFFSET;
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
            sendProposalRequests();
        }
    }

    private void handleCollectingState() {
        // If we received proposals, evaluate immediately
        if (receivedResponseCount >= sentRequestCount && sentRequestCount > 0) {
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
                            currentSubject.getVacantes(),
                            profesor.getNombre()
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

        //TODO: It is really mandatory to send a REJECT_PROPOSAL?
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

        /*
        String conversationId = batchAccept.getConversationId();

        simpleRTT.messageSent(
                conversationId,
                profesor.getAID(),
                originalMsg.getSender(),
                "ACCEPT_PROPOSAL"
        );
        profesor.getPerformanceMonitor().recordMessageSent(batchAccept, "ACCEPT_PROPOSAL");*/

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
                //String conversationId = confirm.getConversationId();
                //simpleRTT.messageReceived(conversationId, confirm);
                //profesor.getPerformanceMonitor().recordMessageReceived(confirm, "INFORM");
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

    private int sentRequestCount = 0;
    private int receivedResponseCount = 0;

    public synchronized void incrementResponseCount() {
        receivedResponseCount++;
        //notifyProposalReceived();

        //System.out.println("[DEBUG] Response received: " + receivedResponseCount + "/" + sentRequestCount);

        if (receivedResponseCount >= sentRequestCount && sentRequestCount > 0) {
            //System.out.println("[DEBUG] All responses received, proceeding to evaluation");
            currentState = NegotiationState.EVALUATING_PROPOSALS;
        }
    }

    // In NegotiationStateBehaviour.java where the CFP is sent
    private void sendProposalRequests() {
        sentRequestCount = 0;
        receivedResponseCount = 0;

        try {
            List<DFAgentDescription> results = DFCache.search(profesor, AgenteSala.SERVICE_NAME);
            if (results.isEmpty()) {
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            if (currentSubject == null) {
                return;
            }

            for (DFAgentDescription room : results) {
                if (canQuickReject(currentSubject, room)) {
                    continue;
                }

                String conversationId = "neg-" + profesor.getNombre() + "-" +
                        room.getName().getLocalName() + "-" +
                        System.currentTimeMillis();

                ACLMessage cfp = createCFPMessage(currentSubject);
                cfp.setConversationId(conversationId);
                cfp.addReceiver(room.getName());

                rttLogger.startRequest(
                        myAgent.getLocalName(),
                        conversationId,
                        ACLMessage.CFP,
                        room.getName().getLocalName(),
                        null,
                        "classroom-availability"
                );

                profesor.send(cfp);
                sentRequestCount++;
            }
        } catch (Exception e) {
            System.err.println("Error sending proposal requests: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private final Map<String, Boolean> quickRejectCache = new ConcurrentHashMap<>();

    // Cache key generator
    private String getCacheKey(Asignatura subject, String roomId) {
        return subject.getCodigoAsignatura() + "-" + roomId;
    }

    private final int ROOM_CAMPUS_INDEX = 0;
    private final int ROOM_CAPACITY_INDEX = 2;

    //FIXME: La capacidad está como "turno" en el json de salas
    private boolean canQuickReject(Asignatura subject, DFAgentDescription room) {
        String cacheKey = getCacheKey(subject, room.getName().getLocalName());

        return quickRejectCache.computeIfAbsent(cacheKey, k -> {
            ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
            List<Property> props = new ArrayList<>();
            sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

            String roomCampus = (String) props.get(ROOM_CAMPUS_INDEX).getValue();
            int roomCapacity = Integer.parseInt((String) props.get(ROOM_CAPACITY_INDEX).getValue());

            // Quick reject conditions
            if (!roomCampus.equals(subject.getCampus())) {
                return true;
            }

            // Meeting room logic
            boolean subjectNeedsMeetingRoom = subject.getVacantes() < MEETING_ROOM_THRESHOLD;
            boolean isMeetingRoom = roomCapacity < MEETING_ROOM_THRESHOLD;

            // Reject if meeting room requirements don't match
            if (subjectNeedsMeetingRoom != isMeetingRoom) {
                return true;
            }

            // For meeting rooms, we're more lenient with capacity
            if (isMeetingRoom) {
                return roomCapacity < Math.ceil(subject.getVacantes() * 0.8); // Allow some flexibility
            }

            // For regular rooms
            return roomCapacity < subject.getVacantes();
        });
    }


    private ACLMessage createCFPMessage(Asignatura currentSubject) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);

        cfp.setSender(profesor.getAID());
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