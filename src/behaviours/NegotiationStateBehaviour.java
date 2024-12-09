package behaviours;

import agentes.AgenteProfesor;
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
import objetos.Propuesta;
import objetos.AssignationData;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

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
            System.out.println("Profesor " + profesor.getNombre() + " iniciando negociación para " +
                    currentSubject.getNombre() + " (" + bloquesPendientes + " horas)");
            sendProposalRequests();
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
            currentState = NegotiationState.COLLECTING_PROPOSALS;
            proposalReceived = false;
        } else {
            System.out.println("Error: No hay asignatura actual para " + profesor.getNombre());
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

        if (evaluateProposals(currentProposals)) {
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

    private void handleNoProposals() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (bloquesPendientes == profesor.getCurrentSubject().getHoras()) {
                System.out.println("Profesor " + profesor.getNombre() + " no pudo obtener propuestas para " +
                        profesor.getCurrentSubject().getNombre() + " después de " + MAX_RETRIES + " intentos");
                profesor.moveToNextSubject();
            } else {
                System.out.println("Profesor " + profesor.getNombre() + " buscando nueva sala para bloques restantes de " +
                        profesor.getCurrentSubject().getNombre());
                assignationData.setSalaAsignada(null);
            }
            retryCount = 0;
            currentState = NegotiationState.SETUP;
        } else {
            System.out.println("Profesor " + profesor.getNombre() + ": Reintentando solicitud de propuestas. " +
                    "Intento " + (retryCount + 1) + " de " + MAX_RETRIES);
            sendProposalRequests();
            proposalTimeout = System.currentTimeMillis() + TIMEOUT_PROPUESTA;
        }
    }

    private void handleProposalFailure() {
        retryCount++;
        if (retryCount >= MAX_RETRIES) {
            if (assignationData.hasSalaAsignada()) {
                System.out.println("Profesor " + profesor.getNombre() + ": Buscando otra sala para los bloques restantes");
                assignationData.setSalaAsignada(null);
            } else {
                System.out.println("Profesor " + profesor.getNombre() + " no pudo completar la asignación de " +
                        profesor.getCurrentSubject().getNombre());
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

    private boolean evaluateProposals(List<Propuesta> proposals) {
        if (proposals.isEmpty()) return false;

        // Try to assign best proposal
        for (Propuesta propuesta : proposals) {
            if (tryAssignProposal(propuesta)) {
                return true;
            }
        }

        return false;
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

                System.out.printf("Profesor %s: Asignado bloque %d del día %s en sala %s para %s (quedan %d horas)%n",
                        profesor.getNombre(), bloque, dia, propuesta.getCodigo(),
                        currentSubject.getNombre(), bloquesPendientes);

                return true;
            }
            block(100); // Block for 100ms between checks
        }

        System.out.println("Timeout esperando confirmación de sala " + propuesta.getCodigo());
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

            System.out.println("Profesor " + profesor.getNombre() + " solicitando propuestas para " +
                    currentSubject.getNombre() + " (bloques pendientes: " + bloquesPendientes +
                    ", sala previa: " + assignationData.getSalaAsignada() + ")");

        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}