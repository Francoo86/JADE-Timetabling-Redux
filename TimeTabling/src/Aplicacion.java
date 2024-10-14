import java.io.FileReader;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

// Clase Aplicacion
public class Aplicacion {
    public static void main(String[] args) {
        // Inicializar el contenedor JADE
        jade.core.Runtime rt = jade.core.Runtime.instance();
        jade.wrapper.ContainerController container = rt.createMainContainer(new jade.core.ProfileImpl());

        try {
            // Leer datos de profesores y salas desde archivos JSON
            JSONParser parser = new JSONParser();
            JSONArray profesoresJson = (JSONArray) parser.parse(new FileReader("profesores.json"));
            JSONArray salasJson = (JSONArray) parser.parse(new FileReader("salas.json"));

            // Crear agentes profesores
            int turno = 1;
            for (Object obj : profesoresJson) {
                JSONObject profesorJson = (JSONObject) obj;
                Object[] profesorArgs = {profesorJson, turno};
                container.createNewAgent("Profesor" + turno, "AgenteProfesor", profesorArgs).start();
                turno++;
            }

            // Crear agentes salas
            for (Object obj : salasJson) {
                JSONObject salaJson = (JSONObject) obj;
                Object[] salaArgs = {salaJson};
                container.createNewAgent("Sala" + salaJson.get("Codigo"), "AgenteSala", salaArgs).start();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
