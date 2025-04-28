package aplicacion;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import agentes.AgenteSupervisor;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import json_stuff.JSONHelper;
import json_stuff.JSONProcessor;
import json_stuff.ProfesorHorarioJSON;
import json_stuff.SalaHorarioJSON;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import performance.RTTLogger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class IterativeAplicacion {
    //private static final String AGENT_OUTPUT = "agent_output";
    private static final String RESULTS_DIR = "agent_output/IterationResults";
    private final int numIterations;
    private final List<IterationResult> results;
    private final PrintWriter logWriter;
    private String scenarioName;

    private volatile boolean supervisorCompleted = false;

    public synchronized void markSupervisorAsFinished() {
        supervisorCompleted = true;
    }

    public IterativeAplicacion(int numIterations, String baseScenario) throws IOException {
        this.numIterations = numIterations;
        this.results = new ArrayList<>();
        this.scenarioName = baseScenario;

        String fullPath = String.format(RESULTS_DIR + "/%s", baseScenario);

        // Create results directory
        //Files.createDirectories(Paths.get(RESULTS_DIR));
        Files.createDirectories(Paths.get(fullPath));
        // Initialize log file
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.logWriter = new PrintWriter(new FileWriter(
                String.format("%s/iteration_log_%s.txt", fullPath, timestamp)));
    }

    private static class IterationResult {
        final int iteration;
        final long duration;
        final int professorAssignments;
        final int roomUtilization;
        final String status;
        final String error;

        IterationResult(int iteration, long duration, int professorAssignments,
                        int roomUtilization, String status, String error) {
            this.iteration = iteration;
            this.duration = duration;
            this.professorAssignments = professorAssignments;
            this.roomUtilization = roomUtilization;
            this.status = status;
            this.error = error;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("iteration", iteration);
            json.put("duration", duration);
            json.put("professorAssignments", professorAssignments);
            json.put("roomUtilization", roomUtilization);
            json.put("status", status);
            if (error != null) {
                json.put("error", error);
            }
            return json;
        }
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logMessage = String.format("[%s] %s", timestamp, message);
        System.out.println(logMessage);
        logWriter.println(logMessage);
        logWriter.flush();
    }

    private void runSingleIteration(int iteration) throws Exception {
        log("Starting iteration " + iteration);
        long startTime = System.currentTimeMillis();

        Runtime rt = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, "true");

        AgentContainer mainContainer = null;
        List<AgentController> professorControllers = new ArrayList<>();
        Map<String, AgentController> roomControllers = new HashMap<>();

        try {
            mainContainer = rt.createMainContainer(profile);
            String salasPath = String.format("scenarios/%s/salas.json", scenarioName);
            String profesoresPath = String.format("scenarios/%s/profesores.json", scenarioName);

            // Load and process data
            JSONArray professorJson = JSONHelper.parseAsArray(profesoresPath);
            JSONArray roomJson = JSONHelper.parseAsArray(salasPath);
            professorJson = JSONProcessor.prepararParalelos(professorJson);

            //CentralizedMonitor.initialize(scenarioName, iteration);

            // Create monitoring for this iteration
            //ThreadBottleneckMonitor perfMonitor = new ThreadBottleneckMonitor(iteration, "MainContainer", scenarioName);
            //perfMonitor.startMonitoring();

            // Initialize rooms
            AtomicInteger totalSubjects = new AtomicInteger(0);
            initializeRooms(mainContainer, roomJson, roomControllers, iteration);

            // Calculate total subjects
            for (Object obj : professorJson) {
                JSONObject professor = (JSONObject) obj;
                JSONArray subjects = (JSONArray) professor.get("Asignaturas");
                totalSubjects.addAndGet(subjects.size());
            }

            // Configure rooms
            configureRooms(roomControllers, totalSubjects.get());

            // Initialize professors
            initializeProfessors(mainContainer, professorJson, professorControllers, iteration);

            // Start first professor
            if (!professorControllers.isEmpty()) {
                professorControllers.get(0).start();
            }

            // Create and start supervisor with proper error handling
            AgentController supervisor = null;
            try {
                Object[] supervisorArgs = {professorControllers, iteration, scenarioName, this};
                supervisor = mainContainer.createNewAgent(
                        "Supervisor",
                        AgenteSupervisor.class.getName(),
                        supervisorArgs
                );
                supervisor.start();

                // Wait for completion with timeout
                waitForCompletion(supervisor); // 3 minute timeout

                /*
                if (!completed) {
                    log("WARNING: Supervisor timeout - forcing completion");
                }*/

                // Get metrics
                int profAssignments = ProfesorHorarioJSON.getInstance().getPendingUpdateCount();
                int roomUtilization = SalaHorarioJSON.getInstance().getPendingUpdateCount();

                // Stop monitoring
                //perfMonitor.stopMonitoring();

                // Record results
                long duration = System.currentTimeMillis() - startTime;
                results.add(new IterationResult(
                        iteration, duration, profAssignments, roomUtilization, "success", null));

                log(String.format("Iteration %d completed successfully in %d ms", iteration, duration));

            } catch (Exception e) {
                String error = String.format("Error in iteration %d: %s", iteration, e.getMessage());
                log(error);
                results.add(new IterationResult(
                        iteration, System.currentTimeMillis() - startTime, 0, 0, "error", error));
            }

        } catch (Exception e) {
            String error = String.format("Error in iteration %d: %s", iteration, e.getMessage());
            log(error);
            e.printStackTrace();
            results.add(new IterationResult(
                    iteration, System.currentTimeMillis() - startTime, 0, 0, "error", error));

        } finally {
            // Cleanup with proper error handling
            if (mainContainer != null) {
                try {
                    // Ensure JSON files are saved before container shutdown
                    ProfesorHorarioJSON.getInstance().generarArchivoJSON();
                    SalaHorarioJSON.getInstance().generarArchivoJSON();

                    // Give time for files to be written
                    Thread.sleep(2000);

                    mainContainer.kill();
                } catch (Exception e) {
                    log("Error during container cleanup: " + e.getMessage());
                }
            }
            Thread.sleep(5000); // Wait before next iteration
        }
    }

    private void waitForCompletion(AgentController supervisor) throws Exception {
        // Reset the completion flag before waiting
        supervisorCompleted = false;

        log("Waiting for supervisor to complete execution...");

        // Wait until the flag is set
        while (!supervisorCompleted) {
            try {
                // Still do a check on agent state as a fallback
                if (supervisor.getState().getCode() == jade.core.Agent.AP_DELETED) {
                    log("Supervisor completion detected via agent state.");
                    supervisorCompleted = true;
                    break;
                }

                // Sleep to avoid busy waiting
                Thread.sleep(1000);

            } catch (StaleProxyException e) {
                // Check if this is because the agent is gone (which means it completed)
                if (e.getMessage() != null && e.getMessage().contains("No such agent exists")) {
                    log("Supervisor agent no longer exists, assuming completion.");
                    supervisorCompleted = true;
                    break;
                } else {
                    // Other unexpected error
                    log("Error checking supervisor state: " + e.getMessage());
                    // Don't break, keep waiting in case it's a temporary issue
                }
            } catch (InterruptedException e) {
                log("Wait interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
                throw new Exception("Execution interrupted while waiting for supervisor");
            }
        }

        log("Supervisor execution completed.");
    }

    private void initializeRooms(AgentContainer container, JSONArray roomsJson,
                                 Map<String, AgentController> controllers, int numIterations) throws StaleProxyException {
        for (Object obj : roomsJson) {
            JSONObject roomJson = (JSONObject) obj;
            String codigo = (String) roomJson.get("Codigo");
            Object[] roomArgs = {roomJson.toJSONString(), numIterations, scenarioName};

            AgentController room = container.createNewAgent(
                    "Sala" + codigo,
                    AgenteSala.class.getName(),
                    roomArgs
            );
            room.start();
            controllers.put(codigo, room);
        }
    }

    private void initializeProfessors(AgentContainer container, JSONArray professorJson,
                                      List<AgentController> controllers, int currIteration) throws StaleProxyException {
        for (int i = 0; i < professorJson.size(); i++) {
            JSONObject profJson = (JSONObject) professorJson.get(i);
            Object[] profArgs = {profJson.toJSONString(), i, currIteration, scenarioName};

            AgentController prof = container.createNewAgent(
                    AgenteProfesor.AGENT_NAME + i,
                    AgenteProfesor.class.getName(),
                    profArgs
            );
            controllers.add(prof);
            prof.start();
        }
    }

    private void configureRooms(Map<String, AgentController> controllers, int totalSubjects) {
        controllers.forEach((codigo, controller) -> {
            try {
                interfaces.SalaInterface roomInterface =
                        controller.getO2AInterface(interfaces.SalaInterface.class);
                if (roomInterface != null) {
                    roomInterface.setTotalSolicitudes(totalSubjects);
                }
            } catch (StaleProxyException e) {
                log("Error configuring room " + codigo + ": " + e.getMessage());
            }
        });
    }

    private void saveResults() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // Save detailed results
        JSONArray jsonResults = new JSONArray();
        results.forEach(result -> jsonResults.add(result.toJson()));

        String fullLogPath = String.format("%s/%s/iteration_log_%s.json",
                RESULTS_DIR, scenarioName, timestamp);

        //Path resultsPath = Paths.get(RESULTS_DIR, "iteration_results_" + timestamp + ".json");
        Path resultsPath = Paths.get(fullLogPath);
        try (Writer writer = Files.newBufferedWriter(resultsPath)) {
            jsonResults.writeJSONString(writer);
        }

        // Calculate and save summary
        List<IterationResult> successfulRuns = results.stream()
                .filter(r -> "success".equals(r.status))
                .toList();

        if (!successfulRuns.isEmpty()) {
            DoubleSummaryStatistics durationStats = successfulRuns.stream()
                    .mapToDouble(r -> r.duration)
                    .summaryStatistics();

            JSONObject summary = new JSONObject();
            summary.put("totalIterations", numIterations);
            summary.put("successfulRuns", successfulRuns.size());
            summary.put("failedRuns", results.size() - successfulRuns.size());
            summary.put("averageDuration", durationStats.getAverage());
            summary.put("minDuration", durationStats.getMin());
            summary.put("maxDuration", durationStats.getMax());
            summary.put("stdDevDuration", calculateStdDev(successfulRuns));
            summary.put("avgProfessorAssignments",
                    successfulRuns.stream().mapToDouble(r -> r.professorAssignments).average().orElse(0));
            summary.put("avgRoomUtilization",
                    successfulRuns.stream().mapToDouble(r -> r.roomUtilization).average().orElse(0));

            String summaryPathStr = String.format("%s/%s/iteration_summary_%s.json",
                    RESULTS_DIR, scenarioName, timestamp);

            //Path summaryPath = Paths.get(RESULTS_DIR, "iteration_summary_" + timestamp + ".json");
            Path summaryPath = Paths.get(summaryPathStr);
            try (Writer writer = Files.newBufferedWriter(summaryPath)) {
                summary.writeJSONString(writer);
            }

            printSummary(summary);
        }
    }

    private double calculateStdDev(List<IterationResult> results) {
        double mean = results.stream().mapToDouble(r -> r.duration).average().orElse(0);
        double variance = results.stream()
                .mapToDouble(r -> Math.pow(r.duration - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private void printSummary(JSONObject summary) {
        log("\nIteration Summary:");
        log("-".repeat(50));
        log(String.format("Total Iterations: %d", summary.get("totalIterations")));
        log(String.format("Successful Runs: %d", summary.get("successfulRuns")));
        log(String.format("Failed Runs: %d", summary.get("failedRuns")));
        log(String.format("Average Duration: %.2f ms", (Double)summary.get("averageDuration")));
        log(String.format("Min Duration: %.2f ms", (Double)summary.get("minDuration")));
        log(String.format("Max Duration: %.2f ms", (Double)summary.get("maxDuration")));
        log(String.format("Std Dev Duration: %.2f ms", (Double)summary.get("stdDevDuration")));
        log(String.format("Avg Professor Assignments: %.2f",
                (Double)summary.get("avgProfessorAssignments")));
        log(String.format("Avg Room Utilization: %.2f",
                (Double)summary.get("avgRoomUtilization")));
    }

    public void runIterations() {
        try {
            for (int i = 0; i < numIterations; i++) {
                runSingleIteration(i + 1);
            }
            saveResults();

        } catch (Exception e) {
            log("Critical error during iterations: " + e.getMessage());
            e.printStackTrace();
        } finally {
            logWriter.close();
        }
    }

    public static void main(String[] args) {
        try {
            for (String arg : args) {
                System.out.println("Argument: " + arg);
            }

            int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            String selectedScenario = args.length > 1 ? args[1].toLowerCase() : "small";

            System.out.println("Running " + iterations + " iterations with scenario: " + selectedScenario);

            if (!Arrays.asList("small", "medium", "full").contains(selectedScenario)) {
                System.err.println("Invalid scenario. Use 'small', 'medium', or 'full'.");
                return;
            }

            //SimpleRTT.getInstance().changeToScenarioPath(selectedScenario);
            RTTLogger.getInstance().start(selectedScenario);

            IterativeAplicacion runner = new IterativeAplicacion(iterations, selectedScenario);
            runner.runIterations();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}