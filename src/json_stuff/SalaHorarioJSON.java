package json_stuff;

import constants.enums.Day;
import interfaces.SalaDataInterface;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import objetos.AsignacionSala;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private String scenario;

    private SalaHorarioJSON() {
        pendingUpdates = new ConcurrentHashMap<>();
        allRoomCodes = ConcurrentHashMap.newKeySet();
        updateCount = new AtomicInteger(0);
        writeLock = new ReentrantLock();
    }

    public static final String FINAL_JSON_NAME = "Horarios_salas.json";

    public synchronized void setScenario(String scenario) {
        this.scenario = scenario;
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
                    asignaturaJSON.put("Docente", asignacion.getProfesor());
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
            Map<String, JSONObject> pendingUpdatesCopy = new ConcurrentHashMap<>(pendingUpdates);

            flushUpdates();

            JSONArray jsonArray = new JSONArray();

            for (String roomCode : allRoomCodes) {
                JSONObject salaJSON = pendingUpdatesCopy.getOrDefault(roomCode, createEmptySalaJSON(roomCode));
                jsonArray.add(salaJSON);
            }

            if (!jsonArray.isEmpty()) {
                JSONHelper.writeJsonFile(FINAL_JSON_NAME, jsonArray, scenario);
                System.out.println("Generated final Horarios_salas_" + scenario + ".json with " + jsonArray.size() + " salas");

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
                JSONHelper.writeJsonFile(FINAL_JSON_NAME, jsonArray, scenario);
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

    public void generateSupervisorFinalReport(List<AgentController> salaControllers) {
        writeLock.lock();
        try {
            System.out.println("[SUPERVISOR] Generating comprehensive final report for " +
                    salaControllers.size() + " classrooms");

            JSONArray jsonArray = new JSONArray();
            Map<String, Map<Day, List<AsignacionSala>>> allRoomData = new HashMap<>();

            // First collect all data directly from sala agents
            for (AgentController salaController : salaControllers) {
                try {
                    // Get the O2A interface to directly access the sala's data
                    SalaDataInterface salaInterface =
                            salaController.getO2AInterface(SalaDataInterface.class);

                    if (salaInterface != null) {
                        String roomCode = salaInterface.getCodigo();
                        String campus = salaInterface.getCampus();
                        Map<Day, List<AsignacionSala>> horario = salaInterface.getHorarioOcupado();

                        // Store the data
                        allRoomData.put(roomCode, horario);

                        // Create JSON for this sala
                        JSONObject salaJSON = createSalaJSON(roomCode, campus, horario);
                        jsonArray.add(salaJSON);

                        System.out.println("[SUPERVISOR] Retrieved data for room " + roomCode +
                                " - Found " + countAssignments(horario) + " assignments");
                    }
                } catch (StaleProxyException e) {
                    System.err.println("[SUPERVISOR] Error accessing sala agent: " + e.getMessage());
                }
            }

            // If we didn't get all rooms, add the ones we know about from our tracking
            for (String roomCode : allRoomCodes) {
                if (!allRoomData.containsKey(roomCode)) {
                    // Get from pending updates if available
                    if (pendingUpdates.containsKey(roomCode)) {
                        jsonArray.add(pendingUpdates.get(roomCode));
                        System.out.println("[SUPERVISOR] Used pending update data for room " + roomCode);
                    } else {
                        // Create empty entry as last resort
                        jsonArray.add(createEmptySalaJSON(roomCode));
                        System.out.println("[SUPERVISOR] Created empty entry for room " + roomCode);
                    }
                }
            }

            // Write to file
            if (!jsonArray.isEmpty()) {
                //JSONHelper.writeJsonFile("Horarios_salas.json", jsonArray);
                JSONHelper.writeJsonFile(FINAL_JSON_NAME, jsonArray, scenario);
                System.out.println("[SUPERVISOR] Generated " + FINAL_JSON_NAME +
                        jsonArray.size() + " salas and " +
                        countTotalAssignments(jsonArray) + " total assignments with scenario " + scenario);
            }
        } finally {
            writeLock.unlock();
        }
    }

    // Helper method to count assignments in a horario
    private int countAssignments(Map<Day, List<AsignacionSala>> horario) {
        int count = 0;
        for (List<AsignacionSala> dayList : horario.values()) {
            for (AsignacionSala asignacion : dayList) {
                if (asignacion != null) {
                    count++;
                }
            }
        }
        return count;
    }

    // Helper method to count total assignments in the JSON array
    private int countTotalAssignments(JSONArray jsonArray) {
        int count = 0;
        for (Object obj : jsonArray) {
            JSONObject salaJSON = (JSONObject) obj;
            JSONArray asignaturas = (JSONArray) salaJSON.get("Asignaturas");
            if (asignaturas != null) {
                count += asignaturas.size();
            }
        }
        return count;
    }

    public String getFinalJSONPath() {
        return JSONHelper.getBaseOutputPath() + "/" + scenario + "/" + FINAL_JSON_NAME;
    }

    public boolean isJsonFileGenerated() {
        java.io.File file = new java.io.File(getFinalJSONPath());
        return file.exists() && file.isFile();
    }
}