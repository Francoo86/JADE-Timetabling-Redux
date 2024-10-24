import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import jade.core.behaviours.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;
import java.util.*;

public class Aplicacion extends Agent {
    private int profesorActual = 0;
    private List<AgentController> profesoresControllers = new ArrayList<>();
    private Map<String, AgentController> salasControllers = new HashMap<>();
    private static final long TIEMPO_ESPERA = 360000; // 6 minutos
    private static final long TIEMPO_ENTRE_PROFESORES = 3000; // 3 segundos

    protected void setup() {
        System.out.println("Agente Aplicacion iniciado");

        try {
            // Aumentar límite de resultados del DF
            System.setProperty("jade_domain_df_maxresult", "-1");

            // Cargar archivos JSON
            JSONArray profesoresJson = JSONHelper.parseAsArray("profesores.json");
            JSONArray salasJson = JSONHelper.parseAsArray("salas.json");

            // Crear e iniciar agentes sala primero
            System.out.println("Iniciando creación de agentes sala...");
            salasControllers = createSalaAgents(getContainerController(), salasJson);

            // Esperar a que las salas estén inicializadas
            Thread.sleep(2000);

            // Configurar total de solicitudes para las salas
            int totalAsignaturas = calculateTotalAsignaturas(profesoresJson);
            setTotalSolicitudesForSalas(totalAsignaturas);

            // Crear agentes profesor (sin iniciarlos)
            System.out.println("Creando agentes profesor...");
            profesoresControllers = createProfesorAgents(getContainerController(), profesoresJson);

            // Esperar un momento para asegurar la creación
            Thread.sleep(2000);

            // Iniciar solo el primer profesor
            if (!profesoresControllers.isEmpty()) {
                profesoresControllers.get(0).start();
                System.out.println("Primer profesor iniciado");
            }

            // Agregar comportamiento para monitorear el progreso
            addBehaviour(new MonitorearProgresoComportamiento(this, 5000));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Map<String, AgentController> createSalaAgents(AgentContainer container, JSONArray salasJson)
            throws StaleProxyException {
        Map<String, AgentController> controllers = new HashMap<>();

        for (Object obj : salasJson) {
            JSONObject salaJson = (JSONObject) obj;
            String codigo = (String) salaJson.get("Codigo");
            String jsonString = salaJson.toJSONString();

            Object[] salaArgs = {jsonString};
            AgentController sala = container.createNewAgent(
                    "Sala" + codigo,
                    "AgenteSala",
                    salaArgs
            );
            sala.start();
            controllers.put(codigo, sala);
            System.out.println("Agente Sala " + codigo + " creado e iniciado con JSON: " + jsonString);
        }

        return controllers;
    }

    private List<AgentController> createProfesorAgents(AgentContainer container, JSONArray profesoresJson)
            throws StaleProxyException {
        List<AgentController> controllers = new ArrayList<>();
        int profesorCount = 0;

        for (Object obj : profesoresJson) {
            JSONObject profesorJson = (JSONObject) obj;
            String nombre = (String) profesorJson.get("Nombre");

            JSONObject profesorCompleto = new JSONObject();
            profesorCompleto.put("Nombre", nombre);
            profesorCompleto.put("RUT", profesorJson.get("RUT"));
            profesorCompleto.put("Asignaturas", profesorJson.get("Asignaturas"));

            String jsonString = profesorCompleto.toJSONString();
            Object[] profesorArgs = {jsonString, profesorCount};

            AgentController profesor = container.createNewAgent(
                    "Profesor" + (++profesorCount),
                    "AgenteProfesor",
                    profesorArgs
            );
            controllers.add(profesor);
            System.out.println("Agente Profesor " + nombre + " creado (no iniciado) con JSON: " + jsonString);
        }

        return controllers;
    }

    private int calculateTotalAsignaturas(JSONArray profesoresJson) {
        int total = 0;
        for (Object obj : profesoresJson) {
            JSONObject profesor = (JSONObject) obj;
            JSONArray asignaturas = (JSONArray) profesor.get("Asignaturas");
            total += asignaturas.size();
        }
        System.out.println("Total de asignaturas a asignar: " + total);
        return total;
    }

    private void setTotalSolicitudesForSalas(int totalAsignaturas) {
        for (Map.Entry<String, AgentController> entry : salasControllers.entrySet()) {
            try {
                SalaInterface salaInterface = entry.getValue().getO2AInterface(SalaInterface.class);
                if (salaInterface != null) {
                    salaInterface.setTotalSolicitudes(totalAsignaturas);
                    System.out.println("Total de solicitudes establecido para sala: " + entry.getKey());
                }
            } catch (StaleProxyException e) {
                System.out.println("Error al configurar solicitudes para sala: " + entry.getKey());
                e.printStackTrace();
            }
        }
    }

    private class MonitorearProgresoComportamiento extends TickerBehaviour {
        private int contadorSinCambios = 0;
        private static final int MAX_SIN_CAMBIOS = 30; // Increased timeout
        private Map<Integer, Boolean> profesoresFinalizados = new HashMap<>();
        private long ultimoCambio = System.currentTimeMillis();

        public MonitorearProgresoComportamiento(Agent a, long period) {
            super(a, period);
            this.ultimoCambio = System.currentTimeMillis();
            this.profesoresFinalizados = new HashMap<>();
        }

        protected void onTick() {
            try {
                if (profesorActual < profesoresControllers.size()) {
                    AgentController currentProfesor = profesoresControllers.get(profesorActual);

                    try {
                        int agentState = currentProfesor.getState().getCode();
                        // Check if agent is killed/terminated (State.AGENT_KILLED)
                        if (agentState == 4) { // 4 is the code for AGENT_KILLED
                            if (!profesoresFinalizados.getOrDefault(profesorActual, false)) {
                                System.out.println("Profesor " + profesorActual + " terminó correctamente");
                                profesoresFinalizados.put(profesorActual, true);
                                profesorActual++;
                                ultimoCambio = System.currentTimeMillis();
                                contadorSinCambios = 0;

                                if (profesorActual < profesoresControllers.size()) {
                                    Thread.sleep(TIEMPO_ENTRE_PROFESORES);
                                    profesoresControllers.get(profesorActual).start();
                                    System.out.println("Iniciando profesor " + profesorActual);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("Error verificando estado del profesor " + profesorActual);
                        contadorSinCambios++;
                    }
                }

                // Check for timeout or completion
                if (System.currentTimeMillis() - ultimoCambio > TIEMPO_ESPERA ||
                        contadorSinCambios >= MAX_SIN_CAMBIOS ||
                        profesorActual >= profesoresControllers.size()) {
                    System.out.println("Iniciando finalización del proceso...");
                    finalizarProceso();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void finalizarProceso() {
            try {
                // Force remaining professors to finish
                for (int i = profesorActual; i < profesoresControllers.size(); i++) {
                    try {
                        profesoresControllers.get(i).kill();
                        System.out.println("Profesor " + i + " terminado forzosamente");
                    } catch (Exception e) {
                        System.out.println("Error al terminar profesor " + i);
                    }
                }

                // Generate final JSON files with delay
                Thread.sleep(2000);
                System.out.println("Generando archivos JSON finales...");
                ProfesorHorarioJSON.getInstance().generarArchivoJSON();
                SalaHorarioJSON.getInstance().generarArchivoJSON();

                stop();
                myAgent.doDelete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    protected void takeDown() {
        System.out.println("Aplicación finalizada. Archivos JSON generados.");
    }

    public static void main(String[] args) {
        // Establecer el límite de resultados del DF antes de crear el contenedor
        System.setProperty("jade_domain_df_maxresult", "-1");

        Runtime rt = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, "true");

        AgentContainer mainContainer = rt.createMainContainer(profile);

        try {
            mainContainer.createNewAgent("Aplicacion", "Aplicacion", new Object[]{}).start();
        } catch (StaleProxyException e) {
            e.printStackTrace();
        }
    }
}