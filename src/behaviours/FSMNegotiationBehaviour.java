package behaviours;

import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.lang.acl.UnreadableException;
import jade.proto.ContractNetInitiator;
import jade.domain.FIPANames;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import constants.enums.Day;
import objetos.Asignatura;
import objetos.AssignationData;
import objetos.helper.BatchProposal;
import objetos.ClassroomAvailability;
import objetos.helper.BatchAssignmentRequest;
import objetos.helper.BatchAssignmentConfirmation;
import evaluators.ConstraintEvaluator;
import df.DFCache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.IOException;

public class FSMNegotiationBehaviour extends FSMBehaviour {
    // States
    private static final String SETUP = "SETUP";
    private static final String COLLECTING = "COLLECTING";
    private static final String EVALUATING = "EVALUATING";
    private static final String FINISHED = "FINISHED";

    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<BatchProposal> propuestas = new ConcurrentLinkedQueue<>();
    private int bloquesPendientes = 0;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;
    private static final long TIMEOUT_PROPUESTA = 1000; // 1 second
    private final ConstraintEvaluator evaluator;
    private final AssignationData assignationData = new AssignationData();
    private static final int MEETING_ROOM_THRESHOLD = 10; // Threshold for meeting room logic

    // Used for performance monitoring
    private long negotiationStartTime;
    private final Map<String, Long> subjectNegotiationTimes = new HashMap<>();

    public FSMNegotiationBehaviour(AgenteProfesor profesor) {
        super(profesor);
        this.profesor = profesor;
        this.evaluator = new ConstraintEvaluator(profesor);
        this.negotiationStartTime = System.currentTimeMillis();

        // Register states
        registerFirstState(new SetupState(), SETUP);
        registerState(new CollectingProposalsState(), COLLECTING);
        registerState(new EvaluatingState(), EVALUATING);
        registerLastState(new FinishedState(), FINISHED);

        // Register transitions
        registerDefaultTransition(SETUP, COLLECTING);
        registerTransition(COLLECTING, EVALUATING, 0); // Proposals received
        registerTransition(COLLECTING, SETUP, 1);      // No proposals or timeout
        registerTransition(EVALUATING, SETUP, 0);      // Continue with more blocks
        registerTransition(EVALUATING, FINISHED, 1);   // Done with subject
        registerTransition(EVALUATING, COLLECTING, 2); // Retry with same subject
        registerTransition(SETUP, FINISHED, 1);        // No more subjects
    }

    public int getBloquesPendientes() {
        return bloquesPendientes;
    }

