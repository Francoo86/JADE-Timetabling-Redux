package behaviours.parallel;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.enums.Day;
import constants.enums.TipoContrato;
import df.DFCache;
import evaluators.ConstraintEvaluator;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.ParallelBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import json_stuff.ProfesorHorarioJSON;
import objetos.Asignatura;
import objetos.AssignationData;
import objetos.ClassroomAvailability;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
import objetos.helper.BatchProposal;
import performance.AgentMessageLogger;
import performance.RTTLogger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Comportamiento paralelo mejorado para negociación de profesores con capacidades avanzadas de renegociación
 */
public class ParallelNegotiationBehaviour extends ParallelBehaviour {
    private final AgenteProfesor profesor;
    private final SubjectNegotiationManager negotiationManager;
    private final MessageRouter messageRouter;

    // Performance logging
    private final RTTLogger rttLogger;
    private final AgentMessageLogger messageLogger;

    // Configuration
    private static final int MAX_CONCURRENT_NEGOTIATIONS = 3;
    private static final long NEGOTIATION_TIMEOUT = 10000; // 10 seconds
    private static final int MAX_RETRIES = 3;
    private static final int MEETING_ROOM_THRESHOLD = 10;

    public ParallelNegotiationBehaviour(AgenteProfesor profesor) {
        super(profesor, WHEN_ALL);
        this.profesor = profesor;
        this.negotiationManager = new SubjectNegotiationManager(profesor);
        this.messageRouter = new MessageRouter(negotiationManager);
        this.rttLogger = RTTLogger.getInstance();
        this.messageLogger = AgentMessageLogger.getInstance();

        initializeBehaviours();
    }

    private void initializeBehaviours() {
        // Main coordinator
        addSubBehaviour(new ParallelCoordinatorBehaviour());
        // Message handler
        addSubBehaviour(new ParallelMessageHandler());
    }

    /**
     * Negotiation state for a specific subject
     */
    private static class SubjectNegotiationState {
        private final Asignatura subject;
        private final int subjectIndex;
        private final String negotiationId;
        private final AgenteProfesor profesor;
        private final AssignationData assignationData;

        private volatile int blocksRemaining;
        private final Map<Day, Set<Integer>> assignedBlocks;
        private final ConcurrentLinkedQueue<BatchProposal> proposals;
        private final AtomicBoolean isActive;
        private volatile long lastActivityTime;
        private volatile int retryCount;
        private volatile NegotiationPhase phase;
        private final AtomicInteger sentRequests;
        private final AtomicInteger receivedResponses;
        private volatile long phaseStartTime;

        public enum NegotiationPhase {
            INITIALIZING, REQUESTING, COLLECTING, EVALUATING, ASSIGNING, COMPLETED, FAILED
        }

        public SubjectNegotiationState(Asignatura subject, int index, String id, AgenteProfesor profesor) {
            this.subject = subject;
            this.subjectIndex = index;
            this.negotiationId = id;
            this.profesor = profesor;
            this.assignationData = new AssignationData();
            this.blocksRemaining = subject.getHoras();
            this.assignedBlocks = new ConcurrentHashMap<>();
            this.proposals = new ConcurrentLinkedQueue<>();
            this.isActive = new AtomicBoolean(false);
            this.lastActivityTime = System.currentTimeMillis();
            this.retryCount = 0;
            this.phase = NegotiationPhase.INITIALIZING;
            this.sentRequests = new AtomicInteger(0);
            this.receivedResponses = new AtomicInteger(0);
            this.phaseStartTime = System.currentTimeMillis();
        }

        public boolean isComplete() {
            return blocksRemaining <= 0 || phase == NegotiationPhase.COMPLETED;
        }

        public boolean isStale(long timeout) {
            return System.currentTimeMillis() - lastActivityTime > timeout;
        }

        public boolean shouldRetry() {
            return retryCount < MAX_RETRIES && !isComplete();
        }

        public synchronized void updateActivity() {
            lastActivityTime = System.currentTimeMillis();
        }

