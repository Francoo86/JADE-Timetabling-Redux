package agentes;

import behaviours.MessageCollectorBehaviour;
import behaviours.NegotiationStateBehaviour;
import constants.Messages;
import constants.enums.Day;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import json_stuff.ProfesorHorarioJSON;
import objetos.Asignatura;
import objetos.BloqueInfo;
import objetos.Propuesta;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AgenteProfesor extends Agent {
    public static final String AGENT_NAME = "Profesor";
    public static final String SERVICE_NAME = AGENT_NAME.toLowerCase(Locale.ROOT);
    private String nombre;
    //private String rut;
    private int turno;
    private List<Asignatura> asignaturas;
    private int asignaturaActual = 0;
    private Map<Day, Set<Integer>> horarioOcupado; // dia -> bloques
    private int orden;
    private JSONObject horarioJSON;
    private static final long TIMEOUT_PROPUESTA = 5000; // 5 segundos para recibir propuestas
    private boolean isRegistered = false;
    private boolean isCleaningUp = false;
    private boolean negociacionIniciada = false;
    //TODO: Cambiar el mapeo de string a int porque los días son del 0-6 (asumiendo que el lunes es 0).
    //TODO-2: Pienso que puede ser mejor tener un objeto que contenga la información de los bloques asignados.
    private Map<Day, Map<String, List<Integer>>> bloquesAsignadosPorDia; // dia -> (bloque -> asignatura)

    //METODOS EXPUESTOS PARA EL BEHAVIOUR
    @Override
    public String toString() {
        return nombre;
    }

    public boolean canUseMoreSubjects() {
        return asignaturaActual < asignaturas.size();
    }

    public int getOrden() {
        return orden;
    }

    public Asignatura getCurrentSubject() {
        return asignaturas.get(asignaturaActual);
    }

    public int getCurrentSubjectIndex() {
        return asignaturaActual;
    }

    public void moveToNextSubject() {
        asignaturaActual++;
    }

    public boolean isBlockAvailable(Day dia, int bloque) {
        return !horarioOcupado.containsKey(dia) || !horarioOcupado.get(dia).contains(bloque);
    }

    public String getNombre() {
        return nombre;
    }

    public Map<String, List<Integer>> getBlocksByDay(Day dia) {
        return bloquesAsignadosPorDia.getOrDefault(dia, new HashMap<>());
    }

    public Map<Day, List<Integer>> getBlocksBySubject(String nombreAsignatura) {
        Map<Day, List<Integer>> bloquesAsignados = new HashMap<>();
        for (Map.Entry<Day, Map<String, List<Integer>>> entry : bloquesAsignadosPorDia.entrySet()) {
            List<Integer> bloques = entry.getValue().getOrDefault(nombreAsignatura, new ArrayList<>());
            if (!bloques.isEmpty()) {
                bloquesAsignados.put(entry.getKey(), new ArrayList<>(bloques));
            }
        }
        return bloquesAsignados;
    }

    public BloqueInfo getBloqueInfo(Day dia, int bloque) {
        Map<String, List<Integer>> clasesDelDia = getBlocksByDay(dia);
        if(clasesDelDia == null) {
            return null;
        }

        for (Map.Entry<String, List<Integer>> entry : clasesDelDia.entrySet()) {
            //si no hay bloque asociado a la asignatura, pasar de largo
            if(!entry.getValue().contains(bloque)) {
                continue;
            }
            // Buscar el campus de la asignatura
            for (Asignatura asig : asignaturas) {
                if (asig.getNombre().equals(entry.getKey())) {
                    return new BloqueInfo(asig.getCampus(), bloque);
                }
            }
        }

        //agregar esto mientras refactorizo lo otro
        return null;
    }

    //TODO: Refactorizar esto, ya que se ve bien feo
    public void updateScheduleInfo(Day dia, String sala, int bloque, String nombreAsignatura, int satisfaccion) {
        // Actualizar horario ocupado
        horarioOcupado.computeIfAbsent(dia, k -> new HashSet<>()).add(bloque);

        // Actualizar bloques por día
        bloquesAsignadosPorDia.computeIfAbsent(dia, k -> new HashMap<>())
                .computeIfAbsent(nombreAsignatura, k -> new ArrayList<>())
                .add(bloque);

        actualizarHorarioJSON(dia, sala, bloque, satisfaccion);
    }

    @Override
    protected void setup() {
        // Load data from JSON
        Object[] args = getArguments();
        if (args != null && args.length > 1) {
            String jsonString = (String) args[0];
            orden = (Integer) args[1];
            cargarDatos(jsonString);
        }

        // Initialize data structures
        initializeDataStructures();

        // Register in DF
        registrarEnDF();

        // Create shared proposal queue and behaviors
        ConcurrentLinkedQueue<Propuesta> propuestas = new ConcurrentLinkedQueue<>();
        NegotiationStateBehaviour stateBehaviour = new NegotiationStateBehaviour(this, 1000, propuestas);
        MessageCollectorBehaviour messageCollector = new MessageCollectorBehaviour(this, propuestas, stateBehaviour);

        // Add debug behavior
        /*
        addBehaviour(new TickerBehaviour(this, 5000) {
            protected void onTick() {
                addBehaviour(new MessageQueueDebugBehaviour());
            }
        });*/

        //System.out.println("Profesor " + nombre + " iniciando con " + asignaturas.size() + " asignaturas");

        // Add negotiation behaviors based on order
        if (orden == 0) {
            //System.out.println("Profesor " + nombre + " iniciando negociación inmediatamente");
            addBehaviour(stateBehaviour);
            addBehaviour(messageCollector);
        } else {
            //System.out.println("Profesor " + nombre + " esperando su turno");
            addBehaviour(new EsperarTurnoBehaviour(this, stateBehaviour, messageCollector));
        }
    }

    private void initializeDataStructures() {
        // Initialize schedule tracking
        horarioOcupado = new HashMap<>();

        // Initialize JSON structures
        horarioJSON = new JSONObject();
        horarioJSON.put("Asignaturas", new JSONArray());

        // Initialize daily block assignments
        bloquesAsignadosPorDia = new HashMap<>();
        for (Day dia : Day.values()) {
            bloquesAsignadosPorDia.put(dia, new HashMap<>());
        }
    }

    private void registrarEnDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());      // AID del agente
            ServiceDescription sd = new ServiceDescription();
            sd.setType(SERVICE_NAME);   // Tipo de servicio
            sd.setName(AGENT_NAME + orden);
            //Esto se pasa en el mensaje del CFP.
            //Confirmar bien...
            sd.addProperties(new Property("orden", orden));
            dfd.addServices(sd);
            DFService.register(this, dfd);
            isRegistered = true;        // Marcar como registrado   
            //System.out.println("Profesor " + nombre + " registrado en DF");
        } catch (FIPAException fe) {
            System.err.println("Error registrando profesor " + nombre + " en DF: " + fe.getMessage());
            fe.printStackTrace();
        }
    }

    private void cargarDatos(String jsonString) {
        // Parsear JSON y cargar datos del profesor y asignaturas en listas de objetos
        try {
            // Cargar datos del profesor
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            //rut = (String) jsonObject.get("RUT");
            nombre = (String) jsonObject.get("Nombre");
            //quiero creer que este es el orden (?)
            turno = ((Number) jsonObject.get("Turno")).intValue();

            // Cargar asignaturas
            asignaturas = new ArrayList<>();
            JSONArray asignaturasJson = (JSONArray) jsonObject.get("Asignaturas");
            for (Object obj : asignaturasJson) {
                JSONObject asignaturaJson = (JSONObject) obj;
                Asignatura parsedSubject = Asignatura.fromJson(asignaturaJson);
                asignaturas.add(parsedSubject);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public class EsperarTurnoBehaviour extends CyclicBehaviour {
        private final AgenteProfesor profesor;
        private final NegotiationStateBehaviour stateBehaviour;
        private final MessageCollectorBehaviour messageCollector;

        public EsperarTurnoBehaviour(AgenteProfesor profesor,
                                     NegotiationStateBehaviour stateBehaviour,
                                     MessageCollectorBehaviour messageCollector) {
            super(profesor);
            this.profesor = profesor;
            this.stateBehaviour = stateBehaviour;
            this.messageCollector = messageCollector;
        }

        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchContent(Messages.START)
            );

            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                String nextOrdenStr = msg.getUserDefinedParameter("nextOrden");
                int nextOrden = Integer.parseInt(nextOrdenStr);

                if (nextOrden == profesor.getOrden()) {
                    //System.out.println("Profesor " + profesor.getNombre() +
                    //        " (orden " + profesor.getOrden() + ") activating on START signal");

                    // Add negotiation behaviors when it's our turn
                    myAgent.addBehaviour(stateBehaviour);
                    myAgent.addBehaviour(messageCollector);

                    // Remove this waiting behavior
                    myAgent.removeBehaviour(this);
                }
            } else {
                block();
            }
        }
    }

    //why is this a god object?

    public void actualizarHorarioJSON(Day dia, String sala, int bloque, int satisfaccion) {
        // Actualizar JSON con la asignatura actual y sus datos
        JSONObject asignatura = new JSONObject();
        asignatura.put("Nombre", asignaturas.get(asignaturaActual).getNombre());
        asignatura.put("Sala", sala);
        asignatura.put("Bloque", bloque);
        asignatura.put("Dia", dia.getDisplayName());
        asignatura.put("Satisfaccion", satisfaccion);
        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignatura);

        /*
        System.out.println("Profesor " + nombre + ": Asignada " +
                asignaturas.get(asignaturaActual).getNombre() +
                " en sala " + sala + ", día " + dia + ", bloque " + bloque);*/
    }

    public void finalizarNegociaciones() {
        // Finalizar negociaciones y limpiar
        try {
            if (isCleaningUp) {
                return;
            }
            isCleaningUp = true;

            // Guardar horario final
            ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(
                    nombre, horarioJSON, asignaturas.size());

            // Notificar al siguiente profesor antes de hacer cleanup
            notificarSiguienteProfesor();

            // Realizar cleanup y terminar
            cleanup();
        } catch (Exception e) {
            System.err.println("Error finalizando negociaciones para profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void notificarSiguienteProfesor() {
        // Notificar al siguiente profesor para que inicie su proceso de negociación
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(SERVICE_NAME);
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(this, template); // Buscar profesores

            //System.out.println("[DEBUG] Current profesor: orden=" + orden +
            //        ", localName=" + getAID().getLocalName());

            // Buscar el siguiente profesor en la lista
            for (DFAgentDescription dfd : result) {     // Iterar sobre los profesores
                String targetName = dfd.getName().getLocalName();   // Nombre del profesor
                //System.out.println("[DEBUG] Found professor: " + targetName);

                // Enviar mensaje de inicio al siguiente profesor en la lista (si no es este mismo)
                if(targetName.equals(getAID().getLocalName())) {
                    continue;
                }

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(dfd.getName());
                msg.setContent(Messages.START);
                msg.addUserDefinedParameter("nextOrden", Integer.toString(orden + 1));
                send(msg);
                //System.out.println("[DEBUG] Sent START message to: " + targetName);
            }
        } catch (Exception e) {
            System.err.println("Error notificando siguiente profesor: " + e.getMessage());
        }
    }

    private synchronized void cleanup() {
        // Limpiar y terminar el agente
        try {
            if (isRegistered) {
                // Verificar si aún estamos registrados antes de hacer deregister
                DFAgentDescription dfd = new DFAgentDescription(); 
                dfd.setName(getAID());
                DFAgentDescription[] result = DFService.search(this, dfd);

                if (result != null && result.length > 0) {      // Si aún estamos registrados en DF
                    DFService.deregister(this);    // Deregistrar agente
                    System.out.println("Profesor " + nombre + " eliminado del DF");
                }
                isRegistered = false;
            }

            // Esperar un momento para asegurar que la notificación se envio
            Thread.sleep(1000);
            doDelete();
        } catch (Exception e) {
            System.err.println("Error durante cleanup de profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
        }

        isCleaningUp = false;
    }

    @Override
    protected void takeDown() {
        //mostrar resumen final por profesor...
        int asignadas = ((JSONArray) horarioJSON.get("Asignaturas")).size();
        System.out.println("Profesor " + nombre + " finalizado con " +
                asignadas + "/" + asignaturas.size() + " asignaturas asignadas");
    }

    //FINES DE DEBUG!!!
    private class MessageQueueDebugBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("\n=== DEBUG: Message Queue Status for " + myAgent.getLocalName() + " ===");
            System.out.println("Queue Size: " + myAgent.getCurQueueSize());

            // Create a template that matches ALL messages
            MessageTemplate mt = MessageTemplate.MatchAll();

            // Store messages to put them back later
            List<ACLMessage> messages = new ArrayList<>();

            // Retrieve and inspect all messages
            ACLMessage msg;
            int msgCount = 0;
            while ((msg = myAgent.receive(mt)) != null) {
                msgCount++;
                System.out.println("\nMessage #" + msgCount + ":");
                System.out.println("  Performative: " + ACLMessage.getPerformative(msg.getPerformative()));
                System.out.println("  Sender: " + (msg.getSender() != null ? msg.getSender().getLocalName() : "null"));
                System.out.println("  Receiver(s):");
                for (jade.util.leap.Iterator it = msg.getAllReceiver(); it.hasNext(); ) {
                    AID receiver = (AID) it.next();
                    System.out.println("    - " + receiver.getLocalName());
                }
                System.out.println("  Content: " + msg.getContent());
                System.out.println("  Conversation ID: " + msg.getConversationId());
                System.out.println("  Protocol: " + msg.getProtocol());
                System.out.println("  Reply-To:");
                Iterator<?> replyTo = msg.getAllReplyTo();
                while (replyTo.hasNext()) {
                    AID aid = (AID) replyTo.next();
                    System.out.println("    - " + aid.getLocalName());
                }
                System.out.println("  Reply-By: " + msg.getReplyByDate());
                System.out.println("  Language: " + msg.getLanguage());
                System.out.println("  Ontology: " + msg.getOntology());
                System.out.println("  User-Defined Parameters:");
                for (Object param : msg.getAllUserDefinedParameters().keySet()) {
                    System.out.println("    - " + param + ": " + msg.getUserDefinedParameter((String) param));
                }

                // Store the message to put it back in queue
                messages.add(msg);
            }

            // Put all messages back in the queue
            for (ACLMessage m : messages) {
                myAgent.postMessage(m);
            }

            System.out.println("\nTotal messages inspected: " + msgCount);
            System.out.println("=== End Message Queue Debug ===\n");
        }
    }

}