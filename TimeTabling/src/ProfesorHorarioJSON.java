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

    public void generarArchivoJSON() {
        JSONArray jsonArray = new JSONArray();
        jsonArray.addAll(profesoresHorarios.values());

        try (FileWriter file = new FileWriter("Horarios_asignados.json")) {
            file.write(formatJSONString(jsonArray.toJSONString()));
            System.out.println("Archivo Horarios_asignados.json generado con " +
                    profesoresHorarios.size() + " profesores");

            // Imprimir resumen de asignaciones
            for (JSONObject profesor : profesoresHorarios.values()) {
                String nombreProf = (String) profesor.get("Nombre");
                JSONArray asignaturasProf = (JSONArray) profesor.get("Asignaturas");
                int solicitudes = ((Number) profesor.get("Solicitudes")).intValue();
                System.out.println("Profesor " + nombreProf + ": " +
                        asignaturasProf.size() + "/" + solicitudes +
                        " asignaturas asignadas");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String formatJSONString(String jsonString) {
        StringBuilder sb = new StringBuilder();
        int indentLevel = 0;
        boolean inQuotes = false;

        for (char c : jsonString.toCharArray()) {
            switch (c) {
                case '{':
                case '[':
                    sb.append(c).append("\n");
                    indentLevel++;
                    addIndentation(sb, indentLevel);
                    break;
                case '}':
                case ']':
                    sb.append("\n");
                    indentLevel--;
                    addIndentation(sb, indentLevel);
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c);
                    if (!inQuotes) {
                        sb.append("\n");
                        addIndentation(sb, indentLevel);
                    }
                    break;
                case ':':
                    sb.append(c).append(" ");
                    break;
                case '\"':
                    inQuotes = !inQuotes;
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private void addIndentation(StringBuilder sb, int indentLevel) {
        for (int i = 0; i < indentLevel; i++) {
            sb.append("  ");
        }
    }
}