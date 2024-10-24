import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class SalaHorarioJSON {
    private static SalaHorarioJSON instance;
    private Map<String, JSONObject> salasHorarios;

    private SalaHorarioJSON() {
        salasHorarios = new ConcurrentHashMap<>();
    }

    public static synchronized SalaHorarioJSON getInstance() {
        if (instance == null) {
            instance = new SalaHorarioJSON();
        }
        return instance;
    }

    public void agregarHorarioSala(String codigo, Map<String, List<AsignacionSala>> horario) {
        JSONObject salaJSON = new JSONObject();
        salaJSON.put("Codigo", codigo);

        JSONArray asignaturasJSON = new JSONArray();
        for (Map.Entry<String, List<AsignacionSala>> entry : horario.entrySet()) {
            String dia = entry.getKey();
            List<AsignacionSala> asignaciones = entry.getValue();
            for (int i = 0; i < asignaciones.size(); i++) {
                AsignacionSala asignacion = asignaciones.get(i);
                if (asignacion != null) {
                    JSONObject asignaturaJSON = new JSONObject();
                    asignaturaJSON.put("Nombre", asignacion.getNombreAsignatura());
                    asignaturaJSON.put("Bloque", i + 1);
                    asignaturaJSON.put("Dia", dia);
                    asignaturaJSON.put("Valoracion", asignacion.getValoracion());
                    asignaturasJSON.add(asignaturaJSON);
                }
            }
        }
        salaJSON.put("Asignaturas", asignaturasJSON);
        salasHorarios.put(codigo, salaJSON);
    }

    public void generarArchivoJSON() {
        JSONArray jsonArray = new JSONArray();
        jsonArray.addAll(salasHorarios.values());

        JSONHelper.writeJsonFile("Salidas_salas.json", jsonArray.toJSONString());
    }
}

class AsignacionSala {
    private String nombreAsignatura;
    private int valoracion;

    public AsignacionSala(String nombreAsignatura, int valoracion) {
        this.nombreAsignatura = nombreAsignatura;
        this.valoracion = valoracion;
    }

    public String getNombreAsignatura() {
        return nombreAsignatura;
    }

    public int getValoracion() {
        return valoracion;
    }
}