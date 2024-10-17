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
            // Create the main container
            Runtime rt = Runtime.instance();
            rt.setCloseVM(true);
            Profile profile = new ProfileImpl(null, 1200, null);
            profile.setParameter(Profile.GUI, "true");
            AgentContainer container = rt.createMainContainer(profile);

            // Load professor and classroom data
            JSONArray profesoresJson = loadJsonArray("profesores.json");
            JSONArray salasJson = loadJsonArray("salas.json");

            // Create professor agents
            Map<String, JSONObject> profesoresMap = createProfesorAgents(container, profesoresJson);

            // Create classroom agents
            Map<String, AgentController> salasControllers = createSalaAgents(container, salasJson);

            // Wait for agents to initialize
            Thread.sleep(2000);

            // Set total requests for each classroom
            int totalAsignaturas = calculateTotalAsignaturas(profesoresMap);
            setTotalSolicitudesForSalas(salasControllers, totalAsignaturas);

            // Wait for agents to complete their work
            System.out.println("Esperando a que los agentes completen su trabajo...");
            Thread.sleep(60000);

            // Generate CSV file
            System.out.println("Iniciando generación de archivo CSV...");
            HorarioExcelGenerator.getInstance().generarArchivoCSV("horarios.csv");

        } catch (Exception e) {
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

        for (Object obj : profesoresJson) {
            JSONObject profesorJson = (JSONObject) obj;
            String nombre = (String) profesorJson.get("Nombre");
            String rut = (String) profesorJson.get("RUT");
            String key = nombre + "-" + rut;

            if (!profesoresMap.containsKey(key)) {
                profesoresMap.put(key, new JSONObject());
                profesoresMap.get(key).put("Nombre", nombre);
                profesoresMap.get(key).put("RUT", rut);
                profesoresMap.get(key).put("Asignaturas", new JSONArray());
            }

            JSONArray asignaturas = (JSONArray) profesoresMap.get(key).get("Asignaturas");
            asignaturas.add(profesorJson.get("Asignatura"));

            // Create professor agent
            String jsonString = profesoresMap.get(key).toJSONString();
            Object[] profesorArgs = {jsonString};
            AgentController profesor = container.createNewAgent("Profesor" + (++profesorCount), "AgenteProfesor", profesorArgs);
            profesor.start();
            System.out.println("Agente Profesor " + nombre + " creado con JSON: " + jsonString);
        }

        return profesoresMap;
    }

    private Map<String, AgentController> createSalaAgents(AgentContainer container, JSONArray salasJson) throws StaleProxyException {
        Map<String, AgentController> salasControllers = new HashMap<>();

        for (Object obj : salasJson) {
            JSONObject salaJson = (JSONObject) obj;
            String codigo = (String) salaJson.get("Codigo");
            String jsonString = salaJson.toJSONString();
            Object[] salaArgs = {jsonString};
            AgentController sala = container.createNewAgent("Sala" + codigo, "AgenteSala", salaArgs);
            sala.start();
            salasControllers.put(codigo, sala);
            System.out.println("Agente Sala " + codigo + " creado con JSON: " + jsonString);
        }

        return salasControllers;
    }

    private int calculateTotalAsignaturas(Map<String, JSONObject> profesoresMap) {
        int totalAsignaturas = 0;
        for (JSONObject profesorJson : profesoresMap.values()) {
            JSONArray asignaturas = (JSONArray) profesorJson.get("Asignaturas");
            totalAsignaturas += asignaturas.size();
        }
        return totalAsignaturas;
    }

    private void setTotalSolicitudesForSalas(Map<String, AgentController> salasControllers, int totalAsignaturas) {
        for (Map.Entry<String, AgentController> entry : salasControllers.entrySet()) {
            String codigo = entry.getKey();
            AgentController salaController = entry.getValue();
            try {
                SalaInterface salaInterface = salaController.getO2AInterface(SalaInterface.class);
                if (salaInterface != null) {
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
        jade.core.Runtime rt = jade.core.Runtime.instance();
        Profile p = new ProfileImpl();
        AgentContainer container = rt.createMainContainer(p);

        try {
            AgentController ac = container.createNewAgent("Aplicacion", "Aplicacion", new Object[]{});
            ac.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}