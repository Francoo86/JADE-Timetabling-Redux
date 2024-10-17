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

    @Override
    public void setTotalSolicitudes(int total) {
        this.totalSolicitudes = total;
        System.out.println("Total de solicitudes establecido para sala " + codigo + ": " + total);
        addBehaviour(new VerificarFinalizacionBehaviour(this, 1000));
    }

    /* 
    1. Recepción de Solicitudes: Continuamente recibe solicitudes de asignación de horarios.
    2. Generación de Propuestas: Genera propuestas de horarios basadas en la disponibilidad.
    3. Esperar Respuesta: Espera la aceptación o rechazo de la propuesta.
    4. Actualización de Horario: Si la propuesta es aceptada, actualiza el horario.
    5. Verificación de Finalización: Periódicamente verifica si se han procesado todas las solicitudes y genera un archivo Excel con el horario final.
    */

    private class RecibirSolicitudBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchConversationId("solicitud-horario")
            );
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                solicitudesProcesadas++;

                String solicitud = msg.getContent();
                Asignatura asignatura = Asignatura.fromString(solicitud);

                String propuesta = generarPropuesta(asignatura);

                if (!propuesta.isEmpty()) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(propuesta);
                    myAgent.send(reply);

                    addBehaviour(new EsperarRespuestaBehaviour(myAgent, msg.getSender(), propuesta, asignatura.getNombre()));
                }
            } else {
                block();
            }
        }
    }

    // Recorre el mapa horario para encontrar un bloque vacío. Si encuentra un bloque vacío, retorna el día y el número del bloque. Si no retorna una cadena vacía.
    private String generarPropuesta(Asignatura asignatura) {
        if (asignatura.getVacantes() > this.capacidad) {
            return "";
        }
        for (Map.Entry<String, List<String>> entry : horario.entrySet()) {
            String dia = entry.getKey();
            List<String> bloques = entry.getValue();

            for (int i = 0; i < bloques.size(); i++) {
                if (bloques.get(i).isEmpty()) {
                    return dia + "," + (i + 1) + "," + codigo;
                }
            }
        }
        return "";
    }


    private class EsperarRespuestaBehaviour extends Behaviour {
        private boolean received = false;
        private MessageTemplate mt;
        private String propuesta;
        private String nombreAsignatura;

        public EsperarRespuestaBehaviour(Agent a, jade.core.AID sender, String propuesta, String nombreAsignatura) {
            super(a);
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

        public void action() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    String[] partes = propuesta.split(",");
                    String dia = partes[0];
                    int bloque = Integer.parseInt(partes[1]);
                    horario.get(dia).set(bloque - 1, nombreAsignatura);
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

    private class VerificarFinalizacionBehaviour extends TickerBehaviour {
        private int ultimasSolicitudesProcesadas = 0;
        private int contadorSinCambios = 0;
        private static final int MAX_SIN_CAMBIOS = 10;

        public VerificarFinalizacionBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            System.out.println("Verificando finalización para sala " + codigo + ". Procesadas: " + solicitudesProcesadas + " de " + totalSolicitudes);

            if (solicitudesProcesadas >= totalSolicitudes ||
                    (solicitudesProcesadas == ultimasSolicitudesProcesadas && ++contadorSinCambios >= MAX_SIN_CAMBIOS)) {
                SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, horario);
                System.out.println("Sala " + codigo + " ha finalizado su asignación de horarios y enviado los datos.");
                stop();
                myAgent.doDelete();
            } else if (solicitudesProcesadas != ultimasSolicitudesProcesadas) {
                ultimasSolicitudesProcesadas = solicitudesProcesadas;
                contadorSinCambios = 0;
            }
        }
    }
}


