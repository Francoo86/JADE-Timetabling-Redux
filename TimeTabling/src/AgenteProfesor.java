import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.util.*;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private Asignatura asignatura;
    private int turno;
    private List<String> horarioAsignado;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString;
            if (args[0] instanceof JSONObject) {
                jsonString = ((JSONObject) args[0]).toString();
            } else {
                jsonString = (String) args[0];
            }
            int turnoArg = Integer.parseInt((String) args[1]);
            loadFromJsonString(jsonString, turnoArg);
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

    private void loadFromJsonString(String jsonString, int turnoArg) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            
            nombre = (String) jsonObject.get("Nombre");
            rut = (String) jsonObject.get("RUT");
            
            JSONObject asignaturaJson = (JSONObject) jsonObject.get("Asignatura");
            asignatura = new Asignatura(
                (String) asignaturaJson.get("Nombre"),
                ((Number) asignaturaJson.get("Nivel")).intValue(),
                ((Number) asignaturaJson.get("Semestre")).intValue(),
                ((Number) asignaturaJson.get("Horas")).intValue(),
                ((Number) asignaturaJson.get("Vacantes")).intValue()
            );
            
            turno = turnoArg;
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private class SolicitarHorarioBehaviour extends OneShotBehaviour {  // Se crea un comportamiento de un solo paso
        public void action() {  // Se define la acción del comportamiento
            // Buscar agentes Sala
            DFAgentDescription template = new DFAgentDescription();  // Se crea una plantilla para buscar agentes
            ServiceDescription sd = new ServiceDescription();  // Se crea una descripción del servicio
            sd.setType("sala");  // Se define el tipo de servicio
            template.addServices(sd);  // Se agrega el servicio a la plantilla
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);  // Se buscan agentes que cumplan con la plantilla
                if (result.length > 0) {  // Si se encontraron agentes
                    // Enviar solicitud de horario a todas las salas
                    ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);  // Se crea un mensaje de solicitud
                    for (DFAgentDescription agenteS : result) {  // Para cada agente encontrado
                        msg.addReceiver(agenteS.getName());  // Se agrega el agente como receptor del mensaje
                    }
                    msg.setContent(asignatura.toString());  // Se agrega el contenido del mensaje
                    msg.setConversationId("solicitud-horario");  // Se define el ID de la conversación
                    myAgent.send(msg);  // Se envía el mensaje

                    // Agregar comportamiento para recibir propuestas
                    myAgent.addBehaviour(new RecibirPropuestaBehaviour());  // Se agrega el comportamiento para recibir propuestas
                }
            } catch (FIPAException fe) {  // Se captura una excepción si ocurre
                fe.printStackTrace();  // Se imprime la excepción
            }
        }
    }

    private class RecibirPropuestaBehaviour extends Behaviour {  // Se crea un comportamiento cíclico
        private int repliesCount = 0;  // Se inicializa el contador de respuestas
        private boolean done = false;  // Se inicializa la bandera de finalización

        public void action() {  // Se define la acción del comportamiento
            MessageTemplate mt = MessageTemplate.and(    // Se crea una plantilla de mensajes
                    MessageTemplate.MatchConversationId("solicitud-horario"),  // Se define el ID de la conversación
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE)  // Se define el tipo de mensaje
            );
            ACLMessage reply = myAgent.receive(mt);  // Se recibe un mensaje que cumpla con la plantilla
            if (reply != null) {   // Si se recibió un mensaje
                // Procesar propuesta de horario
                String propuesta = reply.getContent();  // Se obtiene el contenido del mensaje
                if (evaluarPropuesta(propuesta)) {  // Si la propuesta es válida
                    // Aceptar la propuesta
                    ACLMessage accept = reply.createReply();  // Se crea un mensaje de respuesta
                    accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);  // Se define el tipo de mensaje
                    accept.setContent("Propuesta aceptada");    // Se define el contenido del mensaje
                    myAgent.send(accept);
                    horarioAsignado = Arrays.asList(propuesta.split(","));  // Se asigna el horario
                    System.out.println("Profesor " + nombre + " ha aceptado el horario: " + horarioAsignado);  // Se imprime el horario asignado
                    done = true;  // Se finaliza el comportamiento
                } else {
                    // Rechazar la propuesta
                    ACLMessage reject = reply.createReply();  // Se crea un mensaje de respuesta
                    reject.setPerformative(ACLMessage.REJECT_PROPOSAL);  // Se define el tipo de mensaje
                    reject.setContent("Propuesta rechazada");  // Se define el contenido del mensaje
                    myAgent.send(reject);  // Se envía el mensaje
                }
                repliesCount++;  // Se incrementa el contador de respuestas
            } else {
                block();  // Se bloquea el comportamiento
            }
        }

        private boolean evaluarPropuesta(String propuesta) {  // Se define la evaluación de la propuesta
            // Aquí puedes implementar la lógica para evaluar la propuesta
            // Por ahora, aceptamos la primera propuesta válida
            return propuesta.split(",").length == asignatura.horas;  // Se acepta la propuesta si tiene la cantidad de horas de la asignatura
        }

        public boolean done() {
            return done || repliesCount == 5; // Asumimos que hay 5 días en la semana  
        }
    }
}