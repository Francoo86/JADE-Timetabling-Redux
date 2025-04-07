package behaviours;

import agentes.AgenteProfesor;
import agentes.AgenteProfesorFSM;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.lang.acl.UnreadableException;
import jade.domain.FIPANames;

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
import performance.SimpleRTT;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.IOException;

public class NegotiationFSMBehaviour extends FSMBehaviour {
    // States
    private static final String SETUP = "SETUP";
    private static final String COLLECTING = "COLLECTING";
    private static final String EVALUATING = "EVALUATING";
    private static final String FINISHED = "FINISHED";

    private final AgenteProfesorFSM profesor;
    private final ConcurrentLinkedQueue<BatchProposal> propuestas;
    private int bloquesPendientes = 0;
    private int retryCount = 0;
    private static final int BASE_MAX_RETRIES = 3;
    private static final long BASE_TIMEOUT_PROPUESTA = 1000; // 1 second base timeout
    private final ConstraintEvaluator evaluator;
    private final AssignationData assignationData = new AssignationData();
    private static final int MEETING_ROOM_THRESHOLD = 10; // Threshold for meeting room logic
    private final SimpleRTT rttTracker;

    // Enhanced tracking for subject instances
    private Map<String, Integer> instanceCounters = new HashMap<>();
    private final Set<String> processedSubjectKeys = new HashSet<>();
    private final Set<String> failedRoomAssignments = new HashSet<>();

    // Used for performance monitoring
    private long negotiationStartTime;
    private final Map<String, Long> subjectNegotiationTimes = new HashMap<>();
    private final Map<String, Set<String>> responsiveRooms = new HashMap<>();

    public NegotiationFSMBehaviour(AgenteProfesorFSM profesor, ConcurrentLinkedQueue<BatchProposal> propuestas) {
        super(profesor);
        this.profesor = profesor;
        this.evaluator = new ConstraintEvaluator(profesor);
        this.negotiationStartTime = System.currentTimeMillis();
        this.propuestas = propuestas;
        this.rttTracker = SimpleRTT.getInstance();

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
        registerTransition(EVALUATING, FINISHED, 1);   // Done with all subjects
        registerTransition(EVALUATING, COLLECTING, 2); // Retry with same subject
        registerTransition(SETUP, FINISHED, 1);        // No more subjects
    }

    public int getBloquesPendientes() {
        return bloquesPendientes;
    }

    private void logState(String state, String details) {
        System.out.printf("[%s] %s - Professor: %s, Subject: %s, Pending: %d/%d, Retry: %d%n",
                state,
                details,
                profesor.getNombre(),
                profesor.getCurrentSubject() != null ? profesor.getCurrentSubject().getNombre() : "None",
                bloquesPendientes,
                profesor.getCurrentSubject() != null ? profesor.getCurrentSubject().getHoras() : 0,
                retryCount);
    }

    private int getAdjustedMaxRetries() {
        Asignatura current = profesor.getCurrentSubject();
        if (current == null) return BASE_MAX_RETRIES;

        // Adjust based on subject priority or complexity
        if (bloquesPendientes > 0 && bloquesPendientes < current.getHoras()) {
            // We've already assigned some blocks, try harder to complete
            return BASE_MAX_RETRIES + 2;
        }

        return BASE_MAX_RETRIES;
    }

    private long calculateAdaptiveTimeout() {
        // Start with base timeout
        long baseTimeout = BASE_TIMEOUT_PROPUESTA;

        // Add exponential backoff based on retry count
        long additionalTime = (long) Math.pow(2, retryCount) * 200;

        // Add time based on subject complexity (number of hours)
        Asignatura current = profesor.getCurrentSubject();
        long subjectComplexity = current != null ? current.getHoras() * 100 : 0;

        return baseTimeout + additionalTime + subjectComplexity;
    }

    private String getCurrentSubjectKey() {
        Asignatura current = profesor.getCurrentSubject();
        if (current == null) return "";
        return String.format("%s-%s-%s",
                current.getNombre(),
                current.getCodigoAsignatura(),
                current.getActividad().toString());
    }

