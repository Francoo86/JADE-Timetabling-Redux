import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.util.*;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private List<Asignatura> asignaturas;
    private JSONObject horarioJSON;
    private int asignaturaActual = 0;
    private Map<String, Set<String>> horarioOcupado;
    private int orden;
    private static final long TIMEOUT_RESPUESTA = 300000; // 5 minutos

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 1) {
            String jsonString = (String) args[0];
            orden = (Integer) args[1];
            loadFromJsonString(jsonString);
        }

        horarioJSON = new JSONObject();
        horarioJSON.put("Asignaturas", new JSONArray());
        horarioOcupado = new HashMap<>();

        System.out.println("Agente Profesor " + nombre + " creado. Orden: " + orden +
                ". Asignaturas totales: " + asignaturas.size());

        // Registrar en páginas amarillas con el orden como parte del servicio
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("profesor");
        sd.setName("Profesor" + orden);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        if (orden == 0) {
            iniciarNegociaciones();
        } else {
            esperarTurno();
        }
    }

    private void loadFromJsonString(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            nombre = (String) jsonObject.get("Nombre");
            rut = (String) jsonObject.get("RUT");

            asignaturas = new ArrayList<>();
            JSONArray asignaturasJson = (JSONArray) jsonObject.get("Asignaturas");

            if (asignaturasJson != null) {
                for (Object asignaturaObj : asignaturasJson) {
                    JSONObject asignaturaJson = (JSONObject) asignaturaObj;
                    if (asignaturaJson != null) {
                        String nombreAsignatura = (String) asignaturaJson.get("Nombre");
                        int horas = getIntValue(asignaturaJson, "Horas", 0);
                        int vacantes = getIntValue(asignaturaJson, "Vacantes", 0);
                        asignaturas.add(new Asignatura(nombreAsignatura, 0, 0, horas, vacantes));
                    }
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private int getIntValue(JSONObject json, String key, int defaultValue) {
        Object value = json.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private void esperarTurno() {
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null && msg.getContent().equals("START")) {
                    System.out.println("Profesor " + nombre + " (orden " + orden + ") recibió señal de inicio");
                    iniciarNegociaciones();
                    myAgent.removeBehaviour(this);
                } else {
                    block();
                }
            }
        });
    }

    private void iniciarNegociaciones() {
        System.out.println("Profesor " + nombre + " (orden " + orden + ") iniciando negociaciones para " +
                asignaturas.size() + " asignaturas");

        // Asegurar que estamos empezando desde el principio
        asignaturaActual = 0;
        horarioOcupado.clear();

        // Agregar el comportamiento de negociación
        addBehaviour(new NegociarAsignaturasBehaviour());

        // Agregar un comportamiento de timeout por si algo sale mal
        addBehaviour(new WakerBehaviour(this, TIMEOUT_RESPUESTA) {
            protected void onWake() {
                if (asignaturaActual < asignaturas.size()) {
                    System.out.println("Timeout en negociaciones para profesor " + nombre +
                            ". Forzando finalización...");
                    finalizarNegociaciones();
                }
            }
        });
    }

    private class NegociarAsignaturasBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt;
        private List<ACLMessage> propuestas = new ArrayList<>();
        private int respuestasEsperadas = 0;
        private long tiempoInicio;

        public void action() {
            switch (step) {
                case 0:
                    solicitarPropuestas();
                    break;
                case 1:
                    recibirPropuestas();
                    break;
                case 2:
                    evaluarPropuestas();
                    break;
                case 3:
                    finalizarNegociaciones();
                    break;
            }
        }

        private void solicitarPropuestas() {
            System.out.println("Profesor " + nombre + " (orden " + orden + ") solicitando propuestas para: " +
                    asignaturas.get(asignaturaActual).getNombre());

            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("sala");
            template.addServices(sd);

            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                if (result.length > 0) {
                    ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                    for (DFAgentDescription agenteS : result) {
                        msg.addReceiver(agenteS.getName());
                    }
                    msg.setContent(asignaturas.get(asignaturaActual).toString());
                    msg.setConversationId("solicitud-propuesta");
                    msg.setReplyByDate(new Date(System.currentTimeMillis() + TIMEOUT_RESPUESTA));
                    myAgent.send(msg);
                    respuestasEsperadas = result.length;
                    tiempoInicio = System.currentTimeMillis();
                    step = 1;
                    mt = MessageTemplate.and(
                            MessageTemplate.MatchConversationId("solicitud-propuesta"),
                            MessageTemplate.MatchPerformative(ACLMessage.PROPOSE)
                    );
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }

        private void recibirPropuestas() {
            ACLMessage reply = myAgent.receive(mt);
            if (reply != null) {
                propuestas.add(reply);
                System.out.println("Profesor " + nombre + " recibió propuesta " + propuestas.size() +
                        " de " + respuestasEsperadas + " para " +
                        asignaturas.get(asignaturaActual).getNombre());
                if (--respuestasEsperadas == 0 ||
                        (System.currentTimeMillis() - tiempoInicio) >= TIMEOUT_RESPUESTA) {
                    step = 2;
                }
            } else {
                block(1000);
            }
        }

        private void evaluarPropuestas() {
            if (!propuestas.isEmpty()) {
                ACLMessage mejorPropuesta = null;
                int mejorValoracion = -1;

                // Primera pasada: buscar valoración 10 (coincidencia exacta)
                for (ACLMessage propuesta : propuestas) {
                    int valoracion = evaluarPropuesta(propuesta);
                    if (valoracion == 10) {
                        mejorPropuesta = propuesta;
                        mejorValoracion = valoracion;
                        break;
                    }
                }

                // Si no hay valoración 10, buscar la mejor disponible
                if (mejorPropuesta == null) {
                    for (ACLMessage propuesta : propuestas) {
                        int valoracion = evaluarPropuesta(propuesta);
                        if (valoracion > mejorValoracion) {
                            mejorPropuesta = propuesta;
                            mejorValoracion = valoracion;
                        }
                    }
                }

                if (mejorPropuesta != null) {
                    aceptarPropuesta(mejorPropuesta, mejorValoracion);
                    rechazarOtrasPropuestas(mejorPropuesta);
                    System.out.println("Profesor " + nombre + " aceptó propuesta para " +
                            asignaturas.get(asignaturaActual).getNombre() +
                            " con valoración " + mejorValoracion);
                }
            }

            // Preparar siguiente asignatura o finalizar
            asignaturaActual++;
            if (asignaturaActual < asignaturas.size()) {
                step = 0;
                propuestas.clear();
                System.out.println("Profesor " + nombre + " pasando a siguiente asignatura (" +
                        asignaturaActual + "/" + asignaturas.size() + ")");
            } else {
                step = 3;
                System.out.println("Profesor " + nombre + " completó todas sus asignaturas");
            }
        }

        private int evaluarPropuesta(ACLMessage propuesta) {
            String[] partes = propuesta.getContent().split(",");
            String dia = partes[0];
            int bloque = Integer.parseInt(partes[1]);
            int capacidad = Integer.parseInt(partes[3]);
            int vacantes = asignaturas.get(asignaturaActual).getVacantes();

            // Verificar si el horario ya está ocupado
            String key = dia + "-" + bloque;
            if (horarioOcupado.containsKey(key)) {
                return -1;
            }

            // Evaluar según capacidad
            if (capacidad == vacantes) return 10;
            if (capacidad > vacantes) return 5;
            return -1;
        }

        private void aceptarPropuesta(ACLMessage propuesta, int valoracion) {
            ACLMessage accept = propuesta.createReply();
            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            accept.setContent(propuesta.getContent() + "," + valoracion);
            myAgent.send(accept);
            actualizarHorario(propuesta.getContent(), asignaturas.get(asignaturaActual), valoracion);
        }

        private void rechazarOtrasPropuestas(ACLMessage propuestaAceptada) {
            for (ACLMessage propuesta : propuestas) {
                if (!propuesta.equals(propuestaAceptada)) {
                    ACLMessage reject = propuesta.createReply();
                    reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    myAgent.send(reject);
                }
            }
        }

        public boolean done() {
            return step == 3;
        }
    }

    private void actualizarHorario(String propuesta, Asignatura asignatura, int valoracion) {
        String[] partes = propuesta.split(",");
        String dia = partes[0];
        int bloque = Integer.parseInt(partes[1]);
        String sala = partes[2];

        // Crear objeto JSON para la asignatura
        JSONObject asignaturaJSON = new JSONObject();
        asignaturaJSON.put("Nombre", asignatura.getNombre());
        asignaturaJSON.put("Sala", sala);
        asignaturaJSON.put("Bloque", bloque);
        asignaturaJSON.put("Dia", dia);
        asignaturaJSON.put("Satisfaccion", valoracion);

        // Asegurar que el array de asignaturas existe
        if (!horarioJSON.containsKey("Asignaturas")) {
            horarioJSON.put("Asignaturas", new JSONArray());
        }

        // Agregar la asignatura al array
        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignaturaJSON);

        // Actualizar horario ocupado
        String key = dia + "-" + bloque;
        horarioOcupado.computeIfAbsent(key, k -> new HashSet<>()).add(sala);

        System.out.println("Horario actualizado para " + nombre + ": " + asignatura.getNombre() +
                " en sala " + sala + ", día " + dia + ", bloque " + bloque +
                ", satisfacción " + valoracion);
    }

    private void finalizarNegociaciones() {
        System.out.println("Profesor " + nombre + " (orden " + orden + ") iniciando finalización...");

        // Ensure horarioJSON is properly initialized
        if (horarioJSON == null) {
            horarioJSON = new JSONObject();
            horarioJSON.put("Asignaturas", new JSONArray());
        }

        // Add missing fields if any asignatura wasn't processed
        for (int i = asignaturaActual; i < asignaturas.size(); i++) {
            Asignatura asignatura = asignaturas.get(i);
            JSONObject asignaturaJSON = new JSONObject();
            asignaturaJSON.put("Nombre", asignatura.getNombre());
            asignaturaJSON.put("Estado", "No asignada");
            ((JSONArray) horarioJSON.get("Asignaturas")).add(asignaturaJSON);
        }

        // Ensure ProfesorHorarioJSON is updated
        ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(nombre, horarioJSON, asignaturas.size());
        System.out.println("Profesor " + nombre + " guardó su horario con " +
                ((JSONArray)horarioJSON.get("Asignaturas")).size() + " asignaturas");

        // Notify next professor with delay
        notificarSiguienteProfesor();

        try {
            Thread.sleep(2000); // Wait for JSON processing
            DFService.deregister(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        doDelete();
    }

    private void notificarSiguienteProfesor() {
        // Add delay to ensure proper agent registration
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("profesor");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(this, template);
            boolean nextFound = false;

            for (DFAgentDescription agente : result) {
                String nombreAgente = agente.getName().getLocalName();
                int ordenAgente = extraerOrden(nombreAgente);
                if (ordenAgente == orden + 1) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(agente.getName());
                    msg.setContent("START");
                    send(msg);
                    System.out.println("Profesor " + nombre + " notificó exitosamente al siguiente profesor: " + nombreAgente);
                    nextFound = true;
                    break;
                }
            }

            if (!nextFound) {
                System.out.println("Profesor " + nombre + " es el último o no encontró siguiente profesor.");
            }
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    private int extraerOrden(String nombreAgente) {
        try {
            if (nombreAgente.startsWith("Profesor")) {
                return Integer.parseInt(nombreAgente.substring(8));
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return Integer.MAX_VALUE;
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("Profesor " + nombre + " terminando...");

        // Asegurar que el horario se guarde antes de terminar
        if (horarioJSON != null && !((JSONArray)horarioJSON.get("Asignaturas")).isEmpty()) {
            ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(nombre, horarioJSON, asignaturas.size());
        }
    }
}