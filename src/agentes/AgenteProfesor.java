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

    //private Map<String, Integer> subjectInstanceHours = new HashMap<>();
    private int currentInstanceIndex = 0;

    public String getCurrentSubjectKey() {
        Asignatura current = getCurrentSubject();
        return String.format("%s-%d", current.getNombre(), currentInstanceIndex);
    }

    public void moveToNextSubject() {
        String currentName = getCurrentSubject().getNombre();
        String currentCode = getCurrentSubject().getCodigoAsignatura();
        asignaturaActual++;

        // Reset instance counter when moving to different subject
        if (asignaturaActual < asignaturas.size() &&
                !(asignaturas.get(asignaturaActual).getNombre().equals(currentName) &&
                        asignaturas.get(asignaturaActual).getCodigoAsignatura().equals(currentCode))) {
            currentInstanceIndex = 0;
        } else {
            currentInstanceIndex++;
        }
    }

    // Helper method to get required hours for current subject
    public int getCurrentSubjectRequiredHours() {
        return getCurrentSubject().getHoras();
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

    private String getCurrentInstanceKey() {
        Asignatura current = getCurrentSubject();
        return String.format("%s-%s-%d",
                current.getNombre(),
                current.getCodigoAsignatura(),
                currentInstanceIndex);  // Instance index
    }

    //TODO: Refactorizar esto, ya que se ve bien feo
    public void updateScheduleInfo(Day dia, String sala, int bloque, String nombreAsignatura, int satisfaccion) {
        String currentInstanceKey = getCurrentInstanceKey();

        // Update horario ocupado
        horarioOcupado.computeIfAbsent(dia, k -> new HashSet<>()).add(bloque);

        // Update bloques por día with instance information
        bloquesAsignadosPorDia.computeIfAbsent(dia, k -> new HashMap<>())
                .computeIfAbsent(currentInstanceKey, k -> new ArrayList<>())
                .add(bloque);

        actualizarHorarioJSON(dia, sala, bloque, satisfaccion);//, currentInstanceKey);
    }

    private Map<String, Integer> requiredHoursPerInstance;
    private Map<String, String> subjectInstanceKeys; // Track which instance a subject belongs to
    private int instanceCounter = 0;

    private String generateInstanceKey(String nombre, String codigo) {
        return String.format("%s-%s-%d", nombre, codigo, instanceCounter++);
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

        // Initialize required hours per subject
        requiredHoursPerInstance = new HashMap<>();
        subjectInstanceKeys = new HashMap<>();

        for (Asignatura asig : asignaturas) {
            String instanceKey = generateInstanceKey(asig.getNombre(), asig.getCodigoAsignatura());
            requiredHoursPerInstance.put(instanceKey, asig.getHoras());
            subjectInstanceKeys.put(instanceKey, asig.getNombre());
        }

        // Register in DF
        registrarEnDF();

        // Create shared proposal queue and behaviors
        ConcurrentLinkedQueue<Propuesta> propuestas = new ConcurrentLinkedQueue<>();
        NegotiationStateBehaviour stateBehaviour = new NegotiationStateBehaviour(this, 1000, propuestas);
        MessageCollectorBehaviour messageCollector = new MessageCollectorBehaviour(this, propuestas, stateBehaviour);

        if (orden == 0) {
            addBehaviour(stateBehaviour);
            addBehaviour(messageCollector);
        } else {
            addBehaviour(new EsperarTurnoBehaviour(this, stateBehaviour, messageCollector));
        }
    }

    private Map<String, Integer> subjectCompletedHours = new HashMap<>();

    public String getSubjectKey(Asignatura subject) {
        return subject.getNombre() + "-" + subject.getCodigoAsignatura();
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
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(SERVICE_NAME);
            sd.setName(AGENT_NAME + orden);

            // Properly create and add the order property
            Property ordenProp = new Property();
            ordenProp.setName("orden");
            ordenProp.setValue(orden);
            sd.addProperties(ordenProp);

            dfd.addServices(sd);
            DFService.register(this, dfd);
            isRegistered = true;
            System.out.println("Professor " + nombre + " registered with order " + orden);
        } catch (FIPAException fe) {
            System.err.println("Error registering professor " + nombre + " in DF: " + fe.getMessage());
            fe.printStackTrace();
        }
    }

    private String sanitizeSubjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
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
                    System.out.println("Professor " + profesor.getNombre() +
                            " received START signal. My order=" + profesor.getOrden() +
                            ", requested order=" + nextOrden);

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
        // Get current subject
        Asignatura currentSubject = asignaturas.get(asignaturaActual);

        JSONObject asignatura = new JSONObject();
        asignatura.put("Nombre", currentSubject.getNombre());
        asignatura.put("Sala", sala);
        asignatura.put("Bloque", bloque);
        asignatura.put("Dia", dia.getDisplayName());
        asignatura.put("Satisfaccion", satisfaccion);
        // Add these to track instances
        asignatura.put("CodigoAsignatura", currentSubject.getCodigoAsignatura());
        asignatura.put("Instance", currentInstanceIndex); // or currentInstanceIndex if you have it

        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignatura);
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
                    nombre, horarioJSON, asignaturas);

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
        try {
            int nextOrden = orden + 1;
            System.out.println("Current professor: " + nombre + " (order=" + orden +
                    ") looking for next professor (order=" + nextOrden + ")");

            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(SERVICE_NAME);

            // Create proper property for search
            Property ordenProp = new Property();
            ordenProp.setName("orden");
            ordenProp.setValue(nextOrden);
            sd.addProperties(ordenProp);

            template.addServices(sd);

            // Add retry logic for DF search
            DFAgentDescription[] result = null;
            int retries = 3;
            while (retries > 0 && (result == null || result.length == 0)) {
                result = DFService.search(this, template);
                if (result.length == 0) {
                    System.out.println("Retry " + retries + ": No professors found, waiting...");
                    Thread.sleep(1000);
                    retries--;
                }
            }

            if (result.length == 0) {
                System.out.println("Warning: No next professor found after retries");
                return;
            }

            // Find the correct next professor
            for (DFAgentDescription dfd : result) {
                ServiceDescription service = (ServiceDescription) dfd.getAllServices().next();
                Iterator props = service.getAllProperties();
                while (props.hasNext()) {
                    Property prop = (Property) props.next();
                    if ("orden".equals(prop.getName())) {
                        int foundOrder = Integer.parseInt(prop.getValue().toString());
                        if (foundOrder == nextOrden) {
                            notifyNextProfessor(dfd, nextOrden);
                            return;
                        }
                    }
                }
            }

            System.out.println("Warning: Could not find professor with order " + nextOrden);

        } catch (Exception e) {
            System.err.println("Error notifying next professor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void notifyNextProfessor(DFAgentDescription dfd, int nextOrden) {
        try {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(dfd.getName());
            msg.setContent(Messages.START);
            msg.addUserDefinedParameter("nextOrden", Integer.toString(nextOrden));
            send(msg);
            System.out.println("Successfully notified next professor " +
                    dfd.getName().getLocalName() + " with order: " + nextOrden);
        } catch (Exception e) {
            System.err.println("Error sending notification: " + e.getMessage());
            e.printStackTrace();
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
        // Get actual completion numbers
        Map<String, Integer> assignedHours = new HashMap<>();
        JSONArray asignaturas = (JSONArray) horarioJSON.get("Asignaturas");

        // Count assigned hours per instance
        for (Object obj : asignaturas) {
            JSONObject asignatura = (JSONObject) obj;
            String nombreAsig = (String) asignatura.get("Nombre");
            String codigo = (String) asignatura.get("CodigoAsignatura");
            int instance = ((Number) asignatura.get("Instance")).intValue();
            String instanceKey = String.format("%s-%s-%d", nombreAsig, codigo, instance);
            assignedHours.merge(instanceKey, 1, Integer::sum);
        }

        // Count completed subjects
        int completedCount = 0;
        int totalSubjects = 0;
        int totalRequiredHours = 0;

        for (Asignatura asig : this.asignaturas) {
            totalSubjects++;
            totalRequiredHours += asig.getHoras();

            String instanceKey = String.format("%s-%s-%d",
                    asig.getNombre(),
                    asig.getCodigoAsignatura(),
                    totalSubjects - 1);

            if (assignedHours.getOrDefault(instanceKey, 0) >= asig.getHoras()) {
                completedCount++;
            }
        }

        System.out.printf("Profesor %s finalizado con %d/%d asignaturas completas (requirió %d horas totales)%n",
                nombre,
                completedCount,
                totalSubjects,
                totalRequiredHours);
    }
}