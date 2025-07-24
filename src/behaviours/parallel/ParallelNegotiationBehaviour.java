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
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simplified parallel negotiation - processes all subjects concurrently
 * but with clear state management and no over-engineering
 */
public class ParallelNegotiationBehaviour extends Behaviour {
    private final AgenteProfesor profesor;
    private final List<SubjectNegotiation> negotiations;
    private final ConstraintEvaluator evaluator;
    private final AgentMessageLogger messageLogger;
    private final RTTLogger rttLogger;

    // Simple configuration
    private static final long TIMEOUT_MS = 8000;
    private static final int MAX_RETRIES = 3;
    private static final int MEETING_ROOM_THRESHOLD = 10;

    private boolean done = false;
    private int completedSubjects = 0;

    public ParallelNegotiationBehaviour(AgenteProfesor profesor) {
        this.profesor = profesor;
        this.negotiations = new ArrayList<>();
        this.evaluator = new ConstraintEvaluator(profesor);
        this.messageLogger = AgentMessageLogger.getInstance();
        this.rttLogger = RTTLogger.getInstance();

        initializeNegotiations();
    }

    /**
     * Simple representation of each subject negotiation
     */
    private static class SubjectNegotiation {
        final Asignatura subject;
        final int subjectIndex;

        // State
        int blocksRemaining;
        int retryCount = 0;
        boolean isActive = false;
        boolean waitingForConfirmation = false;

        // Negotiation tracking
        long negotiationStartTime = 0;
        int sentRequests = 0;
        int receivedResponses = 0;
        Queue<BatchProposal> proposals = new ConcurrentLinkedQueue<>();

        // Conversation tracking - simple map
        Map<String, String> conversationToRoom = new HashMap<>();

        SubjectNegotiation(Asignatura subject, int index) {
            this.subject = subject;
            this.subjectIndex = index;
            this.blocksRemaining = subject.getHoras();
        }

        boolean isComplete() {
            return blocksRemaining <= 0;
        }

        boolean shouldTimeout() {
            return isActive &&
                    System.currentTimeMillis() - negotiationStartTime > TIMEOUT_MS;
        }

        void reset() {
            sentRequests = 0;
            receivedResponses = 0;
            proposals.clear();
            conversationToRoom.clear();
            negotiationStartTime = System.currentTimeMillis();
        }
    }

    private void initializeNegotiations() {
        List<Asignatura> subjects = profesor.getAsignaturas();
        for (int i = 0; i < subjects.size(); i++) {
            negotiations.add(new SubjectNegotiation(subjects.get(i), i));
        }
        System.out.printf("[SIMPLE_PARALLEL] Initialized %d subjects for %s%n",
                subjects.size(), profesor.getNombre());
    }

