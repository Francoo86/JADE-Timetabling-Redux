package json_stuff;

import constants.enums.Day;
import objetos.AsignacionSala;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class SalaHorarioJSON {
    private static SalaHorarioJSON instance;
    private static final ReentrantLock instanceLock = new ReentrantLock();
    private static final int WRITE_THRESHOLD = 20;
    private final Map<String, JSONObject> pendingUpdates;
    private final Set<String> allRoomCodes;
    private final AtomicInteger updateCount;
    private final ReentrantLock writeLock;

    private SalaHorarioJSON() {
        pendingUpdates = new ConcurrentHashMap<>();
        allRoomCodes = ConcurrentHashMap.newKeySet();
        updateCount = new AtomicInteger(0);
        writeLock = new ReentrantLock();
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

// In SalaHorarioJSON.java

    public void agregarHorarioSala(String codigo, String campus, Map<Day, List<AsignacionSala>> horario) {
        try {
            System.out.println("[DEBUG] Adding/updating schedule for room " + codigo);

            // Count assignments before creating JSON
            int assignmentCount = 0;
            for (List<AsignacionSala> dayAssignments : horario.values()) {
                for (AsignacionSala assignment : dayAssignments) {
                    if (assignment != null) {
                        assignmentCount++;
                    }
                }
            }

            System.out.println("[DEBUG] Room " + codigo + " has " + assignmentCount + " total assignments");

            // Create JSON for this update
            JSONObject salaJSON = createSalaJSON(codigo, campus, horario);
            JSONArray asignaturas = (JSONArray) salaJSON.get("Asignaturas");

            System.out.println("[DEBUG] Created JSON object for room " + codigo +
                    " with " + asignaturas.size() + " assignments");

            // Store update in memory
            pendingUpdates.put(codigo, salaJSON);
            allRoomCodes.add(codigo);

            // Check if we should write to disk
            if (updateCount.incrementAndGet() >= WRITE_THRESHOLD) {
                System.out.println("[DEBUG] Threshold reached, flushing updates");
                flushUpdates();
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Error adding classroom schedule for " + codigo + ": " + e.getMessage());
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

    private JSONObject createEmptySalaJSON(String codigo) {
        JSONObject salaJSON = new JSONObject();
        salaJSON.put("Codigo", codigo);
        salaJSON.put("Asignaturas", new JSONArray());
        return salaJSON;
    }

    public void generarArchivoJSON() {
        writeLock.lock();
        try {
            flushUpdates();

            JSONArray jsonArray = new JSONArray();

            for (String roomCode : allRoomCodes) {
                JSONObject salaJSON = pendingUpdates.getOrDefault(roomCode, createEmptySalaJSON(roomCode));
                jsonArray.add(salaJSON);
            }

            if (!jsonArray.isEmpty()) {
                JSONHelper.writeJsonFile("Horarios_salas.json", jsonArray);
                System.out.println("Generated final Horarios_salas.json with " + jsonArray.size() + " salas");

                for (Object obj : jsonArray) {
                    JSONObject sala = (JSONObject) obj;
                    String codigo = (String) sala.get("Codigo");
                    JSONArray asignaturas = (JSONArray) sala.get("Asignaturas");
                    System.out.println("Room " + codigo + ": " +
                            (asignaturas != null ? asignaturas.size() : 0) + " assignments");
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void flushUpdates() {
        if (!writeLock.tryLock()) {
            return;
        }

        try {
            if (pendingUpdates.isEmpty()) {
                return;
            }

            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(pendingUpdates.values());

            if (!jsonArray.isEmpty()) {
                JSONHelper.writeJsonFile("Horarios_salas.json", jsonArray);
                System.out.println("Successfully wrote " + pendingUpdates.size() + " classroom schedules to file");
            }

            pendingUpdates.clear();
            updateCount.set(0);

        } catch (Exception e) {
            System.err.println("Error writing classroom schedules to file: " + e.getMessage());
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }
    }

    public void forceFlush() {
        writeLock.lock();
        try {
            flushUpdates();
        } finally {
            writeLock.unlock();
        }
    }

    public int getPendingUpdateCount() {
        return pendingUpdates.size();
    }

    public void printAssignmentSummary() {
        writeLock.lock();
        try {
            flushUpdates();

            for (JSONObject sala : pendingUpdates.values()) {
                String codigo = (String) sala.get("Codigo");
                JSONArray asignaturas = (JSONArray) sala.get("Asignaturas");
                System.out.println("Room " + codigo + ": " +
                        (asignaturas != null ? asignaturas.size() : 0) + " assignments");
            }
        } finally {
            writeLock.unlock();
        }
    }
}