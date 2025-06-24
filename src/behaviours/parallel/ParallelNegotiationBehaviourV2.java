package behaviours.parallel;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.enums.Day;
import df.DFCache;
import evaluators.ConstraintEvaluator;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.ParallelBehaviour;
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
 * Simple parallel negotiation behavior - no over-engineering
 */
public class ParallelNegotiationBehaviourV2 extends Behaviour {
    private final AgenteProfesor profesor;
    private final List<SubjectTask> tasks;
    private final Map<String, SubjectTask> activeNegotiations;
    private final ConstraintEvaluator evaluator;
    private final AgentMessageLogger messageLogger;
    private final RTTLogger rttLogger;

    // Simple configuration
    private static final int MAX_CONCURRENT = 3;
    private static final long TIMEOUT_MS = 5000;
    private static final int MEETING_ROOM_THRESHOLD = 10;

    private int currentTaskIndex = 0;
    private int completedTasks = 0;
    private boolean done = false;

    public ParallelNegotiationBehaviourV2(AgenteProfesor profesor) {
        this.profesor = profesor;
        this.tasks = new ArrayList<>();
        this.activeNegotiations = new ConcurrentHashMap<>();
        this.evaluator = new ConstraintEvaluator(profesor);
        this.messageLogger = AgentMessageLogger.getInstance();
        this.rttLogger = RTTLogger.getInstance();

        initializeTasks();
    }

    /**
     * Simple task representation for each subject
     */
    private static class SubjectTask {
        final Asignatura subject;
        final int subjectIndex;
        final String taskId;
        int blocksRemaining;
        int retries = 0;
        long startTime;

        // Track sent requests and received responses
        final Map<String, ACLMessage> sentRequests = new HashMap<>();
        final Queue<BatchProposal> proposals = new ConcurrentLinkedQueue<>();
        int responseCount = 0;

        // Assignment tracking
        String lastAssignedRoom = null;
        Day lastAssignedDay = null;
        int lastAssignedBlock = -1;

        boolean hasPartialAssignment = false;
        int consecutiveFailures = 0;
        Set<String> rejectedRooms = new HashSet<>();
        boolean expandedSearch = false;
        int minAcceptableSatisfaction = 7; // Start with high standards

        boolean shouldRenegotiate() {
            // Re-negotiate if:
            // 1. We have partial assignments and got stuck
            // 2. We've been rejected too many times
            // 3. We need to lower our standards
            return (hasPartialAssignment && consecutiveFailures > 0) ||
                    (consecutiveFailures >= 2 && minAcceptableSatisfaction > 3) ||
                    (rejectedRooms.size() > 5 && !expandedSearch);
        }

        void prepareForRenegotiation() {
            if (consecutiveFailures >= 2) {
                // Lower satisfaction standards
                minAcceptableSatisfaction = Math.max(3, minAcceptableSatisfaction - 2);
                System.out.printf("[RENEGOTIATE] Lowering standards to %d for %s%n",
                        minAcceptableSatisfaction, subject.getNombre());
            }

            if (rejectedRooms.size() > 5 && !expandedSearch) {
                // Expand search to other campuses
                expandedSearch = true;
                System.out.printf("[RENEGOTIATE] Expanding search to all campuses for %s%n",
                        subject.getNombre());
            }

            if (hasPartialAssignment && lastAssignedRoom != null) {
                // Try different rooms if stuck with partial assignment
                rejectedRooms.add(lastAssignedRoom);
                lastAssignedRoom = null;
                System.out.printf("[RENEGOTIATE] Trying different rooms for %s%n",
                        subject.getNombre());
            }

            // Reset for new negotiation round
            sentRequests.clear();
            proposals.clear();
            responseCount = 0;
            startTime = System.currentTimeMillis();
        }

        SubjectTask(Asignatura subject, int index) {
            this.subject = subject;
            this.subjectIndex = index;
            this.taskId = subject.getNombre() + "-" + index + "-" + System.currentTimeMillis();
            this.blocksRemaining = subject.getHoras();
        }

        boolean isComplete() {
            return blocksRemaining <= 0;
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        void reset() {
            sentRequests.clear();
            proposals.clear();
            responseCount = 0;
            startTime = System.currentTimeMillis();
        }
    }

    private void initializeTasks() {
        List<Asignatura> subjects = profesor.getAsignaturas();
        for (int i = 0; i < subjects.size(); i++) {
            tasks.add(new SubjectTask(subjects.get(i), i));
        }
        System.out.printf("[PARALLEL] Initialized %d tasks for %s%n",
                tasks.size(), profesor.getNombre());
    }

