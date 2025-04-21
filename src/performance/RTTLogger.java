package performance;

import jade.core.AID;
import jade.lang.acl.ACLMessage;
import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RTTLogger for JADE that mimics the functionality of the SPADE RTTLogger
 * Used to measure round trip times for agent communications
 */
public class RTTLogger {
    private static final class RTTLoggerHolder {
        static final RTTLogger INSTANCE = new RTTLogger();
    }

    // Map to track pending requests
    private final Map<String, RequestData> pendingRequests;

    // Map to track all outgoing messages
    private final Map<String, RequestData> allOutgoingMessages;

    // Queue for storing measurements that need to be written to file
    private final BlockingQueue<RTTMeasurement> writeQueue;

    // Path to output CSV file
    private Path csvPath;

    // Background thread for writing measurements
    private ExecutorService writerThread;
    private ExecutorService cleanupThread;
    private AtomicBoolean isRunning;

    /**
     * Inner class to store data about a request
     */
    private static class RequestData {
        final long startTimeNano;
        final long startTimeWall;
        final String performative;
        final String receiver;
        final String ontology;
        final Map<String, Object> additionalInfo;
        int expectedResponses;
        int responsesReceived;

        RequestData(long startTimeNano, long startTimeWall, String performative,
                    String receiver, String ontology, Map<String, Object> additionalInfo) {
            this.startTimeNano = startTimeNano;
            this.startTimeWall = startTimeWall;
            this.performative = performative;
            this.receiver = receiver;
            this.ontology = ontology != null ? ontology : "NOT-SPECIFIED";
            this.additionalInfo = additionalInfo;
            this.expectedResponses = additionalInfo != null &&
                    additionalInfo.containsKey("expected_responses") ?
                    (Integer)additionalInfo.get("expected_responses") : 1;
            this.responsesReceived = 0;
        }
    }

    /**
     * Represents a single RTT measurement with metadata
     */
    public static class RTTMeasurement {
        final Instant timestamp;
        final String sender;
        final String receiver;
        final String conversationId;
        final String performative;
        final double rtt;  // in milliseconds
        final int messageSize;
        final boolean success;
        final Map<String, Object> additionalInfo;
        final String ontology;

        public RTTMeasurement(Instant timestamp, String sender, String receiver,
                              String conversationId, String performative, double rtt,
                              int messageSize, boolean success, Map<String, Object> additionalInfo,
                              String ontology) {
            this.timestamp = timestamp;
            this.sender = sender;
            this.receiver = receiver;
            this.conversationId = conversationId;
            this.performative = performative;
            this.rtt = rtt;
            this.messageSize = messageSize;
            this.success = success;
            this.additionalInfo = additionalInfo;
            this.ontology = ontology != null ? ontology : "NOT-SPECIFIED";
        }

        public String toCsvRow() {
            return String.format("%s,%s,%s,%s,%s,%.3f,%d,%b,%s,%s",
                    timestamp.toString(),
                    sender,
                    receiver,
                    conversationId,
                    performative,
                    rtt,
                    messageSize,
                    success,
                    additionalInfo != null ? additionalInfo.toString().replace(",", ";") : "",
                    ontology
            );
        }
    }

    /**
     * Private constructor for singleton pattern
     */
    private RTTLogger() {
        this.pendingRequests = new ConcurrentHashMap<>();
        this.allOutgoingMessages = new ConcurrentHashMap<>();
        this.writeQueue = new LinkedBlockingQueue<>();
        this.isRunning = new AtomicBoolean(false);
    }

    /**
     * Get the singleton instance
     */
    public static RTTLogger getInstance() {
        return RTTLoggerHolder.INSTANCE;
    }

    /**
     * Initialize and start the logger with specified scenario
     * @param scenario The scenario name for logging
     */
    public void start(String scenario) {
        if (isRunning.compareAndSet(false, true)) {
            try {
                // Set up output directory and file
                Path outputPath = Paths.get("agent_output", "rtt_logs", scenario);
                Files.createDirectories(outputPath);

                // Create a unique filename based on current timestamp
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                        .withZone(ZoneId.systemDefault());
                String timestamp = formatter.format(Instant.now());
                csvPath = outputPath.resolve(String.format("rtt_measurements_%s_%s.csv", scenario, timestamp));

                // Write headers if the file doesn't exist
                if (!Files.exists(csvPath)) {
                    Files.write(csvPath,
                            "Timestamp,Sender,Receiver,ConversationID,Performative,RTT_ms,MessageSize_bytes,Success,AdditionalInfo,Ontology\n".getBytes(),
                            StandardOpenOption.CREATE);
                }

                // Start background writer
                writerThread = Executors.newSingleThreadExecutor();
                writerThread.submit(this::backgroundWriter);

                // Start cleanup task for stale entries
                cleanupThread = Executors.newSingleThreadScheduledExecutor();
                ((ScheduledExecutorService)cleanupThread).scheduleAtFixedRate(
                        this::cleanupStaleEntries, 30, 30, TimeUnit.SECONDS);

                System.out.println("RTTLogger started successfully for scenario: " + scenario);
            } catch (IOException e) {
                System.err.println("Error initializing RTTLogger: " + e.getMessage());
                isRunning.set(false);
            }
        }
    }

