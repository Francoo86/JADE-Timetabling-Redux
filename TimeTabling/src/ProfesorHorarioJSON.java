import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProfesorHorarioJSON {
    private static ProfesorHorarioJSON instance;
    private Map<String, JSONObject> profesoresHorarios;

    private ProfesorHorarioJSON() {
        profesoresHorarios = new ConcurrentHashMap<>();
    }

    public static synchronized ProfesorHorarioJSON getInstance() {
        if (instance == null) {
            instance = new ProfesorHorarioJSON();
        }
        return instance;
    }

    public void agregarHorarioProfesor(String nombre, JSONObject horario, int solicitudes) {
        JSONObject profesorJSON = new JSONObject();
        profesorJSON.put("Nombre", nombre);
        profesorJSON.put("Asignaturas", horario.get("Asignaturas"));
        profesorJSON.put("Solicitudes", solicitudes);
        profesoresHorarios.put(nombre, profesorJSON);
    }

    public void generarArchivoJSON() {
        JSONArray jsonArray = new JSONArray();
        jsonArray.addAll(profesoresHorarios.values());

        try (FileWriter file = new FileWriter("Horarios_asignados.json")) {
            file.write(jsonArray.toJSONString());
            System.out.println("Archivo Horarios_asignados.json generado exitosamente.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}