import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;

// Configura y lanza agentes basados en datos leídos de archivos JSON.
public class Aplicacion extends Agent {
    protected void setup() {
        System.out.println("Agente Aplicacion iniciado");

        try {
            // Crear el contenedor principal
            Runtime rt = Runtime.instance();
            Profile p = new ProfileImpl();
            AgentContainer container = rt.createMainContainer(p);

            // Leer datos de profesores y salas desde archivos JSON
            JSONParser parser = new JSONParser();
            JSONArray profesoresJson = (JSONArray) parser.parse(new FileReader("profesores.json"));
            JSONArray salasJson = (JSONArray) parser.parse(new FileReader("salas.json"));

            System.out.println("Archivos JSON leídos correctamente");

            // Crear agentes profesores
            int turno = 1;
            for (Object obj : profesoresJson) {
                JSONObject profesorJson = (JSONObject) obj;
                Object[] args = {profesorJson, turno};
                AgentController ac = container.createNewAgent("Profesor" + turno, "AgenteProfesor", args);
                ac.start();
                System.out.println("Agente Profesor" + turno + " creado");
                turno++;
            }

            // Crear agentes salas
            for (Object obj : salasJson) {
                JSONObject salaJson = (JSONObject) obj;
                Object[] args = {salaJson};
                String codigo = (String) salaJson.get("Codigo");
                AgentController ac = container.createNewAgent("Sala" + codigo, "AgenteSala", args);
                ac.start();
                System.out.println("Agente Sala" + codigo + " creado");
            }

        } catch (Exception e) {
            System.out.println("Error durante la ejecución: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
