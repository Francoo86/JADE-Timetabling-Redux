package json_stuff;

import constants.enums.Day;
import objetos.AsignacionSala;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class SalaHorarioJSON {
    private static SalaHorarioJSON instance;
    private static final ReentrantLock instanceLock = new ReentrantLock();

    // Threshold for number of updates before writing to disk
    private static final int WRITE_THRESHOLD = 20;

    // In-memory storage of pending updates
    private final Map<String, JSONObject> pendingUpdates;
    private final AtomicInteger updateCount;

    // Lock for file writing operations
    private final ReentrantLock writeLock;

    private SalaHorarioJSON() {
        pendingUpdates = new ConcurrentHashMap<>();
        updateCount = new AtomicInteger(0);
        writeLock = new ReentrantLock();
    }

    public void generarArchivoJSON() {
        writeLock.lock();
        try {
            // Force write any pending updates first
            flushUpdates();

            // Then do the final write of everything
            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(pendingUpdates.values());

            if (!jsonArray.isEmpty()) {
                JSONHelper.writeJsonFile("Horarios_salas.json", jsonArray);
                System.out.println("Generated final Horarios_salas.json with " + pendingUpdates.size() + " salas");
            }
        } finally {
            writeLock.unlock();
        }
    }

    public static SalaHorarioJSON getInstance() {
        if (instance == null) {
            instanceLock.lock();
            try {
                if (instance == null) {
                    instance = new SalaHorarioJSON();
                }
            } finally {
                instanceLock.unlock();
            }
        }
        return instance;
    }

    public void agregarHorarioSala(String codigo, String campus, Map<Day, List<AsignacionSala>> horario) {
        try {
            // Create JSON for this update
            JSONObject salaJSON = createSalaJSON(codigo, campus, horario);

            // Store update in memory
            pendingUpdates.put(codigo, salaJSON);

            // Check if we should write to disk
            if (updateCount.incrementAndGet() >= WRITE_THRESHOLD) {
                flushUpdates();
            }
        } catch (Exception e) {
            System.err.println("Error adding classroom schedule: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private JSONObject createSalaJSON(String codigo, String campus, Map<Day, List<AsignacionSala>> horario) {
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
                }
            }
        }

        salaJSON.put("Asignaturas", asignaturasJSON);
        return salaJSON;
    }

    private void flushUpdates() {
        // Try to acquire write lock - if can't get it immediately, skip this flush
        if (!writeLock.tryLock()) {
            return;
        }

        try {
            // Check if there are any updates to write
            if (pendingUpdates.isEmpty()) {
                return;
            }

            // Create JSON array with all pending updates
            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(pendingUpdates.values());

            // Write to file
            if (!jsonArray.isEmpty()) {
                JSONHelper.writeJsonFile("Horarios_salas.json", jsonArray);
                System.out.println("Successfully wrote " + pendingUpdates.size() + " classroom schedules to file");
            }

            // Clear pending updates and reset counter
            pendingUpdates.clear();
            updateCount.set(0);

        } catch (Exception e) {
            System.err.println("Error writing classroom schedules to file: " + e.getMessage());
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }
    }

    // Force write all pending updates - called during cleanup
    public void forceFlush() {
        writeLock.lock();
        try {
            flushUpdates();
        } finally {
            writeLock.unlock();
        }
    }

    // Get current number of pending updates
    public int getPendingUpdateCount() {
        return pendingUpdates.size();
    }

    // Generate summary of assignments
    public void printAssignmentSummary() {
        writeLock.lock();
        try {
            // Force write any pending updates before generating summary
            flushUpdates();

            for (JSONObject sala : pendingUpdates.values()) {
                String codigo = (String) sala.get("Codigo");
                JSONArray asignaturas = (JSONArray) sala.get("Asignaturas");
                System.out.println("Sala " + codigo + ": " +
                        (asignaturas != null ? asignaturas.size() : 0) + " asignaturas asignadas");
            }
        } finally {
            writeLock.unlock();
        }
    }
}