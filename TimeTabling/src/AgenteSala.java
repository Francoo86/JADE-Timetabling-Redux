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

public class AgenteSala extends Agent implements SalaInterface {
    private String codigo;
    private int capacidad;
    private Map<String, List<String>> horario;
    private int solicitudesProcesadas = 0;
    private int totalSolicitudes;

    // Inicialización del agente
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString = (String) args[0];
            loadFromJsonString(jsonString);
        }

        // Inicializa el horario con días de la semana y bloques vacíos
        horario = new HashMap<>();
        String[] dias = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            horario.put(dia, new ArrayList<>(Arrays.asList("", "", "", "", "")));
        }

        System.out.println("Agente Sala " + codigo + " iniciado. Capacidad: " + capacidad);

        // Registra el agente en el Directorio de Facilitadores (DF).
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

        // Habilitar O2A
        setEnabledO2ACommunication(true, 0);
        // Registrar la interfaz O2A
        registerO2AInterface(SalaInterface.class, this);

        // Agregar comportamientos
        addBehaviour(new RecibirSolicitudBehaviour());
    }

    // Carga los datos de la sala desde una cadena JSON
    private void loadFromJsonString(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);  // Parsea la cadena JSON.

            codigo = (String) jsonObject.get("Codigo");                 // Obtiene el código.
            capacidad = ((Number) jsonObject.get("Capacidad")).intValue();      // Obtiene la capacidad.
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    // Establece el total de solicitudes que se procesarán en la sala.
    @Override
    public void setTotalSolicitudes(int total) {
        this.totalSolicitudes = total;
        System.out.println("Total de solicitudes establecido para sala " + codigo + ": " + total);
        addBehaviour(new VerificarFinalizacionBehaviour(this, 1000));   // Agrega un comportamiento para verificar la finalización, T = 1 seg.
    }

    /* 
    1. Recepción de Solicitudes: Continuamente recibe solicitudes de asignación de horarios.
    2. Generación de Propuestas: Genera propuestas de horarios basadas en la disponibilidad y la capacidad de la sala.
    3. Esperar Respuesta: Espera la aceptación o rechazo de la propuesta.
    4. Actualización de Horario: Si la propuesta es aceptada, actualiza el horario de la sala.
    5. Verificación de Finalización: Periódicamente verifica si se han procesado todas las solicitudes y, si es así, agrega el horario final a un objeto JSON y elimina el agente.
    */

    private class RecibirSolicitudBehaviour extends CyclicBehaviour {
        // Filtra los mensajes de solicitud de horario.
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchConversationId("solicitud-horario")
            );
            ACLMessage msg = myAgent.receive(mt);  // Recibe el mensaje.
            if (msg != null) { // Si el mensaje no es nulo, procesa la solicitud.
                solicitudesProcesadas++;

                String solicitud = msg.getContent();  // Obtiene el contenido del mensaje.
                Asignatura asignatura = Asignatura.fromString(solicitud);

                String propuesta = generarPropuesta(asignatura);  // Genera una propuesta de horario.

                if (!propuesta.isEmpty()) {     // Si la propuesta es valida, se envía.
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(propuesta);
                    myAgent.send(reply);

                    // Esperar la respuesta de aceptación o rechazo de la propuesta.
                    addBehaviour(new EsperarRespuestaBehaviour(myAgent, msg.getSender(), propuesta, asignatura.getNombre()));
                }
            } else {
                block();
            }
        }
    }

    // Recorre el mapa horario para encontrar un bloque vacío. Si encuentra un bloque vacío, retorna el día y el número del bloque. Si no retorna una cadena vacía.
    private String generarPropuesta(Asignatura asignatura) {    // Si la asignatura tiene más vacantes que la capacidad de la sala, no se hace propuesta.
        if (asignatura.getVacantes() > this.capacidad) {
            return "";
        }
        for (Map.Entry<String, List<String>> entry : horario.entrySet()) {  // Recorre el horario de la sala.
            // Obtiene el día y los bloques de la sala.
            String dia = entry.getKey();
            List<String> bloques = entry.getValue();

            for (int i = 0; i < bloques.size(); i++) {  // Recorre los bloques del día.
                if (bloques.get(i).isEmpty()) {     // Si el bloque está vacío, retorna la propuesta.
                    return dia + "," + (i + 1) + "," + codigo;      
                }
            }
        }
        return "";
    }

    // Comportamiento para esperar la respuesta de aceptación o rechazo de la propuesta.
    private class EsperarRespuestaBehaviour extends Behaviour {
        private boolean received = false;
        private MessageTemplate mt;
        private String propuesta;
        private String nombreAsignatura;

        // Constructor recibe el agente, el emisor del mensaje, la propuesta y el nombre de la asignatura.
        public EsperarRespuestaBehaviour(Agent a, jade.core.AID sender, String propuesta, String nombreAsignatura) {
            super(a);
            // Establece el template del mensaje para aceptar o rechazar la propuesta.
            this.mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(sender),
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                            MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
                    )
            );
            this.propuesta = propuesta;
            this.nombreAsignatura = nombreAsignatura;
        }
        
        // Método que se ejecuta al recibir un mensaje.
        public void action() {
            ACLMessage msg = myAgent.receive(mt);   // Recibe el mensaje.
            if (msg != null) {
                // Si el mensaje es de aceptación, actualiza el horario.
                if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {  
                    String[] partes = propuesta.split(",");
                    String dia = partes[0];
                    int bloque = Integer.parseInt(partes[1]);
                    horario.get(dia).set(bloque - 1, nombreAsignatura);
                    System.out.println("Sala " + codigo + " ha actualizado su horario: " + horario);
                }
                received = true;   // Marca el comportamiento como finalizado.
            } else {
                block();  // Bloquea el comportamiento hasta recibir un mensaje.
            }
        }
        // Devuelve true si se ha recibido una respuesta,
        public boolean done() {
            return received;
        }
    }

    private class VerificarFinalizacionBehaviour extends TickerBehaviour {
        private int ultimasSolicitudesProcesadas = 0;   // Número de solicitudes procesadas en la última verificación.
        private int contadorSinCambios = 0;        // Contador de verificaciones sin cambios.
        private static final int MAX_SIN_CAMBIOS = 10;  // Número máximo de verificaciones sin cambios.
        
        public VerificarFinalizacionBehaviour(Agent a, long period) {     
            super(a, period);   // Establece el periodo entre cada verificación.
        }

        protected void onTick() {
            System.out.println("Verificando finalización para sala " + codigo + ". Procesadas: " + solicitudesProcesadas + " de " + totalSolicitudes);

            // Si se han procesado todas las solicitudes o si no ha habido cambios en el último ciclo, se finaliza el agente.
            if (solicitudesProcesadas >= totalSolicitudes ||
                    (solicitudesProcesadas == ultimasSolicitudesProcesadas && ++contadorSinCambios >= MAX_SIN_CAMBIOS)) {
                // Se agrega el horario de la sala al JSON de horarios.
                SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, horario);
                System.out.println("Sala " + codigo + " ha finalizado su asignación de horarios y enviado los datos.");
                stop();
                myAgent.doDelete();
            } else if (solicitudesProcesadas != ultimasSolicitudesProcesadas) {
                // Si se han procesado solicitudes en el último ciclo, se actualiza el contador de solicitudes y se reinicia el contador de verificaciones sin cambios.
                ultimasSolicitudesProcesadas = solicitudesProcesadas;
                contadorSinCambios = 0;
            }
        }
    }
}


