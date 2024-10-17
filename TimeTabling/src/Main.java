import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.ContainerController;

import jade.wrapper.StaleProxyException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

public class Main {
    public static void main(String[] args) {
        try {
            Runtime rt = Runtime.instance();    // Se crea una instancia de la clase Runtime
            rt.setCloseVM(true);  // Se establece que la máquina virtual se cierre al finalizar la ejecución
            
            // Se crea un perfil con los parámetros de la máquina virtual
            Profile profile = new ProfileImpl(null, 1200, null);  
            profile.setParameter(Profile.GUI, "true");
            AgentContainer container = rt.createMainContainer(profile); // Create the main container for the agents

            JSONArray profesoresJson = loadJsonArray("profesores.json"); // Se carga el archivo JSON de profesores
            Map<String, JSONObject> profesoresMap = new HashMap<>();  // Se crea un mapa para almacenar los profesores
            // Clave (key): Una combinación única de Nombre y RUT del profesor.
            // Valor (JSONObject): Un objeto JSON que contiene: Nombre, RUT, Asignaturas: Una lista de asignaturas que imparte.

            int totalAsignaturas = 0;  // Se inicializa el total de asignaturas en 0
            
            // Se itera sobre cada objeto en el arreglo JSON.
            // Se extraen los campos Nombre y RUT de cada profesor.
            // Se crea una clave única combinando Nombre y RUT.
            for (Object obj : profesoresJson) {
                JSONObject profesorJson = (JSONObject) obj;
                String nombre = (String) profesorJson.get("Nombre");
                String rut = (String) profesorJson.get("RUT");
                String key = nombre + "-" + rut;

                // Si el profesor no existe en el mapa, se crea un nuevo objeto JSON
                if (!profesoresMap.containsKey(key)) {
                    profesoresMap.put(key, new JSONObject());
                    profesoresMap.get(key).put("Nombre", nombre);
                    profesoresMap.get(key).put("RUT", rut);
                    profesoresMap.get(key).put("Asignaturas", new JSONArray());
                }
                
                // Se añade la asignatura al profesor
                JSONArray asignaturas = (JSONArray) profesoresMap.get(key).get("Asignaturas");
                asignaturas.add(profesorJson.get("Asignatura"));
                totalAsignaturas++;
            }

            // Crear agentes profesores
            int profesorCount = 0;
            for (JSONObject profesorJson : profesoresMap.values()) {
                String nombre = (String) profesorJson.get("Nombre");
                String jsonString = profesorJson.toJSONString();
                Object[] profesorArgs = {jsonString};
                AgentController profesor = container.createNewAgent("Profesor" + (++profesorCount), "AgenteProfesor", profesorArgs);
                profesor.start();   // Se inicia el agente profesor
                System.out.println("Agente Profesor " + nombre + " creado con JSON: " + jsonString);
            }

            // Leer datos de salas desde archivo JSON
            JSONArray salasJson = loadJsonArray("salas.json");
            Map<String, AgentController> salasControllers = new HashMap<>();
            for (Object obj : salasJson) {
                JSONObject salaJson = (JSONObject) obj;
                String codigo = (String) salaJson.get("Codigo");
                String jsonString = salaJson.toJSONString();
                Object[] salaArgs = {jsonString};
                AgentController sala = container.createNewAgent("Sala" + codigo, "AgenteSala", salaArgs);
                sala.start();   // Se inicia el agente sala
                salasControllers.put(codigo, sala);
                System.out.println("Agente Sala " + codigo + " creado con JSON: " + jsonString);
            }

            // Esperar un poco para asegurarse de que los agentes estén completamente iniciados
            Thread.sleep(2000);

            // Establecer el número total de solicitudes para cada sala
            for (Map.Entry<String, AgentController> entry : salasControllers.entrySet()) {
                String codigo = entry.getKey(); // Obtener el código de la sala
                AgentController salaController = entry.getValue(); // Obtener el controlador del agente de la sala
                try {
                    // Obtener la interfaz O2A (Object-to-Agent) para la sala
                    SalaInterface salaInterface = salaController.getO2AInterface(SalaInterface.class);
                    if (salaInterface != null) {
                        // Establecer el número total de solicitudes en la interfaz de la sala
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

            // Esperar a que todos los agentes terminen (puedes ajustar este tiempo según sea necesario)
            System.out.println("Esperando a que los agentes completen su trabajo...");
            Thread.sleep(60000);

            // Generar archivo CSV
            System.out.println("Iniciando generación de archivo CSV...");
            HorarioExcelGenerator.getInstance().generarArchivoCSV("horarios.csv");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Método para cargar un archivo JSON
    private static JSONArray loadJsonArray(String filename) throws Exception {
        JSONParser parser = new JSONParser();
        try (FileReader reader = new FileReader(filename)) {
            return (JSONArray) parser.parse(reader);
        }
    }
}