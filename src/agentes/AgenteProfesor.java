package agentes;

import constants.BlockOptimization;
import constants.BlockScore;
import constants.Commons;
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
import objetos.AssignationData;
import objetos.Propuesta;
import objetos.BloqueInfo;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

import java.util.*;

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

    @Override
    protected void setup() {
        // Cargar datos del JSON
        Object[] args = getArguments();
        if (args != null && args.length > 1) {
            String jsonString = (String) args[0];
            orden = (Integer) args[1];
            cargarDatos(jsonString);
        }

        horarioOcupado = new HashMap<>();   // Inicializar horario ocupado
        horarioJSON = new JSONObject();     // Inicializar horario JSON
        horarioJSON.put("Asignaturas", new JSONArray());    // Inicializar lista de asignaturas

        // Registrar en DF
        registrarEnDF();

        // Añadir comportamientos de debug
        addBehaviour(new TickerBehaviour(this, 5000) { // Every 5 seconds
            protected void onTick() {
                addBehaviour(new MessageQueueDebugBehaviour());
            }
        });

        // Comportamiento principal
        if (orden == 0) {
            iniciarNegociacion();
        } else {    // Esperar a que el profesor anterior termine
            addBehaviour(new EsperarTurnoBehaviour());
        }

        bloquesAsignadosPorDia = new HashMap<>();   // Inicializar bloques asignados por día
        for (Day dia : Day.values()) {  // Inicializar días
            bloquesAsignadosPorDia.put(dia, new HashMap<>());   // Inicializar asignaturas por día
        }
        System.out.println("Profesor " + nombre + " (orden " + orden + ") iniciado");
    }

    private void iniciarNegociacion() {
        // Evitar iniciar negociación más de una vez por error
        if (!negociacionIniciada) {
            negociacionIniciada = true;
            System.out.println("Profesor " + nombre + " iniciando proceso de negociación");
            addBehaviour(new NegociarAsignaturasBehaviour());
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
            System.out.println("Profesor " + nombre + " registrado en DF"); 
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

    private class EsperarTurnoBehaviour extends CyclicBehaviour {
        public void action() {
            // Coincidir con el profesor anterior para iniciar negociación
            // En este caso verificamos si el mensaje empieza con "START"
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchContent(Messages.START)
            );

            ACLMessage msg = myAgent.receive(mt);   // Recibir mensaje que coincida con la plantilla
            if (msg != null) {
                String nextOrdenStr = msg.getUserDefinedParameter("nextOrden");   // Obtener orden del siguiente profesor
                int nextOrden = Integer.parseInt(nextOrdenStr); 

                System.out.println("[DEBUG] " + nombre + " received START message. My orden=" +
                        orden + ", nextOrden=" + nextOrden);

                // Unicamente iniciar negociación si el mensaje es para este profesor
                if (nextOrden == orden) {
                    System.out.println("Profesor " + nombre + " (orden " + orden + ") activating on START signal");
                    iniciarNegociacion();
                    myAgent.removeBehaviour(this);  // Remover comportamiento de espera
                } else {
                    System.out.println("[DEBUG] Ignoring START message (not for me)");
                }
            } else {
                block();
                System.out.println("[DEBUG] " + nombre + " (orden=" + orden + ") waiting for START signal");
            }
        }
    }

    //why is this a god object?
    private class NegociarAsignaturasBehaviour extends Behaviour {
        private int step = 0;
        private List<Propuesta> propuestas;
        private boolean finished = false;
        private long tiempoInicio;
        private int intentos = 0;
        private static final int MAX_INTENTOS = 3;
        private int bloquesPendientes = 0;
        //TODO: Cambiar a un nombre más descriptivo
        private final AssignationData assignationData = new AssignationData();

        //Iniciar las negociaciones para la asignatura actual a las salas.
        private void setupNegotiation() {
            Asignatura asignaturaActualObj = asignaturas.get(asignaturaActual);
            bloquesPendientes = asignaturaActualObj.getHoras();
            assignationData.clear();
            System.out.println("Profesor " + nombre + " iniciando negociación para " +
                    asignaturaActualObj.getNombre() + " (" + bloquesPendientes + " horas)");
            solicitarPropuestas();
            propuestas = new ArrayList<>();
            tiempoInicio = System.currentTimeMillis();
            step = 1;
        }

        //Colecciona las propuestas.
        //FIXME: De momento solo considera las PROPOSE.
        private void collectProposals() {
            MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
            );

            ACLMessage reply = myAgent.receive(mt);
            if (reply != null) {
                if (reply.getPerformative() == ACLMessage.PROPOSE) {
                    Propuesta propuesta = Propuesta.parse(reply.getContent());
                    propuesta.setMensaje(reply);
                    propuestas.add(propuesta);
                }
            }

            if (System.currentTimeMillis() - tiempoInicio > TIMEOUT_PROPUESTA) {
                if (!propuestas.isEmpty()) {
                    step = 2;
                } else {
                    manejarTimeoutPropuestas();
                }
            } else {
                block();
            }
        }

        private void evaluateProposals() {
            boolean asignacionExitosa = procesarPropuestas();
            if (asignacionExitosa) {
                if (bloquesPendientes == 0) {
                    intentos = 0;
                    asignaturaActual++;
                    step = 0;
                } else {
                    propuestas.clear();
                    solicitarPropuestas();
                    tiempoInicio = System.currentTimeMillis();
                    step = 1;
                }
            } else {
                manejarFalloPropuesta();
            }
        }

        public void action() {
            switch (step) {
                case 0: // Iniciar negociación, verificar si hay más asignaturas, si no ya damos por terminado.
                    if (asignaturaActual < asignaturas.size()) {
                        setupNegotiation();
                    } else {
                        finished = true;
                    }
                    break;
                case 1: // Recolectar propuestas (si es propone o rechaza)
                    collectProposals();
                    break;

                case 2: // Evaluar propuestas
                    evaluateProposals();
                    break;
            }
        }

        private void solicitarPropuestas() {
            // Solicitar propuestas para la asignatura actual a los agentes de sala
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                //Nos interesa que el servicio sea de tipo sala.
                sd.setType(AgenteSala.SERVICE_NAME);
                template.addServices(sd);
                DFAgentDescription[] result = DFService.search(myAgent, template);

                if(result.length == 0) {
                    System.out.println("No se encontraron salas para la asignatura " + asignaturas.get(asignaturaActual).getNombre());
                    return;
                }

                //Enviar un CFP para todos los agentes de sala
                ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                for (DFAgentDescription dfd : result) {
                    cfp.addReceiver(dfd.getName());
                }

                // Preparar información de la solicitud
                Asignatura asignatura = asignaturas.get(asignaturaActual);  // Obtener asignatura actual
                String solicitudInfo = String.format("%s,%d,%s,%s,%d",  // Información de la solicitud de propuestas
                        asignatura.getNombre(),
                        asignatura.getVacantes(),
                        //Enviar información acerca de la ultima propuesta asignada
                        assignationData.getSalaAsignada(),
                        assignationData.getUltimoDiaAsignado(),
                        assignationData.getUltimoBloqueAsignado());

                cfp.setContent(solicitudInfo);
                cfp.setConversationId("neg-" + nombre + "-" + asignaturaActual + "-" + bloquesPendientes);
                myAgent.send(cfp);

                System.out.println("Profesor " + nombre + " solicitando propuestas para " +
                        asignatura.getNombre() + " (bloques pendientes: " + bloquesPendientes +
                        ", sala previa: " + assignationData.getSalaAsignada() + ")");
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }

        private boolean procesarPropuestas() {
            // Verificar si hay propuestas válidas
            if (propuestas.isEmpty()) return false;

            ordenarPropuestas();    // Ordenar propuestas priorizando

            for (Propuesta propuesta : propuestas) {    // Iterar sobre las propuestas
                if (intentarAsignarPropuesta(propuesta)) {  // Intentar asignar la propuesta
                    return true;
                }
            }

            return false;
        }

        //TODO: Implementar las directrices de asignación de bloques y sortearlas aqui.
        private void ordenarPropuestas() {
            final int currentSubjectIndex = AgenteProfesor.this.asignaturaActual;
            final Asignatura currentSubject = asignaturas.get(currentSubjectIndex);
            final String subjectName = currentSubject.getNombre();
            final String currentCampus = currentSubject.getCampus();
            final int currentNivel = currentSubject.getNivel();

            // Filtrar propuestas inválidas antes de ordenar
            propuestas.removeIf(p -> !esPropuestaValida(p, subjectName));

            // Obtener bloques ya asignados para la asignatura actual
            Map<Day, List<Integer>> bloquesAsignados = new HashMap<>();
            for (Map.Entry<Day, Map<String, List<Integer>>> entry : bloquesAsignadosPorDia.entrySet()) {
                List<Integer> bloques = entry.getValue().getOrDefault(subjectName, new ArrayList<>());
                if (!bloques.isEmpty()) {
                    bloquesAsignados.put(entry.getKey(), new ArrayList<>(bloques));
                }
            }

            propuestas.sort((p1, p2) -> {
                // Primera prioridad: Verificar el límite de bloques por día
                int bloquesP1 = contarBloquesPorDia(p1.getDia(), subjectName);
                int bloquesP2 = contarBloquesPorDia(p2.getDia(), subjectName);

                if (bloquesP1 < 2 && bloquesP2 >= 2) return -1;
                if (bloquesP1 >= 2 && bloquesP2 < 2) return 1;

                // Segunda prioridad: Optimización de bloques
                //FIXME: Realmente esto tiene que ser tan rebuscado?
                BlockScore score1 = BlockOptimization.getInstance().evaluateBlock(
                    currentCampus, currentNivel, p1.getBloque(), p1.getDia(), bloquesAsignados
                );
                BlockScore score2 = BlockOptimization.getInstance().evaluateBlock(
                    currentCampus, currentNivel, p2.getBloque(), p2.getDia(), bloquesAsignados
                );

                if (score1.getScore() != score2.getScore()) {
                    return score2.getScore() - score1.getScore();
                }

                // Tercera prioridad: Gestión de traslados entre campus
                int valorTraslado1 = evaluarTraslado(p1, currentCampus);
                int valorTraslado2 = evaluarTraslado(p2, currentCampus);

                if (valorTraslado1 != valorTraslado2) {
                    return valorTraslado2 - valorTraslado1;
                }

                // Cuarta prioridad: Satisfacción del profesor
                return p2.getSatisfaccion() - p1.getSatisfaccion();
            });
        }

        // Directriz N2 ---------------------------------------------------------------------------------------------
        private boolean esPropuestaValida(Propuesta propuesta, String nombreAsignatura) {
            // Validar el bloque 9 - solo permitir para horas impares restantes
            if (propuesta.getBloque() == Commons.MAX_BLOQUE_DIURNO && bloquesPendientes % 2 == 0) {
                return false;
            }

            // Validar límite de bloques por día
            if (contarBloquesPorDia(propuesta.getDia(), nombreAsignatura) >= 2) {
                return false;
            }

            // Si quedan bloques pares, asegurar que haya consecutividad disponible
            if (bloquesPendientes >= 2 && !hayConsecutividadDisponible(propuesta)) {
                return false;
            }

            return true;
        }

        private boolean hayConsecutividadDisponible(Propuesta propuesta) {
            //String dia = propuesta.getDia();
            Day dia = propuesta.getDia();
            int bloque = propuesta.getBloque();

            // Verificar bloque anterior
            boolean bloqueAnteriorDisponible = bloque > 1 &&
                (!horarioOcupado.containsKey(dia) || !horarioOcupado.get(dia).contains(bloque - 1));

            // Verificar bloque siguiente
            boolean bloqueSiguienteDisponible = bloque < Commons.MAX_BLOQUE_DIURNO &&
                (!horarioOcupado.containsKey(dia) || !horarioOcupado.get(dia).contains(bloque + 1));

            return bloqueAnteriorDisponible || bloqueSiguienteDisponible;
        }

        private int contarBloquesPorDia(Day dia, String nombreAsignatura) {
            Map<String, List<Integer>> asignaturasEnDia = bloquesAsignadosPorDia.getOrDefault(dia, new HashMap<>());
            List<Integer> bloques = asignaturasEnDia.getOrDefault(nombreAsignatura, new ArrayList<>());
            return bloques.size();
        }
        //------------------------------------------------------------------------------------------------------------

        // Directriz N3 ----------------------------------------------------------------------------------------------
        private int evaluarTraslado(Propuesta propuesta, String campusAsignaturaActual) {
            Day dia = propuesta.getDia();
            int bloque = propuesta.getBloque();
            String campusPropuesta = getCampusSala(propuesta.getCodigo());

            if (campusPropuesta.equals(campusAsignaturaActual)) {
                return 100;
            }

            if (hayTrasladoEnDia(dia)) {
                return 0;
            }

            BloqueInfo bloqueAnterior = getBloqueInfo(dia, bloque - 1);
            BloqueInfo bloqueSiguiente = getBloqueInfo(dia, bloque + 1);

            if (bloqueAnterior != null &&
                !bloqueAnterior.getCampus().equals(campusPropuesta) &&
                Math.abs(bloqueAnterior.getBloque() - bloque) == 1) {
                return 25;
            }

            if (bloqueSiguiente != null &&
                !bloqueSiguiente.getCampus().equals(campusPropuesta) &&
                Math.abs(bloqueSiguiente.getBloque() - bloque) == 1) {
                return 25;
            }

            return 75;
        }

        private boolean hayTrasladoEnDia(Day dia) {
            String campusAnterior = null;

            Map<String, List<Integer>> clasesDelDia = bloquesAsignadosPorDia.get(dia);
            if (clasesDelDia == null || clasesDelDia.isEmpty()) {
                return false;
            }

            List<BloqueInfo> bloques = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> entry : clasesDelDia.entrySet()) {
                for (Integer bloque : entry.getValue()) {
                    BloqueInfo info = getBloqueInfo(dia, bloque);
                    if (info != null) {
                        bloques.add(info);
                    }
                }
            }

            Collections.sort(bloques, Comparator.comparingInt(BloqueInfo::getBloque));

            int traslados = 0;
            for (BloqueInfo bloque : bloques) {
                if (campusAnterior != null && !campusAnterior.equals(bloque.getCampus())) {
                    traslados++;
                }
                campusAnterior = bloque.getCampus();
            }

            return traslados > 0;
        }

        private BloqueInfo getBloqueInfo(Day dia, int bloque) {
            Map<String, List<Integer>> clasesDelDia = bloquesAsignadosPorDia.get(dia);
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

        private String getCampusSala(String codigoSala) {
            // Esta información debería venir del sistema, por ahora la hardcodeamos según los datos
            // Se podría mejorar manteniendo un mapa de salas-campus
            // FIXME: Esto se hace con los datos de la guía academica.
            if (codigoSala.startsWith("A")) {
                return "Playa Brava";
            } else if (codigoSala.startsWith("B")) {
                return "Huayquique";
            }
            return "";
        }
        //------------------------------------------------------------------------------------------------------------

        //TODO: Mantener simple
        private boolean intentarAsignarPropuesta(Propuesta propuesta) {
            //String dia = propuesta.getDia();
            Day dia = propuesta.getDia();
            int bloque = propuesta.getBloque();

            // Verificar si el bloque está libre
            if (!horarioOcupado.containsKey(dia) ||
                !horarioOcupado.get(dia).contains(bloque)) {

                // Intentar enviar la aceptación
                if (enviarAceptacionPropuesta(propuesta)) {
                    // Si se acepta, actualizar los registros
                    actualizarRegistrosAsignacion(propuesta);
                    return true;
                }
            }

            return false;
        }

        private void actualizarRegistrosAsignacion(Propuesta prop) {
            // Actualizar registros de asignación de bloques y salas en el horario y JSON
            String nombreAsignatura = asignaturas.get(asignaturaActual).getNombre();

            //obtener los datos de la propuesta
            Day dia = prop.getDia();
            int bloque = prop.getBloque();
            String sala = prop.getCodigo();
            int satisfaccion = prop.getSatisfaccion();

            // Actualizar horario ocupado
            horarioOcupado.computeIfAbsent(dia, k -> new HashSet<>()).add(bloque);

            // Actualizar bloques por día
            bloquesAsignadosPorDia.computeIfAbsent(dia, k -> new HashMap<>())
                .computeIfAbsent(nombreAsignatura, k -> new ArrayList<>())
                .add(bloque);

            // Actualizar estado de negociación
            bloquesPendientes--;

            assignationData.assign(dia, sala, bloque);

            // Actualizar JSON
            actualizarHorarioJSON(dia, sala, bloque, satisfaccion);

            System.out.printf("Profesor %s: Asignado bloque %d del día %s en sala %s para %s " +
                    "(quedan %d horas)%n", nombre, bloque, dia, sala, nombreAsignatura, bloquesPendientes);
        }

        private boolean enviarAceptacionPropuesta(Propuesta propuesta) {
            try {
                // Enviar aceptación de propuesta al agente de sala
                ACLMessage accept = propuesta.getMensaje().createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(String.format("%s,%d,%s,%d,%s,%d",
                        propuesta.getDia(),
                        propuesta.getBloque(),
                        asignaturas.get(asignaturaActual).getNombre(),
                        propuesta.getSatisfaccion(),
                        propuesta.getCodigo(),
                        asignaturas.get(asignaturaActual).getVacantes()));
                myAgent.send(accept);

                // Esperar confirmación de la asignación
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchSender(propuesta.getMensaje().getSender()),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                );
                ACLMessage confirm = myAgent.blockingReceive(mt, 5000);
                return confirm != null;
            } catch (Exception e) {
                System.err.println("Error enviando aceptación: " + e.getMessage());
                return false;
            }
        }

        private void manejarTimeoutPropuestas() {
            // En caso de timeout al recibir propuestas, reintentar o pasar a la siguiente asignatura
            intentos++;
            if (intentos >= MAX_INTENTOS) {
                if (bloquesPendientes == asignaturas.get(asignaturaActual).getHoras()) {
                    // Si no hemos podido asignar ningún bloque, pasar a la siguiente asignatura
                    System.out.println("Profesor " + nombre + " no pudo obtener propuestas para " +
                            asignaturas.get(asignaturaActual).getNombre() +
                            " después de " + MAX_INTENTOS + " intentos");
                    asignaturaActual++;
                } else {
                    // Si ya asignamos algunos bloques pero no todos, intentar con otra sala
                    System.out.println("Profesor " + nombre + " buscando nueva sala para bloques restantes de " +
                            asignaturas.get(asignaturaActual).getNombre());
                    assignationData.setSalaAsignada(null);
                }
                intentos = 0;
                step = 0;
            } else {
                System.out.println("Profesor " + nombre + ": Reintentando solicitud de propuestas. " +
                        "Intento " + (intentos + 1) + " de " + MAX_INTENTOS);
                solicitarPropuestas();
                tiempoInicio = System.currentTimeMillis();
            }
        }

        private void manejarFalloPropuesta() {
            // En caso de no poder asignar ninguna propuesta, reintentar o pasar a la siguiente asignatura
            intentos++;
            if (intentos >= MAX_INTENTOS) {
                if (assignationData.hasSalaAsignada()) {
                    // Intentar con otra sala si la actual no tiene más espacios disponibles
                    System.out.println("Profesor " + nombre + ": Buscando otra sala para los bloques restantes");
                    assignationData.setSalaAsignada(null);
                } else {
                    // Si ya probamos con todas las salas, pasar a la siguiente asignatura
                    System.out.println("Profesor " + nombre + " no pudo completar la asignación de " +
                            asignaturas.get(asignaturaActual).getNombre());
                    asignaturaActual++;
                }
                //resetear los intentos, ya que se intentará con otra sala
                intentos = 0;
                step = 0;
            } else {
                propuestas.clear();
                solicitarPropuestas();
                tiempoInicio = System.currentTimeMillis();
                step = 1;
            }
        }

        public boolean done() {
            // Verificar si el proceso de negociación ha finalizado
            if (finished) {
                System.out.println("Profesor " + nombre + " completó proceso de negociación");
                finalizarNegociaciones();
            }
            return finished;
        }
    }

    private void actualizarHorarioJSON(Day dia, String sala, int bloque, int satisfaccion) {
        // Actualizar JSON con la asignatura actual y sus datos
        JSONObject asignatura = new JSONObject();
        asignatura.put("Nombre", asignaturas.get(asignaturaActual).getNombre());
        asignatura.put("Sala", sala);
        asignatura.put("Bloque", bloque);
        asignatura.put("Dia", dia.getDisplayName());
        asignatura.put("Satisfaccion", satisfaccion);
        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignatura);

        System.out.println("Profesor " + nombre + ": Asignada " +
                asignaturas.get(asignaturaActual).getNombre() +
                " en sala " + sala + ", día " + dia + ", bloque " + bloque);
    }

    private void finalizarNegociaciones() {
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

            System.out.println("[DEBUG] Current profesor: orden=" + orden +
                    ", localName=" + getAID().getLocalName());

            // Buscar el siguiente profesor en la lista
            for (DFAgentDescription dfd : result) {     // Iterar sobre los profesores
                String targetName = dfd.getName().getLocalName();   // Nombre del profesor
                System.out.println("[DEBUG] Found professor: " + targetName);

                // Enviar mensaje de inicio al siguiente profesor en la lista (si no es este mismo)
                if(targetName.equals(getAID().getLocalName())) {
                    continue;
                }

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(dfd.getName());
                msg.setContent(Messages.START);
                msg.addUserDefinedParameter("nextOrden", Integer.toString(orden + 1));
                send(msg);
                System.out.println("[DEBUG] Sent START message to: " + targetName);
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