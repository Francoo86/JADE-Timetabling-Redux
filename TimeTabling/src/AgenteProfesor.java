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
    private List<Asignatura> asignaturas;  // Se crea una lista de asignaturas
    private Map<String, List<String>> horario;  // Se crea un mapa para almacenar el horario, donde las claves son los días de la semana y los valores son listas de bloques horarios

    protected void setup() {
        // Obtiene los argumentos pasados al agente y carga los datos del profesor desde un JSON.
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString = (String) args[0];
            loadFromJsonString(jsonString);
        }

        // Inicializa el horario del profesor con bloques vacíos para cada día de la semana.
        horario = new HashMap<>();
        String[] dias = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            horario.put(dia, new ArrayList<>(Arrays.asList("", "", "", "", "")));
        }

        System.out.println("Agente Profesor " + nombre + " iniciado. Asignaturas: " + asignaturas.size());

        // Registra el agente en el DF para que otros agentes puedan encontrarlo.
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
                String nombreAsignatura = (String) asignaturaJson.get("Nombre");
                int horas = getIntValue(asignaturaJson, "Horas", 0);
                int vacantes = getIntValue(asignaturaJson, "Vacantes", 0);
                asignaturas.add(new Asignatura(nombreAsignatura, 0, 0, horas, vacantes));
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private int getIntValue(JSONObject json, String key, int defaultValue) {
        Object value = json.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /*Búsqueda de Salas: El agente busca otros agentes de tipo "sala" en el Directorio de Facilitadores (DF).
    1.Solicitud de Horario: Envía una solicitud de horario a todas las salas encontradas para la asignatura actual.
    2.Recepción de Propuestas: Recibe propuestas de horario de las salas.
    3.Evaluación de Propuestas: Evalúa si la propuesta es aceptable verificando si el bloque horario propuesto está disponible en su horario.
    4.Aceptación o Rechazo:
    Si la propuesta es aceptable, acepta la propuesta, actualiza su horario y pasa a la siguiente asignatura.
    Si la propuesta no es aceptable, rechaza la propuesta y espera otra. */

    /*TODO: Solo se esta preocupando de su horario, no de las asignaturas. Asegurar que todas las sus asignaturas han sido asignadas.
    Verificar Disponibilidad de la Sala: Además de verificar si el bloque horario está libre en el horario del profesor, también se debe verificar si la sala está disponible para ese bloque horario.
    Asignar Diferentes Bloques Horarios: Asegurarse de que cada asignatura se asigne a un bloque horario diferente.*/

    private class SolicitarHorarioBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt;
        private int asignaturaActual = 0;
        private int intentos = 0;
        private static final int MAX_INTENTOS = 3;

        public void action() {
            switch (step) {
                case 0:
                    // Buscar salas y enviar solicitud
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("sala");
                    template.addServices(sd);
                    try {
                        DFAgentDescription[] result = DFService.search(myAgent, template);
                        if (result.length > 0) {
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
                    // Recibir y evaluar propuestas
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
                            intentos = 0;
                            step = asignaturaActual < asignaturas.size() ? 0 : 2;
                        } else {
                            // Rechazar la propuesta
                            ACLMessage reject = reply.createReply();
                            reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                            reject.setContent("Propuesta rechazada");
                            myAgent.send(reject);
                            intentos++;
                            if (intentos >= MAX_INTENTOS) {
                                System.out.println("No se pudo asignar horario para " + asignaturas.get(asignaturaActual).getNombre() + " después de " + MAX_INTENTOS + " intentos.");
                                asignaturaActual++;
                                intentos = 0;
                                step = asignaturaActual < asignaturas.size() ? 0 : 2;
                            } else {
                                step = 0;
                            }
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
            int bloque = Integer.parseInt(partes[1]);  // Divide la propuesta en día y bloque horario.
            return horario.get(dia).get(bloque - 1).isEmpty();  // Verifica si el bloque horario está disponible en el horario del profesor.
        }

        private void actualizarHorario(String propuesta, String nombreAsignatura) {
            String[] partes = propuesta.split(",");
            String dia = partes[0];
            int bloque = Integer.parseInt(partes[1]);  // Divide la propuesta en día y bloque horario.
            horario.get(dia).set(bloque - 1, nombreAsignatura);  // Actualiza el horario del profesor con la asignatura asignada.
        }
        
        // TODO: Actualizar a cuando todas las asignaturas han sido asignadas.
        public boolean done() {
            return step == 2;   // El comportamiento está completo cuando se han procesado todas las asignaturas.
        }
    }
}