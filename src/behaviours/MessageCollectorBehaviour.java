package behaviours;

import agentes.AgenteProfesor;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import objetos.ClassroomAvailability;
import objetos.helper.BatchProposal;
import performance.RTTLogger;

import java.util.Queue;

// Message handler for collecting proposals
public class MessageCollectorBehaviour extends CyclicBehaviour {
    private final AgenteProfesor profesor;
    private final Queue<BatchProposal> batchProposals;
    private final NegotiationStateBehaviour stateBehaviour;
    private final RTTLogger rttLogger;

    public MessageCollectorBehaviour(AgenteProfesor profesor,
                                     Queue<BatchProposal> batchProposals,
                                     NegotiationStateBehaviour stateBehaviour) {
        super(profesor);
        this.profesor = profesor;
        this.batchProposals = batchProposals;
        this.stateBehaviour = stateBehaviour;

        rttLogger = RTTLogger.getInstance();
    }

    private void logRequest(ACLMessage reply, boolean success) {
        rttLogger.endRequest(
                myAgent.getLocalName(),
                reply.getConversationId(),
                reply.getPerformative(),
                reply.getByteSequenceContent().length,
                success,
                null,
                "classroom-availability"
        );
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
                    logRequest(reply, true);

                    ClassroomAvailability sala = (ClassroomAvailability) reply.getContentObject();
                    if (sala == null) {
                        System.out.println("Null classroom availability received");
                        return;
                    }

                    BatchProposal batchProposal = new BatchProposal(sala, reply);
                    batchProposals.offer(batchProposal);
                    stateBehaviour.incrementResponseCount();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                logRequest(reply, false);
                stateBehaviour.incrementResponseCount();
            }
        } else {
            block(50);
        }
    }
}