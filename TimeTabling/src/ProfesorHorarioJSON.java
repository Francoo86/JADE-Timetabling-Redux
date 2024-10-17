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
            file.write(formatJSONString(jsonArray.toJSONString()));
            System.out.println("Archivo Horarios_asignados.json generado exitosamente.");
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