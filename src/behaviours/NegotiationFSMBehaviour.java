package behaviours;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.enums.Day;
import df.DFCache;
import evaluators.ConstraintEvaluator;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import objetos.Asignatura;
import objetos.AssignationData;
import objetos.ClassroomAvailability;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
import objetos.helper.BatchProposal;
import performance.RTTLogger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FSM implementation of the negotiation process for professors.
 * Combines the functionality of NegotiationStateBehaviour and MessageCollectorBehaviour
 * into a single state machine.
 */
public class NegotiationFSMBehaviour extends FSMBehaviour {
    // State names
    private static final String SETUP = "SETUP";
    private static final String COLLECTING = "COLLECTING";
    private static final String EVALUATING = "EVALUATING";
    private static final String FINISHED = "FINISHED";

    // Constants
    private static final int MAX_RETRIES = 3;
    private static final long TIMEOUT_PROPUESTA = 5000; // 5 seconds
    private static final long BACKOFF_TIME_OFFSET = 1000; // 1 second
    private static final int MEETING_ROOM_THRESHOLD = 10;

    // State tracking
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<BatchProposal> batchProposals;
    private final AssignationData assignationData;
    private final ConstraintEvaluator evaluator;
    private final Map<String, Boolean> quickRejectCache;

    // Negotiation tracking
    private int bloquesPendientes = 0;
    private int retryCount = 0;
    private long proposalTimeout;
    private int sentRequestCount = 0;
    private final AtomicInteger receivedResponseCount = new AtomicInteger(0);
    private long negotiationStartTime;
    private final Map<String, Long> subjectNegotiationTimes = new HashMap<>();

    // Performance logging
    private RTTLogger rttLogger;

    public NegotiationFSMBehaviour(AgenteProfesor profesor) {
        this.profesor = profesor;
        this.batchProposals = new ConcurrentLinkedQueue<>();
        this.assignationData = new AssignationData();
        this.evaluator = new ConstraintEvaluator(profesor);
        this.quickRejectCache = new HashMap<>();
        this.rttLogger = RTTLogger.getInstance();

        // Register states
        registerFirstState(new SetupState(), SETUP);
        registerState(new CollectingState(), COLLECTING);
        registerState(new EvaluatingState(), EVALUATING);
        registerLastState(new FinishedState(), FINISHED);

        // Register transitions
        registerDefaultTransition(SETUP, COLLECTING);
        registerTransition(COLLECTING, EVALUATING, 0);
        registerTransition(COLLECTING, SETUP, 1);
        registerTransition(EVALUATING, SETUP, 0);
        registerTransition(EVALUATING, COLLECTING, 1);
        registerTransition(EVALUATING, FINISHED, 2);
        registerTransition(SETUP, FINISHED, 1);
    }

    /**
     * Reset counter between state transitions
     */
    private class ResetResponseCounter extends OneShotBehaviour {
        @Override
        public void action() {
            receivedResponseCount.set(0);
        }
    }

    /**
     * Setup state - initializes negotiation for current subject
     */
    private class SetupState extends OneShotBehaviour {
        @Override
        public void action() {
            receivedResponseCount.set(0);

            System.out.println("Entering SETUP state for " + profesor.getNombre());

            if (!profesor.canUseMoreSubjects()) {
                System.out.println("No more subjects to process for " + profesor.getNombre());
                // Transition to FINISHED
                onEnd = 1;
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            if (currentSubject == null) {
                System.out.println("Error: No current subject available for " + profesor.getNombre());
                // Transition to FINISHED
                onEnd = 1;
                return;
            }

            bloquesPendientes = currentSubject.getHoras();
            assignationData.clear();

            // Setup performance tracking
            negotiationStartTime = System.currentTimeMillis();

            System.out.printf("[SETUP] Starting assignment for %s (Code: %s) - Required hours: %d%n",
                    currentSubject.getNombre(),
                    currentSubject.getCodigoAsignatura(),
                    currentSubject.getHoras());

            sendProposalRequests();
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;

            // Default transition to COLLECTING
            onEnd = 0;
        }

        private int onEnd = 0;

        @Override
        public int onEnd() {
            return onEnd;
        }
    }

    /**
     * Collecting state - waits for and collects proposals
     */
    private class CollectingState extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("Entering COLLECTING state");

            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
            );

            boolean collectingDone = false;

