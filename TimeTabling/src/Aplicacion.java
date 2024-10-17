import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class Aplicacion extends Agent {

    protected void setup() {
        System.out.println("Agente Aplicacion iniciado");

        try {
            // Carga los archivos JSON
            JSONArray profesoresJson = loadJsonArray("profesores.json");
            JSONArray salasJson = loadJsonArray("salas.json");

            // Crea los agentes profesores y guarda sus datos en un mapa
            Map<String, JSONObject> profesoresMap = createProfesorAgents(getContainerController(), profesoresJson);
            // Crea los agentes salas y guarda sus controladores en un mapa
            Map<String, AgentController> salasControllers = createSalaAgents(getContainerController(), salasJson);
            
            // Espera 2 minutos para que los agentes se inicialicen
            Thread.sleep(120000);  // TODO: buena o mala practica?

            int totalAsignaturas = calculateTotalAsignaturas(profesoresMap);    // Calcula el total de asignaturas
            setTotalSolicitudesForSalas(salasControllers, totalAsignaturas);    // Establece el total de solicitudes para las salas
            // TODO: buena o mala practica?

            // Espera a que los agentes completen su trabajo
            System.out.println("Esperando a que los agentes completen su trabajo...");
            Thread.sleep(60000);    // 1 minuto
            // TODO: buena o mala practica?

            // Generate JSON files
            System.out.println("Iniciando generación de archivos JSON...");
            ProfesorHorarioJSON.getInstance().generarArchivoJSON();
            SalaHorarioJSON.getInstance().generarArchivoJSON();
            System.out.println("Archivos JSON generados exitosamente.");

        } catch (Exception e) {
            System.out.println("Test");
            e.printStackTrace();
        }
    }

    private JSONArray loadJsonArray(String filename) throws Exception {
        JSONParser parser = new JSONParser();
        try (FileReader reader = new FileReader(filename)) {
            return (JSONArray) parser.parse(reader);
        }
    }

    private Map<String, JSONObject> createProfesorAgents(AgentContainer container, JSONArray profesoresJson) throws StaleProxyException {
        Map<String, JSONObject> profesoresMap = new HashMap<>();
        int profesorCount = 0;

        for (Object obj : profesoresJson) {     // Itera sobre los profesores.
            JSONObject profesorJson = (JSONObject) obj;
            String nombre = (String) profesorJson.get("Nombre");
            String rut = (String) profesorJson.get("RUT");
            String key = nombre + "-" + rut;  // Clave única para cada profesor.

            if (!profesoresMap.containsKey(key)) {      // Si el profesor no existe en el mapa lo agrega con sus datos.
                profesoresMap.put(key, new JSONObject());
                profesoresMap.get(key).put("Nombre", nombre);
                profesoresMap.get(key).put("RUT", rut);
                profesoresMap.get(key).put("Asignaturas", new JSONArray());
            }

            JSONArray asignaturas = (JSONArray) profesoresMap.get(key).get("Asignaturas");  // Obtiene las asignaturas del profesor.
            asignaturas.add(profesorJson.get("Asignatura")); // Agrega la asignatura al profesor.

            // Crea el agente Profesor con los datos del profesor.
            String jsonString = profesoresMap.get(key).toJSONString();
            Object[] profesorArgs = {jsonString};
            AgentController profesor = container.createNewAgent("Profesor" + (++profesorCount), "AgenteProfesor", profesorArgs);
            profesor.start();
            System.out.println("Agente Profesor " + nombre + " creado con JSON: " + jsonString);
        }

        return profesoresMap;
    }

    private Map<String, AgentController> createSalaAgents(AgentContainer container, JSONArray salasJson) throws StaleProxyException {
        Map<String, AgentController> salasControllers = new HashMap<>();    // Mapa para guardar los controladores(agentes) de las salas.

        for (Object obj : salasJson) {  // Itera sobre las salas.
            JSONObject salaJson = (JSONObject) obj;
            String codigo = (String) salaJson.get("Codigo");    // Obtiene el código de la sala.
            String jsonString = salaJson.toJSONString();
            Object[] salaArgs = {jsonString};
            AgentController sala = container.createNewAgent("Sala" + codigo, "AgenteSala", salaArgs);   // Crea el agente Sala con los datos de la sala.
            sala.start();   // Inicia el agente Sala.
            salasControllers.put(codigo, sala); // Guarda el controlador del agente Sala en un mapa.
            System.out.println("Agente Sala " + codigo + " creado con JSON: " + jsonString);    
        }

        return salasControllers;    // Retorna el mapa de controladores de las salas.
    }

    private int calculateTotalAsignaturas(Map<String, JSONObject> profesoresMap) {
        int totalAsignaturas = 0;
        for (JSONObject profesorJson : profesoresMap.values()) { 
            JSONArray asignaturas = (JSONArray) profesorJson.get("Asignaturas");
            totalAsignaturas += asignaturas.size();
        }
        return totalAsignaturas;
    }

    // Establecer el total de solicitudes de asignaturas para cada sala a través de sus agentes.
    private void setTotalSolicitudesForSalas(Map<String, AgentController> salasControllers, int totalAsignaturas) {
        for (Map.Entry<String, AgentController> entry : salasControllers.entrySet()) {  // Itera sobre las salas.
            String codigo = entry.getKey();
            AgentController salaController = entry.getValue();
            try {
                SalaInterface salaInterface = salaController.getO2AInterface(SalaInterface.class);  // Obtiene la interfaz SalaInterface del agente Sala.
                if (salaInterface != null) {    // Si la interfaz es valida, establece el total de solicitudes de asignaturas.
                    salaInterface.setTotalSolicitudes(totalAsignaturas);    
                    System.out.println("Total de solicitudes establecido para sala: " + codigo);
                } else {
                    System.out.println("No se pudo obtener la interfaz SalaInterface para la sala: " + codigo);
                }
            } catch (StaleProxyException e) {
                System.out.println("Error al obtener la interfaz para la sala: " + codigo);
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        // Iniccializa el entorno de Jade
        Runtime rt = Runtime.instance();

        // Crea un perfil con el host y GUI
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, "true");

        // Crea el contenedor principal
        AgentContainer mainContainer = rt.createMainContainer(profile);

        try {
            // Crear el agente Aplicacion
            mainContainer.createNewAgent("Aplicacion", "Aplicacion", new Object[]{}).start();
        } catch (StaleProxyException e) {
            e.printStackTrace();
        }
    }
}