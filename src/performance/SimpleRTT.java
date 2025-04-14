package performance;

import jade.core.AID;
import jade.lang.acl.ACLMessage;
import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

public class SimpleRTT {
    private static SimpleRTT instance;
    private final Map<String, MessageData> messageTimestamps;
    private String outputFile;
    private static final long CLEANUP_THRESHOLD = 30000;

    private static class MessageData {
        long sendTimeNano;
        long sendTimeMillis;
        String senderAgent;
        String receiverAgent;
        String messageType;

        MessageData(long sendTimeNano, long sendTimeMillis, String sender, String receiver, String type) {
            this.sendTimeNano = sendTimeNano;
            this.sendTimeMillis = sendTimeMillis;
            this.senderAgent = sender;
            this.receiverAgent = receiver != null ? receiver : "broadcast";
            this.messageType = type;
        }
    }

    private SimpleRTT() {
        this.messageTimestamps = new ConcurrentHashMap<>();
        this.outputFile = "agent_output/rtt_data.csv";
        createOutputFile();
        startCleanupTask();
    }

    public synchronized void changeToScenarioPath(String scenarioPath) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSSSSSX")
                .withZone(ZoneId.systemDefault());
        String validTimestamp = formatter.format(Instant.now());
        this.outputFile = String.format("%s/rtt_data_%s.csv", scenarioPath, validTimestamp);
        createOutputFile();
    }

    public static synchronized SimpleRTT getInstance() {
        if (instance == null) {
            instance = new SimpleRTT();
        }
        return instance;
    }

    private void createOutputFile() {
        try {
            String fullPath = Paths.get(outputFile).toAbsolutePath().toString();

            File outputDir = new File(fullPath).getParentFile();
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write("timestamp,conversation_id,message_type,sender,receiver,rtt_ms\n");
            }
        } catch (IOException e) {
            System.err.println("Error creating RTT output file: " + e.getMessage());
        }
    }

    public void messageSent(String conversationId, AID sender, AID receiver, String messageType) {
        if (conversationId == null) return;

        messageTimestamps.put(conversationId,
                new MessageData(
                        System.nanoTime(),
                        System.currentTimeMillis(),
                        sender != null ? sender.toString() : "unknown",
                        receiver != null ? receiver.toString() : "broadcast",
                        messageType != null ? messageType : "unknown"
                )
        );
    }

    public void messageReceived(String conversationId, ACLMessage response) {
        if (conversationId == null) return;

        MessageData sendData = messageTimestamps.remove(conversationId);
        if (sendData != null) {
            long rttNano = System.nanoTime() - sendData.sendTimeNano;
            double rttMs = rttNano / 1_000_000.0; // Convertir a milisegundos con precisión decimal
            if (rttMs >= 0) {
                saveRTTData(conversationId, sendData, rttMs);
            }
        }
    }

    private void saveRTTData(String conversationId, MessageData data, double rtt) {
        try {
            String entry = String.format("%d,%s,%s,%s,%s,%.10f\n",
                    data.sendTimeMillis,
                    conversationId,
                    data.messageType,
                    data.senderAgent,
                    data.receiverAgent,
                    rtt
            );

            Files.write(
                    Paths.get(outputFile),
                    entry.getBytes(),
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("Error saving RTT data: " + e.getMessage());
        }
    }

    private void startCleanupTask() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            messageTimestamps.entrySet().removeIf(entry ->
                    (currentTime - entry.getValue().sendTimeMillis) > CLEANUP_THRESHOLD
            );
        }, 30, 30, TimeUnit.SECONDS);
    }
}