    /**
     * Stop the logger and clean up resources
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                // Shut down background threads
                if (writerThread != null) {
                    writerThread.shutdown();
                    if (!writerThread.awaitTermination(5, TimeUnit.SECONDS)) {
                        writerThread.shutdownNow();
                    }
                }

                if (cleanupThread != null) {
                    cleanupThread.shutdown();
                    if (!cleanupThread.awaitTermination(5, TimeUnit.SECONDS)) {
                        cleanupThread.shutdownNow();
                    }
                }

                // Flush remaining measurements
                flushRemainingMeasurements();

                System.out.println("RTTLogger stopped successfully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while stopping RTTLogger: " + e.getMessage());
            }
        }
    }

    /**
     * Start tracking a request
     *
     * @param agentName Name of the agent making the request
     * @param conversationId Unique ID for the conversation
     * @param performative The FIPA performative being used
     * @param receiver The receiver of the message
     * @param additionalInfo Additional information to log
     * @param ontology The ontology of the message
     */
    public void startRequest(String agentName, String conversationId, String performative,
                             String receiver, Map<String, Object> additionalInfo, String ontology) {
        if (conversationId == null || conversationId.isEmpty()) {
            System.out.println("Warning: Empty conversation_id in start_request from " + agentName);
            return;
        }

        RequestData startData = new RequestData(
                System.nanoTime(),
                System.currentTimeMillis(),
                performative,
                receiver,
                ontology,
                additionalInfo
        );

        pendingRequests.put(conversationId, startData);
        allOutgoingMessages.put(conversationId, startData);

        System.out.println("DEBUG: " + agentName + " starting request " + conversationId + " to " + receiver);
    }

    /**
     * Record any outgoing message, even without formal start_request
     */
    public void recordMessageSent(String agentName, String conversationId, String performative,
                                  String receiver, String ontology) {
        if (conversationId == null || conversationId.isEmpty()) {
            System.out.println("Warning: Empty conversation_id in record_message_sent from " + agentName);
            return;
        }

        synchronized (allOutgoingMessages) {
            // Only record if not already tracked by start_request
            if (!allOutgoingMessages.containsKey(conversationId)) {
                allOutgoingMessages.put(conversationId, new RequestData(
                        System.nanoTime(),
                        System.currentTimeMillis(),
                        performative,
                        receiver,
                        ontology,
                        null
                ));
                System.out.println("DEBUG: " + agentName + " recording message " + conversationId + " to " + receiver);
            }
        }
    }

    /**
     * Record a received message and calculate RTT
     */
    public void recordMessageReceived(String agentName, String conversationId, String performative,
                                      String sender, int messageSize, String ontology) {
        if (conversationId == null || conversationId.isEmpty()) {
            System.out.println("Warning: Empty conversation_id in record_message_received from " + agentName);
            return;
        }

        // First check if this message exists in our tracking maps
        RequestData outgoingData = allOutgoingMessages.get(conversationId);

        if (outgoingData != null) {
            // Process measurement
            long endTimeNs = System.nanoTime();
            long startTimeNs = outgoingData.startTimeNano;
            double rtt = (endTimeNs - startTimeNs) / 1_000_000.0;

            // Create measurement
            RTTMeasurement measurement = new RTTMeasurement(
                    Instant.now(),
                    agentName,
                    sender,
                    conversationId,
                    performative,
                    rtt,
                    messageSize,
                    true,
                    outgoingData.additionalInfo,
                    outgoingData.ontology != null ? outgoingData.ontology : ontology
            );

            // Queue for writing
            writeQueue.add(measurement);

            System.out.println("DEBUG: " + agentName + " received response for " + conversationId +
                    " from " + sender + ", RTT=" + String.format("%.3f", rtt) + "ms");
        } else {
            System.out.println("DEBUG: " + agentName + " received message " + conversationId +
                    " from " + sender + " with no matching sent message");
        }
    }

