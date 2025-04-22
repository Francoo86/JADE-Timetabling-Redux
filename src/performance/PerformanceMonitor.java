package performance;

import jade.core.AID;
import jade.lang.acl.ACLMessage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public class PerformanceMonitor {
    private static final String BASE_PATH = "PerformanceLogs/";
    private final String iterationId;
    private final String agentId;
    private PrintWriter cpuWriter;
    private PrintWriter dfWriter;
    private PrintWriter rttWriter;
    private ScheduledFuture<?> monitoringTask;
    private final ScheduledExecutorService scheduler;
    private static final OperatingSystemMXBean osBean;
    private final ConcurrentHashMap<String, MessageTimingInfo> messageTimings;

    private String baseScenario;

    // Inner class to store message timing information
    private static class MessageTimingInfo {
        final long startTime;
        final String sender;
        final String receiver;
        final String messageType;

        MessageTimingInfo(long startTime, String sender, String receiver, String messageType) {
            this.startTime = startTime;
            this.sender = sender;
            this.receiver = receiver;
            this.messageType = messageType;
        }
    }

    static {
        osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    }

    public PerformanceMonitor(int iterationNumber, String agentIdentifier, String scenario) {
        this.iterationId = "Iteration" + iterationNumber;
        this.agentId = agentIdentifier;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.messageTimings = new ConcurrentHashMap<>();
        this.baseScenario = scenario;
        initializeWriters();

        // Start periodic cleanup of stale message timings
        scheduler.scheduleAtFixedRate(this::cleanupStaleTimings, 30, 30, TimeUnit.SECONDS);
    }

    private void cleanupStaleTimings() {
        long currentTime = System.nanoTime();
        long timeout = TimeUnit.SECONDS.toNanos(30); // 30 second timeout

        messageTimings.entrySet().removeIf(entry ->
                (currentTime - entry.getValue().startTime) > timeout);
    }

    private void initializeWriters() {
        try {
            String fullPath = "agent_output" + "/" + BASE_PATH + baseScenario + "/";
            // Create directory if it doesn't exist
            Files.createDirectories(Paths.get(fullPath));

            // Define file paths with iteration and agent identifiers
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String cpuPath = String.format("%s/%s_%s_cpu.csv", fullPath, iterationId, timestamp);
            String dfPath = String.format("%s/%s_%s_df.csv", fullPath, iterationId, timestamp);
            String rttPath = String.format("%s/%s_%s_rtt.csv", fullPath, iterationId, timestamp);

            // Initialize writers in append mode
            cpuWriter = new PrintWriter(new FileWriter(cpuPath, true));
            dfWriter = new PrintWriter(new FileWriter(dfPath, true));
            rttWriter = new PrintWriter(new FileWriter(rttPath, true));

            // Write headers
            writeHeaderIfNeeded(cpuPath, "Timestamp,AgentId,CPUUsage,SystemLoad,ProcessCPUTime,AvailableProcessors");
            writeHeaderIfNeeded(dfPath, "Timestamp,AgentId,Operation,ResponseTime_ms,NumResults,Status");
            writeHeaderIfNeeded(rttPath, "Timestamp,AgentId,ConversationId,MessageType,RTT_ms,SenderAgent,ReceiverAgent,MessagePerformative");

        } catch (IOException e) {
            System.err.println("Error initializing writers for " + agentId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void writeHeaderIfNeeded(String filePath, String header) throws IOException {
        if (new File(filePath).length() == 0) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, false))) {
                writer.println(header);
            }
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
            synchronized (this) {
                if (cpuWriter != null) {
                    cpuWriter.close();
                }
                if (dfWriter != null) {
                    dfWriter.close();
                }
                if (rttWriter != null) {
                    rttWriter.close();
                }
            }
        } catch (Exception e) {
            System.err.println("Error closing writers for " + agentId + ": " + e.getMessage());
        }
    }

    private void recordCPUMetrics() {
        double cpuLoad = osBean.getProcessCpuLoad() * 100;
        double systemLoad = osBean.getSystemCpuLoad() * 100;
        long processCpuTime = osBean.getProcessCpuTime();
        int availableProcessors = osBean.getAvailableProcessors();

        synchronized (cpuWriter) {
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

        synchronized (dfWriter) {
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

    // Method to record when a message is sent
    public void recordMessageSent(ACLMessage msg, String messageType) {
        String conversationId = msg.getConversationId();
        AID senderAID = msg.getSender();
        if(conversationId == null || senderAID == null) return;

        String sender = senderAID.getLocalName();
        String receiver = msg.getAllReceiver().hasNext() ?
                msg.getAllReceiver().next().toString() : "MULTICAST";

        messageTimings.put(conversationId, new MessageTimingInfo(
                System.nanoTime(),
                sender,
                receiver,
                messageType
        ));

        // Log the send event
        logMessageEvent(conversationId, messageType + "_SENT", 0, sender, receiver, msg.getPerformative());
    }

    // Method to record when a message is received and calculate RTT
    public void recordMessageReceived(ACLMessage msg, String messageType) {
        String conversationId = msg.getConversationId();
        MessageTimingInfo timing = messageTimings.remove(conversationId);

        if (timing != null) {
            long rtt = (System.nanoTime() - timing.startTime) / 1_000_000; // Convert to milliseconds
            logMessageEvent(conversationId, messageType + "_RECEIVED", rtt,
                    timing.sender, msg.getSender().getLocalName(), msg.getPerformative());
        } else {
            // Log receive event even without RTT information
            logMessageEvent(conversationId, messageType + "_RECEIVED", -1,
                    "UNKNOWN", msg.getSender().getLocalName(), msg.getPerformative());
        }
    }

    private void logMessageEvent(String conversationId, String messageType, long rtt,
                                 String sender, String receiver, int performative) {
        synchronized (rttWriter) {
            rttWriter.printf("%s,%s,%s,%s,%d,%s,%s,%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    agentId,
                    conversationId,
                    messageType,
                    rtt,
                    sender,
                    receiver,
                    ACLMessage.getPerformative(performative)
            );
            rttWriter.flush();
        }
    }

    // Legacy method for backward compatibility
    public void recordMessageMetrics(String conversationId, String messageType,
                                     long rtt, String sender, String receiver) {
        synchronized (rttWriter) {
            rttWriter.printf("%s,%s,%s,%s,%d,%s,%s,%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    agentId,
                    conversationId,
                    messageType,
                    rtt,
                    sender,
                    receiver,
                    "UNKNOWN"
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