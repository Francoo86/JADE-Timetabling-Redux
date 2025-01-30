package performance;

import jade.core.Agent;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

public class PerformanceMonitor {
    private static final OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final String BASE_PATH = "performance_logs/";
    private final Agent monitoredAgent;
    private final String agentType;
    private PrintWriter cpuWriter;
    private ScheduledFuture<?> monitoringTask;

    public PerformanceMonitor(Agent agent, String type) {
        this.monitoredAgent = agent;
        this.agentType = type;
        initializeLogDirectory();
    }

    private void initializeLogDirectory() {
        File directory = new File(BASE_PATH);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void startMonitoring() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("%s%s_%s_%s_cpu.csv",
                BASE_PATH, agentType, monitoredAgent.getLocalName(), timestamp);

        try {
            cpuWriter = new PrintWriter(new FileWriter(filename));
            cpuWriter.println("Timestamp,AgentName,CPUUsage,SystemLoad,ProcessCPUTime,AvailableProcessors");

            monitoringTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    double cpuLoad = osBean.getProcessCpuLoad() * 100;
                    double systemLoad = osBean.getSystemCpuLoad() * 100;
                    long processCpuTime = osBean.getProcessCpuTime();
                    int availableProcessors = osBean.getAvailableProcessors();

                    cpuWriter.printf("%s,%s,%.2f,%.2f,%d,%d%n",
                            LocalDateTime.now(),
                            monitoredAgent.getLocalName(),
                            cpuLoad,
                            systemLoad,
                            processCpuTime,
                            availableProcessors);
                    cpuWriter.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 0, 100, TimeUnit.MILLISECONDS);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopMonitoring() {
        if (monitoringTask != null) {
            monitoringTask.cancel(false);
        }
        if (cpuWriter != null) {
            cpuWriter.close();
        }
    }
}