        public synchronized void assignBlock(Day day, int block, String roomCode) {
            assignedBlocks.computeIfAbsent(day, k -> ConcurrentHashMap.newKeySet()).add(block);
            blocksRemaining--;
            assignationData.assign(day, roomCode, block);
            updateActivity();

            if (blocksRemaining <= 0) {
                phase = NegotiationPhase.COMPLETED;
            }

            System.out.printf("[STATE] %s assigned block %d on %s in room %s (%d remaining)%n",
                    subject.getNombre(), block, day, roomCode, blocksRemaining);
        }

        public void resetForRetry() {
            proposals.clear();
            sentRequests.set(0);
            receivedResponses.set(0);
            phase = NegotiationPhase.INITIALIZING;
            updateActivity();
        }

        public boolean hasReceivedAllResponses() {
            return receivedResponses.get() >= sentRequests.get() && sentRequests.get() > 0;
        }

        public boolean isPhaseTimedOut(long timeout) {
            return System.currentTimeMillis() - phaseStartTime > timeout;
        }

        // Getters
        public Asignatura getSubject() { return subject; }
        public String getNegotiationId() { return negotiationId; }
        public int getBlocksRemaining() { return blocksRemaining; }
        public Queue<BatchProposal> getProposals() { return proposals; }
        public boolean isActive() { return isActive.get(); }
        public void setActive(boolean active) {
            isActive.set(active);
            if (active) phase = NegotiationPhase.INITIALIZING;
        }
        public int getRetryCount() { return retryCount; }
        public void incrementRetry() { retryCount++; updateActivity(); }
        public NegotiationPhase getPhase() { return phase; }
        public void setPhase(NegotiationPhase phase) {
            this.phase = phase;
            this.phaseStartTime = System.currentTimeMillis();
            updateActivity();
            System.out.printf("[PHASE] %s -> %s%n", negotiationId, phase);
        }
        public AssignationData getAssignationData() { return assignationData; }
        public AtomicInteger getSentRequests() { return sentRequests; }
        public AtomicInteger getReceivedResponses() { return receivedResponses; }
    }

    /**
     * Message router
     */
    private static class MessageRouter {
        private final SubjectNegotiationManager negotiationManager;

        public MessageRouter(SubjectNegotiationManager negotiationManager) {
            this.negotiationManager = negotiationManager;
        }

        public void routeMessage(ACLMessage msg) {
            String negotiationId = extractNegotiationId(msg);
            SubjectNegotiationState state = negotiationManager.getNegotiation(negotiationId);

            if (state == null || !state.isActive()) {
                return;
            }

            switch (msg.getPerformative()) {
                case ACLMessage.PROPOSE:
                    handleProposal(msg, state);
                    break;
                case ACLMessage.REFUSE:
                    handleRefusal(msg, state);
                    break;
                case ACLMessage.INFORM:
                    handleConfirmation(msg, state);
                    break;
            }
        }

        private void handleProposal(ACLMessage msg, SubjectNegotiationState state) {
            try {
                ClassroomAvailability availability = (ClassroomAvailability) msg.getContentObject();
                if (availability != null) {
                    BatchProposal proposal = new BatchProposal(availability, msg);
                    state.getProposals().offer(proposal);
                    state.getReceivedResponses().incrementAndGet();
                    state.updateActivity();

                    System.out.printf("[ROUTER] Added proposal from %s for %s (total: %d/%d)%n",
                            availability.getCodigo(), state.getNegotiationId(),
                            state.getReceivedResponses().get(), state.getSentRequests().get());
                }
            } catch (UnreadableException e) {
                System.err.println("Error reading proposal: " + e.getMessage());
            }
        }

        private void handleRefusal(ACLMessage msg, SubjectNegotiationState state) {
            state.getReceivedResponses().incrementAndGet();
            state.updateActivity();
            System.out.printf("[ROUTER] Received refusal for %s (%d/%d)%n",
                    state.getNegotiationId(),
                    state.getReceivedResponses().get(),
                    state.getSentRequests().get());
        }

