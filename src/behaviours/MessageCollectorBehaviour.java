package behaviours;

import agentes.AgenteProfesor;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import objetos.ClassroomAvailability;
import objetos.helper.BatchProposal;
import performance.SimpleRTT;

import java.util.Queue;

// Message handler for collecting proposals
public class MessageCollectorBehaviour extends CyclicBehaviour {
    private final AgenteProfesor profesor;
    private final Queue<BatchProposal> batchProposals;
    private final NegotiationStateBehaviour stateBehaviour;
    private final SimpleRTT rttTracker;

    public MessageCollectorBehaviour(AgenteProfesor profesor,
                                     Queue<BatchProposal> batchProposals,
                                     NegotiationStateBehaviour stateBehaviour) {
        super(profesor);
        this.profesor = profesor;
        this.batchProposals = batchProposals;
        this.stateBehaviour = stateBehaviour;

        this.rttTracker = SimpleRTT.getInstance();
    }

    @Override
    public void action() {
        MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
        );

        ACLMessage reply = myAgent.receive(mt);

        if (reply != null) {
            String conversationId = reply.getConversationId();

            if (reply.getPerformative() == ACLMessage.PROPOSE) {
                try {
                    // Record the message receipt for RTT calculation
                    rttTracker.messageReceived(conversationId, reply);
                    profesor.getPerformanceMonitor().recordMessageReceived(reply, "PROPOSE");

                    ClassroomAvailability sala = (ClassroomAvailability) reply.getContentObject();
                    if (sala == null) {
                        System.out.println("Null classroom availability received");
                        return;
                    }

                    BatchProposal batchProposal = new BatchProposal(sala, reply);
                    batchProposals.offer(batchProposal);
                    stateBehaviour.notifyProposalReceived();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                rttTracker.messageReceived(conversationId, reply);
                profesor.getPerformanceMonitor().recordMessageReceived(reply, "REFUSE");
            }
        } else {
            block(50);
        }
    }
}