    /**
     * End a request and calculate RTT accurately
     */
    public Double endRequest(String agentName, String conversationId, String responsePerformative,
                             int messageSize, boolean success, Map<String, Object> extraInfo, String ontology) {
        if (conversationId == null || conversationId.isEmpty()) {
            System.out.println("Warning: Empty conversation_id in end_request from " + agentName);
            return null;
        }

        synchronized (pendingRequests) {
            // First check pending_requests (formal requests)
            RequestData requestData = pendingRequests.get(conversationId);

            if (requestData == null) {
                // Then check all_outgoing_messages (informal tracking)
                requestData = allOutgoingMessages.get(conversationId);
            }

            if (requestData != null) {
                long endTimeNs = System.nanoTime();
                long startTimeNs = requestData.startTimeNano;
                double rtt = (endTimeNs - startTimeNs) / 1_000_000.0;

                // Combine additional info
                Map<String, Object> additionalInfo = new ConcurrentHashMap<>();
                if (requestData.additionalInfo != null) {
                    additionalInfo.putAll(requestData.additionalInfo);
                }
                if (extraInfo != null) {
                    additionalInfo.putAll(extraInfo);
                }

                // Create measurement
                RTTMeasurement measurement = new RTTMeasurement(
                        Instant.now(),
                        agentName,
                        requestData.receiver,
                        conversationId,
                        responsePerformative != null ? responsePerformative : requestData.performative,
                        rtt,
                        messageSize,
                        success,
                        additionalInfo,
                        ontology != null ? ontology : requestData.ontology
                );

                // Queue measurement for writing
                writeQueue.add(measurement);

                // Remove from pending_requests but keep in all_outgoing_messages for multiple responses
                pendingRequests.remove(conversationId);

                return rtt;
            } else {
                System.out.println("Warning: No request data found for conversation_id " +
                        conversationId + " in end_request");
                return null;
            }
        }
    }

    /**
     * Background task to clean up stale entries
     */
    private void cleanupStaleEntries() {
        try {
            final long STALE_THRESHOLD_MS = 60000; // 60 seconds
            long now = System.currentTimeMillis();

            synchronized (pendingRequests) {
                pendingRequests.entrySet().removeIf(entry ->
                        now - entry.getValue().startTimeWall > STALE_THRESHOLD_MS);
            }

            synchronized (allOutgoingMessages) {
                allOutgoingMessages.entrySet().removeIf(entry ->
                        now - entry.getValue().startTimeWall > STALE_THRESHOLD_MS * 2); // Double timeout
            }
        } catch (Exception e) {
            System.err.println("Error in cleanup task: " + e.getMessage());
        }
    }

    /**
     * Background writer to handle writing measurements to CSV with batching
     */
    private void backgroundWriter() {
        try {
            while (isRunning.get() || !writeQueue.isEmpty()) {
                try {
                    // Process in small batches to handle bursts more efficiently
                    StringBuilder batch = new StringBuilder();
                    int processed = 0;

                    // Try to get up to 50 measurements at once
                    for (int i = 0; i < 50; i++) {
                        RTTMeasurement measurement = writeQueue.poll();
                        if (measurement == null) break;

                        batch.append(measurement.toCsvRow()).append("\n");
                        processed++;
                    }

                    // If batch is empty and queue isn't, wait for an item
                    if (processed == 0 && !writeQueue.isEmpty()) {
                        RTTMeasurement measurement = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (measurement != null) {
                            batch.append(measurement.toCsvRow()).append("\n");
                            processed++;
                        }
                    }

                    // Write batch if not empty
                    if (processed > 0) {
                        try {
                            Files.write(csvPath, batch.toString().getBytes(),
                                    StandardOpenOption.APPEND);
                        } catch (IOException e) {
                            System.err.println("Error writing RTT measurements batch: " + e.getMessage());
                        }
                    } else if (isRunning.get()) {
                        // If no batch was formed and still running, wait a bit
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Background writer interrupted: " + e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error in background writer: " + e.getMessage());
        }
    }

    /**
     * Flush remaining measurements on shutdown
     */
    private void flushRemainingMeasurements() {
        try {
            if (writeQueue.isEmpty()) return;

            StringBuilder batch = new StringBuilder();
            RTTMeasurement measurement;

            while ((measurement = writeQueue.poll()) != null) {
                batch.append(measurement.toCsvRow()).append("\n");
            }

            if (batch.length() > 0) {
                Files.write(csvPath, batch.toString().getBytes(), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            System.err.println("Error flushing measurements: " + e.getMessage());
        }
    }

    /**
     * Generate a unique ID for RTT tracking
     */
    public static String generateRttId() {
        return "rtt-" + UUID.randomUUID().toString();
    }

    /**
     * Convenience method to add RTT tracking to ACLMessage
     */
    public static void addRttTracking(ACLMessage msg) {
        String rttId = generateRttId();
        msg.addUserDefinedParameter("rtt-id", rttId);
    }
}