        private void handleConfirmation(ACLMessage msg, SubjectNegotiationState state) {
            try {
                BatchAssignmentConfirmation confirmation =
                        (BatchAssignmentConfirmation) msg.getContentObject();

                if (confirmation != null) {
                    int originalIndex = state.profesor.asignaturaActual;
                    state.profesor.asignaturaActual = state.subjectIndex;
                    for (BatchAssignmentConfirmation.ConfirmedAssignment assignment :
                            confirmation.getConfirmedAssignments()) {

                        // Usar el nuevo método con contexto
                        state.profesor.updateScheduleInfoWithContext(
                                assignment.getDay(),
                                assignment.getClassroomCode(),
                                assignment.getBlock(),
                                state.getSubject().getNombre(),
                                assignment.getSatisfaction(),
                                state.subjectIndex  // Pasar el índice correcto
                        );

                        // Update negotiation state
                        state.assignBlock(
                                assignment.getDay(),
                                assignment.getBlock(),
                                assignment.getClassroomCode()
                        );
                    }

                    // Move to next phase
                    if (state.isComplete()) {
                        state.setPhase(SubjectNegotiationState.NegotiationPhase.COMPLETED);
                    } else {
                        state.setPhase(SubjectNegotiationState.NegotiationPhase.INITIALIZING);
                    }
                }
            } catch (UnreadableException e) {
                System.err.println("Error reading confirmation: " + e.getMessage());
            }
        }

        private String extractNegotiationId(ACLMessage msg) {
            String conversationId = msg.getConversationId();
            if (conversationId != null && conversationId.contains("-")) {
                String[] parts = conversationId.split("-");
                if (parts.length >= 4) {
                    return parts[0] + "-" + parts[1] + "-" + parts[2] + "-" + parts[3];
                }
            }
            String negotiationId = msg.getUserDefinedParameter("negotiationId");
            return negotiationId != null ? negotiationId : "unknown-" + System.currentTimeMillis();
        }
    }

    /**
     * Main coordinator behaviour
     */
    private class ParallelCoordinatorBehaviour extends TickerBehaviour {
        private boolean initialized = false;

        public ParallelCoordinatorBehaviour() {
            super(profesor, 500); // Check every 500ms
        }

        @Override
        protected void onTick() {
            if (!initialized) {
                System.out.println("[PARALLEL] Initializing for " + profesor.getNombre());
                negotiationManager.initializeAllNegotiations();
                initialized = true;
                return;
            }

            // Process active negotiations
            processActiveNegotiations();

            // Check if all complete
            if (negotiationManager.allNegotiationsComplete()) {
                System.out.println("[PARALLEL] All negotiations completed for " + profesor.getNombre());

                // Forzar guardado antes de finalizar
                ProfesorHorarioJSON.getInstance().flushUpdates(true);

                profesor.finalizarNegociaciones();
                stop();
            }
        }

        private void processActiveNegotiations() {
            for (String negotiationId : negotiationManager.getActiveNegotiationIds()) {
                SubjectNegotiationState state = negotiationManager.getNegotiation(negotiationId);

                if (state == null || !state.isActive()) continue;

                switch (state.getPhase()) {
                    case INITIALIZING:
                        sendProposalRequests(state);
                        state.setPhase(SubjectNegotiationState.NegotiationPhase.COLLECTING);
                        break;

                    case COLLECTING:
                        // Check if we have all responses or timeout
                        if (state.hasReceivedAllResponses()) {
                            System.out.printf("[COLLECTING] All responses received for %s%n",
                                    state.getNegotiationId());
                            state.setPhase(SubjectNegotiationState.NegotiationPhase.EVALUATING);
                        } else if (state.isPhaseTimedOut(5000)) {
                            System.out.printf("[TIMEOUT] Collection timeout for %s with %d/%d responses%n",
                                    state.getNegotiationId(),
                                    state.getReceivedResponses().get(),
                                    state.getSentRequests().get());
                            if (!state.getProposals().isEmpty()) {
                                state.setPhase(SubjectNegotiationState.NegotiationPhase.EVALUATING);
                            } else {
                                handleNoProposals(state);
                            }
                        }
                        break;

                    case EVALUATING:
                        evaluateProposals(state);
                        break;

                    case ASSIGNING:
                        // Waiting for confirmation - handled by message router
                        if (state.isPhaseTimedOut(3000)) {
                            System.out.printf("[TIMEOUT] Assignment timeout for %s%n",
                                    state.getNegotiationId());
                            handleRetry(state);
                        }
                        break;

                    case COMPLETED:
                        negotiationManager.completeNegotiation(state.getNegotiationId());
                        break;

                    case FAILED:
                        System.out.printf("[FAILED] %s failed after %d retries%n",
                                state.getSubject().getNombre(), state.getRetryCount());
                        negotiationManager.completeNegotiation(state.getNegotiationId());
                        break;
                }
            }
        }

