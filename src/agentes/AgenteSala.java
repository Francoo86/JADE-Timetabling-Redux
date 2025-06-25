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
    private Map<Day, List<AsignacionSala>> horarioOcupado;

    private RTTLogger rttLogger;
    private AgentMessageLogger messageLogger = AgentMessageLogger.getInstance();

    @Override
    protected void setup() {
        String scenario = "small";
        rttLogger = RTTLogger.getInstance();

        // Inicializar estructuras - MISMO CÓDIGO QUE ANTES
        initializeSchedule();

        Object[] args = getArguments();
        int currIteration = args.length > 0 ? (int) args[1] : 0;
        scenario = args.length > 1 ? (String) args[2] : "small";

        setEnabledO2ACommunication(true, 10);
        registerO2AInterface(SalaDataInterface.class, this);

        if (args != null && args.length > 0) {
            parseJSON((String) args[0]);
        }

        registrarEnDF();

        // ÚNICO BEHAVIOUR - Simple y directo
        addBehaviour(new SimplifiedResponderBehaviour());
    }

    private void initializeSchedule() {
        horarioOcupado = new HashMap<>();
        for (Day dia : Day.values()) {
            List<AsignacionSala> asignaciones = new ArrayList<>();
            for (int i = 0; i < Commons.MAX_BLOQUE_DIURNO; i++) {
                asignaciones.add(null);
            }
            horarioOcupado.put(dia, asignaciones);
        }
    }

    private class SimplifiedResponderBehaviour extends CyclicBehaviour {
        private final Object assignmentLock = new Object();

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

        private void procesarSolicitud(ACLMessage msg) {
            try {
                Map<String, List<Integer>> availableBlocks = getAvailableBlocks();
                if (!availableBlocks.isEmpty()) {
                    ClassroomAvailability availability = new ClassroomAvailability(
                            codigo, campus, capacidad, availableBlocks
                    );

                    ACLMessage reply = msg.createReply();

                    String taskId = msg.getUserDefinedParameter("taskId");
                    if (taskId != null) {
                        reply.addUserDefinedParameter("taskId", taskId);
                    }

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
                    String taskId = msg.getUserDefinedParameter("taskId");
                    if (taskId != null) {
                        reply.addUserDefinedParameter("taskId", taskId);
                    }

                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO AVAILABLE BLOCKS");

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
            }
        }

        // ✅ CONFIRMACIÓN SIMPLE - Solo un lock, sin throttling
        private void confirmarAsignacion(ACLMessage msg) {
            // ✅ ÚNICO PUNTO DE SINCRONIZACIÓN - Simple y efectivo
            synchronized (assignmentLock) {
                try {
                    BatchAssignmentRequest batchRequest = (BatchAssignmentRequest) msg.getContentObject();
                    List<BatchAssignmentConfirmation.ConfirmedAssignment> confirmedAssignments = new ArrayList<>();

                    System.out.printf("[ASSIGN] Room %s processing assignment request%n", codigo);

                    for (BatchAssignmentRequest.AssignmentRequest request : batchRequest.getAssignments()) {
                        if (!request.getClassroomCode().equals(codigo)) {
                            continue;
                        }

                        int bloque = request.getBlock() - 1;
                        List<AsignacionSala> asignaciones = horarioOcupado.get(request.getDay());

                        // ✅ VERIFICACIÓN ATÓMICA SIMPLE
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

                            System.out.printf("[SUCCESS] Room %s assigned %s to block %d on %s%n",
                                    codigo, request.getSubjectName(), request.getBlock(), request.getDay());
                        } else {
                            System.out.printf("[CONFLICT] Room %s cannot assign block %d on %s (already taken)%n",
                                    codigo, request.getBlock(), request.getDay());
                        }
                    }

                    ACLMessage reply = msg.createReply();

                    String taskId = msg.getUserDefinedParameter("taskId");
                    if (taskId != null) {
                        reply.addUserDefinedParameter("taskId", taskId);
                    }

                    if (!confirmedAssignments.isEmpty()) {
                        //ACLMessage confirm = msg.createReply();
                        reply.setPerformative(ACLMessage.INFORM);
                        reply.setContentObject(new BatchAssignmentConfirmation(confirmedAssignments));
                        messageLogger.logMessageSent(myAgent.getLocalName(), reply);
                        send(reply);

                        System.out.printf("[CONFIRM] Room %s confirmed %d assignments%n",
                                codigo, confirmedAssignments.size());
                    } else {
                        //ACLMessage reject = msg.createReply();
                        reply.setPerformative(ACLMessage.FAILURE);
                        reply.setContent("NO_BLOCKS_AVAILABLE");
                        messageLogger.logMessageSent(myAgent.getLocalName(), reply);
                        send(reply);

                        System.out.printf("[REJECT] Room %s rejected all assignments (conflicts)%n", codigo);
                    }

                } catch (Exception e) {
                    System.err.printf("Error processing assignment in room %s: %s%n", codigo, e.getMessage());
                    e.printStackTrace();

                    ACLMessage error = msg.createReply();
                    error.setPerformative(ACLMessage.FAILURE);
                    error.setContent("PROCESSING_ERROR");
                    messageLogger.logMessageSent(myAgent.getLocalName(), error);
                    send(error);
                }
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
    }

    // ✅ RESTO DE MÉTODOS IGUALES - No cambiar lo que funciona
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

    private void registrarEnDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(SERVICE_NAME);
            sd.setName(codigo);
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

    @Override
    public String getCodigo() { return codigo; }

    @Override
    public String getCampus() { return campus; }

    public boolean isMeetingRoom() {
        return capacidad < 10;
    }

    @Override
    public Map<Day, List<AsignacionSala>> getHorarioOcupado() {
        Map<Day, List<AsignacionSala>> copy = new HashMap<>();
        synchronized (horarioOcupado) {
            for (Map.Entry<Day, List<AsignacionSala>> entry : horarioOcupado.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return copy;
    }

    private synchronized void cleanup() {
        try {
            if (isRegistered) {
                DFAgentDescription dfd = new DFAgentDescription();
                dfd.setName(getAID());
                DFAgentDescription[] result = DFService.search(this, dfd);

                if (result != null && result.length > 0) {
                    DFService.deregister(this);
                    System.out.println("Sala " + codigo + " eliminada del DF");
                }
                isRegistered = false;
            }

            System.out.println("Guardando estado final de sala " + codigo);

        } catch (Exception e) {
            System.err.println("Error durante cleanup de sala " + codigo + ": " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        cleanup();
        System.out.println("Sala " + codigo + " finalizada");
    }
}