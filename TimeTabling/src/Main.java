import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.ContainerController;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;

public class Main {
    public static void main(String[] args) {
        try {
            // Get a hold on JADE runtime
            Runtime rt = Runtime.instance();

            // Exit the JVM when there are no more containers around
            rt.setCloseVM(true);

            // Create a default profile
            Profile profile = new ProfileImpl(null, 1200, null);
            profile.setParameter(Profile.GUI, "true"); // This line enables the GUI

            // Create a new non-main container, connecting to the default main container
            ContainerController cc = rt.createMainContainer(profile);

            // Cargar y crear agentes profesores
            JSONArray profesoresJson = loadJsonArray("profesores.json");
            for (int i = 0; i < profesoresJson.size(); i++) {
                JSONObject profesorJson = (JSONObject) profesoresJson.get(i);
                String nombre = (String) profesorJson.get("Nombre");
                String jsonString = profesorJson.toJSONString();
                Object[] profesorArgs = {jsonString, String.valueOf(i + 1)};
                AgentController profesor = cc.createNewAgent("Profesor" + (i + 1), "AgenteProfesor", profesorArgs);
                profesor.start();
                System.out.println("Agente Profesor " + nombre + " creado con JSON: " + jsonString);
            }

            // Cargar y crear agentes salas
            JSONArray salasJson = loadJsonArray("salas.json");
            for (int i = 0; i < salasJson.size(); i++) {
                JSONObject salaJson = (JSONObject) salasJson.get(i);
                String codigo = (String) salaJson.get("Codigo");
                String jsonString = salaJson.toJSONString();
                Object[] salaArgs = {jsonString};
                AgentController sala = cc.createNewAgent("Sala" + codigo, "AgenteSala", salaArgs);
                sala.start();
                System.out.println("Agente Sala " + codigo + " creado con JSON: " + jsonString);
            }

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