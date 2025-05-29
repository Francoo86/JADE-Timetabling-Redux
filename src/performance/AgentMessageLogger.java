package performance;

import jade.lang.acl.ACLMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simplified Message Logger for JADE agents
 * Focuses only on message communications like your professor's example
 */
public class AgentMessageLogger {
    private static final class LoggerHolder {
        static final AgentMessageLogger INSTANCE = new AgentMessageLogger();
    }

    public static class MessageLogEntry {
        final LocalDateTime timestamp;
        final String agent;
        final String agentAction; // SEND or RECEIVE
        final String sender;
        final String receivers;
        final String performative;
        final String conversationId;
        final String content;
        final long sequenceId;

        public MessageLogEntry(String agent, String agentAction, String sender,
                               String receivers, String performative, String conversationId,
                               String content, long sequenceId) {
            this.timestamp = LocalDateTime.now();
            this.agent = agent;
            this.agentAction = agentAction;
            this.sender = sender;
            this.receivers = receivers;
            this.performative = performative;
            this.conversationId = conversationId != null ? conversationId : "";
            this.content = content != null ? content.substring(0, Math.min(content.length(), 100)) : "";
            this.sequenceId = sequenceId;
        }

        public String toCsvRow() {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            return String.format("%s,%s,%s,%s,%s,%s,%s,\"%s\",%d",
                    timestamp.format(formatter),
                    agent,
                    agentAction,
                    sender,
                    receivers,
                    performative,
                    conversationId,
                    content.replace("\"", "\"\""), // Escape quotes
                    sequenceId
            );
        }
    }

    private final BlockingQueue<MessageLogEntry> logQueue;
    private final ExecutorService writerThread;
    private final AtomicBoolean isRunning;
    private final AtomicLong sequenceCounter;
    private Path logPath;

    private AgentMessageLogger() {
        this.logQueue = new LinkedBlockingQueue<>();
        this.writerThread = Executors.newSingleThreadExecutor();
        this.isRunning = new AtomicBoolean(false);
        this.sequenceCounter = new AtomicLong(0);
    }

    public static AgentMessageLogger getInstance() {
        return LoggerHolder.INSTANCE;
    }

    public void start(String scenario) {
        if (isRunning.compareAndSet(false, true)) {
            try {
                Path outputPath = Paths.get("agent_output", "message_logs", scenario);
                Files.createDirectories(outputPath);

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
                String timestamp = LocalDateTime.now().format(formatter);
                logPath = outputPath.resolve(String.format("agent_messages_%s_%s.csv", scenario, timestamp));

                // Write CSV headers (matching your professor's format)
                String headers = "timestamp,agent,agentAction,sender,receivers,performative,conversationId,content,sequenceId\n";
                Files.write(logPath, headers.getBytes(), StandardOpenOption.CREATE);

                writerThread.submit(this::backgroundWriter);
                System.out.println("Simple Message Logger started for scenario: " + scenario);
            } catch (IOException e) {
                System.err.println("Error starting Simple Message Logger: " + e.getMessage());
                isRunning.set(false);
            }
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                writerThread.shutdown();
                flushRemainingEntries();
                System.out.println("Simple Message Logger stopped");
            } catch (Exception e) {
                System.err.println("Error stopping logger: " + e.getMessage());
            }
        }
    }

    /**
     * Log a message being sent
     */
    public void logMessageSent(String agentName, ACLMessage message) {
        if (!isRunning.get()) return;

        StringBuilder receivers = new StringBuilder();
        message.getAllReceiver().forEachRemaining(obj -> {
            jade.core.AID aid = (jade.core.AID) obj;
            if (receivers.length() > 0) receivers.append(";");
            receivers.append(aid.getLocalName());
        });

        MessageLogEntry entry = new MessageLogEntry(
                agentName,
                "SEND",
                agentName,
                receivers.toString(),
                getPerformativeName(message.getPerformative()),
                message.getConversationId(),
                message.getContent(),
                sequenceCounter.incrementAndGet()
        );
        logQueue.offer(entry);
    }

    /**
     * Log a message being received
     */
    public void logMessageReceived(String agentName, ACLMessage message) {
        if (!isRunning.get()) return;

        MessageLogEntry entry = new MessageLogEntry(
                agentName,
                "RECEIVE",
                message.getSender().getLocalName(),
                agentName,
                getPerformativeName(message.getPerformative()),
                message.getConversationId(),
                message.getContent(),
                sequenceCounter.incrementAndGet()
        );
        logQueue.offer(entry);
    }

    private String getPerformativeName(int performative) {
        switch (performative) {
            case ACLMessage.CFP: return "CFP";
            case ACLMessage.PROPOSE: return "PROPOSE";
            case ACLMessage.ACCEPT_PROPOSAL: return "ACCEPT_PROPOSAL";
            case ACLMessage.REJECT_PROPOSAL: return "REJECT_PROPOSAL";
            case ACLMessage.INFORM: return "INFORM";
            case ACLMessage.REQUEST: return "REQUEST";
            case ACLMessage.REFUSE: return "REFUSE";
            case ACLMessage.AGREE: return "AGREE";
            case ACLMessage.CANCEL: return "CANCEL";
            case ACLMessage.FAILURE: return "FAILURE";
            case ACLMessage.NOT_UNDERSTOOD: return "NOT_UNDERSTOOD";
            default: return "UNKNOWN_" + performative;
        }
    }

    private void backgroundWriter() {
        try {
            while (isRunning.get() || !logQueue.isEmpty()) {
                MessageLogEntry entry = logQueue.take();
                if (entry != null) {
                    String csvRow = entry.toCsvRow() + "\n";
                    Files.write(logPath, csvRow.getBytes(), StandardOpenOption.APPEND);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("Error writing message log: " + e.getMessage());
        }
    }

    private void flushRemainingEntries() {
        try {
            StringBuilder batch = new StringBuilder();
            MessageLogEntry entry;
            while ((entry = logQueue.poll()) != null) {
                batch.append(entry.toCsvRow()).append("\n");
            }
            if (batch.length() > 0) {
                Files.write(logPath, batch.toString().getBytes(), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            System.err.println("Error flushing message logs: " + e.getMessage());
        }
    }
}