    private class SetupState extends OneShotBehaviour {
        @Override
        public void action() {
            logState(SETUP, "Starting setup phase");

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
                // Reset state for new subject
                bloquesPendientes = currentSubject.getHoras();
                assignationData.clear();
                failedRoomAssignments.clear();
                retryCount = 0;

                // Track negotiation times
                negotiationStartTime = System.currentTimeMillis();

                String subjectKey = getCurrentSubjectKey();
                System.out.printf("[SETUP] Starting assignment for %s (Code: %s, Activity: %s) - Required hours: %d%n",
                        currentSubject.getNombre(),
                        currentSubject.getCodigoAsignatura(),
                        currentSubject.getActividad(),
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
                    System.out.println("No classroom agents found.");
                    return;
                }

                Asignatura currentSubject = profesor.getCurrentSubject();
                if (currentSubject == null) {
                    System.err.println("Warning: No current subject available for professor " + profesor.getNombre());
                    return;
                }

                // Record rooms we're sending to in this conversation
                String conversationId = "neg-" + profesor.getNombre() + "-" + bloquesPendientes;
                Set<String> roomsForThisRound = new HashSet<>();
                responsiveRooms.put(conversationId, roomsForThisRound);

                // Create CFP message
                ACLMessage cfp = createCFPMessage(currentSubject, conversationId);

                // Track rooms that have previously failed for this subject
                String subjectKey = getCurrentSubjectKey();

                // Add receivers after filtering
                int sentCount = 0;
                for (DFAgentDescription room : results) {
                    String roomName = room.getName().getLocalName();

                    // Skip rooms that have already failed for this subject
                    String roomSubjectKey = subjectKey + "-" + roomName;
                    if (failedRoomAssignments.contains(roomSubjectKey)) {
                        System.out.printf("Skipping previously failed room %s for %s%n",
                                roomName, subjectKey);
                        continue;
                    }

                    if (!canQuickReject(currentSubject, room)) {
                        cfp.addReceiver(room.getName());
                        roomsForThisRound.add(roomName);
                        sentCount++;
                    }
                }

                if (sentCount > 0) {
                    // Start RTT tracking
                    rttTracker.messageSent(conversationId, profesor.getAID(), null, "CFP");

                    profesor.getPerformanceMonitor().recordMessageSent(cfp, "CFP");
                    profesor.send(cfp);

                    System.out.printf("Sent %d CFP messages for %s%n", sentCount, currentSubject.getNombre());
                } else {
                    System.out.printf("No suitable rooms available for %s after filtering%n",
                            currentSubject.getNombre());
                }
            } catch (Exception e) {
                System.err.println("Error sending proposal requests: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private final Map<String, Boolean> quickRejectCache = new ConcurrentHashMap<>();

        // Cache key generator
        private String getCacheKey(Asignatura subject, String roomId) {
            return String.format("%s-%s-%s-%s",
                    subject.getNombre(),
                    subject.getCodigoAsignatura(),
                    subject.getActividad(),
                    roomId);
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

        private ACLMessage createCFPMessage(Asignatura currentSubject, String conversationId) {
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);

            cfp.setSender(profesor.getAID());
            cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);

            // Build request info
            String solicitudInfo = String.format("%s,%d,%d,%s,%d,%s,%s,%d,%s",
                    sanitizeSubjectName(currentSubject.getNombre()),
                    currentSubject.getVacantes(),
                    currentSubject.getNivel(),
                    currentSubject.getCampus(),
                    bloquesPendientes,
                    assignationData.getSalaAsignada(),
                    assignationData.getUltimoDiaAsignado() != null ?
                            assignationData.getUltimoDiaAsignado().toString() : "",
                    assignationData.getUltimoBloqueAsignado(),
                    currentSubject.getActividad().toString());  // Add activity type

            cfp.setContent(solicitudInfo);
            cfp.setConversationId(conversationId);

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

    private class CollectingProposalsState extends WakerBehaviour {
        private boolean proposalReceived = false;
        private final MessageTemplate proposalTemplate;
        private long lastCheckTime;

        public CollectingProposalsState() {
            super(profesor, calculateAdaptiveTimeout());

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

            logState(COLLECTING, "Starting proposal collection phase");
        }

        @Override
        protected void onWake() {
            // Instead of a separate behavior, collect messages directly
            long startTime = System.currentTimeMillis();
            long endTime = startTime + calculateAdaptiveTimeout();

            while (System.currentTimeMillis() < endTime) {
                ACLMessage msg = myAgent.receive(proposalTemplate);
                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.PROPOSE) {
                        try {
                            rttTracker.messageReceived(msg.getConversationId(), msg);
                            profesor.getPerformanceMonitor().recordMessageReceived(msg, "PROPOSE");

                            // Record message metrics
                            profesor.getPerformanceMonitor().recordMessageMetrics(
                                    msg.getConversationId(),
                                    "PROPOSE_RECEIVED",
                                    0, // RTT calculated elsewhere
                                    msg.getSender().getLocalName(),
                                    profesor.getLocalName()
                            );

                            // Process proposal
                            ClassroomAvailability sala = (ClassroomAvailability) msg.getContentObject();
                            if (sala == null) {
                                System.out.println("Null classroom availability received");
                                continue;
                            }

                            BatchProposal batchProposal = new BatchProposal(sala, msg);
                            propuestas.offer(batchProposal);
                            proposalReceived = true;

                            // Track responsive rooms
                            String conversationId = msg.getConversationId();
                            Set<String> rooms = responsiveRooms.get(conversationId);
                            if (rooms != null) {
                                rooms.add(msg.getSender().getLocalName());
                            }

                        } catch (Exception e) {
                            System.err.println("Error processing proposal: " + e.getMessage());
                            e.printStackTrace();
                        }
                    } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                        // Track refusals for analysis
                        System.out.printf("Received REFUSE from %s%n", msg.getSender().getLocalName());
                    }
                }

                // Check if we've collected enough proposals
                if (!propuestas.isEmpty() && proposalReceived) {
                    System.out.printf("Collected %d proposals, ending collection phase early%n", propuestas.size());
                    break;
                }

                // Short sleep to avoid CPU spinning
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Check proposal state at the end of timeout
            if (!propuestas.isEmpty()) {
                proposalReceived = true;
                System.out.printf("Collection complete: %d proposals received%n", propuestas.size());
            } else {
                System.out.println("No proposals received after timeout");
            }
        }

        @Override
        public int onEnd() {
            if (proposalReceived && !propuestas.isEmpty()) {
                return 0; // Go to EVALUATING
            } else {
                retryCount++;

                if (retryCount >= getAdjustedMaxRetries()) {
                    // Reset retry count for next subject
                    logState(COLLECTING, "Exceeded maximum retries, moving to next setup phase");
                    retryCount = 0;

                    if (bloquesPendientes == profesor.getCurrentSubject().getHoras()) {
                        // No blocks assigned yet, move to next subject
                        System.out.printf("No proposals after %d retries for %s, moving to next subject%n",
                                getAdjustedMaxRetries(), profesor.getCurrentSubject().getNombre());
                        profesor.moveToNextSubject();
                    } else {
                        // Some blocks assigned, try different approach
                        System.out.printf("Partial assignment (%d/%d blocks) for %s, clearing sala preference%n",
                                profesor.getCurrentSubject().getHoras() - bloquesPendientes,
                                profesor.getCurrentSubject().getHoras(),
                                profesor.getCurrentSubject().getNombre());
                        assignationData.setSalaAsignada(null);
                    }
                } else {
                    System.out.printf("No proposals received, retry %d/%d%n",
                            retryCount, getAdjustedMaxRetries());
                }
                return 1; // Go back to SETUP
            }
        }
    }