        private void evaluateProposals(SubjectNegotiationState state) {
            List<BatchProposal> proposals = new ArrayList<>();

            // Collect all proposals
            BatchProposal proposal;
            while ((proposal = state.getProposals().poll()) != null) {
                proposals.add(proposal);
            }

            System.out.printf("[EVALUATING] Processing %d proposals for %s%n",
                    proposals.size(), state.getNegotiationId());

            if (!proposals.isEmpty()) {
                // Filter and sort
                ConstraintEvaluator evaluator = negotiationManager.getEvaluator();
                List<BatchProposal> validProposals = evaluator.filterAndSortProposals(proposals);

                if (!validProposals.isEmpty()) {
                    state.setPhase(SubjectNegotiationState.NegotiationPhase.ASSIGNING);
                    attemptAssignment(state, validProposals);
                } else {
                    System.out.printf("[EVALUATING] No valid proposals for %s%n",
                            state.getNegotiationId());
                    handleRetry(state);
                }
            } else {
                handleNoProposals(state);
            }
        }

        private void attemptAssignment(SubjectNegotiationState state, List<BatchProposal> validProposals) {
            try {
                for (BatchProposal proposal : validProposals) {
                    if (state.getBlocksRemaining() <= 0) break;

                    if (tryAssignFromProposal(state, proposal)) {
                        System.out.printf("[ASSIGNMENT] Sent assignment request for %s to room %s%n",
                                state.getSubject().getNombre(), proposal.getRoomCode());
                        return; // Wait for confirmation
                    }
                }

                // If no assignment was possible
                handleRetry(state);

            } catch (Exception e) {
                System.err.printf("[ERROR] Assignment error for %s: %s%n",
                        state.getNegotiationId(), e.getMessage());
                state.setPhase(SubjectNegotiationState.NegotiationPhase.FAILED);
            }
        }

        private void handleNoProposals(SubjectNegotiationState state) {
            System.out.printf("[NO_PROPOSALS] No proposals received for %s%n",
                    state.getNegotiationId());
            handleRetry(state);
        }

        private void handleRetry(SubjectNegotiationState state) {
            if (state.shouldRetry()) {
                state.incrementRetry();
                state.resetForRetry();
                System.out.printf("[RETRY] Retrying %s (attempt %d)%n",
                        state.getNegotiationId(), state.getRetryCount());
            } else {
                state.setPhase(SubjectNegotiationState.NegotiationPhase.FAILED);
            }
        }
    }

