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

public class AgenteSala extends Agent {
    private String codigo;
    private int capacidad;
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

        System.out.println("Agente Sala " + codigo + " iniciado. Capacidad: " + capacidad);

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

    private void loadFromJsonString(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);

            codigo = (String) jsonObject.get("Codigo");
            capacidad = ((Number) jsonObject.get("Capacidad")).intValue();
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private class RecibirSolicitudBehaviour extends CyclicBehaviour {
        public void action() {  // Se define la acción del comportamiento
            MessageTemplate mt = MessageTemplate.and(  // Se crea una plantilla de mensaje
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),  // Se espera un mensaje de solicitud
                    MessageTemplate.MatchConversationId("solicitud-horario")  // Se espera un mensaje con la conversación "solicitud-horario"
            );
            ACLMessage msg = myAgent.receive(mt);  // Se recibe un mensaje que cumpla con la plantilla
            if (msg != null) {
                // Procesar solicitud de horario
                String solicitud = msg.getContent();    // Se obtiene el contenido del mensaje
                Asignatura asignatura = Asignatura.fromString(solicitud);  // Se convierte el contenido en una asignatura

                // Generar propuesta de horario
                List<String> propuesta = generarPropuesta(asignatura);  // Se genera una propuesta de horario
  
                if (!propuesta.isEmpty()) {  // Si la propuesta no está vacía
                    ACLMessage reply = msg.createReply();  // Se crea una respuesta al mensaje recibido
                    reply.setPerformative(ACLMessage.PROPOSE);  // Se define la acción del mensaje como propuesta
                    reply.setContent(String.join(",", propuesta));  // Se agrega la propuesta al contenido del mensaje
                    myAgent.send(reply);  // Se envía la respuesta al mensaje

                    // Esperar respuesta del profesor
                    myAgent.addBehaviour(new EsperarRespuestaBehaviour(myAgent, msg.getSender(), propuesta));  // Se agrega un comportamiento para esperar la respuesta del profesor
                }
            } else {
                block();  // Se bloquea el comportamiento si no se reciben mensajes
            }
        }

        private List<String> generarPropuesta(Asignatura asignatura) {
            List<String> propuesta = new ArrayList<>();  // Se crea una lista vacía para la propuesta
            int horasAsignadas = 0;  // Se inicializa el contador de horas asignadas

            for (Map.Entry<String, List<String>> entry : horario.entrySet()) {  // Para cada día de la semana
                String dia = entry.getKey();  // Se obtiene el día
                List<String> bloques = entry.getValue();  // Se obtienen los bloques horarios

                for (int i = 0; i < bloques.size(); i++) {
                    if (bloques.get(i).isEmpty() && horasAsignadas < asignatura.horas) {  // Si el bloque está vacío y no se han asignado todas las horas
                        propuesta.add(dia + "-" + (i + 1));  // Se agrega el bloque a la propuesta
                        horasAsignadas++;  // Se incrementa el contador de horas asignadas
                    }

                    if (horasAsignadas == asignatura.horas) {  // Si se han asignado todas las horas
                        return propuesta;   // Se retorna la propuesta
                    }
                }
            }

            // Si no se pudo asignar todas las horas, retornar una lista vacía
            return new ArrayList<>();
        }
    }

    private class EsperarRespuestaBehaviour extends Behaviour {
        private boolean received = false;  // Se inicializa la bandera de recepción
        private MessageTemplate mt;  // Se inicializa la plantilla de mensaje
        private List<String> propuesta;  // Se inicializa la propuesta

        public EsperarRespuestaBehaviour(Agent a, jade.core.AID sender, List<String> propuesta) {
            super(a);  // Se llama al constructor de la clase padre
            this.mt = MessageTemplate.and(  // Se crea una plantilla de mensaje
                    MessageTemplate.MatchSender(sender),  // Se espera un mensaje del profesor
                    MessageTemplate.or(  // Se espera un mensaje de aceptación o rechazo
                            MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),  // Se espera un mensaje de aceptación
                            MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)  // Se espera un mensaje de rechazo
                    )
            );
            this.propuesta = propuesta;  // Se asigna la propuesta
        }

        public void action() {
            ACLMessage msg = myAgent.receive(mt);  // Se recibe un mensaje que cumpla con la plantilla
            if (msg != null) {  // Si se recibe un mensaje
                if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {  // Si el mensaje es de aceptación
                    // Actualizar el horario de la sala 
                    for (String bloque : propuesta) {       // Para cada bloque de la propuesta
                        String[] partes = bloque.split("-");   // Se separan las partes del bloque
                        String dia = partes[0];  // Se obtiene el día  
                        int hora = Integer.parseInt(partes[1]) - 1;  // Se obtiene la hora
                        horario.get(dia).set(hora, msg.getSender().getLocalName());  // Se actualiza el horario
                    }
                    System.out.println("Sala " + codigo + " ha actualizado su horario: " + horario);   // Se imprime el horario actualizado
                }
                received = true;  // Se marca la bandera de recepción
            } else {
                block();  // Se bloquea el comportamiento si no se recibe un mensaje
            }
        }

        public boolean done() {  // Se define si el comportamiento ha finalizado
            return received;  // Se retorna la bandera de recepción
        }
    }
}


