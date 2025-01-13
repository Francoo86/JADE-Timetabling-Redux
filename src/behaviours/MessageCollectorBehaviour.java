package behaviours;

import agentes.AgenteProfesor;
import constants.Messages;
import constants.enums.Day;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import objetos.Asignatura;
import objetos.ClassroomAvailability;
import objetos.Propuesta;
import objetos.helper.BatchProposal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// Message handler for collecting proposals
public class MessageCollectorBehaviour extends CyclicBehaviour {
    private final AgenteProfesor profesor;
    private final Queue<BatchProposal> batchProposals;
    private final NegotiationStateBehaviour stateBehaviour;

    public MessageCollectorBehaviour(AgenteProfesor profesor,
                                     Queue<BatchProposal> batchProposals,
                                     NegotiationStateBehaviour stateBehaviour) {
        super(profesor);
        this.profesor = profesor;
        this.batchProposals = batchProposals;
        this.stateBehaviour = stateBehaviour;
    }

    @Override
    public void action() {
        MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
        );

        ACLMessage reply = myAgent.receive(mt);

        if (reply != null) {
            if (reply.getPerformative() == ACLMessage.PROPOSE) {
                try {
                    ClassroomAvailability sala = (ClassroomAvailability) reply.getContentObject();
                    if (sala == null) {
                        System.out.println("Null classroom availability received");
                        return;
                    }

                    // Create single batch proposal instead of multiple individual ones
                    BatchProposal batchProposal = new BatchProposal(sala, reply);
                    batchProposals.offer(batchProposal);
                    stateBehaviour.notifyProposalReceived();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            block();
        }
    }
}