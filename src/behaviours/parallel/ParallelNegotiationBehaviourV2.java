package behaviours.parallel;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.enums.Day;
import df.DFCache;
import evaluators.ConstraintEvaluator;
import jade.core.behaviours.Behaviour;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import objetos.Asignatura;
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
import java.util.stream.Collectors;

/**
 * Simplified parallel negotiation behavior - robust and deadlock-free
 */
public class ParallelNegotiationBehaviourV2 extends Behaviour {
    private final AgenteProfesor profesor;
    private final List<SubjectTask> tasks;
    private final Map<String, SubjectTask> tasksByConvId;
    private final ConstraintEvaluator evaluator;
    private final AgentMessageLogger messageLogger;
    private final RTTLogger rttLogger;

    // Configuration
    private static final int MAX_CONCURRENT = 2;
    private static final long TIMEOUT_MS = 5000;
    private static final long STUCK_THRESHOLD_MS = 2000;
    private static final int MAX_RETRIES = 3;
    private static final int MEETING_ROOM_THRESHOLD = 10;
    private static final int EARLY_TERMINATION_THRESHOLD = 5;

    private int completedTasks = 0;
    private boolean done = false;

    private final Queue<SubjectTask> pendingTasks;  // Add this
    private final Set<SubjectTask> activeTasks;      // Add this

    public ParallelNegotiationBehaviourV2(AgenteProfesor profesor) {
        this.profesor = profesor;
        this.tasks = new ArrayList<>();
        this.tasksByConvId = new ConcurrentHashMap<>();
        this.evaluator = new ConstraintEvaluator(profesor);
        this.messageLogger = AgentMessageLogger.getInstance();
        this.rttLogger = RTTLogger.getInstance();
        this.pendingTasks = new LinkedList<>();  // Add this
        this.activeTasks = new HashSet<>();      // Add this

        initializeTasks();
    }

    /**
     * Task representation for each subject
     */
    private static class SubjectTask {
        final Asignatura subject;
        final int subjectIndex;
        int blocksRemaining;
        int retries = 0;
        long startTime;
        boolean isActive = false;

        // Tracking
        int sentCount = 0;
        int receivedCount = 0;
        Set<String> pendingConversations = new HashSet<>();
        Queue<BatchProposal> proposals = new ConcurrentLinkedQueue<>();

        // Assignment state
        String lastAssignedRoom = null;
        Day lastAssignedDay = null;
        int lastAssignedBlock = -1;
        boolean hasPartialAssignment = false;

        // Negotiation strategy
        Set<String> rejectedRooms = new HashSet<>();
        boolean expandedSearch = false;
        int minAcceptableSatisfaction = 7;

        SubjectTask(Asignatura subject, int index) {
            this.subject = subject;
            this.subjectIndex = index;
            this.blocksRemaining = subject.getHoras();
        }

        boolean isComplete() {
            return blocksRemaining <= 0;
        }

        void reset() {
            sentCount = 0;
            receivedCount = 0;
            pendingConversations.clear();
            proposals.clear();
            startTime = System.currentTimeMillis();
        }

        void adjustNegotiationStrategy() {
            if (retries > 0) {
                minAcceptableSatisfaction = Math.max(3, minAcceptableSatisfaction - 2);
            }
            if (rejectedRooms.size() > 5 && !expandedSearch) {
                expandedSearch = true;
            }
        }
    }

    private void initializeTasks() {
        List<Asignatura> subjects = profesor.getAsignaturas();
        for (int i = 0; i < subjects.size(); i++) {
            SubjectTask task = new SubjectTask(subjects.get(i), i);
            tasks.add(task);
            pendingTasks.offer(task);  // Add all tasks to pending queue
        }
        System.out.printf("[PARALLEL] Initialized %d tasks for %s%n",
                tasks.size(), profesor.getNombre());
    }

    // 4. Replace startNewNegotiations method
    private void startNewNegotiations() {
        while (activeTasks.size() < MAX_CONCURRENT && !pendingTasks.isEmpty()) {
            SubjectTask task = pendingTasks.poll();

            if (task == null) break;

            if (!task.isComplete()) {
                startTaskNegotiation(task);
                activeTasks.add(task);
            } else {
                completedTasks++;
            }
        }
    }

