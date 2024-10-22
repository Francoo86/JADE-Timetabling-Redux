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
    private Map<String, List<AsignacionSala>> horario;
    private int solicitudesProcesadas = 0;
    private int totalSolicitudes;
    private int bloquesDisponibles;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString = (String) args[0];
            loadFromJsonString(jsonString);
        }

        horario = new HashMap<>();
        String[] dias = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            List<AsignacionSala> bloques = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                bloques.add(null); // Inicialmente todos los bloques están vacíos
            }
            horario.put(dia, bloques);
        }
        bloquesDisponibles = 25; // 5 días x 5 bloques

        System.out.println("Agente Sala " + codigo + " iniciado. Capacidad: " + capacidad);

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

        setEnabledO2ACommunication(true, 0);
        registerO2AInterface(SalaInterface.class, this);

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

    private class RecibirSolicitudBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null && bloquesDisponibles > 0) {
                solicitudesProcesadas++;
                String solicitud = msg.getContent();
                Asignatura asignatura = Asignatura.fromString(solicitud);

                // Generar propuestas solo para bloques disponibles
                List<String> propuestas = generarPropuestas(asignatura);

                // Agregar delay aleatorio para evitar congestión (entre 100ms y 1s)
                try {
                    Thread.sleep((long) (Math.random() * 900 + 100));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // Enviar propuestas
                for (String propuesta : propuestas) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(propuesta + "," + capacidad);
                    myAgent.send(reply);
                }

                addBehaviour(new EsperarRespuestaBehaviour(myAgent, msg.getSender(), propuestas, asignatura.getNombre()));
            } else {
                block();
            }
        }
    }

    private List<String> generarPropuestas(Asignatura asignatura) {
        List<String> propuestas = new ArrayList<>();
        for (Map.Entry<String, List<AsignacionSala>> entry : horario.entrySet()) {
            String dia = entry.getKey();
            List<AsignacionSala> bloques = entry.getValue();

            for (int i = 0; i < bloques.size(); i++) {
                if (bloques.get(i) == null) {  // Si el bloque está disponible
                    propuestas.add(dia + "," + (i + 1) + "," + codigo);
                }
            }
        }
        return propuestas;
    }

    private class EsperarRespuestaBehaviour extends Behaviour {
        private boolean received = false;
        private MessageTemplate mt;
        private List<String> propuestas;
        private String nombreAsignatura;

        public EsperarRespuestaBehaviour(Agent a, jade.core.AID sender, List<String> propuestas, String nombreAsignatura) {
            super(a);
            this.mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(sender),
                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)
            );
            this.propuestas = propuestas;
            this.nombreAsignatura = nombreAsignatura;
        }

        public void action() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                String propuestaAceptada = msg.getContent();
                String[] partes = propuestaAceptada.split(",");
                String dia = partes[0];
                int bloque = Integer.parseInt(partes[1]);

                // Crear una nueva AsignacionSala con el nombre de la asignatura y valoración
                int valoracion = Integer.parseInt(partes[partes.length - 1]); // La valoración viene al final
                AsignacionSala asignacion = new AsignacionSala(nombreAsignatura, valoracion);

                // Actualizar el horario con la nueva asignación
                horario.get(dia).set(bloque - 1, asignacion);
                bloquesDisponibles--;

                System.out.println("Sala " + codigo + " ha sido asignada para " + nombreAsignatura +
                        " en " + dia + ", bloque " + bloque + " con valoración " + valoracion);
                received = true;

                if (bloquesDisponibles == 0) {
                    try {
                        DFService.deregister(myAgent);
                    } catch (FIPAException fe) {
                        fe.printStackTrace();
                    }
                }
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
            System.out.println("Verificando finalización para sala " + codigo +
                    ". Procesadas: " + solicitudesProcesadas +
                    " de " + totalSolicitudes +
                    ". Bloques disponibles: " + bloquesDisponibles);

            if (bloquesDisponibles == 0 || solicitudesProcesadas >= totalSolicitudes ||
                    (solicitudesProcesadas == ultimasSolicitudesProcesadas && ++contadorSinCambios >= MAX_SIN_CAMBIOS)) {
                // Ahora el horario es del tipo correcto (Map<String, List<AsignacionSala>>)
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