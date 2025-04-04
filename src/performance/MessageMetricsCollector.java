package performance;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.DFService;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

public class MessageMetricsCollector {
    private static final String BASE_PATH = "performance_logs/";
    private final Agent monitoredAgent;
    private final String agentType;
    private PrintWriter rttWriter;
    private PrintWriter dfWriter;
    private final ConcurrentHashMap<String, Long> messageStartTimes = new ConcurrentHashMap<>();

    public MessageMetricsCollector(Agent agent, String type) {
        this.monitoredAgent = agent;
        this.agentType = type;
        initializeLogFiles();
    }

    private void initializeLogFiles() {
        try {
            File directory = new File(BASE_PATH);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            // RTT log file
            String rttFilename = String.format("%s%s_%s_%s_rtt.csv",
                    BASE_PATH, agentType, monitoredAgent.getLocalName(), timestamp);
            rttWriter = new PrintWriter(new FileWriter(rttFilename));
            rttWriter.println("Timestamp,ConversationId,MessageType,RTT_ms,SenderAgent,ReceiverAgent");

            // DF log file
            String dfFilename = String.format("%s%s_%s_%s_df.csv",
                    BASE_PATH, agentType, monitoredAgent.getLocalName(), timestamp);
            dfWriter = new PrintWriter(new FileWriter(dfFilename));
            dfWriter.println("Timestamp,Operation,ResponseTime_ms,NumResults,Status");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void recordMessageSent(ACLMessage msg) {
        messageStartTimes.put(msg.getConversationId(), System.nanoTime());
    }

    public void recordMessageReceived(ACLMessage msg) {
        Long startTime = messageStartTimes.remove(msg.getConversationId());
        if (startTime != null) {
            long rtt = (System.nanoTime() - startTime) / 1_000_000; // Convert to milliseconds
            rttWriter.printf("%s,%s,%s,%d,%s,%s%n",
                    LocalDateTime.now(),
                    msg.getConversationId(),
                    ACLMessage.getPerformative(msg.getPerformative()),
                    rtt,
                    msg.getSender().getLocalName(),
                    msg.getAllReceiver().next().toString());
            rttWriter.flush();
        }
    }

    public void recordDFOperation(String operation, long startTime, int numResults, String status) {
        long responseTime = (System.nanoTime() - startTime) / 1_000_000; // Convert to milliseconds
        dfWriter.printf("%s,%s,%d,%d,%s%n",
                LocalDateTime.now(),
                operation,
                responseTime,
                numResults,
                status);
        dfWriter.flush();
    }

    public CyclicBehaviour createMessageMonitorBehaviour() {
        return new CyclicBehaviour(monitoredAgent) {
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg != null) {
                    recordMessageReceived(msg);
                } else {
                    block();
                }
            }
        };
    }

    public void measureDFSearch(String serviceType) {
        long startTime = System.nanoTime();
        try {
            DFAgentDescription template = new DFAgentDescription();
            DFAgentDescription[] results = DFService.search(monitoredAgent, template);
            recordDFOperation("search", startTime, results.length, "success");
        } catch (Exception e) {
            recordDFOperation("search", startTime, 0, "error: " + e.getMessage());
        }
    }

    public void close() {
        if (rttWriter != null) {
            rttWriter.close();
        }
        if (dfWriter != null) {
            dfWriter.close();
        }
    }
}