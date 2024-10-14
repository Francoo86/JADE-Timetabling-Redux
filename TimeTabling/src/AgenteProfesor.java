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
    private List<Asignatura> asignaturas;
    private int turno;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            JSONObject profesorJson = (JSONObject) args[0];
            nombre = (String) profesorJson.get("Nombre");
            rut = (String) profesorJson.get("RUT");
            asignaturas = parseAsignaturas((JSONObject) profesorJson.get("Asignatura"));
            turno = ((Long) args[1]).intValue();
        }

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
        addBehaviour(new RecibirPropuestaBehaviour());
    }

    private List<Asignatura> parseAsignaturas(JSONObject asignaturaJson) {
        List<Asignatura> asignaturas = new ArrayList<>();
        String nombre = (String) asignaturaJson.get("Nombre");
        int nivel = ((Long) asignaturaJson.get("Nivel")).intValue();
        int semestre = ((Long) asignaturaJson.get("Semestre")).intValue();
        int horas = ((Long) asignaturaJson.get("Horas")).intValue();
        int vacantes = ((Long) asignaturaJson.get("Vacantes")).intValue();
        asignaturas.add(new Asignatura(nombre, nivel, semestre, horas, vacantes));
        return asignaturas;
    }

    private class SolicitarHorarioBehaviour extends OneShotBehaviour {
        public void action() {
            // Enviar solicitud de horario a los agentes Sala
            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
            // Añadir destinatarios (agentes Sala)
            msg.setContent("Solicitud de horario para " + nombre);
            send(msg);
        }
    }

    private class RecibirPropuestaBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.PROPOSE);
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                // Procesar propuesta de horario
                String propuesta = msg.getContent();
                // Evaluar propuesta
                boolean aceptada = evaluarPropuesta(propuesta);

                ACLMessage reply = msg.createReply();
                if (aceptada) {
                    reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    reply.setContent("Propuesta aceptada");
                    // Informar horario resultante
                    informarHorarioResultante(propuesta);
                    // Indicar siguiente agente profesor
                    indicarSiguienteProfesor();
                } else {
                    reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    reply.setContent("Propuesta rechazada");
                }
                send(reply);
            } else {
                block();
            }
        }

        private boolean evaluarPropuesta(String propuesta) {
            // Lógica para evaluar la propuesta
            return true; // Por ahora, siempre aceptamos la primera propuesta
        }

        private void informarHorarioResultante(String horario) {
            // Lógica para informar el horario resultante
        }

        private void indicarSiguienteProfesor() {
            // Lógica para indicar el siguiente profesor
        }
    }
}