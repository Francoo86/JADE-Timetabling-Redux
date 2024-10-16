import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.util.*;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private List<Asignatura> asignaturas;
    private Map<String, List<String>> horario;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString = (String) args[0];
            loadFromJsonString(jsonString);
        }

        horario = new HashMap<>();
        String[] dias = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            horario.put(dia, new ArrayList<>(Arrays.asList("", "", "", "", "")));
        }

        System.out.println("Agente Profesor " + nombre + " iniciado. Asignaturas: " + asignaturas.size());

        // Registrar el agente en el DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("profesor");
        sd.setName(getName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Agregar comportamientos
        addBehaviour(new SolicitarHorarioBehaviour());
    }

    private void loadFromJsonString(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);

            nombre = (String) jsonObject.get("Nombre");
            rut = (String) jsonObject.get("RUT");

            asignaturas = new ArrayList<>();
            JSONArray asignaturasJson = (JSONArray) jsonObject.get("Asignaturas");
            for (Object asignaturaObj : asignaturasJson) {
                JSONObject asignaturaJson = (JSONObject) asignaturaObj;
                asignaturas.add(new Asignatura(
                        (String) asignaturaJson.get("Nombre"),
                        ((Number) asignaturaJson.get("Nivel")).intValue(),
                        ((Number) asignaturaJson.get("Semestre")).intValue(),
                        ((Number) asignaturaJson.get("Horas")).intValue(),
                        ((Number) asignaturaJson.get("Vacantes")).intValue()
                ));
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private class SolicitarHorarioBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt;
        private int asignaturaActual = 0;

        public void action() {
            switch (step) {
                case 0:
                    // Buscar agentes Sala
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("sala");
                    template.addServices(sd);
                    try {
                        DFAgentDescription[] result = DFService.search(myAgent, template);
                        if (result.length > 0) {
                            // Enviar solicitud de horario a todas las salas
                            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
                            for (DFAgentDescription agenteS : result) {
                                msg.addReceiver(agenteS.getName());
                            }
                            msg.setContent(asignaturas.get(asignaturaActual).toString());
                            msg.setConversationId("solicitud-horario");
                            myAgent.send(msg);
                            step = 1;
                            mt = MessageTemplate.and(
                                    MessageTemplate.MatchConversationId("solicitud-horario"),
                                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE)
                            );
                        }
                    } catch (FIPAException fe) {
                        fe.printStackTrace();
                    }
                    break;
                case 1:
                    // Recibir propuestas de horario
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        String propuesta = reply.getContent();
                        if (evaluarPropuesta(propuesta)) {
                            // Aceptar la propuesta
                            ACLMessage accept = reply.createReply();
                            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                            accept.setContent("Propuesta aceptada");
                            myAgent.send(accept);
                            actualizarHorario(propuesta, asignaturas.get(asignaturaActual).getNombre());
                            System.out.println("Profesor " + nombre + " ha aceptado el horario para " + asignaturas.get(asignaturaActual).getNombre() + ": " + propuesta);
                            asignaturaActual++;
                            step = asignaturaActual < asignaturas.size() ? 0 : 2;
                        } else {
                            // Rechazar la propuesta
                            ACLMessage reject = reply.createReply();
                            reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                            reject.setContent("Propuesta rechazada");
                            myAgent.send(reject);
                        }
                    } else {
                        block();
                    }
                    break;
            }
        }

        private boolean evaluarPropuesta(String propuesta) {
            String[] partes = propuesta.split(",");
            String dia = partes[0];
            int bloque = Integer.parseInt(partes[1]);
            return horario.get(dia).get(bloque - 1).isEmpty();
        }

        private void actualizarHorario(String propuesta, String nombreAsignatura) {
            String[] partes = propuesta.split(",");
            String dia = partes[0];
            int bloque = Integer.parseInt(partes[1]);
            horario.get(dia).set(bloque - 1, nombreAsignatura);
        }

        public boolean done() {
            return step == 2;
        }
    }
}