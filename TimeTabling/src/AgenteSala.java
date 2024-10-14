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

public class AgenteSala extends Agent {
    private String codigo;
    private int capacidad;
    private Map<String, List<String>> horario; // Mapa de día -> lista de bloques

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            JSONObject salaJson = (JSONObject) args[0];
            codigo = (String) salaJson.get("Codigo");
            capacidad = ((Long) salaJson.get("Capacidad")).intValue();
        }

        horario = new HashMap<>();
        // Inicializar horario vacío
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
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                // Procesar solicitud de horario
                String solicitud = msg.getContent();
                // Generar propuesta de horario
                String propuesta = generarPropuesta(solicitud);

                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(propuesta);
                send(reply);
            } else {
                block();
            }
        }

        private String generarPropuesta(String solicitud) {
            // Lógica para generar una propuesta de horario
            // Por ahora, retornamos una propuesta simple
            return "Propuesta de horario para " + solicitud;
        }
    }
}


