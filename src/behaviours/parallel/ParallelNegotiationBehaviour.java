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
    private final ConflictResolutionManager conflictManager;
    private final RenegotiationManager renegotiationManager;
    private final MessageRouter messageRouter;
    private final PerformanceMonitor performanceMonitor;

    // Performance logging
    private final RTTLogger rttLogger;
    private final AgentMessageLogger messageLogger;

    // Configuration
    private static final int MAX_CONCURRENT_NEGOTIATIONS = 3; // Limitar concurrencia
    private static final long NEGOTIATION_TIMEOUT = 15000; // 15 seconds
    private static final long STALE_THRESHOLD = 10000; // 10 seconds
    private static final int MAX_RETRIES = 5;
    private static final int MEETING_ROOM_THRESHOLD = 10;

    public ParallelNegotiationBehaviour(AgenteProfesor profesor) {
        super(profesor, WHEN_ALL);
        this.profesor = profesor;
        this.negotiationManager = new SubjectNegotiationManager(profesor);
        this.conflictManager = new ConflictResolutionManager(profesor);
        this.renegotiationManager = new RenegotiationManager(profesor, negotiationManager);
        this.messageRouter = new MessageRouter(negotiationManager, conflictManager);
        this.performanceMonitor = new PerformanceMonitor();
        this.rttLogger = RTTLogger.getInstance();
        this.messageLogger = AgentMessageLogger.getInstance();

        initializeBehaviours();
    }

    private void initializeBehaviours() {
        // Comportamiento principal de coordinación
        addSubBehaviour(new ParallelCoordinatorBehaviour());

        // Comportamiento de manejo de mensajes
        addSubBehaviour(new ParallelMessageHandler());

        // Comportamiento de resolución de conflictos
        addSubBehaviour(new ConflictDetectionBehaviour());

        // Comportamiento de renegociación
        addSubBehaviour(new RenegotiationBehaviour());

        // Monitor de performance
        addSubBehaviour(new PerformanceMonitorBehaviour());
    }

    /**
     * Estado mejorado de negociación para una asignatura específica
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
        private final Map<String, Integer> roomPreferences; // room -> preference score
        private final Map<String, Integer> roomRejections; // room -> rejection count
        private volatile long lastActivityTime;
        private volatile int retryCount;
        private volatile NegotiationPhase phase;
        private final AtomicInteger sentRequests;
        private final AtomicInteger receivedResponses;
        private final List<String> rejectedRooms;

        public enum NegotiationPhase {
            INITIALIZING, REQUESTING, WAITING, EVALUATING, ASSIGNING, COMPLETED, FAILED
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
            this.roomPreferences = new ConcurrentHashMap<>();
            this.roomRejections = new ConcurrentHashMap<>();
            this.lastActivityTime = System.currentTimeMillis();
            this.retryCount = 0;
            this.phase = NegotiationPhase.INITIALIZING;
            this.sentRequests = new AtomicInteger(0);
            this.receivedResponses = new AtomicInteger(0);
            this.rejectedRooms = Collections.synchronizedList(new ArrayList<>());
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
            roomPreferences.merge(roomCode, 1, Integer::sum);
            assignationData.assign(day, roomCode, block);
            updateActivity();

            if (blocksRemaining <= 0) {
                phase = NegotiationPhase.COMPLETED;
            }

            System.out.printf("[STATE] %s assigned block %d on %s in room %s (%d remaining)%n",
                    subject.getNombre(), block, day, roomCode, blocksRemaining);
        }

        public synchronized void rejectRoom(String roomCode, String reason) {
            roomRejections.merge(roomCode, 1, Integer::sum);
            rejectedRooms.add(roomCode);
            updateActivity();
            System.out.printf("[STATE] %s rejected room %s: %s%n", subject.getNombre(), roomCode, reason);
        }

        public boolean isRoomRejected(String roomCode) {
            return roomRejections.getOrDefault(roomCode, 0) > 2; // Reject after 2 failures
        }

        public void resetForRetry() {
            proposals.clear();
            sentRequests.set(0);
            receivedResponses.set(0);
            phase = NegotiationPhase.INITIALIZING;
            updateActivity();
        }

        public String getPreferredRoom() {
            return roomPreferences.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        // Getters and setters
        public Asignatura getSubject() {
            return subject;
        }

        public String getNegotiationId() {
            return negotiationId;
        }

        public int getBlocksRemaining() {
            return blocksRemaining;
        }

        public Queue<BatchProposal> getProposals() {
            return proposals;
        }

        public boolean isActive() {
            return isActive.get();
        }

        public void setActive(boolean active) {
            isActive.set(active);
            if (active) {
                phase = NegotiationPhase.INITIALIZING;
            }
        }

        public Map<String, Integer> getRoomPreferences() {
            return roomPreferences;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void incrementRetry() {
            retryCount++;
            updateActivity();
        }

        public void resetRetries() {
            retryCount = 0;
            updateActivity();
        }

        public NegotiationPhase getPhase() {
            return phase;
        }

        public void setPhase(NegotiationPhase phase) {
            this.phase = phase;
            updateActivity();
            System.out.printf("[PHASE] %s -> %s%n", negotiationId, phase);
        }

        public AssignationData getAssignationData() {
            return assignationData;
        }

        public AtomicInteger getSentRequests() {
            return sentRequests;
        }

        public AtomicInteger getReceivedResponses() {
            return receivedResponses;
        }

        public List<String> getRejectedRooms() {
            return rejectedRooms;
        }
    }

    /**
     * Router inteligente de mensajes
     */
    private static class MessageRouter {
        private final SubjectNegotiationManager negotiationManager;
        private final ConflictResolutionManager conflictManager;

        public MessageRouter(SubjectNegotiationManager negotiationManager,
                             ConflictResolutionManager conflictManager) {
            this.negotiationManager = negotiationManager;
            this.conflictManager = conflictManager;
        }

        public void routeMessage(ACLMessage msg) {
            String negotiationId = extractNegotiationId(msg);
            SubjectNegotiationState state = negotiationManager.getNegotiation(negotiationId);

            if (state == null || !state.isActive()) {
                System.out.printf("[ROUTER] Ignoring message for inactive negotiation %s%n", negotiationId);
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
                case ACLMessage.FAILURE:
                    conflictManager.handleConflict(negotiationId, msg);
                    break;
                default:
                    System.out.printf("[ROUTER] Unknown message type %d for %s%n",
                            msg.getPerformative(), negotiationId);
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

                    System.out.printf("[ROUTER] Added proposal from %s for %s%n",
                            availability.getCodigo(), state.getNegotiationId());
                }
            } catch (UnreadableException e) {
                System.err.println("Error reading proposal: " + e.getMessage());
            }
        }

        private void handleRefusal(ACLMessage msg, SubjectNegotiationState state) {
            String roomCode = msg.getSender().getLocalName().replace("Sala", "");
            state.rejectRoom(roomCode, msg.getContent());
            state.getReceivedResponses().incrementAndGet();
        }

        private void handleConfirmation(ACLMessage msg, SubjectNegotiationState state) {
            try {
                BatchAssignmentConfirmation confirmation =
                        (BatchAssignmentConfirmation) msg.getContentObject();

                if (confirmation != null) {
                    for (BatchAssignmentConfirmation.ConfirmedAssignment assignment :
                            confirmation.getConfirmedAssignments()) {

                        // Update professor schedule
                        state.profesor.updateScheduleInfo(
                                assignment.getDay(),
                                assignment.getClassroomCode(),
                                assignment.getBlock(),
                                state.getSubject().getNombre(),
                                assignment.getSatisfaction()
                        );

                        // Update negotiation state
                        state.assignBlock(
                                assignment.getDay(),
                                assignment.getBlock(),
                                assignment.getClassroomCode()
                        );
                    }
                }
            } catch (UnreadableException e) {
                System.err.println("Error reading confirmation: " + e.getMessage());
            }
        }

        private String extractNegotiationId(ACLMessage msg) {
            String conversationId = msg.getConversationId();
            if (conversationId != null) {
                return conversationId;
            }
            return msg.getUserDefinedParameter("negotiationId");
        }
    }

    /**
     * Manager avanzado para renegociación con múltiples estrategias
     */
    private static class RenegotiationManager {
        private final AgenteProfesor profesor;
        private final SubjectNegotiationManager negotiationManager;
        private final Queue<RenegotiationTask> renegotiationQueue;
        private final Map<String, RenegotiationStrategy> strategies;

        public RenegotiationManager(AgenteProfesor profesor, SubjectNegotiationManager negotiationManager) {
            this.profesor = profesor;
            this.negotiationManager = negotiationManager;
            this.renegotiationQueue = new ConcurrentLinkedQueue<>();
            this.strategies = initializeStrategies();
        }

        private Map<String, RenegotiationStrategy> initializeStrategies() {
            Map<String, RenegotiationStrategy> strategies = new HashMap<>();
            strategies.put("RELAX_CONSTRAINTS", new RelaxConstraintsStrategy());
            strategies.put("ALTERNATIVE_CAMPUS", new AlternativeCampusStrategy());
            strategies.put("FRAGMENT_BLOCKS", new FragmentBlocksStrategy());
            strategies.put("SUBOPTIMAL_ROOMS", new SuboptimalRoomsStrategy());
            strategies.put("TIME_FLEXIBILITY", new TimeFlexibilityStrategy());
            return strategies;
        }

        public void addForRenegotiation(SubjectNegotiationState state, String reason) {
            RenegotiationTask task = new RenegotiationTask(state, reason, selectBestStrategy(state));
            renegotiationQueue.offer(task);
            state.setActive(false);

            System.out.printf("[RENEGOTIATION] Added %s to renegotiation queue (reason: %s, strategy: %s)%n",
                    state.getSubject().getNombre(), reason, task.getStrategy().getName());
        }

        private RenegotiationStrategy selectBestStrategy(SubjectNegotiationState state) {
            // Estrategia inteligente basada en el estado actual
            Asignatura subject = state.getSubject();
            TipoContrato tipoContrato = profesor.getTipoContrato();

            // Si es jornada parcial, más flexibilidad temporal
            if (tipoContrato == TipoContrato.JORNADA_PARCIAL) {
                return strategies.get("TIME_FLEXIBILITY");
            }

            // Si necesita sala de reuniones pero no encuentra
            if (subject.getVacantes() < MEETING_ROOM_THRESHOLD) {
                return strategies.get("SUBOPTIMAL_ROOMS");
            }

            // Si ha rechazado muchas salas, relajar restricciones
            if (state.getRejectedRooms().size() > 5) {
                return strategies.get("RELAX_CONSTRAINTS");
            }

            // Default: fragmentar bloques
            return strategies.get("FRAGMENT_BLOCKS");
        }

        public void processRenegotiations() {
            RenegotiationTask task = renegotiationQueue.poll();
            if (task != null) {
                performRenegotiation(task);
            }
        }

        private void performRenegotiation(RenegotiationTask task) {
            SubjectNegotiationState state = task.getState();
            RenegotiationStrategy strategy = task.getStrategy();

            System.out.printf("[RENEGOTIATION] Applying %s strategy to %s%n",
                    strategy.getName(), state.getSubject().getNombre());

            try {
                if (strategy.apply(state, profesor)) {
                    // Reset state for new attempt
                    state.resetForRetry();
                    state.resetRetries();
                    state.setActive(true);

                    System.out.printf("[RENEGOTIATION] Successfully applied %s to %s%n",
                            strategy.getName(), state.getSubject().getNombre());
                } else {
                    // Try next strategy or give up
                    if (state.getRetryCount() < 2) {
                        state.incrementRetry();
                        RenegotiationTask newTask = new RenegotiationTask(
                                state, "Strategy failed", selectAlternativeStrategy(strategy));
                        renegotiationQueue.offer(newTask);
                    } else {
                        System.out.printf("[RENEGOTIATION] Giving up on %s after multiple strategies%n",
                                state.getSubject().getNombre());
                        negotiationManager.completeNegotiation(state.getNegotiationId()); // Mark as failed but complete
                    }
                }
            } catch (Exception e) {
                System.err.printf("[RENEGOTIATION] Error applying strategy %s: %s%n",
                        strategy.getName(), e.getMessage());
            }
        }

        private RenegotiationStrategy selectAlternativeStrategy(RenegotiationStrategy currentStrategy) {
            // Rotar estrategias
            List<RenegotiationStrategy> available = new ArrayList<>(strategies.values());
            available.remove(currentStrategy);
            return available.isEmpty() ? currentStrategy : available.get(0);
        }

        private static class RenegotiationTask {
            private final SubjectNegotiationState state;
            private final String reason;
            private final RenegotiationStrategy strategy;
            private final long timestamp;

            public RenegotiationTask(SubjectNegotiationState state, String reason, RenegotiationStrategy strategy) {
                this.state = state;
                this.reason = reason;
                this.strategy = strategy;
                this.timestamp = System.currentTimeMillis();
            }

            public SubjectNegotiationState getState() { return state; }
            public String getReason() { return reason; }
            public RenegotiationStrategy getStrategy() { return strategy; }
            public long getTimestamp() { return timestamp; }
        }

        // Estrategias de renegociación
        private interface RenegotiationStrategy {
            boolean apply(SubjectNegotiationState state, AgenteProfesor profesor);
            String getName();
        }

        private static class RelaxConstraintsStrategy implements RenegotiationStrategy {
            @Override
            public boolean apply(SubjectNegotiationState state, AgenteProfesor profesor) {
                // Relajar restricciones de tiempo y campus
                state.getRejectedRooms().clear();
                return true;
            }

            @Override
            public String getName() { return "RELAX_CONSTRAINTS"; }
        }

        private static class AlternativeCampusStrategy implements RenegotiationStrategy {
            @Override
            public boolean apply(SubjectNegotiationState state, AgenteProfesor profesor) {
                // TODO: Implementar lógica para considerar campus alternativo
                return true;
            }

            @Override
            public String getName() { return "ALTERNATIVE_CAMPUS"; }
        }

        private static class FragmentBlocksStrategy implements RenegotiationStrategy {
            @Override
            public boolean apply(SubjectNegotiationState state, AgenteProfesor profesor) {
                // TODO: Implementar lógica para fragmentar bloques de tiempo
                return true;
            }

            @Override
            public String getName() { return "FRAGMENT_BLOCKS"; }
        }

        private static class SuboptimalRoomsStrategy implements RenegotiationStrategy {
            @Override
            public boolean apply(SubjectNegotiationState state, AgenteProfesor profesor) {
                // Aceptar salas subóptimas
                state.getRejectedRooms().clear();
                return true;
            }

            @Override
            public String getName() { return "SUBOPTIMAL_ROOMS"; }
        }

        private static class TimeFlexibilityStrategy implements RenegotiationStrategy {
            @Override
            public boolean apply(SubjectNegotiationState state, AgenteProfesor profesor) {
                // TODO: Implementar mayor flexibilidad temporal
                return true;
            }

            @Override
            public String getName() { return "TIME_FLEXIBILITY"; }
        }
    }

    /**
     * Manager para resolución de conflictos mejorado
     */
    private static class ConflictResolutionManager {
        private final AgenteProfesor profesor;
        private final Map<String, ConflictInfo> activeConflicts;
        private final Random random;

        public ConflictResolutionManager(AgenteProfesor profesor) {
            this.profesor = profesor;
            this.activeConflicts = new ConcurrentHashMap<>();
            this.random = new Random();
        }

        public void handleConflict(String negotiationId, ACLMessage conflictMsg) {
            ConflictInfo conflict = new ConflictInfo(negotiationId, conflictMsg);
            activeConflicts.put(negotiationId, conflict);

            System.out.printf("[CONFLICT] Detected conflict for negotiation %s%n", negotiationId);

            // Implementar resolución de conflicto con backoff inteligente
            resolveConflictWithBackoff(conflict);
        }

        private void resolveConflictWithBackoff(ConflictInfo conflict) {
            // Backoff exponencial con jitter
            int baseDelay = 100;
            int maxDelay = 2000;
            int attempt = conflict.getAttempts();

            int delay = Math.min(baseDelay * (1 << attempt), maxDelay);
            delay += random.nextInt(delay / 2); // Add jitter

            try {
                Thread.sleep(delay);
                System.out.printf("[CONFLICT] Resolved conflict for %s with %dms backoff%n",
                        conflict.negotiationId, delay);

                activeConflicts.remove(conflict.negotiationId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static class ConflictInfo {
            final String negotiationId;
            final ACLMessage originalMessage;
            final long timestamp;
            private int attempts;

            ConflictInfo(String negotiationId, ACLMessage msg) {
                this.negotiationId = negotiationId;
                this.originalMessage = msg;
                this.timestamp = System.currentTimeMillis();
                this.attempts = 0;
            }

            public int getAttempts() { return attempts; }
            public void incrementAttempts() { attempts++; }
        }
    }

    /**
     * Monitor de performance mejorado
     */
    private static class PerformanceMonitor {
        private final Map<String, Long> negotiationStartTimes;
        private final Map<String, Long> negotiationEndTimes;
        private final AtomicInteger totalMessages;
        private final AtomicInteger successfulAssignments;
        private final AtomicInteger failedAssignments;

        public PerformanceMonitor() {
            this.negotiationStartTimes = new ConcurrentHashMap<>();
            this.negotiationEndTimes = new ConcurrentHashMap<>();
            this.totalMessages = new AtomicInteger(0);
            this.successfulAssignments = new AtomicInteger(0);
            this.failedAssignments = new AtomicInteger(0);
        }

        public void startNegotiation(String negotiationId) {
            negotiationStartTimes.put(negotiationId, System.currentTimeMillis());
        }

        public void endNegotiation(String negotiationId, boolean success) {
            negotiationEndTimes.put(negotiationId, System.currentTimeMillis());
            if (success) {
                successfulAssignments.incrementAndGet();
            } else {
                failedAssignments.incrementAndGet();
            }
        }

        public void recordMessage() {
            totalMessages.incrementAndGet();
        }

        public void printSummary() {
            System.out.println("\n=== PERFORMANCE SUMMARY ===");
            System.out.printf("Total messages: %d%n", totalMessages.get());
            System.out.printf("Successful assignments: %d%n", successfulAssignments.get());
            System.out.printf("Failed assignments: %d%n", failedAssignments.get());

            if (!negotiationStartTimes.isEmpty()) {
                double avgTime = negotiationStartTimes.entrySet().stream()
                        .filter(entry -> negotiationEndTimes.containsKey(entry.getKey()))
                        .mapToLong(entry -> negotiationEndTimes.get(entry.getKey()) - entry.getValue())
                        .average()
                        .orElse(0.0);
                System.out.printf("Average negotiation time: %.2f ms%n", avgTime);
            }
        }
    }

    /**
     * Comportamiento coordinador principal para manejo paralelo mejorado
     */
    private class ParallelCoordinatorBehaviour extends TickerBehaviour {
        private boolean initialized = false;
        private long lastProgressCheck = 0;
        private static final long PROGRESS_CHECK_INTERVAL = 5000; // 5 seconds

        public ParallelCoordinatorBehaviour() {
            super(myAgent, 1000); // Check every second
        }

        @Override
        protected void onTick() {
            if (!initialized) {
                System.out.println("[PARALLEL] Initializing parallel negotiations for " + profesor.getNombre());
                negotiationManager.initializeAllNegotiations();
                initialized = true;
                return;
            }

            // Process active negotiations
            processActiveNegotiations();

            // Check for stale negotiations
            handleStaleNegotiations();

            // Progress reporting
            reportProgress();

            // Check if all negotiations are complete
            if (negotiationManager.allNegotiationsComplete()) {
                System.out.println("[PARALLEL] All negotiations completed for " + profesor.getNombre());
                performanceMonitor.printSummary();
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
                        initializeNegotiation(state);
                        break;
                    case REQUESTING:
                        // Waiting for responses - check timeout
                        if (state.isStale(NEGOTIATION_TIMEOUT)) {
                            handleNegotiationTimeout(state);
                        }
                        break;
                    case WAITING:
                        checkForAvailableProposals(state);
                        break;
                    case EVALUATING:
                        evaluateProposals(state);
                        break;
                    case ASSIGNING:
                        // Waiting for assignment confirmation
                        if (state.isStale(5000)) { // 5 second timeout for assignments
                            retryAssignment(state);
                        }
                        break;
                    case COMPLETED:
                        handleCompletedNegotiation(state);
                        break;
                    case FAILED:
                        handleFailedNegotiation(state);
                        break;
                }
            }
        }

        private void initializeNegotiation(SubjectNegotiationState state) {
            performanceMonitor.startNegotiation(state.getNegotiationId());
            sendParallelProposalRequests(state);
            state.setPhase(SubjectNegotiationState.NegotiationPhase.REQUESTING);
        }

        private void handleNegotiationTimeout(SubjectNegotiationState state) {
            System.out.printf("[TIMEOUT] Negotiation %s timed out in %s phase%n",
                    state.getNegotiationId(), state.getPhase());

            if (state.shouldRetry()) {
                state.incrementRetry();
                state.resetForRetry();
                System.out.printf("[RETRY] Retrying negotiation %s (attempt %d)%n",
                        state.getNegotiationId(), state.getRetryCount());
            } else {
                renegotiationManager.addForRenegotiation(state, "Negotiation timeout");
            }
        }

        private void checkForAvailableProposals(SubjectNegotiationState state) {
            // Check if we have received all expected responses
            if (state.getReceivedResponses().get() >= state.getSentRequests().get() &&
                    state.getSentRequests().get() > 0) {

                if (!state.getProposals().isEmpty()) {
                    state.setPhase(SubjectNegotiationState.NegotiationPhase.EVALUATING);
                } else {
                    // No proposals received
                    if (state.shouldRetry()) {
                        state.incrementRetry();
                        state.resetForRetry();
                    } else {
                        renegotiationManager.addForRenegotiation(state, "No proposals received");
                    }
                }
            }
        }

        private void evaluateProposals(SubjectNegotiationState state) {
            List<BatchProposal> proposals = new ArrayList<>();

            // Collect all available proposals
            BatchProposal proposal;
            while ((proposal = state.getProposals().poll()) != null) {
                proposals.add(proposal);
            }

            if (!proposals.isEmpty()) {
                // Filter and sort proposals
                List<BatchProposal> validProposals = negotiationManager.getEvaluator()
                        .filterAndSortProposals(proposals);

                if (!validProposals.isEmpty()) {
                    state.setPhase(SubjectNegotiationState.NegotiationPhase.ASSIGNING);
                    attemptAssignment(state, validProposals);
                } else {
                    System.out.printf("[EVALUATION] No valid proposals for %s%n", state.getNegotiationId());
                    if (state.shouldRetry()) {
                        state.incrementRetry();
                        state.resetForRetry();
                    } else {
                        renegotiationManager.addForRenegotiation(state, "No valid proposals");
                    }
                }
            }
        }

        private void attemptAssignment(SubjectNegotiationState state, List<BatchProposal> validProposals) {
            try {
                boolean assigned = false;

                for (BatchProposal proposal : validProposals) {
                    if (state.getBlocksRemaining() <= 0) break;

                    if (tryAssignFromProposal(state, proposal)) {
                        assigned = true;
                        System.out.printf("[ASSIGNMENT] Successfully assigned blocks for %s from room %s%n",
                                state.getSubject().getNombre(), proposal.getRoomCode());

                        // Check if negotiation is complete
                        if (state.isComplete()) {
                            state.setPhase(SubjectNegotiationState.NegotiationPhase.COMPLETED);
                        } else {
                            // Need more blocks, restart the process
                            state.resetForRetry();
                            state.setPhase(SubjectNegotiationState.NegotiationPhase.INITIALIZING);
                        }
                        break;
                    }
                }

                if (!assigned) {
                    System.out.printf("[ASSIGNMENT] Failed to assign any blocks for %s%n", state.getNegotiationId());
                    if (state.shouldRetry()) {
                        state.incrementRetry();
                        state.resetForRetry();
                    } else {
                        renegotiationManager.addForRenegotiation(state, "Assignment failed");
                    }
                }
            } catch (Exception e) {
                System.err.printf("[ASSIGNMENT] Error during assignment for %s: %s%n",
                        state.getNegotiationId(), e.getMessage());
                state.setPhase(SubjectNegotiationState.NegotiationPhase.FAILED);
            }
        }

        private void retryAssignment(SubjectNegotiationState state) {
            System.out.printf("[RETRY] Assignment timeout for %s, retrying...%n", state.getNegotiationId());
            if (state.shouldRetry()) {
                state.incrementRetry();
                state.resetForRetry();
            } else {
                state.setPhase(SubjectNegotiationState.NegotiationPhase.FAILED);
            }
        }

        private void handleCompletedNegotiation(SubjectNegotiationState state) {
            System.out.printf("[COMPLETED] Subject %s completed successfully with %d blocks assigned%n",
                    state.getSubject().getNombre(),
                    state.getSubject().getHoras() - state.getBlocksRemaining());

            performanceMonitor.endNegotiation(state.getNegotiationId(), true);
            negotiationManager.completeNegotiation(state.getNegotiationId());
        }

        private void handleFailedNegotiation(SubjectNegotiationState state) {
            System.out.printf("[FAILED] Subject %s failed after %d retries%n",
                    state.getSubject().getNombre(), state.getRetryCount());

            performanceMonitor.endNegotiation(state.getNegotiationId(), false);
            renegotiationManager.addForRenegotiation(state, "Negotiation failed");
        }

        private void handleStaleNegotiations() {
            for (String negotiationId : negotiationManager.getActiveNegotiationIds()) {
                SubjectNegotiationState state = negotiationManager.getNegotiation(negotiationId);

                if (state != null && state.isStale(STALE_THRESHOLD)) {
                    System.out.printf("[STALE] Handling stale negotiation %s in phase %s%n",
                            negotiationId, state.getPhase());

                    if (state.shouldRetry()) {
                        state.incrementRetry();
                        state.resetForRetry();
                        System.out.printf("[STALE] Restarting stale negotiation %s%n", negotiationId);
                    } else {
                        renegotiationManager.addForRenegotiation(state, "Stale negotiation");
                    }
                }
            }
        }

        private void reportProgress() {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastProgressCheck > PROGRESS_CHECK_INTERVAL) {
                int active = negotiationManager.getActiveNegotiationIds().size();
                int completed = negotiationManager.completedSubjects.get();
                int total = profesor.getAsignaturas().size();

                System.out.printf("[PROGRESS] Professor %s: %d active, %d/%d completed%n",
                        profesor.getNombre(), active, completed, total);

                lastProgressCheck = currentTime;
            }
        }
    }

    /**
     * Manejador de mensajes paralelo mejorado
     */
    private class ParallelMessageHandler extends CyclicBehaviour {

        @Override
        public void action() {
            boolean messageProcessed = false;

            // Process all available messages in one cycle
            messageProcessed |= handleProposalMessages();
            messageProcessed |= handleConfirmationMessages();
            messageProcessed |= handleConflictMessages();
            messageProcessed |= handleRenegotiationMessages();

            if (!messageProcessed) {
                block(50); // Block briefly if no messages were processed
            }
        }

        private boolean handleProposalMessages() {
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
            );

            boolean processed = false;
            ACLMessage msg;

            while ((msg = myAgent.receive(mt)) != null) {
                messageLogger.logMessageReceived(myAgent.getLocalName(), msg);
                performanceMonitor.recordMessage();
                messageRouter.routeMessage(msg);
                logRequest(msg, msg.getPerformative() == ACLMessage.PROPOSE);
                processed = true;
            }

            return processed;
        }

        private boolean handleConfirmationMessages() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                messageLogger.logMessageReceived(myAgent.getLocalName(), msg);
                performanceMonitor.recordMessage();
                messageRouter.routeMessage(msg);
                return true;
            }

            return false;
        }

        private boolean handleConflictMessages() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.FAILURE),
                    MessageTemplate.MatchContent("CONFLICT")
            );

            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                messageLogger.logMessageReceived(myAgent.getLocalName(), msg);
                performanceMonitor.recordMessage();
                messageRouter.routeMessage(msg);
                return true;
            }

            return false;
        }

        private boolean handleRenegotiationMessages() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchContent("RENEGOTIATE")
            );

            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                messageLogger.logMessageReceived(myAgent.getLocalName(), msg);
                performanceMonitor.recordMessage();
                handleRenegotiationRequest(msg);
                return true;
            }

            return false;
        }

        private void handleRenegotiationRequest(ACLMessage msg) {
            String negotiationId = messageRouter.extractNegotiationId(msg);
            SubjectNegotiationState state = negotiationManager.getNegotiation(negotiationId);

            if (state != null) {
                System.out.printf("[RENEGOTIATION] Received external renegotiation request for %s%n",
                        negotiationId);
                renegotiationManager.addForRenegotiation(state, "External request");
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

    /**
     * Comportamiento para detección y manejo de conflictos
     */
    private class ConflictDetectionBehaviour extends TickerBehaviour {

        public ConflictDetectionBehaviour() {
            super(myAgent, 2000); // Check every 2 seconds
        }

        @Override
        protected void onTick() {
            // Check for potential conflicts between active negotiations
            detectScheduleConflicts();

            // Process renegotiations
            renegotiationManager.processRenegotiations();

            // Check for resource contention
            detectResourceContention();
        }

        private void detectScheduleConflicts() {
            Map<String, SubjectNegotiationState> activeNegotiations =
                    negotiationManager.getAllActiveNegotiations();

            // Check for potential double-booking attempts
            Map<String, Set<String>> roomTimeSlots = new HashMap<>();

            for (SubjectNegotiationState state : activeNegotiations.values()) {
                // Analyze assigned blocks for conflicts
                for (Map.Entry<Day, Set<Integer>> dayEntry : state.assignedBlocks.entrySet()) {
                    Day day = dayEntry.getKey();
                    Set<Integer> blocks = dayEntry.getValue();

                    String preferredRoom = state.getPreferredRoom();
                    if (preferredRoom != null) {
                        for (Integer block : blocks) {
                            String timeSlot = day + "-" + block + "-" + preferredRoom;
                            roomTimeSlots.computeIfAbsent(timeSlot, k -> new HashSet<>())
                                    .add(state.getNegotiationId());
                        }
                    }
                }
            }

            // Report conflicts
            for (Map.Entry<String, Set<String>> entry : roomTimeSlots.entrySet()) {
                if (entry.getValue().size() > 1) {
                    System.out.printf("[CONFLICT] Potential conflict detected at %s between negotiations: %s%n",
                            entry.getKey(), entry.getValue());
                }
            }
        }

        private void detectResourceContention() {
            // Monitor message queue sizes and processing times
            int queueSize = myAgent.getCurQueueSize();
            if (queueSize > 50) { // Threshold for high contention
                System.out.printf("[CONTENTION] High message queue size detected: %d messages%n", queueSize);

                // Implement throttling or priority adjustment
                adjustProcessingStrategy();
            }
        }

        private void adjustProcessingStrategy() {
            // Implement adaptive processing strategy
            // Could involve reducing concurrent negotiations, adjusting timeouts, etc.
            System.out.println("[ADAPTATION] Adjusting processing strategy due to high contention");
        }
    }

    /**
     * Comportamiento específico para renegociación
     */
    private class RenegotiationBehaviour extends TickerBehaviour {

        public RenegotiationBehaviour() {
            super(myAgent, 3000); // Process renegotiations every 3 seconds
        }

        @Override
        protected void onTick() {
            // Process pending renegotiations
            renegotiationManager.processRenegotiations();
        }
    }

    /**
     * Monitor de performance como comportamiento
     */
    private class PerformanceMonitorBehaviour extends TickerBehaviour {

        public PerformanceMonitorBehaviour() {
            super(myAgent, 10000); // Report every 10 seconds
        }

        @Override
        protected void onTick() {
            // Generate periodic performance reports
            generatePerformanceReport();
        }

        private void generatePerformanceReport() {
            int active = negotiationManager.getActiveNegotiationIds().size();
            int completed = negotiationManager.completedSubjects.get();
            int total = profesor.getAsignaturas().size();

            if (completed > 0 || active > 0) {
                System.out.printf("[PERFORMANCE] Professor %s - Active: %d, Completed: %d/%d, Messages: %d%n",
                        profesor.getNombre(), active, completed, total, performanceMonitor.totalMessages.get());
            }
        }
    }

    // Utility methods
    private void sendParallelProposalRequests(SubjectNegotiationState state) {
        try {
            List<DFAgentDescription> rooms = DFCache.search(profesor, AgenteSala.SERVICE_NAME);

            // Filter rooms based on state preferences and constraints
            List<DFAgentDescription> viableRooms = rooms.stream()
                    .filter(room -> shouldSendToRoom(state, room))
                    .collect(Collectors.toList());

            System.out.printf("[REQUEST] Sending proposals to %d rooms for %s%n",
                    viableRooms.size(), state.getNegotiationId());

            for (DFAgentDescription room : viableRooms) {
                ACLMessage cfp = createParallelCFP(state, room);

                rttLogger.startRequest(
                        myAgent.getLocalName(),
                        cfp.getConversationId(),
                        ACLMessage.CFP,
                        room.getName().getLocalName(),
                        null,
                        "parallel-classroom-availability"
                );

                messageLogger.logMessageSent(myAgent.getLocalName(), cfp);
                profesor.send(cfp);
                state.getSentRequests().incrementAndGet();
            }

            state.setPhase(SubjectNegotiationState.NegotiationPhase.WAITING);

        } catch (Exception e) {
            System.err.println("Error sending parallel requests: " + e.getMessage());
            state.setPhase(SubjectNegotiationState.NegotiationPhase.FAILED);
        }
    }

    private boolean shouldSendToRoom(SubjectNegotiationState state, DFAgentDescription room) {
        String roomCode = room.getName().getLocalName().replace("Sala", "");

        // Skip rejected rooms
        if (state.isRoomRejected(roomCode)) {
            return false;
        }

        Asignatura subject = state.getSubject();

        // Basic filtering logic
        ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
        List<Property> props = new ArrayList<>();
        sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

        if (props.size() < 3) return false;

        String roomCampus = (String) props.get(0).getValue();
        int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

        // Campus matching (relaxed for renegotiation)
        if (!roomCampus.equals(subject.getCampus()) && state.getRetryCount() == 0) {
            return false;
        }

        // Capacity check (relaxed for small classes in renegotiation)
        if (roomCapacity < subject.getVacantes() && state.getRetryCount() < 2) {
            return false;
        }

        // Meeting room logic
        boolean subjectNeedsMeetingRoom = subject.getVacantes() < MEETING_ROOM_THRESHOLD;
        boolean isMeetingRoom = roomCapacity < MEETING_ROOM_THRESHOLD;

        if (subjectNeedsMeetingRoom != isMeetingRoom && state.getRetryCount() == 0) {
            return false;
        }

        return true;
    }

    private ACLMessage createParallelCFP(SubjectNegotiationState state, DFAgentDescription room) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setSender(profesor.getAID());
        cfp.addReceiver(room.getName());
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setConversationId(state.getNegotiationId());
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

    private boolean tryAssignFromProposal(SubjectNegotiationState state, BatchProposal proposal) throws IOException {
        List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();

        // Create assignment requests based on available blocks and current needs
        int blocksNeeded = Math.min(state.getBlocksRemaining(), 2); // Max 2 blocks per request
        int blocksRequested = 0;

        Map<Day, Integer> dailyAssignments = new HashMap<>();

        for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                proposal.getDayProposals().entrySet()) {

            if (blocksRequested >= blocksNeeded) break;

            Day day = entry.getKey();
            List<BatchProposal.BlockProposal> blocks = entry.getValue();

            // Skip if day already has assignments
            if (dailyAssignments.getOrDefault(day, 0) >= 2) continue;

            for (BatchProposal.BlockProposal block : blocks) {
                if (blocksRequested >= blocksNeeded) break;

                // Check if professor is available at this time
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
                dailyAssignments.merge(day, 1, Integer::sum);
            }
        }

        if (!requests.isEmpty()) {
            return sendParallelAssignment(state, requests, proposal.getOriginalMessage());
        }

        return false;
    }

    private boolean sendParallelAssignment(SubjectNegotiationState state,
                                           List<BatchAssignmentRequest.AssignmentRequest> requests,
                                           ACLMessage originalMsg) throws IOException {
        BatchAssignmentRequest batchRequest = new BatchAssignmentRequest(requests);

        ACLMessage accept = originalMsg.createReply();
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        accept.setContentObject(batchRequest);
        accept.addUserDefinedParameter("negotiationId", state.getNegotiationId());

        messageLogger.logMessageSent(myAgent.getLocalName(), accept);
        profesor.send(accept);

        System.out.printf("[ASSIGNMENT] Sent assignment request for %d blocks to room %s%n",
                requests.size(), requests.get(0).getClassroomCode());

        return true; // Confirmation will be handled by message router
    }

    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    public int getBloquesPendientes() {
        return negotiationManager.getAllActiveNegotiations().values().stream()
                .mapToInt(SubjectNegotiationState::getBlocksRemaining)
                .sum();
    }

    // Getter for integration with existing professor interface
    public SubjectNegotiationManager getNegotiationManager() {
        return negotiationManager;
    }

    private static class SubjectNegotiationManager {
        private final AgenteProfesor profesor;
        private final Map<String, SubjectNegotiationState> activeNegotiations;
        private final Map<String, SubjectNegotiationState> completedNegotiations;
        private final AtomicInteger completedSubjects;
        private final ConstraintEvaluator evaluator;
        private final Queue<String> pendingNegotiations;
        private final AtomicInteger activeCount;

        public SubjectNegotiationManager(AgenteProfesor profesor) {
            this.profesor = profesor;
            this.activeNegotiations = new ConcurrentHashMap<>();
            this.completedNegotiations = new ConcurrentHashMap<>();
            this.completedSubjects = new AtomicInteger(0);
            this.evaluator = new ConstraintEvaluator(profesor);
            this.pendingNegotiations = new ConcurrentLinkedQueue<>();
            this.activeCount = new AtomicInteger(0);
        }

        public synchronized void initializeAllNegotiations() {
            List<Asignatura> subjects = profesor.getAsignaturas();

            // Crear todas las negociaciones pero no activarlas todas
            for (int i = 0; i < subjects.size(); i++) {
                Asignatura subject = subjects.get(i);
                String negotiationId = generateNegotiationId(subject, i);

                SubjectNegotiationState state = new SubjectNegotiationState(
                        subject, i, negotiationId, profesor
                );

                pendingNegotiations.offer(negotiationId);
                activeNegotiations.put(negotiationId, state);

                System.out.printf("[PARALLEL] Initialized negotiation for %s (ID: %s)%n",
                        subject.getNombre(), negotiationId);
            }

            // Activar las primeras negociaciones
            activateNextNegotiations();
        }

        public synchronized void activateNextNegotiations() {
            while (activeCount.get() < MAX_CONCURRENT_NEGOTIATIONS && !pendingNegotiations.isEmpty()) {
                String negotiationId = pendingNegotiations.poll();
                SubjectNegotiationState state = activeNegotiations.get(negotiationId);

                if (state != null && !state.isActive()) {
                    state.setActive(true);
                    activeCount.incrementAndGet();
                    System.out.printf("[PARALLEL] Activated negotiation %s (%d active)%n",
                            negotiationId, activeCount.get());
                }
            }
        }

        public Set<String> getActiveNegotiationIds() {
            return activeNegotiations.entrySet().stream()
                    .filter(entry -> entry.getValue().isActive())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
        }

        public SubjectNegotiationState getNegotiation(String id) {
            return activeNegotiations.get(id);
        }

        public synchronized void completeNegotiation(String id) {
            SubjectNegotiationState state = activeNegotiations.remove(id);
            if (state != null) {
                completedNegotiations.put(id, state);
                completedSubjects.incrementAndGet();
                activeCount.decrementAndGet();

                System.out.printf("[PARALLEL] Completed negotiation %s (%d/%d subjects done)%n",
                        id, completedSubjects.get(), profesor.getAsignaturas().size());

                // Activar siguiente negociación
                activateNextNegotiations();
            }
        }

        public boolean allNegotiationsComplete() {
            return activeNegotiations.isEmpty() && pendingNegotiations.isEmpty();
        }

        public Map<String, SubjectNegotiationState> getAllActiveNegotiations() {
            return new HashMap<>(activeNegotiations);
        }

        public ConstraintEvaluator getEvaluator() {
            return evaluator;
        }

        private String generateNegotiationId(Asignatura subject, int index) {
            return String.format("%s-%s-%d-%d",
                    profesor.getNombre(),
                    subject.getCodigoAsignatura(),
                    index,
                    System.currentTimeMillis() % 10000);
        }

        /**
         *
         */
    }
}