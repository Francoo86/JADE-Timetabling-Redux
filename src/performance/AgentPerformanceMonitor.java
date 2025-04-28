package performance;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.*;
import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;
import com.sun.management.OperatingSystemMXBean;

/**
 * A performance monitor for JADE agents.
 */
public class AgentPerformanceMonitor {
    // Shared resources
    private static final String BASE_PATH = "agent_output/PerformanceLogs/MetricsAnalysis/";
    private static final ReentrantLock fileLock = new ReentrantLock();
    private static final ConcurrentHashMap<String, String> scenarioFiles = new ConcurrentHashMap<>();

    // Instance properties
    private final String agentId;
    private final String agentType;
    private final String scenario;
    private String csvFilePath;
    private ScheduledFuture<?> monitoringTask;
    private final ScheduledExecutorService scheduler;
    private volatile boolean isMonitoring = false;

    // Monitoring objects
    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;
    private final Runtime runtime;

    /**
     * Creates a new performance monitor for an agent.
     */
    public AgentPerformanceMonitor(String agentId, String agentType, String scenario) {
        this.agentId = agentId;
        this.agentType = agentType;
        this.scenario = scenario;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Monitor-" + agentId);
            return t;
        });

        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.runtime = Runtime.getRuntime();

        // Initialize file path immediately
        this.csvFilePath = initScenarioFile(scenario);
        System.out.println("[Monitor] Agent " + agentId + " using CSV file: " + csvFilePath);
    }

    /**
     * Initialize the file for a scenario.
     */
    private String initScenarioFile(String scenario) {
        try {
            // Create directory
            File outputDir = new File(BASE_PATH + scenario + "/");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
                System.out.println("[Monitor] Created directory: " + outputDir.getAbsolutePath());
            }

            // Create file with SPADE-like naming
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filePath = outputDir.getAbsolutePath() + "/agent_metrics_" + scenario + "_" + timestamp + ".csv";

            File csvFile = new File(filePath);

            // Create file with header if it doesn't exist
            if (!csvFile.exists()) {
                try (FileWriter writer = new FileWriter(csvFile)) {
                    writer.write("Timestamp,AgentID,AgentType,CPU_Percent,Memory_Used_MB,Memory_Total_MB,Heap_Used_MB,Heap_Max_MB,System_Load_Percent\n");
                    System.out.println("[Monitor] Created new CSV file with header: " + filePath);
                } catch (IOException e) {
                    System.err.println("[Monitor] Error writing header: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            scenarioFiles.put(scenario, filePath);
            return filePath;

        } catch (Exception e) {
            System.err.println("[Monitor] Error initializing scenario file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Start collecting performance metrics.
     */
    public void startMonitoring(long intervalMs) {
        if (isMonitoring || csvFilePath == null) return;

        isMonitoring = true;
        monitoringTask = scheduler.scheduleAtFixedRate(
                this::collectMetrics,
                0,
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        System.out.println("[Monitor] Started monitoring for " + agentId + " with interval " + intervalMs + "ms");
    }

    /**
     * Start with default 1-second interval
     */
    public void startMonitoring() {
        startMonitoring(1000);
    }

    /**
     * Stop collecting metrics.
     */
    public void stopMonitoring() {
        if (!isMonitoring) return;

        isMonitoring = false;

        if (monitoringTask != null) {
            monitoringTask.cancel(false);
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[Monitor] Stopped monitoring for " + agentId);
    }

    private void collectMetrics() {
        if (csvFilePath == null) {
            System.err.println("[Monitor] Cannot collect metrics - CSV path is null for " + agentId);
            return;
        }

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            // CPU metrics
            double cpuPercent = osBean.getProcessCpuLoad() * 100.0;
            double systemLoadPercent = osBean.getCpuLoad() * 100.0;

            // Memory metrics
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            double usedMemoryMB = usedMemory / (1024.0 * 1024.0);
            double totalMemoryMB = totalMemory / (1024.0 * 1024.0);

            // Heap metrics
            double heapUsedMB = memoryBean.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
            double heapMaxMB = memoryBean.getHeapMemoryUsage().getMax() / (1024.0 * 1024.0);

            // Format CSV line
            String csvLine = String.format(Locale.US,
                    "%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                    timestamp, agentId, agentType,
                    cpuPercent, usedMemoryMB, totalMemoryMB,
                    heapUsedMB, heapMaxMB, systemLoadPercent);

            // Write to file with synchronized block to prevent concurrent writes
            fileLock.lock();
            try {
                File csvFile = new File(csvFilePath);
                if (!csvFile.exists()) {
                    System.err.println("[Monitor] CSV file disappeared: " + csvFilePath);
                    return;
                }

                try (FileWriter writer = new FileWriter(csvFile, true)) {
                    writer.write(csvLine + "\n");
                    // Debug output to confirm data is being written
                    //System.out.println("[Monitor] Wrote metrics for " + agentId);
                }
            } catch (IOException e) {
                System.err.println("[Monitor] Error writing metrics: " + e.getMessage());
                e.printStackTrace();
            } finally {
                fileLock.unlock();
            }

        } catch (Exception e) {
            System.err.println("[Monitor] Error collecting metrics for " + agentId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}