    private class EvaluatingState extends OneShotBehaviour {
        @Override
        public void action() {
            logState(EVALUATING, "Starting proposal evaluation");

            List<BatchProposal> currentBatchProposals = new ArrayList<>();
            while (!propuestas.isEmpty()) {
                BatchProposal bp = propuestas.poll();
                if (bp != null) {
                    currentBatchProposals.add(bp);
                }
            }

            // Before processing, log the assessment
            System.out.printf("Evaluating %d proposals for %s (pending: %d)%n",
                    currentBatchProposals.size(),
                    profesor.getCurrentSubject().getNombre(),
                    bloquesPendientes);

            List<BatchProposal> validProposals = evaluator.filterAndSortProposals(currentBatchProposals);
            System.out.printf("Found %d valid proposals after filtering%n", validProposals.size());

            if (!validProposals.isEmpty() && tryAssignBatchProposals(validProposals)) {
                retryCount = 0;
            } else {
                // Record failed assignments to avoid retrying same rooms
                for (BatchProposal bp : currentBatchProposals) {
                    String roomCode = bp.getRoomCode();
                    String subjectKey = getCurrentSubjectKey();
                    failedRoomAssignments.add(subjectKey + "-" + roomCode);
                }

                System.out.printf("Failed to assign blocks for %s, retry count: %d/%d%n",
                        profesor.getCurrentSubject().getNombre(), retryCount, getAdjustedMaxRetries());
                retryCount++;
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

            // Sort proposals primarily by satisfaction score (highest first)
            batchProposals.sort(Comparator.comparing(BatchProposal::getSatisfactionScore).reversed());

            // Process each batch proposal (each represents one room's available blocks)
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
                        } else {
                            // Record failed assignment
                            String roomCode = batchProposal.getRoomCode();
                            String subjectKey = getCurrentSubjectKey();
                            failedRoomAssignments.add(subjectKey + "-" + roomCode);

                            System.out.printf("Failed to confirm assignment for %s in room %s%n",
                                    currentSubject.getNombre(), batchProposal.getRoomCode());
                        }
                    } catch (Exception e) {
                        System.err.println("Error in batch assignment: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }

            long totalBatchTime = System.currentTimeMillis() - batchStartTime;
            System.out.printf("[TIMING] Total batch assignment time for %s: %d ms - Total blocks assigned: %d%n",
                    currentSubject.getNombre(), totalBatchTime, totalAssigned);

            // If we couldn't assign enough blocks, record why
            if (totalAssigned < bloquesPendientes) {
                System.out.printf("Failed to assign all blocks: %d/%d assigned. Retry: %d/%d%n",
                        totalAssigned, bloquesPendientes, retryCount, getAdjustedMaxRetries());
            }

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

            // Start RTT tracking for acceptance
            String conversationId = originalMsg.getConversationId() + "-accept";
            rttTracker.messageSent(conversationId, profesor.getAID(), originalMsg.getSender(), "ACCEPT_PROPOSAL");

            // Wait for confirmation
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(originalMsg.getSender()),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)
            );

