package behaviours;

import agentes.AgenteProfesor;
import constants.Messages;
import constants.enums.Day;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import objetos.Propuesta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

// Message handler for collecting proposals
public class MessageCollectorBehaviour extends CyclicBehaviour {
    private final AgenteProfesor profesor;
    private final ConcurrentLinkedQueue<Propuesta> propuestas;
    private final NegotiationStateBehaviour stateBehaviour;

    public MessageCollectorBehaviour(AgenteProfesor profesor,
                                     ConcurrentLinkedQueue<Propuesta> propuestas,
                                     NegotiationStateBehaviour stateBehaviour) {
        super(profesor);
        this.profesor = profesor;
        this.propuestas = propuestas;
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
                Propuesta propuesta = Propuesta.parse(reply.getContent());
                propuesta.setMensaje(reply);
                propuestas.offer(propuesta);
                stateBehaviour.notifyProposalReceived();

                //FIXME: Revisar porque tiene tantas propuestas

//                System.out.println("Propuesta recibida para profesor " + profesor.getNombre() +
//                        " de sala " + propuesta.getCodigo() + ", total propuestas: " + propuestas.size());
            }
        } else {
            block();
        }
    }
}