    private class SetupState extends OneShotBehaviour {
        @Override
        public void action() {
            if (!profesor.canUseMoreSubjects()) {
                long totalTime = System.currentTimeMillis() - negotiationStartTime;
                System.out.printf("[TIMING] Professor %s completed all negotiations in %d ms%n",
                        profesor.getNombre(), totalTime);

                // Print individual subject times
                subjectNegotiationTimes.forEach((subject, time) ->
                        System.out.printf("[TIMING] Subject %s negotiation took %d ms%n", subject, time));

                // We're done with all subjects
                onEnd();
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            if (currentSubject != null) {
                bloquesPendientes = currentSubject.getHoras();
                assignationData.clear();

                negotiationStartTime = System.currentTimeMillis();

                System.out.printf("[SETUP] Starting assignment for %s (Code: %s) - Required hours: %d%n",
                        currentSubject.getNombre(),
                        currentSubject.getCodigoAsignatura(),
                        currentSubject.getHoras());

                sendProposalRequests();
            } else {
                System.out.println("Error: No current subject for " + profesor.getNombre());
                onEnd();
            }
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

                // Create CFP message
                ACLMessage cfp = createCFPMessage(currentSubject);

                // Add receivers after filtering
                results.stream()
                        .filter(room -> !canQuickReject(currentSubject, room))
                        .forEach(room -> cfp.addReceiver(room.getName()));

                profesor.getPerformanceMonitor().recordMessageSent(cfp, "CFP");
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

        private boolean canQuickReject(Asignatura subject, DFAgentDescription room) {
            String cacheKey = getCacheKey(subject, room.getName().getLocalName());

            return quickRejectCache.computeIfAbsent(cacheKey, k -> {
                ServiceDescription sd = (ServiceDescription) room.getAllServices().next();
                List<Property> props = new ArrayList<>();
                sd.getAllProperties().forEachRemaining(prop -> props.add((Property) prop));

                String roomCampus = (String) props.get(0).getValue();
                int roomCapacity = Integer.parseInt((String) props.get(2).getValue());

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

        private String sanitizeSubjectName(String name) {
            return name.replaceAll("[^a-zA-Z0-9]", "");
        }

        @Override
        public int onEnd() {
            if (!profesor.canUseMoreSubjects()) {
                return 1; // Transition to FINISHED
            }
            return 0; // Default transition to COLLECTING
        }
    }

    /**
     * Custom implementation that extends WakerBehaviour but doesn't override the final action() method.
     * Instead, it uses onWake() for the timeout event and handleElapsed() for custom logic.
     */
    private class CollectingProposalsState extends WakerBehaviour {
        private boolean proposalReceived = false;
        private final MessageTemplate proposalTemplate;
        private long lastCheckTime;

        public CollectingProposalsState() {
            super(profesor, TIMEOUT_PROPUESTA);

            // Set up template for both PROPOSE and REFUSE
            MessageTemplate proposeTemplate = MessageTemplate.MatchPerformative(ACLMessage.PROPOSE);
            MessageTemplate refuseTemplate = MessageTemplate.MatchPerformative(ACLMessage.REFUSE);
            this.proposalTemplate = MessageTemplate.or(proposeTemplate, refuseTemplate);

            this.lastCheckTime = System.currentTimeMillis();
        }

        @Override
        public void onStart() {
            super.onStart();
            proposalReceived = false;
            lastCheckTime = System.currentTimeMillis();

            // Start a separate behavior to check for proposals
            myAgent.addBehaviour(new CheckProposalsBehaviour(this));
        }

        @Override
        protected void onWake() {
            // This will be called when the timeout expires
            if (!propuestas.isEmpty()) {
                proposalReceived = true;
            }
        }

        /**
         * Inner behavior to check for proposals while waiting
         */
        private class CheckProposalsBehaviour extends jade.core.behaviours.TickerBehaviour {
            private final CollectingProposalsState parent;

            public CheckProposalsBehaviour(CollectingProposalsState parent) {
                super(profesor, 50); // Check every 50ms
                this.parent = parent;
            }

            @Override
            protected void onTick() {
                ACLMessage msg = myAgent.receive(parent.proposalTemplate);
                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.PROPOSE) {
                        handleProposal(msg);
                        parent.proposalReceived = true;
                    }
                }

                // If we have proposals or timeout expired or parent behavior is done, stop this behavior
                if (parent.proposalReceived || parent.done() || !propuestas.isEmpty()) {
                    stop();
                }
            }

            private void handleProposal(ACLMessage msg) {
                try {
                    profesor.getPerformanceMonitor().recordMessageReceived(msg, "PROPOSE");
                    ClassroomAvailability sala = (ClassroomAvailability) msg.getContentObject();

                    if (sala != null) {
                        BatchProposal batchProposal = new BatchProposal(sala, msg);
                        propuestas.offer(batchProposal);
                    } else {
                        System.out.println("Null classroom availability received");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public int onEnd() {
            if (proposalReceived && !propuestas.isEmpty()) {
                return 0; // Go to EVALUATING
            } else {
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    // Reset retry count for next subject
                    retryCount = 0;

                    if (bloquesPendientes == profesor.getCurrentSubject().getHoras()) {
                        // No blocks assigned yet, move to next subject
                        profesor.moveToNextSubject();
                    } else {
                        // Some blocks assigned, try different approach
                        assignationData.setSalaAsignada(null);
                    }
                }
                return 1; // Go back to SETUP
            }
        }
    }

    private class EvaluatingState extends OneShotBehaviour {
        @Override
        public void action() {
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

        @Override
        public int onEnd() {
            Asignatura currentSubject = profesor.getCurrentSubject();

            if (bloquesPendientes == 0) {
                // Record subject completion time
                String subjectName = currentSubject.getNombre();
                long subjectTime = System.currentTimeMillis() - negotiationStartTime;
                subjectNegotiationTimes.put(subjectName, subjectTime);

                // Move to next subject
                profesor.moveToNextSubject();
                return 0; // Go to SETUP to handle next subject
            } else if (retryCount >= MAX_RETRIES) {
                // Too many retries
                if (assignationData.hasSalaAsignada()) {
                    // Try different room
                    assignationData.setSalaAsignada(null);
                    return 2; // Go to COLLECTING with new parameters
                } else {
                    // Give up on this subject
                    profesor.moveToNextSubject();
                    return 0; // Back to SETUP
                }
            } else if (bloquesPendientes > 0) {
                // Continue with same subject
                return 2; // Go to COLLECTING for more proposals
            }

            // This is a fallback path - should not normally get here
            return 1; // FINISHED
        }
    }

    private class FinishedState extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("Professor " + profesor.getNombre() + " finished all negotiations");
            profesor.finalizarNegociaciones();
        }
    }
}