    @Override
    public void action() {
        // Step 1: Start new negotiations if we have capacity
        startNewNegotiations();

        // Step 2: Process incoming messages
        processMessages();

        // Step 3: Check timeouts and process proposals
        checkTimeoutsAndProcess();

        // Step 4: Check if we're done
        if (completedTasks >= tasks.size()) {
            System.out.printf("[PARALLEL] All tasks completed for %s%n", profesor.getNombre());
            profesor.finalizarNegociaciones();
            done = true;
        }
    }

    private void startNewNegotiations() {
        while (activeNegotiations.size() < MAX_CONCURRENT && currentTaskIndex < tasks.size()) {
            SubjectTask task = tasks.get(currentTaskIndex++);

            if (task.isComplete()) {
                completedTasks++;
                continue;
            }

            System.out.printf("[START] Starting negotiation for %s (blocks needed: %d)%n",
                    task.subject.getNombre(), task.blocksRemaining);

            task.reset();
            sendCFPsForTask(task);
            activeNegotiations.put(task.taskId, task);
        }
    }

    private void sendCFPsForTask(SubjectTask task) {
        try {
            List<DFAgentDescription> rooms = DFCache.search(profesor, AgenteSala.SERVICE_NAME);

            for (DFAgentDescription room : rooms) {
                if (shouldSendToRoom(task, room)) {
                    ACLMessage cfp = createCFP(task, room);

                    // Track the request
                    task.sentRequests.put(room.getName().getLocalName(), cfp);

                    // Send it
                    profesor.send(cfp);
                    messageLogger.logMessageSent(profesor.getLocalName(), cfp);

                    rttLogger.startRequest(
                            profesor.getLocalName(),
                            cfp.getConversationId(),
                            ACLMessage.CFP,
                            room.getName().getLocalName(),
                            null,
                            "classroom-availability"
                    );
                }
            }

            System.out.printf("[CFP] Sent %d requests for %s%n",
                    task.sentRequests.size(), task.subject.getNombre());

        } catch (Exception e) {
            System.err.println("Error sending CFPs: " + e.getMessage());
        }
    }

    private boolean shouldSendToRoom(SubjectTask task, DFAgentDescription room) {
        ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
        List<Property> props = new ArrayList<>();
        sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

        if (props.size() < 3) return false;

        String roomCode = room.getName().getLocalName();
        String roomCampus = (String) props.get(0).getValue();
        int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

        // Skip previously rejected rooms unless we're desperate
        if (task.rejectedRooms.contains(roomCode) && task.retries < 3) {
            return false;
        }

        // Campus filtering based on negotiation state
        if (!roomCampus.equals(task.subject.getCampus())) {
            // Only consider other campuses if:
            // 1. We've expanded search
            // 2. Or it's a retry
            // 3. Or we have partial assignments
            if (!task.expandedSearch && task.retries == 0 && !task.hasPartialAssignment) {
                return false;
            }
        }

        // Capacity filtering with flexibility for re-negotiation
        if (task.subject.getVacantes() < MEETING_ROOM_THRESHOLD) {
            // Small class - be more flexible in later attempts
            boolean isMeetingRoom = roomCapacity < MEETING_ROOM_THRESHOLD;
            if (!isMeetingRoom && task.retries == 0) {
                return false; // First attempt: only meeting rooms
            }
            // Later attempts: accept any room that fits
            return roomCapacity >= task.subject.getVacantes();
        } else {
            // Regular class
            if (roomCapacity < task.subject.getVacantes()) {
                return false;
            }
            // Avoid extremely oversized rooms unless desperate
            if (roomCapacity > task.subject.getVacantes() * 3 && task.retries < 2) {
                return false;
            }
        }

        return true;
    }

    private ACLMessage createCFP(SubjectTask task, DFAgentDescription room) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setSender(profesor.getAID());
        cfp.addReceiver(room.getName());
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

        String conversationId = task.taskId + "-" + room.getName().getLocalName();
        cfp.setConversationId(conversationId);
        cfp.addUserDefinedParameter("taskId", task.taskId);

        // Content format that AgenteSala expects
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

    private void checkTimeoutsAndProcess() {
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, SubjectTask> entry : activeNegotiations.entrySet()) {
            SubjectTask task = entry.getValue();

            boolean allResponses = task.responseCount >= task.sentRequests.size();
            boolean timedOut = task.isTimedOut();

            if (allResponses || timedOut) {
                if (!task.proposals.isEmpty()) {
                    boolean assigned = processProposals(task);
                    if (!assigned) {
                        task.consecutiveFailures++;
                        handleFailedNegotiation(task, toRemove);
                    } else {
                        task.consecutiveFailures = 0;
                    }
                } else {
                    task.consecutiveFailures++;
                    handleFailedNegotiation(task, toRemove);
                }
            }
        }

