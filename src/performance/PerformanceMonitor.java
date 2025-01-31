package performance;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public class PerformanceMonitor {
    private static final String BASE_PATH = "performance_logs/";
    private final String iterationId;
    private final String agentId;
    private PrintWriter cpuWriter;
    private PrintWriter dfWriter;
    private PrintWriter rttWriter;
    private ScheduledFuture<?> monitoringTask;
    private final ScheduledExecutorService scheduler;
    private static final OperatingSystemMXBean osBean;

    static {
        osBean = (OperatingSystemMXBean) ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    }

    public PerformanceMonitor(int iterationNumber, String agentIdentifier) {
        this.iterationId = "Iteration" + iterationNumber;
        this.agentId = agentIdentifier;
        this.scheduler = Executors.newScheduledThreadPool(1);
        initializeWriters();
    }

    private void initializeWriters() {
        try {
            // Create directory if it doesn't exist
            Files.createDirectories(Paths.get(BASE_PATH));

            // Define file paths
            String cpuPath = String.format("%s/%s_cpu.csv", BASE_PATH, iterationId);
            String dfPath = String.format("%s/%s_df.csv", BASE_PATH, iterationId);
            String rttPath = String.format("%s/%s_rtt.csv", BASE_PATH, iterationId);

            // Initialize writers in append mode
            cpuWriter = new PrintWriter(new FileWriter(cpuPath, true));
            dfWriter = new PrintWriter(new FileWriter(dfPath, true));
            rttWriter = new PrintWriter(new FileWriter(rttPath, true));

            // Write headers if files are empty
            writeHeaderIfNeeded(cpuPath, "Timestamp,AgentId,CPUUsage,SystemLoad,ProcessCPUTime,AvailableProcessors");
            writeHeaderIfNeeded(dfPath, "Timestamp,AgentId,Operation,ResponseTime_ms,NumResults,Status");
            writeHeaderIfNeeded(rttPath, "Timestamp,AgentId,ConversationId,MessageType,RTT_ms,SenderAgent,ReceiverAgent");

        } catch (IOException e) {
            System.err.println("Error initializing writers for iteration " + iterationId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void writeHeaderIfNeeded(String filePath, String header) throws IOException {
        if (new File(filePath).length() == 0) {
            PrintWriter writer = new PrintWriter(new FileWriter(filePath, false));
            writer.println(header);
            writer.flush();
            writer.close();
        }
    }

    public void startMonitoring() {
        monitoringTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                recordCPUMetrics();
            } catch (Exception e) {
                System.err.println("Error recording CPU metrics: " + e.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    public void stopMonitoring() {
        if (monitoringTask != null) {
            monitoringTask.cancel(false);
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        closeWriters();
    }

    private void closeWriters() {
        try {
            if (cpuWriter != null) {
                synchronized (cpuWriter) {
                    cpuWriter.close();
                }
            }
            if (dfWriter != null) {
                synchronized (dfWriter) {
                    dfWriter.close();
                }
            }
            if (rttWriter != null) {
                synchronized (rttWriter) {
                    rttWriter.close();
                }
            }
        } catch (Exception e) {
            System.err.println("Error closing writers: " + e.getMessage());
        }
    }

    private void recordCPUMetrics() {
        double cpuLoad = osBean.getProcessCpuLoad() * 100;
        double systemLoad = osBean.getSystemCpuLoad() * 100;
        long processCpuTime = osBean.getProcessCpuTime();
        int availableProcessors = osBean.getAvailableProcessors();

        synchronized(cpuWriter) {
            cpuWriter.printf("%s,%s,%.2f,%.2f,%d,%d%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    agentId,
                    cpuLoad,
                    systemLoad,
                    processCpuTime,
                    availableProcessors
            );
            cpuWriter.flush();
        }
    }

    public void recordDFOperation(String operation, long startTime, int numResults, String status) {
        long responseTime = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

        synchronized(dfWriter) {
            dfWriter.printf("%s,%s,%s,%d,%d,%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    agentId,
                    operation,
                    responseTime,
                    numResults,
                    status
            );
            dfWriter.flush();
        }
    }

    public void recordMessageMetrics(String conversationId, String messageType,
                                     long rtt, String sender, String receiver) {
        synchronized(rttWriter) {
            rttWriter.printf("%s,%s,%s,%s,%d,%s,%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    agentId,
                    conversationId,
                    messageType,
                    rtt,
                    sender,
                    receiver
            );
            rttWriter.flush();
        }
    }

    public static void cleanupFiles() {
        try {
            Files.walk(Paths.get(BASE_PATH))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Error deleting file " + path + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error cleaning up metric files: " + e.getMessage());
        }
    }

    public String getIterationId() {
        return iterationId;
    }

    public String getAgentId() {
        return agentId;
    }
}