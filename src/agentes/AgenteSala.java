package agentes;

import constants.Commons;
import constants.enums.Day;
import interfaces.SalaDataInterface;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.SubscriptionInitiator;
import json_stuff.SalaHorarioJSON;
import objetos.AsignacionSala;
import objetos.ClassroomAvailability;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import performance.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgenteSala extends Agent implements SalaDataInterface {
    public static final String SERVICE_NAME = "sala";
    private boolean isRegistered = false;
    private String codigo;
    private String campus;
    private int capacidad;
    private int turno;
    private Map<Day, List<AsignacionSala>> horarioOcupado; // dia -> lista de asignaciones
    //private AgentPerformanceMonitor performanceMonitor;

    private RTTLogger rttLogger;
    private AgentMessageLogger messageLogger = AgentMessageLogger.getInstance();

    @Override
    protected void setup() {
        String scenario = "small";
        rttLogger = RTTLogger.getInstance();
        // Inicializar estructuras
        initializeSchedule();
        horarioOcupado = new HashMap<>();
        for (Day dia : Day.values()) {
            List<AsignacionSala> asignaciones = new ArrayList<>();
            for (int i = 0; i < Commons.MAX_BLOQUE_DIURNO; i++) { // 5 bloques por día
                asignaciones.add(null);
            }
            horarioOcupado.put(dia, asignaciones);
        }

        //get passed arguments
        Object[] args = getArguments();
        int currIteration = args.length > 0 ? (int) args[1] : 0;
        scenario = args.length > 1 ? (String) args[2] : "small";

        setEnabledO2ACommunication(true, 10);
        registerO2AInterface(SalaDataInterface.class, this);

        //performanceMonitor = new AgentPerformanceMonitor(getLocalName(), "SALA", scenario);

        // Inicializar monitor de rendimiento
        //performanceMonitor = new ThreadBottleneckMonitor(currIteration, "Agent_" + getLocalName(), scenario);
        //performanceMonitor.startMonitoring();
        //addBehaviour(metricsCollector.createMessageMonitorBehaviour());

        // Cargar datos de la sala desde JSON
        if (args != null && args.length > 0) {
            parseJSON((String) args[0]);
        }

        // Registrar en el DF
        registrarEnDF();

        // Agregar comportamiento principal
        addBehaviour(new ResponderSolicitudesBehaviour());

        // Agregar comportamiento para revisar si los profesores han terminado
        //addBehaviour(new ProfessorMonitorBehaviour(this));
    }

    private int MEEETING_ROOM_THRESHOLD = 10;

    @Override
    public String getCodigo() {
        return codigo;
    }

    @Override
    public String getCampus() {
        return campus;
    }

    public boolean isMeetingRoom() {
        return capacidad < MEEETING_ROOM_THRESHOLD;
    }

    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    private void parseJSON(String jsonString) {
        // Parsear JSON y asignar valores
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
        // Inicializar horario con bloques vacíos
        horarioOcupado = new HashMap<>();
        for (Day dia : Day.values()) {
            List<AsignacionSala> asignaciones = new ArrayList<>();
            for (int i = 0; i < Commons.MAX_BLOQUE_DIURNO; i++) {
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
            sd.setType(SERVICE_NAME);
            sd.setName(codigo);
            // Agregar propiedades adicionales
            sd.addProperties(new Property("campus", campus));
            sd.addProperties(new Property("turno", turno));
            sd.addProperties(new Property("capacidad", capacidad));
            dfd.addServices(sd);
            DFService.register(this, dfd);
            isRegistered = true;
        } catch (FIPAException fe) {
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
                messageLogger.logMessageReceived(myAgent.getLocalName(), msg);
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

        private Map<String, List<Integer>> getAvailableBlocks() {
            Map<String, List<Integer>> availableBlocks = new HashMap<>();
            for (Day dia : Day.values()) {
                List<AsignacionSala> asignaciones = horarioOcupado.get(dia);
                List<Integer> freeBlocks = new ArrayList<>();
                for (int bloque = 0; bloque < Commons.MAX_BLOQUE_DIURNO; bloque++) {
                    if (asignaciones.get(bloque) == null) {
                        freeBlocks.add(bloque + 1);
                    }
                }
                if (!freeBlocks.isEmpty()) {
                    availableBlocks.put(dia.toString(), freeBlocks);
                }
            }
            return availableBlocks;
        }

        private void procesarSolicitud(ACLMessage msg) {
            try {
                //getPerformanceMonitor().recordMessageReceived(msg, "CFP");
                //long startTime = System.nanoTime();
                Map<String, List<Integer>> availableBlocks = getAvailableBlocks();
                if (!availableBlocks.isEmpty()) {
                    ClassroomAvailability availability = new ClassroomAvailability(
                            codigo,
                            campus,
                            capacidad,
                            availableBlocks
                    );

                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContentObject(availability);

                    rttLogger.recordMessageSent(
                            myAgent.getLocalName(),
                            msg.getConversationId(),
                            ACLMessage.PROPOSE,
                            msg.getSender().getLocalName(),
                            "classroom-availability"
                    );

                    messageLogger.logMessageSent(myAgent.getLocalName(), reply);

                    send(reply);
                } else {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO AVAILABLE BLOCKS");
                    //getPerformanceMonitor().recordMessageSent(reply, "PROPOSE");
                    //THE SAME BUT REFUSE
                    rttLogger.recordMessageSent(
                            myAgent.getLocalName(),
                            msg.getConversationId(),
                            ACLMessage.REFUSE,
                            msg.getSender().getLocalName(),
                            "classroom-availability"
                    );

                    messageLogger.logMessageSent(myAgent.getLocalName(), reply);
                    send(reply);
                }
            } catch (Exception e) {
                System.err.println("Error processing request in classroom " + codigo + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void confirmarAsignacion(ACLMessage msg) {
            try {
                BatchAssignmentRequest batchRequest = (BatchAssignmentRequest) msg.getContentObject();
                List<BatchAssignmentConfirmation.ConfirmedAssignment> confirmedAssignments = new ArrayList<>();

                System.out.println("\n[DEBUG] Room " + codigo + " processing assignment request");

                for (BatchAssignmentRequest.AssignmentRequest request : batchRequest.getAssignments()) {
                    if (!request.getClassroomCode().equals(codigo)) {
                        System.out.println("[DEBUG] Skipping request for different room: " + request.getClassroomCode());
                        continue;
                    }

                    int bloque = request.getBlock() - 1;
                    List<AsignacionSala> asignaciones = horarioOcupado.get(request.getDay());

                    System.out.println("[DEBUG] Processing request for " + request.getSubjectName() +
                            " Day: " + request.getDay() + " Block: " + request.getBlock());

                    if (asignaciones != null && bloque >= 0 && bloque < asignaciones.size() &&
                            asignaciones.get(bloque) == null) {

                        float capacidadFraccion = (float) request.getVacancy() / capacidad;
                        AsignacionSala nuevaAsignacion = new AsignacionSala(
                                request.getSubjectName(),
                                request.getSatisfaction(),
                                capacidadFraccion,
                                request.getProfName()
                        );
                        asignaciones.set(bloque, nuevaAsignacion);

                        confirmedAssignments.add(new BatchAssignmentConfirmation.ConfirmedAssignment(
                                request.getDay(),
                                request.getBlock(),
                                codigo,
                                request.getSatisfaction()
                        ));

                        System.out.println("[DEBUG] Successfully assigned " + request.getSubjectName() +
                                " to block " + request.getBlock() + " on " + request.getDay());
                    } else {
                        System.out.println("[DEBUG] Could not assign - asignaciones null? " + (asignaciones == null) +
                                " valid block? " + (bloque >= 0 && bloque < (asignaciones != null ? asignaciones.size() : 0)) +
                                " block empty? " + (asignaciones != null && bloque >= 0 &&
                                bloque < asignaciones.size() && asignaciones.get(bloque) == null));
                    }
                }

                // Update JSON after batch processing
                if (!confirmedAssignments.isEmpty()) {
                    verifyAssignments(confirmedAssignments);

                    //SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, campus, horarioOcupado);

                    // Send single confirmation with all successful assignments
                    ACLMessage confirm = msg.createReply();
                    confirm.setPerformative(ACLMessage.INFORM);
                    confirm.setContentObject(new BatchAssignmentConfirmation(confirmedAssignments));
                    /*
                    String conversationId = msg.getConversationId();
                    simpleRTT.messageSent(
                            conversationId,
                            myAgent.getAID(),
                            msg.getSender(),
                            "INFORM"
                    );*/
                    //getPerformanceMonitor().recordMessageSent(confirm, "INFORM");
                    messageLogger.logMessageSent(myAgent.getLocalName(), confirm);
                    send(confirm);
                }

            } catch (Exception e) {
                System.err.println("Error procesando confirmación en sala " + codigo + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void verifyAssignments(List<BatchAssignmentConfirmation.ConfirmedAssignment> assignments) {
        for (BatchAssignmentConfirmation.ConfirmedAssignment assignment : assignments) {
            Day day = assignment.getDay();
            int block = assignment.getBlock() - 1;

            List<AsignacionSala> dayAssignments = horarioOcupado.get(day);
            if (dayAssignments == null || dayAssignments.get(block) == null) {
                System.err.println("WARNING: Assignment verification failed for room " +
                        codigo + " on " + day + " block " + assignment.getBlock());
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
            //SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, campus, horarioOcupado);
    
        } catch (Exception e) {
            System.err.println("Error durante cleanup de sala " + codigo + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    //create a getter for horarioOcupado
    public Map<Day, List<AsignacionSala>> getHorarioOcupado() {
        // Return a defensive copy to avoid concurrent modification issues
        Map<Day, List<AsignacionSala>> copy = new HashMap<>();
        for (Map.Entry<Day, List<AsignacionSala>> entry : horarioOcupado.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }


    @Override
    protected void takeDown() {
        cleanup();
        System.out.println("Sala " + codigo + " finalizada");
    }
}