        toRemove.forEach(activeNegotiations::remove);
    }

    private void handleFailedNegotiation(SubjectTask task, List<String> toRemove) {
        // Check if we should re-negotiate with different criteria
        if (task.shouldRenegotiate()) {
            System.out.printf("[RENEGOTIATE] Adjusting criteria for %s%n",
                    task.subject.getNombre());
            task.prepareForRenegotiation();
            sendCFPsForTask(task);
        } else if (task.retries >= 3) {
            // Give up after 3 retries
            System.out.printf("[FAILED] No assignments for %s after 3 retries%n",
                    task.subject.getNombre());
            toRemove.add(task.taskId);
            completedTasks++;
        } else {
            // Simple retry
            task.retries++;
            System.out.printf("[RETRY] Retrying %s (attempt %d)%n",
                    task.subject.getNombre(), task.retries + 1);
            task.reset();
            sendCFPsForTask(task);
        }
    }

    private void processMessages() {
        MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                        MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                ),
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchPerformative(ACLMessage.FAILURE)
                )
        );

        ACLMessage msg;
        while ((msg = profesor.receive(mt)) != null) {
            messageLogger.logMessageReceived(profesor.getLocalName(), msg);

            // Find the task this message belongs to
            String taskId = msg.getUserDefinedParameter("taskId");
            if (taskId == null) {
                // Try to extract from conversation ID
                String convId = msg.getConversationId();
                if (convId != null) {
                    // Try to find matching task by conversation ID prefix
                    for (String tid : activeNegotiations.keySet()) {
                        if (convId.startsWith(tid)) {
                            taskId = tid;
                            break;
                        }
                    }

                    // If still not found, try to extract from conversation ID format
                    if (taskId == null && convId.contains("-")) {
                        // Assuming format: "taskId-roomName"
                        int lastDash = convId.lastIndexOf("-");
                        if (lastDash > 0) {
                            String potentialTaskId = convId.substring(0, lastDash);
                            // Verify this is actually a valid task ID
                            if (activeNegotiations.containsKey(potentialTaskId)) {
                                taskId = potentialTaskId;
                            }
                        }
                    }
                }
            }

            // CRITICAL: Check if taskId is still null before using it
            if (taskId == null) {
                System.out.printf("[WARNING] Received %s from %s with no identifiable task ID (conv: %s)%n",
                        ACLMessage.getPerformative(msg.getPerformative()),
                        msg.getSender().getLocalName(),
                        msg.getConversationId());

                // Log RTT anyway to avoid losing metrics
                if (msg.getPerformative() != ACLMessage.INFORM) {
                    rttLogger.endRequest(
                            profesor.getLocalName(),
                            msg.getConversationId() != null ? msg.getConversationId() : "unknown",
                            msg.getPerformative(),
                            msg.getByteSequenceContent() != null ? msg.getByteSequenceContent().length : 0,
                            false, // not successful since we can't process it
                            null,
                            "classroom-availability"
                    );
                }
                continue; // Skip this message
            }

            SubjectTask task = activeNegotiations.get(taskId);
            if (task == null) {
                System.out.printf("[ORPHAN_MSG] Received %s for completed/unknown task %s%n",
                        ACLMessage.getPerformative(msg.getPerformative()), taskId);
                continue;
            }

            // Rest of the switch statement remains the same...
            switch (msg.getPerformative()) {
                case ACLMessage.PROPOSE:
                    handleProposal(task, msg);
                    break;

                case ACLMessage.REFUSE:
                    task.responseCount++;
                    task.rejectedRooms.add(msg.getSender().getLocalName());
                    System.out.printf("[REFUSE] Room %s refused request for %s%n",
                            msg.getSender().getLocalName(), task.subject.getNombre());
                    break;

                case ACLMessage.FAILURE:
                    task.responseCount++;
                    task.consecutiveFailures++;
                    task.rejectedRooms.add(msg.getSender().getLocalName());
                    System.out.printf("[FAILURE] Room %s failed assignment for %s: %s%n",
                            msg.getSender().getLocalName(), task.subject.getNombre(),
                            msg.getContent());
                    break;

                case ACLMessage.INFORM:
                    handleConfirmation(task, msg);
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
        }
    }

    private void handleProposal(SubjectTask task, ACLMessage msg) {
        try {
            ClassroomAvailability availability = (ClassroomAvailability) msg.getContentObject();
            if (availability != null) {
                BatchProposal proposal = new BatchProposal(availability, msg);
                task.proposals.offer(proposal);
                task.responseCount++;
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading proposal: " + e.getMessage());
            task.responseCount++;
        }
    }

    // Complete handleConfirmation method for SimpleParallelNegotiationBehaviour:
    private void handleConfirmation(SubjectTask task, ACLMessage msg) {
        try {
            BatchAssignmentConfirmation confirmation =
                    (BatchAssignmentConfirmation) msg.getContentObject();

            if (confirmation != null && !confirmation.getConfirmedAssignments().isEmpty()) {
                // Mark that we have partial assignment
                task.hasPartialAssignment = true;

                // Update professor's schedule - need to handle subject index properly
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

                        // Update task state
                        task.blocksRemaining--;
                        task.lastAssignedRoom = assignment.getClassroomCode();
                        task.lastAssignedDay = assignment.getDay();
                        task.lastAssignedBlock = assignment.getBlock();

                        System.out.printf("[ASSIGNED] %s: block %d on %s in %s (%d remaining)%n",
                                task.subject.getNombre(), assignment.getBlock(),
                                assignment.getDay(), assignment.getClassroomCode(),
                                task.blocksRemaining);
                    }
                } finally {
                    // ALWAYS restore the original index
                    profesor.asignaturaActual = originalIndex;
                }

                // Check if task is complete
                if (task.isComplete()) {
                    activeNegotiations.remove(task.taskId);
                    completedTasks++;
                    System.out.printf("[COMPLETE] Finished %s%n", task.subject.getNombre());
                } else {
                    // Continue negotiation for remaining blocks
                    System.out.printf("[PARTIAL] %s has %d blocks remaining%n",
                            task.subject.getNombre(), task.blocksRemaining);
                    task.reset();
                    sendCFPsForTask(task);
                }
            } else {
                // Empty confirmation - treat as failure
                System.out.printf("[EMPTY_CONFIRM] No assignments confirmed for %s%n",
                        task.subject.getNombre());
                task.consecutiveFailures++;

                // Add the room to rejected list if we can identify it
                String sender = msg.getSender().getLocalName();
                if (sender != null && sender.startsWith("Sala")) {
                    task.rejectedRooms.add(sender);
                }

                // Trigger re-negotiation
                handleFailedNegotiation(task, new ArrayList<>());
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading confirmation: " + e.getMessage());
            // Treat read errors as failures too
            task.consecutiveFailures++;
            handleFailedNegotiation(task, new ArrayList<>());
        }
    }

    private boolean processProposals(SubjectTask task) {
        List<BatchProposal> proposals = new ArrayList<>();
        BatchProposal proposal;
        while ((proposal = task.proposals.poll()) != null) {
            proposals.add(proposal);
        }

        // Filter with current standards
        List<BatchProposal> validProposals = evaluator.filterAndSortProposals(proposals);

        // Apply satisfaction threshold if re-negotiating
        if (task.minAcceptableSatisfaction < 7) {
            validProposals = validProposals.stream()
                    .filter(p -> p.getSatisfactionScore() >= task.minAcceptableSatisfaction)
                    .collect(Collectors.toList());
        }

        if (!validProposals.isEmpty()) {
            return tryAssignment(task, validProposals);
        } else {
            System.out.printf("[NO_VALID] No proposals meeting criteria (min satisfaction: %d) for %s%n",
                    task.minAcceptableSatisfaction, task.subject.getNombre());
            return false;
        }
    }
    private boolean tryAssignment(SubjectTask task, List<BatchProposal> proposals) {
        for (BatchProposal proposal : proposals) {
            if (task.blocksRemaining <= 0) break;

            List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();
            int blocksToRequest = Math.min(task.blocksRemaining, 2);

            // Build assignment requests
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

            // Send assignment request
            if (!requests.isEmpty()) {
                try {
                    BatchAssignmentRequest batchRequest = new BatchAssignmentRequest(requests);

                    ACLMessage accept = proposal.getOriginalMessage().createReply();
                    accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    accept.setContentObject(batchRequest);
                    accept.addUserDefinedParameter("taskId", task.taskId);

                    profesor.send(accept);
                    messageLogger.logMessageSent(profesor.getLocalName(), accept);

                    System.out.printf("[ACCEPT] Sent assignment request for %d blocks to %s%n",
                            requests.size(), proposal.getRoomCode());

                    return true; // Successfully sent assignment
                } catch (IOException e) {
                    System.err.println("Error sending assignment: " + e.getMessage());
                }
            }
        }
        return false; // No assignment made
    }


    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    @Override
    public boolean done() {
        return done;
    }

    public int getBloquesPendientes() {
        return tasks.stream()
                .mapToInt(t -> t.blocksRemaining)
                .sum();
    }
}