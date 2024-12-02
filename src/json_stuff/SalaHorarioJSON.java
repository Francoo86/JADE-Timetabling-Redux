package json_stuff;

import constants.enums.Day;
import objetos.AsignacionSala;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

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

    public void agregarHorarioSala(String codigo, String campus, Map<Day, List<AsignacionSala>> horario) {
        System.out.println("Agregando horario para sala: " + codigo + " (Campus: " + campus + ")");
        
        JSONObject salaJSON = new JSONObject();
        salaJSON.put("Codigo", codigo);
        salaJSON.put("Campus", campus);

        JSONArray asignaturasJSON = new JSONArray();
        for (Map.Entry<Day, List<AsignacionSala>> entry : horario.entrySet()) {
            Day dia = entry.getKey();
            List<AsignacionSala> asignaciones = entry.getValue();
            
            for (int i = 0; i < asignaciones.size(); i++) {
                AsignacionSala asignacion = asignaciones.get(i);
                if (asignacion != null) {
                    JSONObject asignaturaJSON = new JSONObject();
                    asignaturaJSON.put("Nombre", asignacion.getNombreAsignatura());
                    asignaturaJSON.put("Capacidad", asignacion.getCapacidad());
                    asignaturaJSON.put("Bloque", i + 1);
                    asignaturaJSON.put("Dia", dia.getDisplayName());
                    asignaturaJSON.put("Satisfaccion", asignacion.getSatisfaccion());
                    asignaturasJSON.add(asignaturaJSON);
                    
                    System.out.println("Agregada asignatura: " + asignacion.getNombreAsignatura() + 
                                     " (Día: " + dia + ", Bloque: " + (i+1) + ")");
                }
            }
        }
        
        salaJSON.put("Asignaturas", asignaturasJSON);
        salasHorarios.put(codigo, salaJSON);
        
        // Generar el archivo JSON después de cada actualización
        generarArchivoJSON();
    }

    public void generarArchivoJSON() {
        try {
            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(salasHorarios.values());

            // Verificar si hay datos antes de escribir
            if (jsonArray.isEmpty()) {
                System.out.println("ADVERTENCIA: No hay datos de salas para escribir en el JSON");
                return;
            }

            JSONHelper.writeJsonFile("Horarios_salas.json", jsonArray);
            
            System.out.println("Archivo Horarios_salas.json generado con " + salasHorarios.size() + " salas");
            // Imprimir resumen de cada sala
            for (JSONObject sala : salasHorarios.values()) {
                String codigo = (String) sala.get("Codigo");
                JSONArray asignaturas = (JSONArray) sala.get("Asignaturas");
                System.out.println("Sala " + codigo + ": " + asignaturas.size() + " asignaturas asignadas");
            }
        } catch (Exception e) {
            System.err.println("Error al generar el archivo JSON de salas: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

