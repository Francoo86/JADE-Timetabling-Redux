package json_stuff;

import objetos.Asignatura;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ProfesorHorarioJSON {
    private static ProfesorHorarioJSON instance;
    private static final ReentrantLock instanceLock = new ReentrantLock();

    public static final String FINAL_JSON_NAME = "Horarios_asignados.json";

    // Threshold for number of updates before writing to disk
    private static final int WRITE_THRESHOLD = 10;

    // In-memory storage
    private final Map<String, JSONObject> profesoresHorarios;
    private final AtomicInteger updateCount;

    // Lock for file writing operations
    private final ReentrantLock writeLock;

    private String scenario;

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

    public synchronized void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public void agregarHorarioProfesor(String nombre, JSONObject horario, List<Asignatura> originalAsignaturas) {
        try {
            JSONObject profesorJSON = new JSONObject();
            profesorJSON.put("Nombre", nombre);

            JSONArray asignaturas = (JSONArray) horario.get("Asignaturas");
            if (asignaturas == null) {
                asignaturas = new JSONArray();
            }

            // Group assignments by instance
            Map<String, Integer> assignedHoursByInstance = new HashMap<>();
            for (Object obj : asignaturas) {
                JSONObject asignatura = (JSONObject) obj;
                String instanceKey = String.format("%s-%s-%d",
                        asignatura.get("Nombre"),
                        asignatura.get("CodigoAsignatura"),
                        ((Number) asignatura.get("Instance")).intValue());
                assignedHoursByInstance.merge(instanceKey, 1, Integer::sum);
            }

            // Count completed instances by comparing with original requirements
            int completedSubjects = 0;
            for (int i = 0; i < originalAsignaturas.size(); i++) {
                Asignatura original = originalAsignaturas.get(i);
                String instanceKey = String.format("%s-%s-%d",
                        original.getNombre(),
                        original.getCodigoAsignatura(),
                        i);

                int assigned = assignedHoursByInstance.getOrDefault(instanceKey, 0);
                int required = original.getHoras();

                if (assigned >= required) {
                    completedSubjects++;
                }

                // Debug output
                System.out.printf("Subject: %s, Instance: %d, Required: %d, Assigned: %d%n",
                        original.getNombre(), i, required, assigned);
            }

            profesorJSON.put("Asignaturas", asignaturas);
            profesorJSON.put("Solicitudes", originalAsignaturas.size());
            profesorJSON.put("AsignaturasCompletadas", completedSubjects);

            // Store in memory
            profesoresHorarios.put(nombre, profesorJSON);

            if (updateCount.incrementAndGet() >= WRITE_THRESHOLD) {
                flushUpdates(false);
            }
        } catch (Exception e) {
            System.err.println("Error agregando horario del profesor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void flushUpdates(boolean isFinalWrite) {
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

            JSONHelper.writeJsonFile(FINAL_JSON_NAME, jsonArray, scenario);

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
        System.out.println(FINAL_JSON_NAME + " generado con " +
                profesoresHorarios.size() + " profesores");
    }

    public String getFinalJSONPath() {
        return JSONHelper.getBaseOutputPath() + "/" + scenario + "/" + FINAL_JSON_NAME;
    }

    public boolean isJsonFileGenerated() {
        java.io.File file = new java.io.File(getFinalJSONPath());
        return file.exists() && file.isFile();
    }

    // Get current number of pending updates
    public int getPendingUpdateCount() {
        return profesoresHorarios.size();
    }
}