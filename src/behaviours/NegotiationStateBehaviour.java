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
import objetos.Asignatura;
import objetos.BloqueInfo;
import objetos.Propuesta;
import objetos.AssignationData;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class NegotiationStateBehaviour extends TickerBehaviour {
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<Propuesta> propuestas;
    private NegotiationState currentState;
    private long proposalTimeout;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 10;
    private boolean proposalReceived = false;
    private final AssignationData assignationData;
    private int bloquesPendientes = 0;
    private static final long TIMEOUT_PROPUESTA = 5000; // 5 seconds

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
        List<Propuesta> validProposals = filterAndSortProposals(currentProposals);

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
        for (Propuesta propuesta : validProposals) {
            if (tryAssignProposal(propuesta)) {
                return true;
            }
        }
        return false;
    }

    private List<Propuesta> filterAndSortProposals(List<Propuesta> proposals) {
        Asignatura currentSubject = profesor.getCurrentSubject();
        String currentCampus = currentSubject.getCampus();
        int currentNivel = currentSubject.getNivel();

        return proposals.stream()
                .filter(p -> isValidProposal(p, currentSubject))
                .sorted((p1, p2) -> compareProposals(p1, p2, currentSubject, currentCampus, currentNivel))
                .collect(Collectors.toList());
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
    private void handleNoProposals() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (bloquesPendientes == profesor.getCurrentSubject().getHoras()) {
//                System.out.println("Profesor " + profesor.getNombre() + " no pudo obtener propuestas para " +
//                        profesor.getCurrentSubject().getNombre() + " después de " + MAX_RETRIES + " intentos");
                profesor.moveToNextSubject();
            } else {
//                System.out.println("Profesor " + profesor.getNombre() + " buscando nueva sala para bloques restantes de " +
//                        profesor.getCurrentSubject().getNombre());
                assignationData.setSalaAsignada(null);
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
//            System.out.println("Profesor " + profesor.getNombre() + ": Reintentando solicitud de propuestas. " +
//                    "Intento " + (retryCount + 1) + " de " + MAX_RETRIES);
            sendProposalRequests();
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
        }
    }

    private void handleProposalFailure() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (assignationData.hasSalaAsignada()) {
//                System.out.println("Profesor " + profesor.getNombre() + ": Buscando otra sala para los bloques restantes");
                assignationData.setSalaAsignada(null);
            } else {
//                System.out.println("Profesor " + profesor.getNombre() + " no pudo completar la asignación de " +
//                        profesor.getCurrentSubject().getNombre());
                profesor.moveToNextSubject();
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
            currentState = NegotiationState.COLLECTING_PROPOSALS;
            sendProposalRequests();
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
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

    private boolean tryAssignProposal(Propuesta propuesta) {
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
                // Update profesor's schedule
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
    }

    private void sendProposalRequests() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("sala");
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(profesor, template);

            if (result.length == 0) {
                System.out.println("No se encontraron salas disponibles");
                return;
            }

            Asignatura currentSubject = profesor.getCurrentSubject();
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            for (DFAgentDescription dfd : result) {
                cfp.addReceiver(dfd.getName());
            }

            String solicitudInfo = String.format("%s,%d,%s,%s,%d",
                    currentSubject.getNombre(),
                    currentSubject.getVacantes(),
                    assignationData.getSalaAsignada(),
                    assignationData.getUltimoDiaAsignado(),
                    assignationData.getUltimoBloqueAsignado());

            cfp.setContent(solicitudInfo);
            cfp.setConversationId("neg-" + profesor.getNombre() + "-" + bloquesPendientes);
            profesor.send(cfp);

//            System.out.println("Profesor " + profesor.getNombre() + " solicitando propuestas para " +
//                    currentSubject.getNombre() + " (bloques pendientes: " + bloquesPendientes +
//                    ", sala previa: " + assignationData.getSalaAsignada() + ")");

        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}