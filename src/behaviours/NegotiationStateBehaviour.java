package behaviours;

import agentes.AgenteProfesor;
import constants.BlockOptimization;
import constants.BlockScore;
import constants.Commons;
import constants.Messages;
import constants.enums.Day;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import objetos.Asignatura;
import objetos.BloqueInfo;
import objetos.Propuesta;
import objetos.AssignationData;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class NegotiationStateBehaviour extends TickerBehaviour {
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<Propuesta> propuestas;
    private NegotiationState currentState;
    private long proposalTimeout;
    private int retryCount = 0;
    //private static final int MAX_RETRIES = 10;
    private boolean proposalReceived = false;
    private final AssignationData assignationData;
    private int bloquesPendientes = 0;
    private static final long TIMEOUT_PROPUESTA = 500; // 5 seconds

    public enum NegotiationState {
        SETUP,
        COLLECTING_PROPOSALS,
        EVALUATING_PROPOSALS,
        FINISHED
    }

    public NegotiationStateBehaviour(AgenteProfesor profesor, long period, ConcurrentLinkedQueue<Propuesta> propuestas) {
        super(profesor, period);
        this.profesor = profesor;
        this.propuestas = propuestas;
        this.currentState = NegotiationState.SETUP;
        this.assignationData = new AssignationData();
    }

    public synchronized void notifyProposalReceived() {
        this.proposalReceived = true;
    }

    @Override
    protected void onTick() {
        System.out.println(myAgent.getLocalName() + "MSG Pendientes: " + myAgent.getCurQueueSize());
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
        if (!profesor.canUseMoreSubjects()) {
            currentState = NegotiationState.FINISHED;
            profesor.finalizarNegociaciones();
            return;
        }

        Asignatura currentSubject = profesor.getCurrentSubject();
        if (currentSubject != null) {
            bloquesPendientes = currentSubject.getHoras();
            assignationData.clear();

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
            proposalReceived = false;
        } else {
//            System.out.println("Error: No hay asignatura actual para " + profesor.getNombre());
            currentState = NegotiationState.FINISHED;
        }
    }

    private void handleEvaluatingState() {
        List<Propuesta> currentProposals = new ArrayList<>();
        while (!propuestas.isEmpty()) {
            Propuesta p = propuestas.poll();
            if (p != null) {
                currentProposals.add(p);
            }
        }

        // Filter and sort proposals based on constraints
        //FIXME: Dont use this.
        List<Propuesta> validProposals = filterAndSortProposals(currentProposals);

        //ACA TOCA CAMBIAR EL METODO DE ASIGNACION
        if (!validProposals.isEmpty() && tryAssignBestProposal(validProposals)) {
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

    private boolean tryAssignBestProposal(List<Propuesta> validProposals) {
        return tryAssignBatchProposals(validProposals);
    }

    private List<Propuesta> filterAndSortProposals(List<Propuesta> proposals) {
        if (proposals.isEmpty()) {
            return Collections.emptyList();
        }

        Asignatura currentSubject = profesor.getCurrentSubject();
        String currentCampus = currentSubject.getCampus();
        int currentNivel = currentSubject.getNivel();
        String currentAsignaturaNombre = currentSubject.getNombre();

        // Pre-calculate common values
        boolean isOddYear = currentNivel % 2 == 1;
        int targetSize = proposals.size();

        // Use ArrayList for better performance with sorting
        ArrayList<ProposalScore> scoredProposals = new ArrayList<>(targetSize);

        // First pass: Filter and calculate scores simultaneously
        for (Propuesta proposal : proposals) {
            if (!isValidProposalFast(proposal, currentSubject, isOddYear, currentAsignaturaNombre)) {
                continue;
            }

            int score = calculateProposalScore(proposal, currentCampus, currentNivel, currentSubject);
            scoredProposals.add(new ProposalScore(proposal, score));
        }

        // Early return if no valid proposals
        if (scoredProposals.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort in place using a simple comparison
        scoredProposals.sort((ps1, ps2) -> ps2.score - ps1.score);

        // Convert back to List<Propuesta>
        int resultSize = scoredProposals.size();
        ArrayList<Propuesta> result = new ArrayList<>(resultSize);
        for (ProposalScore ps : scoredProposals) {
            result.add(ps.proposal);
        }

        return result;
    }

    // Lightweight class to hold proposal and its score
    private static class ProposalScore {
        final Propuesta proposal;
        final int score;

        ProposalScore(Propuesta proposal, int score) {
            this.proposal = proposal;
            this.score = score;
        }
    }

    // Optimized validation method combining multiple checks
    private boolean isValidProposalFast(Propuesta propuesta, Asignatura asignatura,
                                        boolean isOddYear, String asignaturaNombre) {
        // Check basic time constraints first (fastest checks)
        int bloque = propuesta.getBloque();
        if (bloque < 1 || bloque > Commons.MAX_BLOQUE_DIURNO) {
            return false;
        }

        // Check block 9 constraint
        if (bloque == Commons.MAX_BLOQUE_DIURNO && bloquesPendientes % 2 == 0) {
            return false;
        }

        // Check year-based constraints
        if (isOddYear) {
            if (bloque > 4 && bloque != Commons.MAX_BLOQUE_DIURNO) {
                return false;
            }
        } else {
            if (bloque < 5 && propuesta.getSatisfaccion() < 8) {
                return false;
            }
        }

        // Check block limit per day
        Map<String, List<Integer>> asignaturasEnDia = profesor.getBlocksByDay(propuesta.getDia());
        List<Integer> bloques = asignaturasEnDia.get(asignaturaNombre);
        if (bloques != null && bloques.size() >= 2) {
            return false;
        }

        // More expensive checks
        if (!checkCampusConstraints(propuesta, asignatura.getCampus())) {
            return false;
        }

        // Check consecutive blocks only if needed
        return bloquesPendientes < 2 || hasConsecutiveBlockAvailable(propuesta);
    }

    // Optimized score calculation
    private int calculateProposalScore(Propuesta proposal, String currentCampus,
                                       int nivel, Asignatura subject) {
        int score = 0;

        // Campus score (highest priority)
        if (getCampusSala(proposal.getCodigo()).equals(currentCampus)) {
            score += 10000;
        }

        // Block preference score
        boolean isOddYear = nivel % 2 == 1;
        int bloque = proposal.getBloque();
        if (isOddYear) {
            score += (bloque <= 4) ? 5000 : 0;
        } else {
            score += (bloque >= 5) ? 5000 : 0;
        }

        // Consecutive block bonus
        List<Integer> existingBlocks = profesor.getBlocksBySubject(subject.getNombre())
                .getOrDefault(proposal.getDia(), Collections.emptyList());
        if (!existingBlocks.isEmpty()) {
            for (int existingBlock : existingBlocks) {
                if (Math.abs(existingBlock - bloque) == 1) {
                    score += 2000;
                    break;
                }
            }
        }

        // Satisfaction score
        score += proposal.getSatisfaccion() * 100;

        return score;
    }

    private boolean isValidProposal(Propuesta propuesta, Asignatura asignatura) {
        // Check basic time constraints
        if (!checkTimeConstraints(propuesta)) {
            return false;
        }

        // Check campus constraints
        if (!checkCampusConstraints(propuesta, asignatura.getCampus())) {
            return false;
        }

        // Check year-based constraints
        if (!checkYearBasedConstraints(propuesta, asignatura.getNivel())) {
            return false;
        }

        // Check block 9 constraint
        if (propuesta.getBloque() == Commons.MAX_BLOQUE_DIURNO && bloquesPendientes % 2 == 0) {
            return false;
        }

        // Check block limit per day
        if (countBlocksPerDay(propuesta.getDia(), asignatura.getNombre()) >= 2) {
            return false;
        }

        // Check consecutive blocks availability if needed
        if (bloquesPendientes >= 2 && !hasConsecutiveBlockAvailable(propuesta)) {
            return false;
        }

        return true;
    }

    private int countBlocksPerDay(Day dia, String nombreAsignatura) {
        Map<String, List<Integer>> asignaturasEnDia = profesor.getBlocksByDay(dia);
        List<Integer> bloques = asignaturasEnDia.getOrDefault(nombreAsignatura, new ArrayList<>());
        return bloques.size();
    }

    private boolean checkTimeConstraints(Propuesta propuesta) {
        int bloque = propuesta.getBloque();
        return bloque >= 1 && bloque <= Commons.MAX_BLOQUE_DIURNO;
    }

    private boolean checkCampusConstraints(Propuesta propuesta, String currentCampus) {
        Day dia = propuesta.getDia();
        String proposedCampus = getCampusSala(propuesta.getCodigo());

        // If same campus, always valid
        if (proposedCampus.equals(currentCampus)) {
            return true;
        }

        // Check if there's already a campus transition this day
        if (hasExistingTransitionInDay(dia)) {
            return false;
        }

        // Validate buffer block for campus transition
        return validateTransitionBuffer(propuesta);
    }

    private boolean checkYearBasedConstraints(Propuesta propuesta, int nivel) {
        // First, third, and fifth years prefer morning blocks (1-4)
        // Second, fourth, and sixth years prefer afternoon blocks (5-9)
        boolean isOddYear = nivel % 2 == 1;
        int bloque = propuesta.getBloque();

        if (isOddYear) {
            return bloque <= 4 || bloque == Commons.MAX_BLOQUE_DIURNO;
        } else {
            return bloque >= 5 || propuesta.getSatisfaccion() >= 8;
        }
    }

    private boolean hasConsecutiveBlockAvailable(Propuesta propuesta) {
        Day dia = propuesta.getDia();
        int bloque = propuesta.getBloque();

        boolean previousBlockAvailable = bloque > 1 &&
                profesor.isBlockAvailable(dia, bloque - 1);
        boolean nextBlockAvailable = bloque < Commons.MAX_BLOQUE_DIURNO &&
                profesor.isBlockAvailable(dia, bloque + 1);

        return previousBlockAvailable || nextBlockAvailable;
    }

    private int compareProposals(Propuesta p1, Propuesta p2,
                                 Asignatura subject, String currentCampus, int nivel) {
        // First priority: Block optimization score
        BlockScore score1 = BlockOptimization.getInstance().evaluateBlock(
                currentCampus, nivel, p1.getBloque(), p1.getDia(),
                profesor.getBlocksBySubject(subject.getNombre())
        );
        BlockScore score2 = BlockOptimization.getInstance().evaluateBlock(
                currentCampus, nivel, p2.getBloque(), p2.getDia(),
                profesor.getBlocksBySubject(subject.getNombre())
        );

        if (score1.getScore() != score2.getScore()) {
            return score2.getScore() - score1.getScore();
        }

        // Second priority: Campus transition score
        int transitionScore1 = evaluateCampusTransition(p1, currentCampus);
        int transitionScore2 = evaluateCampusTransition(p2, currentCampus);

        if (transitionScore1 != transitionScore2) {
            return transitionScore2 - transitionScore1;
        }

        // Third priority: Professor satisfaction
        return p2.getSatisfaccion() - p1.getSatisfaccion();
    }

    private int evaluateCampusTransition(Propuesta propuesta, String currentCampus) {
        String proposedCampus = getCampusSala(propuesta.getCodigo());

        if (proposedCampus.equals(currentCampus)) {
            return 100;
        }

        if (hasExistingTransitionInDay(propuesta.getDia())) {
            return 0;
        }

        // Check surrounding blocks for transitions
        BloqueInfo prevBlock = profesor.getBloqueInfo(propuesta.getDia(), propuesta.getBloque() - 1);
        BloqueInfo nextBlock = profesor.getBloqueInfo(propuesta.getDia(), propuesta.getBloque() + 1);

        if (prevBlock != null && !prevBlock.getCampus().equals(proposedCampus)) {
            return 25;
        }

        if (nextBlock != null && !nextBlock.getCampus().equals(proposedCampus)) {
            return 25;
        }

        return 75;
    }

    private boolean hasExistingTransitionInDay(Day dia) {
        String previousCampus = null;
        Map<String, List<Integer>> dayClasses = profesor.getBlocksByDay(dia);

        if (dayClasses == null || dayClasses.isEmpty()) {
            return false;
        }

        List<BloqueInfo> blocks = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : dayClasses.entrySet()) {
            for (Integer bloque : entry.getValue()) {
                BloqueInfo info = profesor.getBloqueInfo(dia, bloque);
                if (info != null) {
                    blocks.add(info);
                }
            }
        }

        Collections.sort(blocks, Comparator.comparingInt(BloqueInfo::getBloque));

        for (BloqueInfo block : blocks) {
            if (previousCampus != null && !previousCampus.equals(block.getCampus())) {
                return true;
            }
            previousCampus = block.getCampus();
        }

        return false;
    }

    private boolean validateTransitionBuffer(Propuesta propuesta) {
        Day dia = propuesta.getDia();
        int bloque = propuesta.getBloque();
        String proposedCampus = getCampusSala(propuesta.getCodigo());

        BloqueInfo prevBlock = profesor.getBloqueInfo(dia, bloque - 1);
        BloqueInfo nextBlock = profesor.getBloqueInfo(dia, bloque + 1);

        // Check if there's at least one empty block between different campuses
        if (prevBlock != null && !prevBlock.getCampus().equals(proposedCampus)) {
            return profesor.isBlockAvailable(dia, bloque - 1);
        }

        if (nextBlock != null && !nextBlock.getCampus().equals(proposedCampus)) {
            return profesor.isBlockAvailable(dia, bloque + 1);
        }

        return true;
    }

    private String getCampusSala(String codigoSala) {
        if (codigoSala.startsWith("A")) {
            return "Playa Brava";
        } else if (codigoSala.startsWith("B")) {
            return "Huayquique";
        }
        return "";
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
            long backoffTime = (long) Math.pow(2, retryCount) * 1000; // 2^retry seconds
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
            sendProposalRequests();
        }
    }

    private void handleProposalFailure() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (assignationData.hasSalaAsignada()) {
                // Try different room if current one isn't working
                assignationData.setSalaAsignada(null);
            } else {
                // If we've tried different rooms without success, move on
                profesor.moveToNextSubject();
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
            currentState = NegotiationState.COLLECTING_PROPOSALS;
            // Add exponential backoff here too
            long backoffTime = (long) Math.pow(2, retryCount) * 1000;
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA + backoffTime;
            sendProposalRequests();
        }
    }

    private void handleCollectingState() {
        // If we received proposals, evaluate immediately
        if (proposalReceived) {
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

    private boolean tryAssignBatchProposals(List<Propuesta> validProposals) {
        Map<String, List<Propuesta>> proposalsBySala = validProposals.stream()
                .collect(Collectors.groupingBy(Propuesta::getCodigo));

        boolean anySuccess = false;
        Asignatura currentSubject = profesor.getCurrentSubject();
        String subjectKey = profesor.getCurrentSubjectKey();
        int requiredHours = profesor.getCurrentSubjectRequiredHours();
        int assignedHours = 0;

        // Track assigned hours per day to prevent over-assignment
        Map<Day, Integer> hoursPerDay = new HashMap<>();

        for (Map.Entry<String, List<Propuesta>> entry : proposalsBySala.entrySet()) {
            if (assignedHours >= requiredHours) break;

            List<Propuesta> salaProposals = entry.getValue();
            if (!salaProposals.isEmpty()) {
                List<BatchAssignmentRequest.AssignmentRequest> requests = new ArrayList<>();

                // Sort proposals by day and block
                salaProposals.sort(Comparator
                        .comparing(Propuesta::getDia)
                        .thenComparing(Propuesta::getBloque));

                for (Propuesta propuesta : salaProposals) {
                    if (assignedHours >= requiredHours) break;

                    Day day = propuesta.getDia();
                    int dayHours = hoursPerDay.getOrDefault(day, 0);

                    // Limit 2 hours per day per subject
                    if (dayHours >= 2) continue;

                    if (profesor.isBlockAvailable(propuesta.getDia(), propuesta.getBloque())) {
                        requests.add(new BatchAssignmentRequest.AssignmentRequest(
                                propuesta.getDia(),
                                propuesta.getBloque(),
                                currentSubject.getNombre(),
                                propuesta.getSatisfaccion(),
                                propuesta.getCodigo(),
                                currentSubject.getVacantes()
                        ));
                        assignedHours++;
                        hoursPerDay.merge(day, 1, Integer::sum);
                    }
                }

                if (!requests.isEmpty()) {
                    try {
                        ACLMessage batchAccept = salaProposals.get(0).getMensaje().createReply();
                        batchAccept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                        batchAccept.setContentObject(new BatchAssignmentRequest(requests));
                        profesor.send(batchAccept);

                        // Wait for batch confirmation
                        MessageTemplate mt = MessageTemplate.and(
                                MessageTemplate.MatchSender(salaProposals.get(0).getMensaje().getSender()),
                                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                        );

                        long startTime = System.currentTimeMillis();
                        long timeout = 1000;

                        while (System.currentTimeMillis() - startTime < timeout) {
                            ACLMessage confirm = myAgent.receive(mt);
                            if (confirm != null) {
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
                                    anySuccess = true;
                                }
                                break;
                            }
                            block(50);
                        }
                        System.out.printf("[BATCH] Subject %s (Code: %s): Assigned %d/%d hours (Pending: %d)%n",
                                currentSubject.getNombre(),
                                currentSubject.getCodigoAsignatura(),
                                assignedHours,
                                requiredHours,
                                bloquesPendientes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (UnreadableException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        return anySuccess && assignedHours == requiredHours;
    }
    //FIXME: Esto envia no por bloques, si no TODAS las propuestas aceptadas
    /*private boolean tryAssignProposal(Propuesta propuesta) {
        Day dia = propuesta.getDia();
        int bloque = propuesta.getBloque();

        if (!profesor.isBlockAvailable(dia, bloque)) {
            return false;
        }

        // Send acceptance message
        ACLMessage accept = propuesta.getMensaje().createReply();
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);

        Asignatura currentSubject = profesor.getCurrentSubject();
        accept.setContent(String.format("%s,%d,%s,%d,%s,%d",
                dia,
                bloque,
                currentSubject.getNombre(),
                propuesta.getSatisfaccion(),
                propuesta.getCodigo(),
                currentSubject.getVacantes()));

        profesor.send(accept);

        // Wait for confirmation with retry
        long startTime = System.currentTimeMillis();
        long timeout = 1000; // 1 second timeout for confirmation

        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchSender(propuesta.getMensaje().getSender()),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
        );

        while (System.currentTimeMillis() - startTime < timeout) {
            ACLMessage confirm = myAgent.receive(mt);
            if (confirm != null) {
                //Aca le pasamos asignacionsala?
                profesor.updateScheduleInfo(
                        dia,
                        propuesta.getCodigo(),
                        bloque,
                        currentSubject.getNombre(),
                        propuesta.getSatisfaccion()
                );

                // Update negotiation state
                bloquesPendientes--;
                assignationData.assign(dia, propuesta.getCodigo(), bloque);

//                System.out.printf("Profesor %s: Asignado bloque %d del día %s en sala %s para %s (quedan %d horas)%n",
//                        profesor.getNombre(), bloque, dia, propuesta.getCodigo(),
//                        currentSubject.getNombre(), bloquesPendientes);

                return true;
            }
            block(100); // Block for 100ms between checks
        }

//        System.out.println("Timeout esperando confirmación de sala " + propuesta.getCodigo());
        return false;
    }*/

    private void sendProposalRequests() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("sala");
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(profesor, template);
            if (result.length == 0) {
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            for (DFAgentDescription dfd : result) {
                cfp.addReceiver(dfd.getName());
            }

            // Enhanced CFP content with more context
            String solicitudInfo = String.format("%s,%d,%d,%s,%d,%s,%s,%d",
                    currentSubject.getNombre(),
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
            profesor.send(cfp);

        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}