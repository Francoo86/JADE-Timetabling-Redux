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
    private static final String CPU_FILE = "cpu_metrics.csv";
    private static final String DF_FILE = "df_metrics.csv";
    private static final String RTT_FILE = "rtt_metrics.csv";

    private static final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    // Static writers shared across instances
    private static PrintWriter cpuWriter;
    private static PrintWriter dfWriter;
    private static PrintWriter rttWriter;
    private static boolean isInitialized = false;
    private static final Object initLock = new Object();

    private final String iterationId;
    private ScheduledFuture<?> monitoringTask;

    public PerformanceMonitor(String iterationId) {
        this.iterationId = iterationId;
        initializeIfNeeded();
    }

    private void initializeIfNeeded() {
        if (!isInitialized) {
            synchronized(initLock) {
                if (!isInitialized) {
                    try {
                        Files.createDirectories(Paths.get(BASE_PATH));

                        // Initialize writers in append mode
                        cpuWriter = new PrintWriter(new FileWriter(BASE_PATH + CPU_FILE, true));
                        dfWriter = new PrintWriter(new FileWriter(BASE_PATH + DF_FILE, true));
                        rttWriter = new PrintWriter(new FileWriter(BASE_PATH + RTT_FILE, true));

                        // Write headers if files are empty
                        writeHeadersIfNeeded();

                        isInitialized = true;
                    } catch (IOException e) {
                        System.err.println("Error initializing performance monitor: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void writeHeadersIfNeeded() throws IOException {
        if (new File(BASE_PATH + CPU_FILE).length() == 0) {
            cpuWriter.println("Iteration,Timestamp,CPUUsage,SystemLoad,ProcessCPUTime,AvailableProcessors");
            cpuWriter.flush();
        }

        if (new File(BASE_PATH + DF_FILE).length() == 0) {
            dfWriter.println("Iteration,Timestamp,Operation,ResponseTime_ms,NumResults,Status");
            dfWriter.flush();
        }

        if (new File(BASE_PATH + RTT_FILE).length() == 0) {
            rttWriter.println("Iteration,Timestamp,ConversationId,MessageType,RTT_ms,SenderAgent,ReceiverAgent");
            rttWriter.flush();
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
    }

    private void recordCPUMetrics() {
        double cpuLoad = osBean.getProcessCpuLoad() * 100;
        double systemLoad = osBean.getSystemCpuLoad() * 100;
        long processCpuTime = osBean.getProcessCpuTime();
        int availableProcessors = osBean.getAvailableProcessors();

        synchronized(cpuWriter) {
            cpuWriter.printf("%s,%s,%.2f,%.2f,%d,%d%n",
                    iterationId,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
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
                    iterationId,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
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
                    iterationId,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    conversationId,
                    messageType,
                    rtt,
                    sender,
                    receiver
            );
            rttWriter.flush();
        }
    }

    public static void closeAllWriters() {
        if (cpuWriter != null) {
            synchronized(cpuWriter) {
                cpuWriter.close();
            }
        }
        if (dfWriter != null) {
            synchronized(dfWriter) {
                dfWriter.close();
            }
        }
        if (rttWriter != null) {
            synchronized(rttWriter) {
                rttWriter.close();
            }
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }

        isInitialized = false;
    }

    public static void cleanupFiles() {
        try {
            Files.deleteIfExists(Paths.get(BASE_PATH + CPU_FILE));
            Files.deleteIfExists(Paths.get(BASE_PATH + DF_FILE));
            Files.deleteIfExists(Paths.get(BASE_PATH + RTT_FILE));
        } catch (IOException e) {
            System.err.println("Error cleaning up metric files: " + e.getMessage());
        }
    }

    public String getIterationId() {
        return iterationId;
    }
}