    @Override
    public void action() {
        // Step 1: Process all incoming messages
        processAllMessages();

        // Step 2: Start negotiations for subjects that need them
        startPendingNegotiations();

        // Step 3: Handle timeouts and process completed negotiations
        handleTimeoutsAndProcessing();

        // Step 4: Check if we're done
        checkCompletion();

        // Small delay to prevent busy waiting
        if (!done) {
            block(50);
        }
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
            processMessage(msg);
        }
    }

    private void processMessage(ACLMessage msg) {
        // Find which negotiation this message belongs to
        SubjectNegotiation negotiation = findNegotiationForMessage(msg);

        if (negotiation == null) {
            System.out.printf("[ORPHAN] Could not match message from %s%n",
                    msg.getSender().getLocalName());
            return;
        }

        switch (msg.getPerformative()) {
            case ACLMessage.PROPOSE:
                handleProposal(negotiation, msg);
                break;

            case ACLMessage.REFUSE:
                handleRefuse(negotiation, msg);
                break;

            case ACLMessage.INFORM:
                handleConfirmation(negotiation, msg);
                return; // Don't increment response count for confirmations

            case ACLMessage.FAILURE:
                handleFailure(negotiation, msg);
                break;
        }

        // Track responses (except for confirmations)
        negotiation.receivedResponses++;

        // Log RTT end
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

    private SubjectNegotiation findNegotiationForMessage(ACLMessage msg) {
        String convId = msg.getConversationId();

        // First try exact conversation ID match
        for (SubjectNegotiation neg : negotiations) {
            if (neg.conversationToRoom.containsKey(convId)) {
                return neg;
            }
        }

        // If that fails, try to match by subject name in conversation ID
        if (convId != null) {
            for (SubjectNegotiation neg : negotiations) {
                String sanitizedName = sanitizeName(neg.subject.getNombre());
                if (convId.contains(sanitizedName) && neg.isActive) {
                    return neg;
                }
            }
        }

        // Last resort: find active negotiation waiting for confirmation from this sender
        String senderName = msg.getSender().getLocalName();
        if (msg.getPerformative() == ACLMessage.INFORM) {
            for (SubjectNegotiation neg : negotiations) {
                if (neg.waitingForConfirmation && neg.conversationToRoom.containsValue(senderName)) {
                    return neg;
                }
            }
        }

        return null;
    }

    private void handleProposal(SubjectNegotiation negotiation, ACLMessage msg) {
        try {
            ClassroomAvailability availability = (ClassroomAvailability) msg.getContentObject();
            if (availability != null) {
                BatchProposal proposal = new BatchProposal(availability, msg);
                negotiation.proposals.offer(proposal);
                System.out.printf("[PROPOSAL] Received proposal from %s for %s%n",
                        msg.getSender().getLocalName(), negotiation.subject.getNombre());
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading proposal: " + e.getMessage());
        }
    }

    private void handleRefuse(SubjectNegotiation negotiation, ACLMessage msg) {
        System.out.printf("[REFUSE] Room %s refused %s%n",
                msg.getSender().getLocalName(), negotiation.subject.getNombre());
    }

    private void handleFailure(SubjectNegotiation negotiation, ACLMessage msg) {
        System.out.printf("[FAILURE] Assignment failed for %s from %s%n",
                negotiation.subject.getNombre(), msg.getSender().getLocalName());
        negotiation.waitingForConfirmation = false;
    }

    private void handleConfirmation(SubjectNegotiation negotiation, ACLMessage msg) {
        negotiation.waitingForConfirmation = false;

        try {
            BatchAssignmentConfirmation confirmation =
                    (BatchAssignmentConfirmation) msg.getContentObject();

            if (confirmation != null && !confirmation.getConfirmedAssignments().isEmpty()) {
                // Switch context to this subject
                int originalIndex = profesor.asignaturaActual;
                profesor.asignaturaActual = negotiation.subjectIndex;

                try {
                    int assignedCount = 0;
                    for (BatchAssignmentConfirmation.ConfirmedAssignment assignment :
                            confirmation.getConfirmedAssignments()) {

                        if (negotiation.blocksRemaining <= 0) {
                            System.out.printf("[EXCESS] Ignoring excess assignment for %s%n",
                                    negotiation.subject.getNombre());
                            break;
                        }

                        profesor.updateScheduleInfo(
                                assignment.getDay(),
                                assignment.getClassroomCode(),
                                assignment.getBlock(),
                                negotiation.subject.getNombre(),
                                assignment.getSatisfaction()
                        );

                        negotiation.blocksRemaining--;
                        assignedCount++;

                        System.out.printf("[ASSIGNED] %s: block %d on %s in %s (%d remaining)%n",
                                negotiation.subject.getNombre(), assignment.getBlock(),
                                assignment.getDay(), assignment.getClassroomCode(),
                                negotiation.blocksRemaining);
                    }

                    System.out.printf("[BATCH_COMPLETE] Assigned %d blocks for %s%n",
                            assignedCount, negotiation.subject.getNombre());

                } finally {
                    profesor.asignaturaActual = originalIndex;
                }

                // Check if subject is complete
                if (negotiation.isComplete()) {
                    negotiation.isActive = false;
                    completedSubjects++;
                    System.out.printf("[SUBJECT_COMPLETE] Finished %s%n",
                            negotiation.subject.getNombre());
                } else {
                    // Continue negotiating for remaining blocks
                    negotiation.reset();
                    // Will be picked up in next startPendingNegotiations call
                }
            } else {
                System.out.printf("[EMPTY_CONFIRM] Empty confirmation for %s%n",
                        negotiation.subject.getNombre());
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading confirmation: " + e.getMessage());
        }
    }

    private void startPendingNegotiations() {
        for (SubjectNegotiation negotiation : negotiations) {
            if (!negotiation.isActive &&
                    !negotiation.isComplete() &&
                    !negotiation.waitingForConfirmation &&
                    negotiation.retryCount < MAX_RETRIES) {

                startNegotiation(negotiation);
            }
        }
    }

    private void startNegotiation(SubjectNegotiation negotiation) {
        negotiation.reset();
        negotiation.isActive = true;

        List<DFAgentDescription> rooms = DFCache.search(profesor, AgenteSala.SERVICE_NAME);

        for (DFAgentDescription room : rooms) {
            if (shouldSendToRoom(negotiation, room)) {
                String roomName = room.getName().getLocalName();
                String convId = String.format("%s-%s-%d",
                        sanitizeName(negotiation.subject.getNombre()),
                        roomName,
                        System.currentTimeMillis());

                ACLMessage cfp = createCFP(negotiation, room);
                cfp.setConversationId(convId);

                // Track this conversation
                negotiation.conversationToRoom.put(convId, roomName);

                // Send message
                profesor.send(cfp);
                messageLogger.logMessageSent(profesor.getLocalName(), cfp);

                // Start RTT tracking
                rttLogger.startRequest(
                        profesor.getLocalName(),
                        convId,
                        ACLMessage.CFP,
                        roomName,
                        null,
                        "classroom-availability"
                );

                negotiation.sentRequests++;
            }
        }

        if (negotiation.sentRequests == 0) {
            System.out.printf("[NO_ROOMS] No suitable rooms for %s%n",
                    negotiation.subject.getNombre());
            negotiation.isActive = false;
            negotiation.retryCount++;
        } else {
            System.out.printf("[STARTED] Sent %d CFPs for %s (retry: %d)%n",
                    negotiation.sentRequests, negotiation.subject.getNombre(),
                    negotiation.retryCount);
        }
    }

    private boolean shouldSendToRoom(SubjectNegotiation negotiation, DFAgentDescription room) {
        ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
        List<Property> props = new ArrayList<>();
        sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

        if (props.size() < 3) return false;

        String roomCampus = (String) props.get(0).getValue();
        int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

        // Basic capacity check
        if (roomCapacity < negotiation.subject.getVacantes()) {
            return false;
        }

        // Campus preference (but allow cross-campus if retrying)
        if (!roomCampus.equals(negotiation.subject.getCampus()) && negotiation.retryCount == 0) {
            return false;
        }

        // Meeting room logic
        boolean needsMeetingRoom = negotiation.subject.getVacantes() < MEETING_ROOM_THRESHOLD;
        boolean isMeetingRoom = roomCapacity < MEETING_ROOM_THRESHOLD;

        // On first try, be strict about meeting room matching
        if (negotiation.retryCount == 0 && needsMeetingRoom != isMeetingRoom) {
            return false;
        }

        return true;
    }

    private void handleTimeoutsAndProcessing() {
        for (SubjectNegotiation negotiation : negotiations) {
            if (negotiation.shouldTimeout() ||
                    (negotiation.isActive && negotiation.receivedResponses >= negotiation.sentRequests)) {

                if (negotiation.waitingForConfirmation) {
                    // Timeout waiting for confirmation
                    System.out.printf("[TIMEOUT] Confirmation timeout for %s%n",
                            negotiation.subject.getNombre());
                    negotiation.waitingForConfirmation = false;
                    retryOrComplete(negotiation);
                } else if (!negotiation.proposals.isEmpty()) {
                    // Process proposals
                    processProposals(negotiation);
                } else {
                    // No proposals received
                    System.out.printf("[NO_PROPOSALS] No proposals for %s%n",
                            negotiation.subject.getNombre());
                    retryOrComplete(negotiation);
                }
            }
        }
    }

    private void processProposals(SubjectNegotiation negotiation) {
        List<BatchProposal> proposalList = new ArrayList<>();
        BatchProposal proposal;
        while ((proposal = negotiation.proposals.poll()) != null) {
            proposalList.add(proposal);
        }

        List<BatchProposal> validProposals = evaluator.filterAndSortProposals(proposalList);

        if (!validProposals.isEmpty()) {
            if (tryAssignment(negotiation, validProposals)) {
                // Assignment sent, now waiting for confirmation
                negotiation.isActive = false;
                negotiation.waitingForConfirmation = true;
                return;
            }
        }

        System.out.printf("[NO_VALID] No valid proposals for %s%n",
                negotiation.subject.getNombre());
        retryOrComplete(negotiation);
    }

    private boolean tryAssignment(SubjectNegotiation negotiation, List<BatchProposal> proposals) {
        for (BatchProposal proposal : proposals) {
            List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();

            // Try to get up to 2 blocks from this room
            int maxBlocks = Math.min(2, negotiation.blocksRemaining);

            for (Map.Entry<Day, List<BatchProposal.BlockProposal>> entry :
                    proposal.getDayProposals().entrySet()) {

                if (requests.size() >= maxBlocks) break;

                Day day = entry.getKey();
                for (BatchProposal.BlockProposal block : entry.getValue()) {
                    if (requests.size() >= maxBlocks) break;

                    if (profesor.isBlockAvailable(day, block.getBlock())) {
                        requests.add(new BatchAssignmentRequest.AssignmentRequest(
                                day,
                                block.getBlock(),
                                negotiation.subject.getNombre(),
                                proposal.getSatisfactionScore(),
                                proposal.getRoomCode(),
                                negotiation.subject.getVacantes(),
                                profesor.getNombre()
                        ));
                    }
                }
            }

            if (!requests.isEmpty()) {
                try {
                    BatchAssignmentRequest batchRequest = new BatchAssignmentRequest(requests);

                    ACLMessage accept = proposal.getOriginalMessage().createReply();
                    accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    accept.setContentObject(batchRequest);

                    profesor.send(accept);
                    messageLogger.logMessageSent(profesor.getLocalName(), accept);

                    System.out.printf("[ACCEPT] Requested %d blocks from %s for %s%n",
                            requests.size(), proposal.getRoomCode(),
                            negotiation.subject.getNombre());

                    return true;
                } catch (IOException e) {
                    System.err.println("Error sending assignment: " + e.getMessage());
                }
            }
        }

        return false;
    }

    private void retryOrComplete(SubjectNegotiation negotiation) {
        negotiation.isActive = false;
        negotiation.retryCount++;

        if (negotiation.retryCount >= MAX_RETRIES) {
            completedSubjects++;
            System.out.printf("[ABANDON] Abandoning %s with %d blocks unassigned%n",
                    negotiation.subject.getNombre(), negotiation.blocksRemaining);
        } else {
            System.out.printf("[RETRY] Will retry %s (attempt %d/%d)%n",
                    negotiation.subject.getNombre(), negotiation.retryCount + 1, MAX_RETRIES);
        }
    }

    private void checkCompletion() {
        System.out.println("COMPLETION CHECK: " + completedSubjects + "/" + negotiations.size() + " PROF : " + profesor.getNombre());

        if (completedSubjects >= negotiations.size()) {
            // Calculate summary
            int totalAssigned = 0;
            int totalRequired = 0;

            for (SubjectNegotiation neg : negotiations) {
                int assigned = neg.subject.getHoras() - neg.blocksRemaining;
                totalAssigned += assigned;
                totalRequired += neg.subject.getHoras();
            }

            System.out.printf("[PARALLEL] All subjects processed for %s%n", profesor.getNombre());
            System.out.printf("[SUMMARY] %s: Assigned %d/%d blocks (%.1f%%)%n",
                    profesor.getNombre(), totalAssigned, totalRequired,
                    (totalAssigned * 100.0) / totalRequired);

            profesor.finalizarNegociaciones();
            done = true;
        }
    }

    private ACLMessage createCFP(SubjectNegotiation negotiation, DFAgentDescription room) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setSender(profesor.getAID());
        cfp.addReceiver(room.getName());
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

        String content = String.format("%s,%d,%d,%s,%d,%s,%s,%d",
                sanitizeName(negotiation.subject.getNombre()),
                negotiation.subject.getVacantes(),
                negotiation.subject.getNivel(),
                negotiation.subject.getCampus(),
                negotiation.blocksRemaining,
                "", // lastAssignedRoom - simplified
                "", // lastAssignedDay - simplified
                -1  // lastAssignedBlock - simplified
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