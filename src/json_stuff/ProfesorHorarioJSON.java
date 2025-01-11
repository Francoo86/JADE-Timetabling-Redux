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

            // Create a map to track hours per unique subject (name + code)
            Map<String, Integer> subjectHours = new HashMap<>();

            // Process asignaturas to count completed subjects
            for (Object obj : asignaturas) {
                JSONObject asignatura = (JSONObject) obj;
                String nombreAsig = (String) asignatura.get("Nombre");
                subjectHours.merge(nombreAsig, 1, Integer::sum);
            }

            // A subject is considered completed if it has the required number of hours assigned
            int completedSubjects = (int) subjectHours.values().stream()
                    .filter(hours -> hours >= 2)  // Minimum 2 hours per subject
                    .count();

            profesorJSON.put("Asignaturas", asignaturas);
            profesorJSON.put("Solicitudes", solicitudes);
            profesorJSON.put("AsignaturasCompletadas", completedSubjects);

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

    public void agregarHorarioProfesor(String nombre, JSONObject horario,
                                       Map<String, Integer> requiredHoursPerInstance,
                                       Map<String, String> subjectInstanceKeys) {
        try {
            JSONObject profesorJSON = new JSONObject();
            profesorJSON.put("Nombre", nombre);

            JSONArray asignaturas = (JSONArray) horario.get("Asignaturas");
            Map<String, Integer> assignedHoursPerInstance = new HashMap<>();

            // Count assigned hours per instance
            for (Object obj : asignaturas) {
                JSONObject asignatura = (JSONObject) obj;
                String instanceKey = (String) asignatura.get("InstanceKey");
                assignedHoursPerInstance.merge(instanceKey, 1, Integer::sum);
            }

            // Check completion per instance
            int completedInstances = 0;
            for (Map.Entry<String, Integer> entry : requiredHoursPerInstance.entrySet()) {
                String instanceKey = entry.getKey();
                int required = entry.getValue();
                int assigned = assignedHoursPerInstance.getOrDefault(instanceKey, 0);
                if (assigned >= required) {
                    completedInstances++;
                }
            }

            profesorJSON.put("Asignaturas", asignaturas);
            profesorJSON.put("Solicitudes", requiredHoursPerInstance.size());
            profesorJSON.put("AsignaturasCompletadas", completedInstances);

            // Debug output
            System.out.printf("Professor %s stats:%n", nombre);
            System.out.println("Per Instance Stats:");
            requiredHoursPerInstance.forEach((instanceKey, required) -> {
                String subjectName = subjectInstanceKeys.get(instanceKey);
                int assigned = assignedHoursPerInstance.getOrDefault(instanceKey, 0);
                System.out.printf("  %s (Instance %s): %d/%d hours assigned%n",
                        subjectName, instanceKey, assigned, required);
            });

            profesoresHorarios.put(nombre, profesorJSON);

            if (updateCount.incrementAndGet() >= WRITE_THRESHOLD) {
                flushUpdates(false);
            }
        } catch (Exception e) {
            System.err.println("Error agregando horario del profesor: " + e.getMessage());
            e.printStackTrace();
        }
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

    public void agregarHorarioProfesor(String nombre, JSONObject horario, Map<String, Integer> requiredHours) {
        try {
            JSONObject profesorJSON = new JSONObject();
            profesorJSON.put("Nombre", nombre);

            JSONArray asignaturas = (JSONArray) horario.get("Asignaturas");
            if (asignaturas == null) {
                asignaturas = new JSONArray();
            }

            // Track assigned hours per subject
            Map<String, Integer> assignedHours = new HashMap<>();

            // Count assigned hours
            for (Object obj : asignaturas) {
                JSONObject asignatura = (JSONObject) obj;
                String nombreAsig = (String) asignatura.get("Nombre");
                assignedHours.merge(nombreAsig, 1, Integer::sum);
            }

            // Check which subjects are complete
            int completedSubjects = 0;
            for (Map.Entry<String, Integer> entry : requiredHours.entrySet()) {
                int assigned = assignedHours.getOrDefault(entry.getKey(), 0);
                if (assigned >= entry.getValue()) {
                    completedSubjects++;
                }
            }

            profesorJSON.put("Asignaturas", asignaturas);
            profesorJSON.put("Solicitudes", requiredHours.size());  // Total unique subjects
            profesorJSON.put("AsignaturasCompletadas", completedSubjects);

            // Store in memory
            profesoresHorarios.put(nombre, profesorJSON);

            // For debugging
            System.out.printf("Professor %s stats:%n", nombre);
            System.out.printf("- Total subjects: %d%n", requiredHours.size());
            System.out.printf("- Required hours per subject:%n");
            requiredHours.forEach((subj, req) ->
                    System.out.printf("  %s: %d/%d hours assigned%n",
                            subj, assignedHours.getOrDefault(subj, 0), req));
            System.out.printf("- Completed subjects: %d%n", completedSubjects);

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