            // Process all available messages
            while (!collectingDone) {
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.PROPOSE) {
                        processProposal(msg);
                    } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                        incrementResponseCount();

                    }
                } else {
                    // No more messages to process
                    collectingDone = true;
                }
            }

            // Check if we've received responses to all our requests
            if (receivedResponseCount.get() >= sentRequestCount && sentRequestCount > 0) {
                if (!batchProposals.isEmpty()) {
                    System.out.println("Received all expected responses with proposals");
                    // Transition to EVALUATING
                    onEnd = 0;
                    return;
                }
            }

            // Check if we hit timeout
            if (System.currentTimeMillis() > proposalTimeout) {
                if (!batchProposals.isEmpty()) {
                    System.out.println("Timeout with proposals - proceeding to evaluation");
                    // Transition to EVALUATING
                    onEnd = 0;
                } else {
                    System.out.println("Timeout with no proposals - handling retry");
                    // Handle no proposals by transitioning to SETUP
                    handleNoProposals();
                    // Transition to SETUP
                    onEnd = 1;
                }
                return;
            }

            // If we get here, we're still waiting for responses
            block(100); // Block for a short time
            // Stay in COLLECTING state
            onEnd = 0;
        }

        private void processProposal(ACLMessage msg) {
            try {
                // Log the RTT for this request
                rttLogger.recordMessageReceived(
                        myAgent.getLocalName(),
                        msg.getConversationId(),
                        msg.getPerformative(),
                        msg.getSender().getLocalName(),
                        msg.getByteSequenceContent().length,
                        "classroom-availability"
                );

                ClassroomAvailability sala = (ClassroomAvailability) msg.getContentObject();
                if (sala == null) {
                    System.out.println("Null classroom availability received");
                    return;
                }

                BatchProposal batchProposal = new BatchProposal(sala, msg);
                batchProposals.offer(batchProposal);
                incrementResponseCount();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

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
            } else {
                // Add exponential backoff to avoid overwhelming the system
                long backoffTime = (long) Math.pow(2, retryCount) * BACKOFF_TIME_OFFSET;
                proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
                sendProposalRequests();
            }
        }

        private int onEnd = 0;

        @Override
        public int onEnd() {
            return onEnd;
        }
    }

    /**
     * Evaluating state - evaluates proposals and attempts assignments
     */
    private class EvaluatingState extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("Entering EVALUATING state");

            List<BatchProposal> currentBatchProposals = new ArrayList<>();
            while (!batchProposals.isEmpty()) {
                BatchProposal bp = batchProposals.poll();
                if (bp != null) {
                    currentBatchProposals.add(bp);
                }
            }

            List<BatchProposal> validProposals = evaluator.filterAndSortProposals(currentBatchProposals);

            if (!validProposals.isEmpty() && tryAssignBatchProposals(validProposals)) {
                retryCount = 0;
                if (bloquesPendientes == 0) {
                    profesor.moveToNextSubject();
                    // Transition to SETUP
                    onEnd = 0;
                } else {
                    sendProposalRequests();
                    proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
                    // Transition to COLLECTING
                    onEnd = 1;
                }
            } else {
                handleProposalFailure();
                // Transition to SETUP or FINISHED
                // This depends on whether there was a failure or if we're done
                if (profesor.canUseMoreSubjects()) {
                    onEnd = 0; // Back to SETUP
                } else {
                    onEnd = 2; // Go to FINISHED
                }
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
            } else {
                // Add exponential backoff here too
                long backoffTime = (long) Math.pow(2, retryCount) * BACKOFF_TIME_OFFSET;
                proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
                sendProposalRequests();
            }
        }

        private int onEnd = 0;

        @Override
        public int onEnd() {
            return onEnd;
        }
    }

    /**
     * Finished state - performs cleanup and notifies the next professor
     */
    private class FinishedState extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("Entering FINISHED state");

            long totalTime = System.currentTimeMillis() - negotiationStartTime;
            System.out.printf("[TIMING] Professor %s completed all negotiations in %d ms%n",
                    profesor.getNombre(), totalTime);

            // Print individual subject times
            subjectNegotiationTimes.forEach((subject, time) ->
                    System.out.printf("[TIMING] Subject %s negotiation took %d ms%n", subject, time));

            profesor.finalizarNegociaciones();
        }
    }

    /**
     * Attempts to assign batch proposals to classrooms
     */
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

        return totalAssigned > 0;
    }

    /**
     * Sends a batch assignment request and waits for confirmation
     */
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

    /**
     * Waits for confirmation from the classroom agent
     */
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

            // Short block to avoid busy waiting
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /**
     * Send proposal requests to potential classrooms
     */
    private void sendProposalRequests() {
        sentRequestCount = 0;
        receivedResponseCount.set(0);

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

            System.out.println("Sent " + sentRequestCount + " proposal requests");
        } catch (Exception e) {
            System.err.println("Error sending proposal requests: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create a CFP message for the current subject
     */
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

    /**
     * Generate cache key for room rejection cache
     */
    private String getCacheKey(Asignatura subject, String roomId) {
        return subject.getCodigoAsignatura() + "-" + roomId;
    }

    private static final int ROOM_CAMPUS_INDEX = 0;
    private static final int ROOM_CAPACITY_INDEX = 2;

    /**
     * Quick rejection logic to avoid unnecessary messages
     */
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

    /**
     * Sanitize a subject name
     */
    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    /**
     * Get the number of blocks pending
     */
    public int getBloquesPendientes() {
        return bloquesPendientes;
    }

    /**
     * Increments the response count
     */
    public void incrementResponseCount() {
        receivedResponseCount.incrementAndGet();
    }
}