    // 5. Replace cleanupStuckNegotiations method
    private void cleanupStuckNegotiations() {
        long now = System.currentTimeMillis();
        List<SubjectTask> stuckTasks = new ArrayList<>();

        for (SubjectTask task : activeTasks) {
            if (task.isActive && (now - task.startTime) > STUCK_THRESHOLD_MS) {
                System.out.printf("[STUCK] Force cleanup for %s after %d ms%n",
                        task.subject.getNombre(), now - task.startTime);
                stuckTasks.add(task);
            }
        }

        for (SubjectTask task : stuckTasks) {
            // Remove from active set and conversation tracking
            activeTasks.remove(task);
            task.pendingConversations.forEach(tasksByConvId::remove);
            task.isActive = false;

            if (task.retries < MAX_RETRIES && task.blocksRemaining > 0) {
                task.retries++;
                pendingTasks.offer(task);  // Re-queue for retry
            } else {
                completedTasks++;
                System.out.printf("[ABANDON] Task %s abandoned after timeouts%n",
                        task.subject.getNombre());
            }
        }
    }

    // 6. Replace retryOrAbandon method
    private void retryOrAbandon(SubjectTask task) {
        // Remove from active tracking
        task.isActive = false;
        activeTasks.remove(task);
        task.pendingConversations.forEach(tasksByConvId::remove);

        if (task.retries < MAX_RETRIES && task.blocksRemaining > 0) {
            task.retries++;
            pendingTasks.offer(task);  // Re-queue instead of index manipulation
            System.out.printf("[RETRY] Will retry %s (attempt %d/%d)%n",
                    task.subject.getNombre(), task.retries + 1, MAX_RETRIES);
        } else {
            completedTasks++;
            if (task.blocksRemaining > 0) {
                System.out.printf("[ABANDON] Abandoning %s with %d blocks unassigned%n",
                        task.subject.getNombre(), task.blocksRemaining);
            }
        }
    }

