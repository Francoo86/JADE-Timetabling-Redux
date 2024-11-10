package json_stuff;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

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

    public synchronized void agregarHorarioProfesor(String nombre, JSONObject horario, int solicitudes) {
        System.out.println("Agregando horario para profesor: " + nombre);

        JSONObject profesorJSON = new JSONObject();
        profesorJSON.put("Nombre", nombre);

        // Ensure asignaturas array exists
        JSONArray asignaturas = (JSONArray) horario.get("Asignaturas");
        if (asignaturas == null) {
            asignaturas = new JSONArray();
        }

        profesorJSON.put("Asignaturas", asignaturas);
        profesorJSON.put("Solicitudes", solicitudes);
        profesorJSON.put("AsignaturasCompletadas", asignaturas.size());

        System.out.println("Profesor " + nombre + ": " + asignaturas.size() +
                "/" + solicitudes + " asignaturas procesadas");

        profesoresHorarios.put(nombre, profesorJSON);

        // Generate JSON file after each professor to ensure data is saved
        generarArchivoJSON();
    }

    private void printAsignationSummary() {
        for (JSONObject profesor : profesoresHorarios.values()) {
            String nombreProf = (String) profesor.get("Nombre");
            JSONArray asignaturasProf = (JSONArray) profesor.get("Asignaturas");
            int solicitudes = ((Number) profesor.get("Solicitudes")).intValue();
            System.out.println("Profesor " + nombreProf + ": " +
                    asignaturasProf.size() + "/" + solicitudes +
                    " asignaturas asignadas");
        }
    }

    public void generarArchivoJSON() {
        JSONArray jsonArray = new JSONArray();
        jsonArray.addAll(profesoresHorarios.values());

        JSONHelper.writeJsonFile("Horarios_asignados.json", jsonArray);

        System.out.println("Archivo Horarios_asignados.json generado con " + profesoresHorarios.size() + " profesores");

        printAsignationSummary();
    }
}