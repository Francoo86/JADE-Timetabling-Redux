package agentes;

import constants.Commons;
import constants.enums.Day;
import jade.domain.FIPANames;
import jade.proto.SubscriptionInitiator;
import objetos.ClassroomAvailability;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
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

import java.io.IOException;
import java.util.*;

public class AgenteSala extends Agent {
    public static final String SERVICE_NAME = "sala";
    private boolean isRegistered = false;
    private String codigo;
    private String campus;
    private int capacidad;
    private int turno;
    private Map<Day, List<AsignacionSala>> horarioOcupado; // dia -> lista de asignaciones

    @Override
    protected void setup() {
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

        // Cargar datos de la sala desde JSON
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            parseJSON((String) args[0]);
        }

        // Registrar en el DF
        registrarEnDF();

        // Agregar comportamiento principal
        addBehaviour(new ResponderSolicitudesBehaviour());

        // Agregar comportamiento para revisar si los profesores han terminado
        addBehaviour(new ProfessorMonitorBehaviour(this));
    }

    private int MEEETING_ROOM_THRESHOLD = 10;

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

    private boolean allDone = false;

    private class ProfessorMonitorBehaviour extends SubscriptionInitiator {
        public ProfessorMonitorBehaviour(Agent a) {
            // Create template directly in constructor
            super(a, createSubscriptionMessage(a));
        }

        // Make this static or move it out
        private static ACLMessage createSubscriptionMessage(Agent a) {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(AgenteProfesor.SERVICE_NAME);
            template.addServices(sd);

            return DFService.createSubscriptionMessage(a,
                    a.getDefaultDF(),
                    template,
                    null);
        }

        @Override
        protected void handleInform(ACLMessage inform) {
            try {
                DFAgentDescription[] results = DFService.decodeNotification(inform.getContent());
                if (results == null || results.length < 1) {
                    // No professors left
                    if (isRegistered) {
                        DFService.deregister(myAgent);
                        isRegistered = false;
                        myAgent.doDelete();
                    }
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
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

        private Map<Day, Integer> dayLoadCount = new HashMap<>();

        private void procesarSolicitud(ACLMessage msg) {
            try {
                String[] solicitudData = msg.getContent().split(",");
                String nombreAsignatura = sanitizeSubjectName(solicitudData[0]);
                int vacantes = Integer.parseInt(solicitudData[1]);
                //int satisfaccion = SatisfaccionHandler.getSatisfaccion(capacidad, vacantes);
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
                if (!availableBlocks.isEmpty()) {
                    // Create single availability object
                    ClassroomAvailability availability = new ClassroomAvailability(
                            codigo,
                            campus,
                            capacidad,
                            availableBlocks
                            //satisfaccion
                    );
                    // Send single response with all availability data
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    try {
                        reply.setContentObject(availability);
                        send(reply);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    // Send refuse if no blocks available
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    send(reply);
                }
            } catch (Exception e) {
                System.err.println("Error processing request in classroom " + codigo + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        private Map<Day, List<Integer>> convertToExistingBlocks(Map<Day, List<AsignacionSala>> horario) {
            Map<Day, List<Integer>> result = new HashMap<>();
            for (Map.Entry<Day, List<AsignacionSala>> entry : horario.entrySet()) {
                List<Integer> blocks = new ArrayList<>();
                List<AsignacionSala> assignments = entry.getValue();
                for (int i = 0; i < assignments.size(); i++) {
                    if (assignments.get(i) != null) {
                        blocks.add(i + 1);
                    }
                }
                if (!blocks.isEmpty()) {
                    result.put(entry.getKey(), blocks);
                }
            }
            return result;
        }

        private Map<String, List<Integer>> getOptimizedAvailableBlocks(
                String asignatura,
                int nivel,
                String preferredCampus,
                int remainingHours) {

            Map<String, List<Integer>> availableBlocks = new HashMap<>();

            for (Day dia : Day.values()) {
                List<AsignacionSala> asignaciones = horarioOcupado.get(dia);
                if (asignaciones == null) continue;

                // Calculate day load
                int currentDayLoad = dayLoadCount.getOrDefault(dia, 0);
                int subjectDayLoad = countSubjectBlocksInDay(dia, asignatura);

                // Skip overloaded days
                if (currentDayLoad >= 6 || subjectDayLoad >= 2) continue;

                List<Integer> freeBlocks = findOptimalBlocksForDay(
                        dia,
                        asignaciones,
                        nivel,
                        remainingHours
                );

                if (!freeBlocks.isEmpty()) {
                    availableBlocks.put(dia.toString(), freeBlocks);
                }
            }

            return availableBlocks;
        }

        private List<Integer> findOptimalBlocksForDay(
                Day dia,
                List<AsignacionSala> asignaciones,
                int nivel,
                int remainingHours) {

            List<Integer> freeBlocks = new ArrayList<>();
            boolean isOddYear = nivel % 2 == 1;

            // Determine preferred time slots
            int startBlock = isOddYear ? 1 : 5;
            int endBlock = isOddYear ? 4 : Commons.MAX_BLOQUE_DIURNO;

            // Find consecutive blocks when possible
            for (int bloque = startBlock; bloque <= endBlock; bloque++) {
                if (asignaciones.get(bloque - 1) == null) {
                    // Check if this could form a consecutive sequence
                    if (freeBlocks.isEmpty() ||
                            bloque == freeBlocks.get(freeBlocks.size() - 1) + 1) {
                        freeBlocks.add(bloque);

                        // Stop if we have enough blocks for remaining hours
                        if (freeBlocks.size() >= remainingHours) break;
                    }
                }
            }

            return freeBlocks;
        }

        private int countSubjectBlocksInDay(Day dia, String asignatura) {
            List<AsignacionSala> asignaciones = horarioOcupado.get(dia);
            if (asignaciones == null) return 0;

            int count = 0;
            for (AsignacionSala asignacion : asignaciones) {
                if (asignacion != null &&
                        asignacion.getNombreAsignatura().equals(asignatura)) {
                    count++;
                }
            }
            return count;
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
                                capacidadFraccion
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

                    SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, campus, horarioOcupado);

                    // Send single confirmation with all successful assignments
                    ACLMessage confirm = msg.createReply();
                    confirm.setPerformative(ACLMessage.INFORM);
                    confirm.setContentObject(new BatchAssignmentConfirmation(confirmedAssignments));
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