            return waitForConfirmation(mt, requests, conversationId);
        }

        private boolean waitForConfirmation(MessageTemplate mt, List<BatchAssignmentRequest.AssignmentRequest> requests,
                                            String conversationId) {
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
                        // Record RTT for confirmation
                        rttTracker.messageReceived(conversationId, confirm);

                        BatchAssignmentConfirmation confirmation =
                                (BatchAssignmentConfirmation) confirm.getContentObject();

                        for (BatchAssignmentConfirmation.ConfirmedAssignment assignment :
                                confirmation.getConfirmedAssignments()) {

                            Asignatura currentSubject = profesor.getCurrentSubject();

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

                            // Mark this subject+activity instance as processed
                            processedSubjectKeys.add(getCurrentSubjectKey());
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

                // Log completion
                System.out.printf("Subject %s (%s) completed successfully!%n",
                        currentSubject.getNombre(), currentSubject.getCodigoAsignatura());

                // Move to next subject
                profesor.moveToNextSubject();

                if (!profesor.canUseMoreSubjects()) {
                    // All subjects completed
                    return 1; // Go to FINISHED
                }

                return 0; // Go to SETUP to handle next subject
            } else if (retryCount >= getAdjustedMaxRetries()) {
                // Too many retries
                if (assignationData.hasSalaAsignada()) {
                    // Try different room
                    System.out.printf("Max retries reached with sala %s, trying different rooms%n",
                            assignationData.getSalaAsignada());
                    assignationData.setSalaAsignada(null);
                    return 2; // Go to COLLECTING with new parameters
                } else {
                    // Give up on this subject
                    System.out.printf("Giving up on subject %s after %d retries%n",
                            currentSubject.getNombre(), retryCount);
                    profesor.moveToNextSubject();
                    return 0; // Back to SETUP
                }
            } else if (bloquesPendientes > 0) {
                // Continue with same subject
                return 2; // Go to COLLECTING for more proposals
            }

            // This is a fallback path - should not normally get here
            return 0; // Go to SETUP as safest option
        }
    }
    private class FinishedState extends OneShotBehaviour {
        @Override
        public void action() {
            logState(FINISHED, "All negotiations completed");

            // Print summary statistics
            Map<String, Integer> completedSubjects = new HashMap<>();
            for (String subjectKey : processedSubjectKeys) {
                completedSubjects.merge(subjectKey.split("-")[0], 1, Integer::sum);
            }

            System.out.println("\n==== NEGOTIATION SUMMARY ====");
            System.out.printf("Professor %s completed negotiation with:%n", profesor.getNombre());
            completedSubjects.forEach((subject, count) -> {
                System.out.printf("- %s: %d instances%n", subject, count);
            });

            // Record responsive rooms statistics
            System.out.println("\nRoom responsiveness statistics:");
            Map<String, Integer> roomResponses = new HashMap<>();
            responsiveRooms.forEach((convoId, rooms) -> {
                rooms.forEach(room -> roomResponses.merge(room, 1, Integer::sum));
            });

            // Sort by most responsive
            roomResponses.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10) // Show top 10 most responsive rooms
                    .forEach(entry -> {
                        System.out.printf("- %s: responded to %d negotiations%n",
                                entry.getKey(), entry.getValue());
                    });

            // Finalize the professor's negotiations
            profesor.finalizarNegociaciones();
        }
    }
}

