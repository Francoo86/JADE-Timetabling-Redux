package agentes;

import constants.Messages;
import constants.SatisfaccionHandler;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import json_stuff.SalaHorarioJSON;
import objetos.AsignacionSala;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.util.*;

public class AgenteSala extends Agent {
    private boolean isRegistered = false;
    private String codigo;
    private String campus;
    private int capacidad;
    private int turno;
    private int bloquesDiarios = 9;
    private Map<String, List<AsignacionSala>> horarioOcupado; // dia -> lista de asignaciones
    private static final String[] DIAS = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};

    @Override
    protected void setup() {
        // Inicializar estructuras
        initializeSchedule();
        horarioOcupado = new HashMap<>();
        for (String dia : DIAS) {
            List<AsignacionSala> asignaciones = new ArrayList<>();
            for (int i = 0; i < bloquesDiarios; i++) { // 5 bloques por día
                asignaciones.add(null);
            }
            horarioOcupado.put(dia, asignaciones);
        }

        // Cargar datos de la sala desde JSON
        Object[] args = getArguments();  // Corregido: usando getArguments() en lugar de getAgents()
        if (args != null && args.length > 0) {
            parseJSON((String)args[0]);
        }

        // Registrar en el DF
        registrarEnDF();

        // Agregar comportamiento principal
        addBehaviour(new ResponderSolicitudesBehaviour());

        System.out.println("Sala " + codigo + " iniciada. Capacidad: " + capacidad);
    }

    private void parseJSON(String jsonString) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject salaJson = (JSONObject) parser.parse(jsonString);
            codigo = (String) salaJson.get("Codigo");
            campus = (String) salaJson.get("Campus");
            capacidad = ((Number) salaJson.get("Capacidad")).intValue();
            turno = ((Number) salaJson.get("Turno")).intValue();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initializeSchedule() {
        horarioOcupado = new HashMap<>();
        for (String dia : DIAS) {
            List<AsignacionSala> asignaciones = new ArrayList<>();
            for (int i = 0; i < bloquesDiarios; i++) {
                asignaciones.add(null);
            }
            horarioOcupado.put(dia, asignaciones);
        }
    }

    private void registrarEnDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("sala");
            sd.setName(codigo);
            // Agregar propiedades adicionales
            sd.addProperties(new Property("campus", campus));
            sd.addProperties(new Property("turno", turno));
            dfd.addServices(sd);
            DFService.register(this, dfd);
            isRegistered = true;
            System.out.println("Sala " + codigo + " registrada en DF (Campus: " + campus + ", Turno: " + turno + ")");
        } catch (FIPAException fe) {
            System.err.println("Error registrando sala " + codigo + " en DF: " + fe.getMessage());
            fe.printStackTrace();
        }
    }

    private class ResponderSolicitudesBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.CFP),
                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)
            );

            ACLMessage msg = receive(mt);
            if (msg != null) {
                switch (msg.getPerformative()) {
                    case ACLMessage.CFP:
                        procesarSolicitud(msg);
                        break;
                    case ACLMessage.ACCEPT_PROPOSAL:
                        confirmarAsignacion(msg);
                        break;
                }
            } else {
                block();
            }
        }

        private void procesarSolicitud(ACLMessage msg) {
            try {
                String[] solicitudData = msg.getContent().split(",");
                String nombreAsignatura = solicitudData[0];
                int vacantes = Integer.parseInt(solicitudData[1]);
                int satisfaccion = SatisfaccionHandler.getSatisfaccion(capacidad, vacantes);

                boolean propuestaEnviada = false;

                for (String dia : DIAS) {
                    List<AsignacionSala> asignaciones = horarioOcupado.get(dia);
                    for (int bloque = 0; bloque < bloquesDiarios; bloque++) {
                        if (asignaciones.get(bloque) == null) {
                            ACLMessage reply = msg.createReply();
                            reply.setPerformative(ACLMessage.PROPOSE);
                            reply.setContent(String.format("%s,%d,%s,%d,%d",
                                    dia, bloque + 1, codigo, capacidad, satisfaccion));
                            send(reply);
                            propuestaEnviada = true;
                            System.out.println("Sala " + codigo + " propone para " + nombreAsignatura +
                                    ": día " + dia + ", bloque " + (bloque + 1) +
                                    ", satisfacción " + satisfaccion);
                        }
                    }
                }

                if (!propuestaEnviada) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    send(reply);
                    System.out.println("Sala " + codigo + " no tiene bloques disponibles para " +
                            nombreAsignatura);
                }
            } catch (Exception e) {
                System.err.println("Error procesando solicitud en sala " + codigo + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void confirmarAsignacion(ACLMessage msg) {
            try {
                String[] datos = msg.getContent().split(",");
                if (datos.length < 6) { // Ahora necesitamos un parámetro adicional para vacantes
                    System.err.println("Error: Formato de mensaje inválido en confirmación para sala " + codigo);
                    return;
                }

                String dia = datos[0];
                int bloque = Integer.parseInt(datos[1]) - 1;
                String nombreAsignatura = datos[2];
                int satisfaccion = Integer.parseInt(datos[3]);
                String salaConfirmada = datos[4];
                int vacantesAsignatura = Integer.parseInt(datos[5]); // Nuevo parámetro

                if (!salaConfirmada.equals(codigo)) {
                    System.err.println("Error: Confirmación recibida para sala incorrecta");
                    return;
                }

                List<AsignacionSala> asignaciones = horarioOcupado.get(dia);
                if (asignaciones != null && bloque >= 0 && bloque < asignaciones.size() &&
                        asignaciones.get(bloque) == null) {

                    // Calcular la capacidad como fracción
                    float capacidadFraccion = (float) vacantesAsignatura / capacidad;

                    AsignacionSala nuevaAsignacion = new AsignacionSala(
                            nombreAsignatura,
                            satisfaccion,
                            capacidadFraccion
                    );
                    asignaciones.set(bloque, nuevaAsignacion);

                    SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, campus, horarioOcupado);

                    ACLMessage confirm = msg.createReply();
                    confirm.setPerformative(ACLMessage.INFORM);
                    confirm.setContent(Messages.CONFIRM);
                    send(confirm);

                    System.out.println(String.format("Sala %s (%s): Asignada %s en %s, bloque %d, satisfacción %d, capacidad %.2f",
                            codigo, campus, nombreAsignatura, dia, (bloque + 1), satisfaccion, capacidadFraccion));
                }
            } catch (Exception e) {
                System.err.println("Error procesando confirmación en sala " + codigo + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private synchronized void cleanup() {
        try {
            if (isRegistered) {
                // Verificar si aún estamos registrados antes de hacer deregister
                DFAgentDescription dfd = new DFAgentDescription();
                dfd.setName(getAID());
                DFAgentDescription[] result = DFService.search(this, dfd);
    
                if (result != null && result.length > 0) {
                    DFService.deregister(this);
                    System.out.println("Sala " + codigo + " eliminada del DF");
                }
                isRegistered = false;
            }
    
            // Asegurarse de guardar el estado final en JSON
            System.out.println("Guardando estado final de sala " + codigo);
            SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, campus, horarioOcupado);
    
        } catch (Exception e) {
            System.err.println("Error durante cleanup de sala " + codigo + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void takeDown() {
        cleanup();
        System.out.println("Sala " + codigo + " finalizada");
    }
}
