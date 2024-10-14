import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.json.simple.JSONObject;
import java.util.*;

public class AgenteSala extends Agent {
    private String codigo;
    private int capacidad;
    private Map<String, List<String>> horario;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            JSONObject salaJson = (JSONObject) args[0];
            codigo = (String) salaJson.get("Codigo");
            capacidad = ((Number) salaJson.get("Capacidad")).intValue();
        }

        horario = new HashMap<>();
        String[] dias = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            horario.put(dia, new ArrayList<>(Arrays.asList("", "", "", "", "")));
        }

        // Registrar el agente en el DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sala");
        sd.setName(getName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Agregar comportamientos
        addBehaviour(new RecibirSolicitudBehaviour());
    }

    private class RecibirSolicitudBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchConversationId("solicitud-horario")
            );
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                // Procesar solicitud de horario
                String solicitud = msg.getContent();
                Asignatura asignatura = Asignatura.fromString(solicitud);

                // Generar propuesta de horario
                List<String> propuesta = generarPropuesta(asignatura);

                if (!propuesta.isEmpty()) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(String.join(",", propuesta));
                    myAgent.send(reply);

                    // Esperar respuesta del profesor
                    myAgent.addBehaviour(new EsperarRespuestaBehaviour(myAgent, msg.getSender(), propuesta));
                }
            } else {
                block();
            }
        }

        private List<String> generarPropuesta(Asignatura asignatura) {
            List<String> propuesta = new ArrayList<>();
            int horasAsignadas = 0;

            for (Map.Entry<String, List<String>> entry : horario.entrySet()) {
                String dia = entry.getKey();
                List<String> bloques = entry.getValue();

                for (int i = 0; i < bloques.size(); i++) {
                    if (bloques.get(i).isEmpty() && horasAsignadas < asignatura.horas) {
                        propuesta.add(dia + "-" + (i + 1));
                        horasAsignadas++;
                    }

                    if (horasAsignadas == asignatura.horas) {
                        return propuesta;
                    }
                }
            }

            // Si no se pudo asignar todas las horas, retornar una lista vacía
            return new ArrayList<>();
        }
    }

    private class EsperarRespuestaBehaviour extends Behaviour {
        private boolean received = false;
        private MessageTemplate mt;
        private List<String> propuesta;

        public EsperarRespuestaBehaviour(Agent a, jade.core.AID sender, List<String> propuesta) {
            super(a);
            this.mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(sender),
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                            MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
                    )
            );
            this.propuesta = propuesta;
        }

        public void action() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    // Actualizar el horario de la sala
                    for (String bloque : propuesta) {
                        String[] partes = bloque.split("-");
                        String dia = partes[0];
                        int hora = Integer.parseInt(partes[1]) - 1;
                        horario.get(dia).set(hora, msg.getSender().getLocalName());
                    }
                    System.out.println("Sala " + codigo + " ha actualizado su horario: " + horario);
                }
                received = true;
            } else {
                block();
            }
        }

        public boolean done() {
            return received;
        }
    }
}


