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
 * Parallel negotiation with explicit state management
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
     * Estados explícitos para cada negociación de materia
     */
    private enum NegotiationState {
        PENDING,           // Lista para empezar
        NEGOTIATING,       // Enviando CFPs y recibiendo propuestas
        PROCESSING,        // Evaluando propuestas recibidas
        WAITING_CONFIRMATION, // Esperando confirmación de asignación
        COMPLETED,         // Materia completamente asignada
        ABANDONED          // Abandonada después de MAX_RETRIES
    }

    /**
     * Representación mejorada de cada negociación de materia
     */
    private static class SubjectNegotiation {
        final Asignatura subject;
        final int subjectIndex;

        // Estado principal
        NegotiationState state = NegotiationState.PENDING;
        int blocksRemaining;
        int retryCount = 0;

        // Tiempos para timeout
        long negotiationStartTime = 0;
        long stateChangeTime = 0;

        // Tracking de negociación
        int sentRequests = 0;
        int receivedResponses = 0;
        Queue<BatchProposal> proposals = new ConcurrentLinkedQueue<>();

        // Mapeo de conversaciones
        Map<String, String> conversationToRoom = new HashMap<>();

        SubjectNegotiation(Asignatura subject, int index) {
            this.subject = subject;
            this.subjectIndex = index;
            this.blocksRemaining = subject.getHoras();
        }

        // Métodos de estado
        boolean isComplete() {
            return blocksRemaining <= 0;
        }

        boolean shouldTimeout() {
            long elapsed = System.currentTimeMillis() - stateChangeTime;

            // Timeout aplica a estados activos
            boolean timeoutApplies = (state == NegotiationState.NEGOTIATING ||
                    state == NegotiationState.WAITING_CONFIRMATION);

            if (timeoutApplies && elapsed > TIMEOUT_MS) {
                System.out.printf("[TIMEOUT_DEBUG] %s in state %s for %dms (limit: %dms)%n",
                        subject.getNombre(), state, elapsed, TIMEOUT_MS);
                return true;
            }

            return false;
        }

        boolean canStart() {
            return state == NegotiationState.PENDING &&
                    !isComplete() &&
                    retryCount < MAX_RETRIES;
        }

        boolean isWaitingForResponses() {
            return state == NegotiationState.NEGOTIATING &&
                    receivedResponses >= sentRequests;
        }

        void changeState(NegotiationState newState) {
            System.out.printf("[STATE_CHANGE] %s: %s -> %s%n",
                    subject.getNombre(), state, newState);
            this.state = newState;
            this.stateChangeTime = System.currentTimeMillis();
        }

        void reset() {
            sentRequests = 0;
            receivedResponses = 0;
            proposals.clear();
            conversationToRoom.clear();
            negotiationStartTime = System.currentTimeMillis();
            stateChangeTime = System.currentTimeMillis();
        }
    }

    private void initializeNegotiations() {
        List<Asignatura> subjects = profesor.getAsignaturas();
        for (int i = 0; i < subjects.size(); i++) {
            negotiations.add(new SubjectNegotiation(subjects.get(i), i));
        }
        System.out.printf("[STATE_INIT] Initialized %d subjects for %s%n",
                subjects.size(), profesor.getNombre());
    }

    @Override
    public void action() {
        // Step 1: Process all incoming messages
        processAllMessages();

        // Step 2: Handle state transitions and timeouts
        processStateTransitions();

        // Step 3: Start new negotiations
        startPendingNegotiations();

        // Step 4: Check completion
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

    private void processStateTransitions() {
        for (SubjectNegotiation negotiation : negotiations) {
            switch (negotiation.state) {
                case NEGOTIATING:
                    if (negotiation.shouldTimeout()) {
                        System.out.printf("[TIMEOUT] %s timed out in NEGOTIATING state%n",
                                negotiation.subject.getNombre());
                        handleNegotiationTimeout(negotiation);
                    } else if (negotiation.isWaitingForResponses()) {
                        // Todas las respuestas recibidas, cambiar a procesamiento
                        negotiation.changeState(NegotiationState.PROCESSING);
                    }
                    break;

                case PROCESSING:
                    // Procesar propuestas inmediatamente
                    processProposals(negotiation);
                    break;

                case WAITING_CONFIRMATION:
                    if (negotiation.shouldTimeout()) {
                        System.out.printf("[TIMEOUT] %s timed out waiting for confirmation%n",
                                negotiation.subject.getNombre());
                        handleConfirmationTimeout(negotiation);
                    }
                    break;
            }
        }
    }

    private void handleNegotiationTimeout(SubjectNegotiation negotiation) {
        if (!negotiation.proposals.isEmpty()) {
            // Tenemos algunas propuestas, procesarlas
            negotiation.changeState(NegotiationState.PROCESSING);
        } else {
            // Sin propuestas, retry o abandonar
            retryOrAbandon(negotiation);
        }
    }

    private void handleConfirmationTimeout(SubjectNegotiation negotiation) {
        System.out.printf("[CONFIRMATION_TIMEOUT] %s didn't receive confirmation%n",
                negotiation.subject.getNombre());
        retryOrAbandon(negotiation);
    }

    private void startPendingNegotiations() {
        for (SubjectNegotiation negotiation : negotiations) {
            if (negotiation.canStart()) {
                startNegotiation(negotiation);
            }
        }
    }

    private void startNegotiation(SubjectNegotiation negotiation) {
        negotiation.reset();
        negotiation.changeState(NegotiationState.NEGOTIATING);

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

                negotiation.conversationToRoom.put(convId, roomName);

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

                negotiation.sentRequests++;
            }
        }

        if (negotiation.sentRequests == 0) {
            System.out.printf("[NO_ROOMS] No suitable rooms for %s%n",
                    negotiation.subject.getNombre());
            retryOrAbandon(negotiation);
        } else {
            System.out.printf("[STARTED] Sent %d CFPs for %s (retry: %d)%n",
                    negotiation.sentRequests, negotiation.subject.getNombre(),
                    negotiation.retryCount);
        }
    }

    private void processProposals(SubjectNegotiation negotiation) {
        List<BatchProposal> proposalList = new ArrayList<>();
        BatchProposal proposal;
        while ((proposal = negotiation.proposals.poll()) != null) {
            proposalList.add(proposal);
        }

        if (proposalList.isEmpty()) {
            System.out.printf("[NO_PROPOSALS] No proposals for %s%n",
                    negotiation.subject.getNombre());
            retryOrAbandon(negotiation);
            return;
        }

        List<BatchProposal> validProposals = evaluator.filterAndSortProposals(proposalList);

        if (!validProposals.isEmpty()) {
            if (tryAssignment(negotiation, validProposals)) {
                // Assignment sent successfully
                negotiation.changeState(NegotiationState.WAITING_CONFIRMATION);
                return;
            }
        }

        System.out.printf("[NO_VALID] No valid proposals for %s%n",
                negotiation.subject.getNombre());
        retryOrAbandon(negotiation);
    }

    private boolean tryAssignment(SubjectNegotiation negotiation, List<BatchProposal> proposals) {
        for (BatchProposal proposal : proposals) {
            List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();

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

    private void retryOrAbandon(SubjectNegotiation negotiation) {
        negotiation.retryCount++;

        if (negotiation.retryCount >= MAX_RETRIES) {
            negotiation.changeState(NegotiationState.ABANDONED);
            completedSubjects++;
            System.out.printf("[ABANDON] Abandoning %s with %d blocks unassigned%n",
                    negotiation.subject.getNombre(), negotiation.blocksRemaining);
        } else {
            negotiation.changeState(NegotiationState.PENDING);
            System.out.printf("[RETRY] Will retry %s (attempt %d/%d)%n",
                    negotiation.subject.getNombre(), negotiation.retryCount + 1, MAX_RETRIES);
        }
    }

    private void handleConfirmation(SubjectNegotiation negotiation, ACLMessage msg) {
        if (negotiation.state != NegotiationState.WAITING_CONFIRMATION) {
            System.out.printf("[UNEXPECTED_CONFIRM] Received confirmation for %s in state %s%n",
                    negotiation.subject.getNombre(), negotiation.state);
            return;
        }

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
                    negotiation.changeState(NegotiationState.COMPLETED);
                    completedSubjects++;
                    System.out.printf("[SUBJECT_COMPLETE] Finished %s%n",
                            negotiation.subject.getNombre());
                } else {
                    // Continue negotiating for remaining blocks
                    negotiation.changeState(NegotiationState.PENDING);
                }
            } else {
                System.out.printf("[EMPTY_CONFIRM] Empty confirmation for %s%n",
                        negotiation.subject.getNombre());
                retryOrAbandon(negotiation);
            }
        } catch (UnreadableException e) {
            System.err.println("Error reading confirmation: " + e.getMessage());
            retryOrAbandon(negotiation);
        }
    }

    // Métodos auxiliares (sin cambios significativos)
    private SubjectNegotiation findNegotiationForMessage(ACLMessage msg) {
        String convId = msg.getConversationId();

        for (SubjectNegotiation neg : negotiations) {
            if (neg.conversationToRoom.containsKey(convId)) {
                return neg;
            }
        }

        if (convId != null) {
            for (SubjectNegotiation neg : negotiations) {
                String sanitizedName = sanitizeName(neg.subject.getNombre());
                if (convId.contains(sanitizedName) &&
                        (neg.state == NegotiationState.NEGOTIATING ||
                                neg.state == NegotiationState.WAITING_CONFIRMATION)) {
                    return neg;
                }
            }
        }

        String senderName = msg.getSender().getLocalName();
        if (msg.getPerformative() == ACLMessage.INFORM) {
            for (SubjectNegotiation neg : negotiations) {
                if (neg.state == NegotiationState.WAITING_CONFIRMATION &&
                        neg.conversationToRoom.containsValue(senderName)) {
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

        if (negotiation.state == NegotiationState.WAITING_CONFIRMATION) {
            retryOrAbandon(negotiation);
        }
    }

    private boolean shouldSendToRoom(SubjectNegotiation negotiation, DFAgentDescription room) {
        ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
        List<Property> props = new ArrayList<>();
        sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

        if (props.size() < 3) return false;

        String roomCampus = (String) props.get(0).getValue();
        int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

        if (roomCapacity < negotiation.subject.getVacantes()) {
            return false;
        }

        if (!roomCampus.equals(negotiation.subject.getCampus()) && negotiation.retryCount == 0) {
            return false;
        }

        boolean needsMeetingRoom = negotiation.subject.getVacantes() < MEETING_ROOM_THRESHOLD;
        boolean isMeetingRoom = roomCapacity < MEETING_ROOM_THRESHOLD;

        if (negotiation.retryCount == 0 && needsMeetingRoom != isMeetingRoom) {
            return false;
        }

        return true;
    }

    private void checkCompletion() {
        long activeNegotiations = negotiations.stream()
                .filter(n -> n.state != NegotiationState.COMPLETED &&
                        n.state != NegotiationState.ABANDONED)
                .count();

        System.out.printf("[COMPLETION_CHECK] %s: %d active, %d completed/abandoned%n",
                profesor.getNombre(), activeNegotiations, completedSubjects);

        if (completedSubjects >= negotiations.size()) {
            // Calculate summary
            int totalAssigned = 0;
            int totalRequired = 0;

            for (SubjectNegotiation neg : negotiations) {
                int assigned = neg.subject.getHoras() - neg.blocksRemaining;
                totalAssigned += assigned;
                totalRequired += neg.subject.getHoras();
            }

            System.out.printf("[PARALLEL_COMPLETE] All subjects processed for %s%n", profesor.getNombre());
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