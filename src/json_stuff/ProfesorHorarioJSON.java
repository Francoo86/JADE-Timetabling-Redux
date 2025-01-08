package json_stuff;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ProfesorHorarioJSON {
    private static ProfesorHorarioJSON instance;
    private static final ReentrantLock instanceLock = new ReentrantLock();

    // Threshold for number of updates before writing to disk
    private static final int WRITE_THRESHOLD = 10;

    // In-memory storage
    private final Map<String, JSONObject> profesoresHorarios;
    private final AtomicInteger updateCount;

    // Lock for file writing operations
    private final ReentrantLock writeLock;

    private ProfesorHorarioJSON() {
        profesoresHorarios = new ConcurrentHashMap<>();
        updateCount = new AtomicInteger(0);
        writeLock = new ReentrantLock();
    }

    public static ProfesorHorarioJSON getInstance() {
        if (instance == null) {
            instanceLock.lock();
            try {
                if (instance == null) {
                    instance = new ProfesorHorarioJSON();
                }
            } finally {
                instanceLock.unlock();
            }
        }
        return instance;
    }

    public void agregarHorarioProfesor(String nombre, JSONObject horario, int solicitudes) {
        try {
            JSONObject profesorJSON = new JSONObject();
            profesorJSON.put("Nombre", nombre);

            JSONArray asignaturas = (JSONArray) horario.get("Asignaturas");
            if (asignaturas == null) {
                asignaturas = new JSONArray();
            }

            profesorJSON.put("Asignaturas", asignaturas);
            profesorJSON.put("Solicitudes", solicitudes);
            profesorJSON.put("AsignaturasCompletadas", asignaturas.size());

            // Store in memory
            profesoresHorarios.put(nombre, profesorJSON);

            // Check if we should write to disk
            if (updateCount.incrementAndGet() >= WRITE_THRESHOLD) {
                flushUpdates(false);
            }
        } catch (Exception e) {
            System.err.println("Error agregando horario del profesor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void flushUpdates(boolean isFinalWrite) {
        // Try to acquire write lock - if can't get it immediately, skip this flush unless it's final write
        if (!isFinalWrite && !writeLock.tryLock()) {
            return;
        }

        if (isFinalWrite) {
            writeLock.lock();
        }

        try {
            if (profesoresHorarios.isEmpty()) {
                return;
            }

            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(profesoresHorarios.values());

            JSONHelper.writeJsonFile("Horarios_asignados.json", jsonArray);

            if (isFinalWrite) {
                printAsignationSummary();
            }

            updateCount.set(0);

        } catch (Exception e) {
            System.err.println("Error writing professor schedules to file: " + e.getMessage());
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }
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

    // Called by AgenteSupervisor for final write
    public void generarArchivoJSON() {
        System.out.println("Generando archivo JSON final de profesores...");
        flushUpdates(true);
        System.out.println("Archivo Horarios_asignados.json generado con " +
                profesoresHorarios.size() + " profesores");
    }

    // Get current number of pending updates
    public int getPendingUpdateCount() {
        return profesoresHorarios.size();
    }
}