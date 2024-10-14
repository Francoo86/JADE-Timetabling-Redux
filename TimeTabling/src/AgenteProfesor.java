import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.*;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private Asignatura asignatura;
    private int turno;
    private List<String> horarioAsignado;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            JSONObject profesorJson = (JSONObject) args[0];
            nombre = (String) profesorJson.get("Nombre");
            rut = (String) profesorJson.get("RUT");

            JSONObject asignaturaJson = (JSONObject) profesorJson.get("Asignatura");
            asignatura = new Asignatura(
                    (String) asignaturaJson.get("Nombre"),
                    ((Number) asignaturaJson.get("Nivel")).intValue(),
                    ((Number) asignaturaJson.get("Semestre")).intValue(),
                    ((Number) asignaturaJson.get("Horas")).intValue(),
                    ((Number) asignaturaJson.get("Vacantes")).intValue()
            );
            turno = ((Number) args[1]).intValue();
        }
        System.out.println("Agente Profesor " + nombre + " iniciado. Turno: " + turno);

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

    private class SolicitarHorarioBehaviour extends OneShotBehaviour {
        public void action() {
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
                    msg.setContent(asignatura.toString());
                    msg.setConversationId("solicitud-horario");
                    myAgent.send(msg);

                    // Agregar comportamiento para recibir propuestas
                    myAgent.addBehaviour(new RecibirPropuestaBehaviour());
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }

    private class RecibirPropuestaBehaviour extends Behaviour {
        private int repliesCount = 0;
        private boolean done = false;

        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchConversationId("solicitud-horario"),
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE)
            );
            ACLMessage reply = myAgent.receive(mt);
            if (reply != null) {
                // Procesar propuesta de horario
                String propuesta = reply.getContent();
                if (evaluarPropuesta(propuesta)) {
                    // Aceptar la propuesta
                    ACLMessage accept = reply.createReply();
                    accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    accept.setContent("Propuesta aceptada");
                    myAgent.send(accept);
                    horarioAsignado = Arrays.asList(propuesta.split(","));
                    System.out.println("Profesor " + nombre + " ha aceptado el horario: " + horarioAsignado);
                    done = true;
                } else {
                    // Rechazar la propuesta
                    ACLMessage reject = reply.createReply();
                    reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    reject.setContent("Propuesta rechazada");
                    myAgent.send(reject);
                }
                repliesCount++;
            } else {
                block();
            }
        }

        private boolean evaluarPropuesta(String propuesta) {
            // Aquí puedes implementar la lógica para evaluar la propuesta
            // Por ahora, aceptamos la primera propuesta válida
            return propuesta.split(",").length == asignatura.horas;
        }

        public boolean done() {
            return done || repliesCount == 5; // Asumimos que hay 5 días en la semana
        }
    }
}