    /**
     * Message handler
     */
    private class ParallelMessageHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                            MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                    ),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)
            );

            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                messageLogger.logMessageReceived(myAgent.getLocalName(), msg);
                messageRouter.routeMessage(msg);

                if (msg.getPerformative() != ACLMessage.INFORM) {
                    logRequest(msg, msg.getPerformative() == ACLMessage.PROPOSE);
                }
            } else {
                block(50);
            }
        }

        private void logRequest(ACLMessage msg, boolean success) {
            rttLogger.endRequest(
                    myAgent.getLocalName(),
                    msg.getConversationId(),
                    msg.getPerformative(),
                    msg.getByteSequenceContent().length,
                    success,
                    null,
                    "parallel-classroom-availability"
            );
        }
    }

    // Utility methods
    private void sendProposalRequests(SubjectNegotiationState state) {
        try {
            List<DFAgentDescription> rooms = DFCache.search(profesor, AgenteSala.SERVICE_NAME);

            // Filter rooms
            List<DFAgentDescription> viableRooms = rooms.stream()
                    .filter(room -> shouldSendToRoom(state, room))
                    .sorted(Comparator.comparing(room -> room.getName().getLocalName()))
                    .collect(Collectors.toList());

            System.out.printf("[REQUEST] Sending %d requests for %s%n",
                    viableRooms.size(), state.getNegotiationId());

            for (DFAgentDescription room : viableRooms) {
                ACLMessage cfp = createCFP(state, room);

                rttLogger.startRequest(
                        myAgent.getLocalName(),
                        cfp.getConversationId(),
                        ACLMessage.CFP,
                        room.getName().getLocalName(),
                        null,
                        "classroom-availability"
                );

                messageLogger.logMessageSent(myAgent.getLocalName(), cfp);
                profesor.send(cfp);
                state.getSentRequests().incrementAndGet();
            }

        } catch (Exception e) {
            System.err.println("Error sending requests: " + e.getMessage());
            state.setPhase(SubjectNegotiationState.NegotiationPhase.FAILED);
        }
    }

    private boolean shouldSendToRoom(SubjectNegotiationState state, DFAgentDescription room) {
        Asignatura subject = state.getSubject();

        ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
        List<Property> props = new ArrayList<>();
        sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

        if (props.size() < 3) return false;

        String roomCampus = (String) props.get(0).getValue();
        int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

        if (!roomCampus.equals(subject.getCampus()) && state.getRetryCount() == 0) {
            return false;
        }

        if (roomCapacity < subject.getVacantes()) {
            return false;
        }

        return true;
    }

    private ACLMessage createCFP(SubjectNegotiationState state, DFAgentDescription room) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setSender(profesor.getAID());
        cfp.addReceiver(room.getName());
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

        String conversationId = state.getNegotiationId() + "-" + System.currentTimeMillis();
        cfp.setConversationId(conversationId);
        cfp.addUserDefinedParameter("negotiationId", state.getNegotiationId());

        Asignatura subject = state.getSubject();
        AssignationData assignationData = state.getAssignationData();

        String content = String.format("%s,%d,%d,%s,%d,%s,%s,%d",
                sanitizeSubjectName(subject.getNombre()),
                subject.getVacantes(),
                subject.getNivel(),
                subject.getCampus(),
                state.getBlocksRemaining(),
                assignationData.getSalaAsignada(),
                assignationData.getUltimoDiaAsignado() != null ?
                        assignationData.getUltimoDiaAsignado().toString() : "",
                assignationData.getUltimoBloqueAsignado()
        );
        cfp.setContent(content);

        return cfp;
    }

    private boolean tryAssignFromProposal(SubjectNegotiationState state, BatchProposal proposal)
            throws IOException {
        List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();

        int blocksNeeded = Math.min(state.getBlocksRemaining(), 2);
        int blocksRequested = 0;

        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                proposal.getDayProposals().entrySet()) {

            if (blocksRequested >= blocksNeeded) break;

            Day day = entry.getKey();

            for (BatchProposal.BlockProposal block : entry.getValue()) {
                if (blocksRequested >= blocksNeeded) break;

                if (!profesor.isBlockAvailable(day, block.getBlock())) continue;

                requests.add(new BatchAssignmentRequest.AssignmentRequest(
                        day,
                        block.getBlock(),
                        state.getSubject().getNombre(),
                        proposal.getSatisfactionScore(),
                        proposal.getRoomCode(),
                        state.getSubject().getVacantes(),
                        profesor.getNombre()
                ));

                blocksRequested++;
            }
        }

        if (!requests.isEmpty()) {
            BatchAssignmentRequest batchRequest = new BatchAssignmentRequest(requests);

            ACLMessage accept = proposal.getOriginalMessage().createReply();
            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            accept.setContentObject(batchRequest);
            accept.addUserDefinedParameter("negotiationId", state.getNegotiationId());

            messageLogger.logMessageSent(myAgent.getLocalName(), accept);
            profesor.send(accept);

            return true;
        }

        return false;
    }

    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    public int getBloquesPendientes() {
        return negotiationManager.getAllActiveNegotiations().values().stream()
                .mapToInt(SubjectNegotiationState::getBlocksRemaining)
                .sum();
    }

    /**
     * Subject negotiation manager
     */
    private static class SubjectNegotiationManager {
        private final AgenteProfesor profesor;
        private final Map<String, SubjectNegotiationState> negotiations;
        private final Queue<String> pendingNegotiations;
        private final AtomicInteger activeCount;
        private final AtomicInteger completedCount;
        private final ConstraintEvaluator evaluator;

        public SubjectNegotiationManager(AgenteProfesor profesor) {
            this.profesor = profesor;
            this.negotiations = new ConcurrentHashMap<>();
            this.pendingNegotiations = new ConcurrentLinkedQueue<>();
            this.activeCount = new AtomicInteger(0);
            this.completedCount = new AtomicInteger(0);
            this.evaluator = new ConstraintEvaluator(profesor);
        }

        public synchronized void initializeAllNegotiations() {
            List<Asignatura> subjects = profesor.getAsignaturas();

            for (int i = 0; i < subjects.size(); i++) {
                Asignatura subject = subjects.get(i);
                String negotiationId = generateNegotiationId(subject, i);

                SubjectNegotiationState state = new SubjectNegotiationState(
                        subject, i, negotiationId, profesor
                );

                negotiations.put(negotiationId, state);
                pendingNegotiations.offer(negotiationId);

                System.out.printf("[INIT] Created negotiation %s for %s%n",
                        negotiationId, subject.getNombre());
            }

            activateNextNegotiations();
        }

        public synchronized void activateNextNegotiations() {
            while (activeCount.get() < MAX_CONCURRENT_NEGOTIATIONS && !pendingNegotiations.isEmpty()) {
                String negotiationId = pendingNegotiations.poll();
                SubjectNegotiationState state = negotiations.get(negotiationId);

                if (state != null && !state.isActive()) {
                    state.setActive(true);
                    activeCount.incrementAndGet();
                    System.out.printf("[ACTIVATE] Activated %s (%d active)%n",
                            negotiationId, activeCount.get());
                }
            }
        }

        public Set<String> getActiveNegotiationIds() {
            return negotiations.entrySet().stream()
                    .filter(e -> e.getValue().isActive() && !e.getValue().isComplete())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
        }

        public SubjectNegotiationState getNegotiation(String id) {
            return negotiations.get(id);
        }

        public synchronized void completeNegotiation(String id) {
            SubjectNegotiationState state = negotiations.get(id);
            if (state != null && state.isActive()) {
                state.setActive(false);
                activeCount.decrementAndGet();
                completedCount.incrementAndGet();

                System.out.printf("[COMPLETE] Completed %s (%d/%d done)%n",
                        id, completedCount.get(), negotiations.size());

                activateNextNegotiations();
            }
        }

        public boolean allNegotiationsComplete() {
            return completedCount.get() >= negotiations.size();
        }

        public Map<String, SubjectNegotiationState> getAllActiveNegotiations() {
            return negotiations.entrySet().stream()
                    .filter(e -> e.getValue().isActive())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        public ConstraintEvaluator getEvaluator() {
            return evaluator;
        }

        private String generateNegotiationId(Asignatura subject, int index) {
            return String.format("%s-%s-%d-%d",
                    profesor.getNombre().split(" ")[0],
                    subject.getCodigoAsignatura().replace(" ", ""),
                    index,
                    System.currentTimeMillis() % 10000
            );
        }
    }
}