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
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private List<Asignatura> asignaturas;
    private JSONObject horarioJSON;
    private int solicitudesProcesadas = 0;

    protected void setup() {
        // Obtiene los argumentos pasados al agente y carga los datos del profesor desde un JSON.
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString = (String) args[0];
            loadFromJsonString(jsonString);
        }

        // Inicializar horarioJSON con un array vacio de asignaturas.
        horarioJSON = new JSONObject();
        horarioJSON.put("Asignaturas", new JSONArray());

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

    // Cargar los datos del profesor desde un String en formato JSON.
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

    /* 
    Búsqueda de Salas: El agente busca otros agentes de tipo "sala" en el Directorio de Facilitadores (DF).
    1. Solicitud de Horario: Envía una solicitud de horario a todas las salas encontradas para la asignatura actual.
    2. Recepción de Propuestas: Recibe propuestas de horario de las salas.
    3. Evaluación de Propuestas: Evalúa si la propuesta es aceptable verificando:
        a. Si el bloque horario propuesto está disponible en su horario.
        b. Si la sala está disponible para ese bloque horario.
    4. Aceptación o Rechazo:
        a. Si la propuesta es aceptable, acepta la propuesta, actualiza su horario y pasa a la siguiente asignatura.
        b. Si la propuesta no es aceptable, rechaza la propuesta y espera otra.
    5. Verificación de Asignación Completa: Asegura que todas las asignaturas han sido asignadas.
    6. Asignación de Diferentes Bloques Horarios: Asegura que cada asignatura se asigne a un bloque horario diferente. 
    */

    private class SolicitarHorarioBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt;
        private int asignaturaActual = 0;
        private int intentos = 0;
        private static final int MAX_INTENTOS = 10;     // Número máximo de intentos para asignar un horario
        //TODO: Buena o mala práctica?

        public void action() {
            switch (step) {
                case 0:
                    // Busca agentes de tipo "sala" en el DF y envía una solicitud de horario.
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("sala");
                    template.addServices(sd);
                    try {
                        // Buscar agentes de tipo "sala" en el DF.
                        DFAgentDescription[] result = DFService.search(myAgent, template);
                        // Si se encontraron agentes de tipo "sala".
                        if (result.length > 0) {
                            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);    // Crear mensaje de solicitud de horario.
                            for (DFAgentDescription agenteS : result) {   // Agregar a los agentes de tipo "sala" como destinatarios.
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
                    // Recibe propuestas de horario de las salas.
                    ACLMessage reply = myAgent.receive(mt); // Recibir mensaje de propuesta de horario.
                    if (reply != null) {
                        solicitudesProcesadas++;
                        String propuesta = reply.getContent();  // Obtener la propuesta de horario.
                        if (evaluarPropuesta(propuesta)) {  // Evaluar si la propuesta es aceptable, caso true.
                            ACLMessage accept = reply.createReply();    // Crear mensaje de aceptación de propuesta.
                            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL); // Establecer tipo de mensaje.
                            accept.setContent("Propuesta aceptada");
                            myAgent.send(accept);   // Enviar mensaje de aceptación.
                            actualizarHorario(propuesta, asignaturas.get(asignaturaActual));    // Actualizar el horario del profesor.
                            System.out.println("Profesor " + nombre + " ha aceptado el horario para " + asignaturas.get(asignaturaActual).getNombre() + ": " + propuesta);
                            asignaturaActual++;
                            intentos = 0;
                            step = asignaturaActual < asignaturas.size() ? 0 : 2;   // Si hay más asignaturas, volver al paso 0, si no, ir al paso 2.
                        } else {    // Si la propuesta no es aceptable.
                            ACLMessage reject = reply.createReply();    // Crear mensaje de rechazo de propuesta.
                            reject.setPerformative(ACLMessage.REJECT_PROPOSAL);     // Establecer tipo de mensaje.
                            reject.setContent("Propuesta rechazada");       // Establecer contenido del mensaje.
                            myAgent.send(reject);       // Enviar mensaje de rechazo.
                            intentos++;
                            if (intentos >= MAX_INTENTOS) {     // Si se supera el número máximo de intentos.
                                System.out.println("No se pudo asignar horario para " + asignaturas.get(asignaturaActual).getNombre() + " después de " + MAX_INTENTOS + " intentos.");
                                asignaturaActual++;     // Pasar a la siguiente asignatura.
                                intentos = 0;
                                    step = asignaturaActual < asignaturas.size() ? 0 : 2;   // Si hay más asignaturas, volver al paso 0, si no, ir al paso 2.
                                } else {
                                step = 0;
                            }
                        }
                    } else {
                        block();
                    }
                    break;
                case 2:
                    // Termina el comportamiento y guarda el horario en un archivo JSON.
                    ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(nombre, horarioJSON, solicitudesProcesadas);
                    myAgent.doDelete();
                    break;

            }
        }

        // Evalúa si la propuesta de horario es aceptable.
        private boolean evaluarPropuesta(String propuesta) {
            // Recibir propuesta de horario como "dia,bloque,sala"
            String[] partes = propuesta.split(",");
            String dia = partes[0];
            int bloque = Integer.parseInt(partes[1]);

            // Verificar si el bloque horario está disponible en el horario del profesor.
            JSONArray asignaturasArray = (JSONArray) horarioJSON.get("Asignaturas");  // Obtener el array de asignaturas del horario.
            for (Object obj : asignaturasArray) {  // Iterar sobre las asignaturas del profesor.
                JSONObject asignaturaJSON = (JSONObject) obj;
                String asignaturaDia = (String) asignaturaJSON.get("Dia");
                int asignaturaBloque;
                Object bloqueObj = asignaturaJSON.get("Bloque");
                if (bloqueObj instanceof Long) {
                    asignaturaBloque = ((Long) bloqueObj).intValue();
                } else if (bloqueObj instanceof Integer) {
                    asignaturaBloque = (Integer) bloqueObj;
                } else {
                    // Handle unexpected type or log an error.
                    continue;
                }

                if (asignaturaDia.equals(dia) && asignaturaBloque == bloque) {
                    return false; // El bloque ya está ocupado.
                }
            }
            return true; // El bloque está disponible.
        }

        // Actualiza el horario del profesor con la nueva asignatura.
        private void actualizarHorario(String propuesta, Asignatura asignatura) {
            // Recibir propuesta de horario como "dia,bloque,sala"
            String[] partes = propuesta.split(",");
            String dia = partes[0];
            int bloque = Integer.parseInt(partes[1]);
            String sala = partes[2];

            // Crear un objeto JSON para representar la asignatura y su horario.
            JSONObject asignaturaJSON = new JSONObject();
            asignaturaJSON.put("Nombre", asignatura.getNombre());
            asignaturaJSON.put("Sala", sala);
            asignaturaJSON.put("Bloque", bloque);
            asignaturaJSON.put("Dia", dia);

            JSONArray asignaturasArray = (JSONArray) horarioJSON.get("Asignaturas");  // Obtener el array de asignaturas del horario.
            asignaturasArray.add(asignaturaJSON);  // Agregar la nueva asignatura al array.
        }

        // Si todas las asignaturas han sido asignadas, guarda el horario del profesor y elimina el agente.
        public boolean done() {
            if (asignaturaActual >= asignaturas.size()) {   
                ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(nombre, horarioJSON, solicitudesProcesadas);  // 
                myAgent.doDelete();
                return true;
            }
            return false;  // Si no se han asignado todas las asignaturas, el comportamiento debe continuar.
        }
    }
}