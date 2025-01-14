package agentes;

import constants.Commons;
import constants.Messages;
import constants.enums.Day;
import jade.proto.SubscriptionInitiator;
import objetos.ClassroomAvailability;
import objetos.helper.BatchAssignmentConfirmation;
import objetos.helper.BatchAssignmentRequest;
import service.SatisfaccionHandler;
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
    private boolean hasInitialized = false;

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
        //hasInitialized = true;

        //System.out.println("Sala " + codigo + " iniciada. Capacidad: " + capacidad);
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
            dfd.addServices(sd);
            DFService.register(this, dfd);
            isRegistered = true;
            //System.out.println("Sala " + codigo + " registrada en DF (Campus: " + campus + ", Turno: " + turno + ")");
        } catch (FIPAException fe) {
            //System.err.println("Error registrando sala " + codigo + " en DF: " + fe.getMessage());
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
            //System.out.println(myAgent.getLocalName() + "MSG Pendientes: " + myAgent.getCurQueueSize());

            if(myAgent.getCurQueueSize() > 100) {
                System.out.println("Sala " + codigo + " tiene " + myAgent.getCurQueueSize() + " mensajes pendientes");
            }

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

        private Map<String, Integer> subjectAssignments = new HashMap<>();
        private Map<Day, Integer> dayLoadCount = new HashMap<>();

        private void procesarSolicitud(ACLMessage msg) {
            try {
                String[] solicitudData = msg.getContent().split(",");
                String nombreAsignatura = sanitizeSubjectName(solicitudData[0]);
                int vacantes = Integer.parseInt(solicitudData[1]);
                int nivel = Integer.parseInt(solicitudData[2]);
                String preferredCampus = solicitudData[3];
                int remainingHours = Integer.parseInt(solicitudData[4]);

                // Calculate base satisfaction
                int satisfaccion = SatisfaccionHandler.getSatisfaccion(capacidad, vacantes);

                // Get available blocks with enhanced distribution
                Map<String, List<Integer>> availableBlocks = getOptimizedAvailableBlocks(
                        nombreAsignatura,
                        nivel,
                        preferredCampus,
                        remainingHours
                );

                if (!availableBlocks.isEmpty()) {
                    // Send proposal with available blocks
                    // Create single availability object
                    ClassroomAvailability availability = new ClassroomAvailability(
                            codigo,
                            campus,
                            capacidad,
                            availableBlocks,
                            satisfaccion
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
                    // Send refusal
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    send(reply);
                }

            } catch (Exception e) {
                System.err.println("Error processing request in room " + codigo + ": " + e.getMessage());
                e.printStackTrace();
            }
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

                for (BatchAssignmentRequest.AssignmentRequest request : batchRequest.getAssignments()) {
                    if (!request.getClassroomCode().equals(codigo)) {
                        continue;
                    }

                    int bloque = request.getBlock() - 1;
                    List<AsignacionSala> asignaciones = horarioOcupado.get(request.getDay());

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
                    }
                }

                // Update JSON after batch processing
                if (!confirmedAssignments.isEmpty()) {
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
