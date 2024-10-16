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
            Runtime rt = Runtime.instance();
            rt.setCloseVM(true);
            Profile profile = new ProfileImpl(null, 1200, null);
            profile.setParameter(Profile.GUI, "true");
            AgentContainer container = rt.createMainContainer(profile);

            JSONArray profesoresJson = loadJsonArray("profesores.json");
            Map<String, JSONObject> profesoresMap = new HashMap<>();
            int totalAsignaturas = 0;

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
                totalAsignaturas++;
            }

            int profesorCount = 0;
            for (JSONObject profesorJson : profesoresMap.values()) {
                String nombre = (String) profesorJson.get("Nombre");
                String jsonString = profesorJson.toJSONString();
                Object[] profesorArgs = {jsonString};
                AgentController profesor = container.createNewAgent("Profesor" + (++profesorCount), "AgenteProfesor", profesorArgs);
                profesor.start();
                System.out.println("Agente Profesor " + nombre + " creado con JSON: " + jsonString);
            }

            JSONArray salasJson = loadJsonArray("salas.json");
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

            // Establecer el número total de solicitudes para cada sala
            for (AgentController salaController : salasControllers.values()) {
                try {
                    SalaInterface salaInterface = salaController.getO2AInterface(SalaInterface.class);
                    if (salaInterface != null) {
                        salaInterface.setTotalSolicitudes(totalAsignaturas);
                        System.out.println("Total de solicitudes establecido para sala: " + salaController.getName());
                    } else {
                        System.out.println("No se pudo obtener la interfaz SalaInterface para la sala: " + salaController.getName());
                    }
                } catch (StaleProxyException e) {
                    System.out.println("Error al obtener la interfaz para la sala: " + salaController.getName());
                    e.printStackTrace();
                }
            }

            // Esperar a que todos los agentes terminen
            System.out.println("Esperando a que los agentes completen su trabajo...");
            Thread.sleep(60000); // Aumentamos el tiempo de espera a 60 segundos

            // Generar archivo CSV
            System.out.println("Iniciando generación de archivo CSV...");
            HorarioExcelGenerator.getInstance().generarArchivoCSV("horarios.csv");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static JSONArray loadJsonArray(String filename) throws Exception {
        JSONParser parser = new JSONParser();
        try (FileReader reader = new FileReader(filename)) {
            return (JSONArray) parser.parse(reader);
        }
    }
}