    // 7. Replace handleConfirmation method
    private void handleConfirmation(SubjectTask task, ACLMessage msg) {
        try {
            BatchAssignmentConfirmation confirmation =
                    (BatchAssignmentConfirmation) msg.getContentObject();

            if (confirmation != null && !confirmation.getConfirmedAssignments().isEmpty()) {
                // Update professor's schedule with proper index handling
                int originalIndex = profesor.asignaturaActual;
                profesor.asignaturaActual = task.subjectIndex;

                try {
                    for (BatchAssignmentConfirmation.ConfirmedAssignment assignment :
                            confirmation.getConfirmedAssignments()) {

                        profesor.updateScheduleInfo(
                                assignment.getDay(),
                                assignment.getClassroomCode(),
                                assignment.getBlock(),
                                task.subject.getNombre(),
                                assignment.getSatisfaction()
                        );

                        task.blocksRemaining--;
                        task.hasPartialAssignment = true;
                        task.lastAssignedDay = assignment.getDay();
                        task.lastAssignedBlock = assignment.getBlock();

                        System.out.printf("[ASSIGNED] %s: block %d on %s in %s (%d remaining)%n",
                                task.subject.getNombre(), assignment.getBlock(),
                                assignment.getDay(), assignment.getClassroomCode(),
                                task.blocksRemaining);
                    }
                } finally {
                    profesor.asignaturaActual = originalIndex;
                }

                if (task.isComplete()) {
                    task.isActive = false;
                    activeTasks.remove(task);  // Remove from active set
                    completedTasks++;
                    System.out.printf("[COMPLETE] Finished %s%n", task.subject.getNombre());
                } else {
                    // Continue negotiation - task stays in activeTasks
                    startTaskNegotiation(task);
                }
            } else {
                handleAssignmentFailure(task);
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading confirmation: " + e.getMessage());
            handleAssignmentFailure(task);
        }
    }

    // 8. Replace checkCompletion method
    private void checkCompletion() {
        // Check if all tasks are done (no pending, no active)
        if (pendingTasks.isEmpty() && activeTasks.isEmpty()) {
            System.out.printf("[PARALLEL] All tasks processed for %s%n", profesor.getNombre());

            // Print summary
            int totalAssigned = tasks.stream()
                    .mapToInt(t -> t.subject.getHoras() - t.blocksRemaining)
                    .sum();
            int totalRequired = tasks.stream()
                    .mapToInt(t -> t.subject.getHoras())
                    .sum();

            System.out.printf("[SUMMARY] %s: Assigned %d/%d blocks (%.1f%%)%n",
                    profesor.getNombre(), totalAssigned, totalRequired,
                    (totalAssigned * 100.0) / totalRequired);

            profesor.finalizarNegociaciones();
            done = true;
        }
    }

    // 9. Update processTaskProposals to add safety check
    private void processTaskProposals(SubjectTask task) {
        if (!task.isActive) {
            // Task was already handled, ignore
            return;
        }

        if (task.proposals.isEmpty()) {
            handleNoProposals(task);
            return;
        }

        List<BatchProposal> proposalList = new ArrayList<>();
        BatchProposal proposal;
        while ((proposal = task.proposals.poll()) != null) {
            proposalList.add(proposal);
        }

        // Use evaluator with task's current standards
        List<BatchProposal> validProposals = evaluator.filterAndSortProposals(proposalList);

        // Apply satisfaction threshold
        if (task.minAcceptableSatisfaction < 7) {
            validProposals = validProposals.stream()
                    .filter(p -> p.getSatisfactionScore() >= task.minAcceptableSatisfaction)
                    .collect(Collectors.toList());
        }

        if (!validProposals.isEmpty()) {
            tryAssignments(task, validProposals);
        } else {
            handleNoValidProposals(task);
        }
    }

    @Override
    public void action() {
        // Step 1: Clean up stuck negotiations
        cleanupStuckNegotiations();

        // Step 2: Start new negotiations
        startNewNegotiations();

        // Step 3: Process ALL messages
        processAllMessages();

        // Step 4: Check if we're done
        checkCompletion();
    }

    private void startTaskNegotiation(SubjectTask task) {
        task.reset();
        task.isActive = true;
        task.adjustNegotiationStrategy();

        List<DFAgentDescription> rooms = DFCache.search(profesor, AgenteSala.SERVICE_NAME);

        for (DFAgentDescription room : rooms) {
            if (shouldSendToRoom(task, room)) {
                String roomName = room.getName().getLocalName();
                String convId = String.format("%s-%s-%d",
                        sanitizeName(task.subject.getNombre()),
                        roomName,
                        System.currentTimeMillis());

                ACLMessage cfp = createCFP(task, room);
                cfp.setConversationId(convId);

                // Track conversation
                tasksByConvId.put(convId, task);
                task.pendingConversations.add(convId);

                // Send and log
                profesor.send(cfp);
                messageLogger.logMessageSent(profesor.getLocalName(), cfp);

                rttLogger.startRequest(
                        profesor.getLocalName(),
                        convId,
                        ACLMessage.CFP,
                        roomName,
                        null,
                        "classroom-availability"
                );

                task.sentCount++;
            }
        }

        System.out.printf("[START] Sent %d CFPs for %s (retry: %d)%n",
                task.sentCount, task.subject.getNombre(), task.retries);
    }

    private boolean shouldSendToRoom(SubjectTask task, DFAgentDescription room) {
        ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
        List<Property> props = new ArrayList<>();
        sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

        if (props.size() < 3) return false;

        String roomCode = room.getName().getLocalName();
        String roomCampus = (String) props.get(0).getValue();
        int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

        // Skip rejected rooms unless desperate
        if (task.rejectedRooms.contains(roomCode) && task.retries < 2) {
            return false;
        }

        // Campus check with expansion
        if (!roomCampus.equals(task.subject.getCampus()) && !task.expandedSearch) {
            return false;
        }

        // Capacity check
        if (roomCapacity < task.subject.getVacantes()) {
            return false;
        }

        // Meeting room logic
        boolean needsMeetingRoom = task.subject.getVacantes() < MEETING_ROOM_THRESHOLD;
        boolean isMeetingRoom = roomCapacity < MEETING_ROOM_THRESHOLD;

        if (needsMeetingRoom && !isMeetingRoom && task.retries == 0) {
            return false;
        }

        return true;
    }

    private void processAllMessages() {
        MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.REFUSE),
                        MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                                MessageTemplate.MatchPerformative(ACLMessage.FAILURE)
                        )
                )
        );

        ACLMessage msg;
        while ((msg = profesor.receive(mt)) != null) {
            messageLogger.logMessageReceived(profesor.getLocalName(), msg);

            String convId = msg.getConversationId();
            SubjectTask task = tasksByConvId.get(convId);

            if (task == null) {
                task = findTaskByContext(msg);
            }

            if (task != null) {
                processMessageForTask(task, msg);
            } else {
                System.out.printf("[ORPHAN] Message from %s with conv %s%n",
                        msg.getSender().getLocalName(), convId);

                // Log RTT for orphaned messages
                if (msg.getPerformative() != ACLMessage.INFORM) {
                    rttLogger.endRequest(
                            profesor.getLocalName(),
                            convId != null ? convId : "unknown",
                            msg.getPerformative(),
                            0,
                            false,
                            null,
                            "classroom-availability"
                    );
                }
            }
        }
    }

    private SubjectTask findTaskByContext(ACLMessage msg) {
        String convId = msg.getConversationId();

        // Strategy 1: Check if conversation ID contains subject name
        if (convId != null) {
            for (SubjectTask task : tasks) {
                if (task.isActive && convId.contains(sanitizeName(task.subject.getNombre()))) {
                    for (String pending : task.pendingConversations) {
                        if (pending.equals(convId)) {
                            return task;
                        }
                    }
                }
            }
        }

        // Strategy 2: Match by sender for active tasks
        String senderName = msg.getSender().getLocalName();
        for (SubjectTask task : tasks) {
            if (task.isActive) {
                for (String pendingConv : task.pendingConversations) {
                    if (pendingConv.contains(senderName)) {
                        return task;
                    }
                }
            }
        }

        // Strategy 3: For confirmations, find task with matching room
        if (msg.getPerformative() == ACLMessage.INFORM && senderName.startsWith("Sala")) {
            return tasks.stream()
                    .filter(t -> t.isActive && senderName.equals(t.lastAssignedRoom))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    private void processMessageForTask(SubjectTask task, ACLMessage msg) {
        task.pendingConversations.remove(msg.getConversationId());
        task.receivedCount++;

        switch (msg.getPerformative()) {
            case ACLMessage.PROPOSE:
                handleProposal(task, msg);
                break;

            case ACLMessage.REFUSE:
                task.rejectedRooms.add(msg.getSender().getLocalName());
                System.out.printf("[REFUSE] Room %s refused %s%n",
                        msg.getSender().getLocalName(), task.subject.getNombre());
                break;

            case ACLMessage.INFORM:
                handleConfirmation(task, msg);
                return; // Don't check for proposal processing after confirmation

            case ACLMessage.FAILURE:
                task.rejectedRooms.add(msg.getSender().getLocalName());
                System.out.printf("[FAILURE] Assignment failed for %s from %s%n",
                        task.subject.getNombre(), msg.getSender().getLocalName());
                break;
        }

        // Log RTT for non-INFORM messages
        if (msg.getPerformative() != ACLMessage.INFORM) {
            rttLogger.endRequest(
                    profesor.getLocalName(),
                    msg.getConversationId(),
                    msg.getPerformative(),
                    msg.getByteSequenceContent() != null ? msg.getByteSequenceContent().length : 0,
                    msg.getPerformative() == ACLMessage.PROPOSE,
                    null,
                    "classroom-availability"
            );
        }

        // Check if we should process proposals
        if (shouldProcessProposals(task)) {
            processTaskProposals(task);
        }
    }

    private void handleProposal(SubjectTask task, ACLMessage msg) {
        try {
            ClassroomAvailability availability = (ClassroomAvailability) msg.getContentObject();
            if (availability != null) {
                BatchProposal proposal = new BatchProposal(availability, msg);
                task.proposals.offer(proposal);
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading proposal: " + e.getMessage());
        }
    }

    private boolean shouldProcessProposals(SubjectTask task) {
        boolean allResponsesReceived = task.receivedCount >= task.sentCount;
        boolean timeoutReached = System.currentTimeMillis() - task.startTime > TIMEOUT_MS;
        boolean hasEnoughProposals = task.proposals.size() >= EARLY_TERMINATION_THRESHOLD;

        return allResponsesReceived ||
                (timeoutReached && !task.proposals.isEmpty()) ||
                hasEnoughProposals;
    }

    private void tryAssignments(SubjectTask task, List<BatchProposal> proposals) {
        for (BatchProposal proposal : proposals) {
            if (task.blocksRemaining <= 0) break;

            List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();
            int blocksToRequest = Math.min(task.blocksRemaining, 2);

            for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                    proposal.getDayProposals().entrySet()) {

                if (requests.size() >= blocksToRequest) break;

                Day day = entry.getKey();
                for (BatchProposal.BlockProposal block : entry.getValue()) {
                    if (requests.size() >= blocksToRequest) break;

                    if (profesor.isBlockAvailable(day, block.getBlock())) {
                        requests.add(new BatchAssignmentRequest.AssignmentRequest(
                                day,
                                block.getBlock(),
                                task.subject.getNombre(),
                                proposal.getSatisfactionScore(),
                                proposal.getRoomCode(),
                                task.subject.getVacantes(),
                                profesor.getNombre()
                        ));
                    }
                }
            }

            if (!requests.isEmpty()) {
                sendAssignmentRequest(task, proposal, requests);
                return; // Wait for confirmation
            }
        }
    }

    private void sendAssignmentRequest(SubjectTask task, BatchProposal proposal,
                                       List<BatchAssignmentRequest.AssignmentRequest> requests) {
        try {
            BatchAssignmentRequest batchRequest = new BatchAssignmentRequest(requests);

            ACLMessage accept = proposal.getOriginalMessage().createReply();
            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            accept.setContentObject(batchRequest);

            // Track this assignment attempt
            task.lastAssignedRoom = proposal.getRoomCode();

            profesor.send(accept);
            messageLogger.logMessageSent(profesor.getLocalName(), accept);

            System.out.printf("[ACCEPT] Requested %d blocks from %s for %s%n",
                    requests.size(), proposal.getRoomCode(), task.subject.getNombre());

        } catch (IOException e) {
            System.err.println("Error sending assignment: " + e.getMessage());
            handleAssignmentFailure(task);
        }
    }

    private void handleNoProposals(SubjectTask task) {
        System.out.printf("[NO_PROPOSALS] No proposals received for %s%n",
                task.subject.getNombre());
        retryOrAbandon(task);
    }

    private void handleNoValidProposals(SubjectTask task) {
        System.out.printf("[NO_VALID] No valid proposals for %s (min satisfaction: %d)%n",
                task.subject.getNombre(), task.minAcceptableSatisfaction);
        retryOrAbandon(task);
    }

    private void handleAssignmentFailure(SubjectTask task) {
        System.out.printf("[ASSIGN_FAIL] Assignment failed for %s%n",
                task.subject.getNombre());
        retryOrAbandon(task);
    }

    private ACLMessage createCFP(SubjectTask task, DFAgentDescription room) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setSender(profesor.getAID());
        cfp.addReceiver(room.getName());
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

        String content = String.format("%s,%d,%d,%s,%d,%s,%s,%d",
                sanitizeName(task.subject.getNombre()),
                task.subject.getVacantes(),
                task.subject.getNivel(),
                task.subject.getCampus(),
                task.blocksRemaining,
                task.lastAssignedRoom != null ? task.lastAssignedRoom : "",
                task.lastAssignedDay != null ? task.lastAssignedDay.toString() : "",
                task.lastAssignedBlock
        );
        cfp.setContent(content);

        return cfp;
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    @Override
    public boolean done() {
        return done;
    }
}