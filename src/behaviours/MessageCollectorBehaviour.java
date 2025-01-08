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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                try {
                    ClassroomAvailability sala = (ClassroomAvailability) reply.getContentObject();
                    if (sala == null) {
                        System.out.println("Sala es null");
                        return;
                    }

                    // Create proposals for all available blocks
                    for (Map.Entry<String, List<Integer>> entry : sala.getAvailableBlocks().entrySet()) {
                        String dia = entry.getKey();
                        List<Integer> bloques = entry.getValue();

                        for (Integer bloque : bloques) {
                            Propuesta propuesta = new Propuesta(
                                    dia,
                                    bloque,
                                    sala.getCodigo(),
                                    sala.getCapacidad(),
                                    sala.getSatisfactionScore()
                            );

                            //set el mensaje de la propuesta
                            propuesta.setMensaje(reply);
                            propuestas.offer(propuesta);
                        }
                    }

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