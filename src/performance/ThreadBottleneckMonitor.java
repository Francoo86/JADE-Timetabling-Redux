package performance;

import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Monitors thread bottlenecks by tracking CPU time per thread
 * and generating thread dumps for detailed analysis.
 */
public class ThreadBottleneckMonitor {
    private static final String BASE_PATH = "agent_output/PerformanceLogs/ThreadInfo/";
    private final String iterationId;
    private final String agentId;
    private final String baseScenario;
    private PrintWriter threadWriter;
    private ScheduledFuture<?> monitoringTask;
    private final ScheduledExecutorService scheduler;
    private final ThreadMXBean threadMXBean;
    private final Map<Long, Long> previousCpuTimes = new HashMap<>();
    private final Map<Long, Long> previousUserTimes = new HashMap<>();
    private long monitoringInterval;

    public ThreadBottleneckMonitor(int iterationNumber, String agentIdentifier, String scenario) {
        this.iterationId = "Iteration" + iterationNumber;
        this.agentId = agentIdentifier;
        this.baseScenario = scenario;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.threadMXBean = ManagementFactory.getThreadMXBean();

        // Enable thread CPU time monitoring
        if (threadMXBean.isThreadCpuTimeSupported()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        } else {
            System.err.println("Thread CPU time monitoring not supported on this JVM");
        }

        initializeWriter();
    }

    private void initializeWriter() {
        try {
            String fullPath = BASE_PATH + baseScenario + "/";
            // Create directory if it doesn't exist
            Files.createDirectories(Paths.get(fullPath));

            // Define file path
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String threadPath = String.format("%s/%s_%s_thread.csv", fullPath, iterationId, timestamp);

            // Initialize writer in append mode
            threadWriter = new PrintWriter(new FileWriter(threadPath, true));

            // Write header
            writeHeaderIfNeeded(threadPath, "Timestamp,ThreadID,ThreadName,ThreadState,CPUTime_ns,UserTime_ns,CPUPercent,BlockedTime,WaitedTime");

        } catch (IOException e) {
            System.err.println("Error initializing thread bottleneck monitor for " + agentId + ": " + e.getMessage());
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

    /**
     * Start monitoring thread CPU usage
     * @param intervalMs the interval between measurements in milliseconds
     */
    public void startMonitoring(long intervalMs) {
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            System.err.println("Thread CPU time monitoring not enabled. Cannot start monitoring.");
            return;
        }

        this.monitoringInterval = intervalMs;

        monitoringTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                recordThreadMetrics();
            } catch (Exception e) {
                System.err.println("Error recording thread metrics: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        // Schedule thread dump every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                generateThreadDump();
            } catch (Exception e) {
                System.err.println("Error generating thread dump: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Start monitoring with default 1-second interval
     */
    public void startMonitoring() {
        startMonitoring(1000);
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

        closeWriter();
    }

    private void closeWriter() {
        try {
            synchronized (this) {
                if (threadWriter != null) {
                    threadWriter.close();
                    threadWriter = null;
                }
            }
        } catch (Exception e) {
            System.err.println("Error closing thread writer for " + agentId + ": " + e.getMessage());
        }
    }

    /**
     * Record CPU and user time metrics for all threads
     */
    private void recordThreadMetrics() {
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            return;
        }

        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, false, false);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        synchronized (threadWriter) {
            for (int i = 0; i < threadIds.length; i++) {
                long threadId = threadIds[i];
                ThreadInfo info = threadInfos[i];

                if (info != null) {
                    long cpuTime = threadMXBean.getThreadCpuTime(threadId);
                    long userTime = threadMXBean.getThreadUserTime(threadId);

                    // Skip if CPU time not available for this thread
                    if (cpuTime == -1 || userTime == -1) {
                        continue;
                    }

                    // Calculate CPU usage percentage since last measurement
                    double cpuPercent = 0;
                    if (previousCpuTimes.containsKey(threadId)) {
                        long previousCpuTime = previousCpuTimes.get(threadId);
                        long cpuTimeDiff = cpuTime - previousCpuTime;

                        // Calculate as percentage of elapsed time
                        // Note: monitoringInterval is in ms, cpuTimeDiff is in ns
                        cpuPercent = ((double)cpuTimeDiff / (monitoringInterval * 1_000_000)) * 100;

                        // Cap at 100% per core
                        cpuPercent = Math.min(cpuPercent, 100.0);
                    }

                    previousCpuTimes.put(threadId, cpuTime);
                    previousUserTimes.put(threadId, userTime);

                    long blockedTime = threadMXBean.isThreadContentionMonitoringEnabled() ?
                            info.getBlockedTime() : -1;
                    long waitedTime = threadMXBean.isThreadContentionMonitoringEnabled() ?
                            info.getWaitedTime() : -1;

                    threadWriter.printf("%s,%d,%s,%s,%d,%d,%.2f,%d,%d%n",
                            timestamp,
                            threadId,
                            info.getThreadName(),
                            info.getThreadState(),
                            cpuTime,
                            userTime,
                            cpuPercent,
                            blockedTime,
                            waitedTime);
                }
            }
            threadWriter.flush();
        }
    }

    /**
     * Generate thread dump to a file
     */
    public void generateThreadDump() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String dumpPath = String.format("%s%s/thread_dump_%s_%s.txt",
                BASE_PATH, baseScenario, agentId, timestamp);

        try (PrintWriter dumpWriter = new PrintWriter(new FileWriter(dumpPath))) {
            // Write header information
            dumpWriter.println("Thread Dump generated at " + timestamp);
            dumpWriter.println("Agent: " + agentId);
            dumpWriter.println("--------------------------------------------------");

            // Get thread information
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

            // Sort threads by CPU time (if available)
            List<ThreadInfo> sortedThreads = Arrays.stream(threadInfos)
                    .sorted((t1, t2) -> {
                        long cpu1 = threadMXBean.getThreadCpuTime(t1.getThreadId());
                        long cpu2 = threadMXBean.getThreadCpuTime(t2.getThreadId());
                        return Long.compare(cpu2, cpu1); // Descending order
                    })
                    .collect(Collectors.toList());

            // Write thread information
            for (ThreadInfo threadInfo : sortedThreads) {
                long threadId = threadInfo.getThreadId();
                long cpuTime = threadMXBean.getThreadCpuTime(threadId);
                long userTime = threadMXBean.getThreadUserTime(threadId);

                dumpWriter.println(String.format(
                        "\"%s\" tid=%d state=%s cpuTime=%dms userTime=%dms",
                        threadInfo.getThreadName(),
                        threadId,
                        threadInfo.getThreadState(),
                        cpuTime / 1_000_000,
                        userTime / 1_000_000));

                // Write stack trace
                StackTraceElement[] stack = threadInfo.getStackTrace();
                for (int i = 0; i < stack.length; i++) {
                    dumpWriter.println("\tat " + stack[i]);
                }

                // Write lock information
                MonitorInfo[] monitors = threadInfo.getLockedMonitors();
                if (monitors.length > 0) {
                    dumpWriter.println("\tLocked monitors:");
                    for (MonitorInfo monitor : monitors) {
                        dumpWriter.println("\t\t- locked " + monitor + " at " +
                                monitor.getLockedStackDepth() + ":" +
                                monitor.getLockedStackFrame());
                    }
                }

                LockInfo[] locks = threadInfo.getLockedSynchronizers();
                if (locks.length > 0) {
                    dumpWriter.println("\tLocked synchronizers:");
                    for (LockInfo lock : locks) {
                        dumpWriter.println("\t\t- " + lock);
                    }
                }

                dumpWriter.println();
            }
        } catch (IOException e) {
            System.err.println("Error generating thread dump: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Correlate Java threads with OS threads using command line tools.
     * This requires Linux/Unix OS and appropriate permissions.
     */
    public void correlateWithOsThreads() {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = String.format("%s%s/thread_os_correlation_%s_%s.txt",
                BASE_PATH, baseScenario, agentId, timestamp);

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            // Write header
            writer.println("JVM Thread to OS Thread correlation");
            writer.println("Timestamp: " + timestamp);
            writer.println("PID: " + pid);
            writer.println("Agent: " + agentId);
            writer.println("--------------------------------------------------");

            // Get JVM thread information
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);
            Map<String, Long> threadNameToId = new HashMap<>();
            for (ThreadInfo info : threadInfos) {
                threadNameToId.put(info.getThreadName(), info.getThreadId());
            }

            // Execute jstack to get native thread IDs
            ProcessBuilder jstackPB = new ProcessBuilder("jstack", "-l", pid);
            Process jstackProcess = jstackPB.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jstackProcess.getInputStream()))) {

                String line;
                String currentThread = null;
                Long currentJavaThreadId = null;
                String nativeThreadId = null;

                while ((line = reader.readLine()) != null) {
                    // Parse thread information
                    if (line.startsWith("\"")) {
                        // New thread entry
                        if (currentThread != null && nativeThreadId != null) {
                            writer.println(String.format(
                                    "Java Thread: \"%s\" (id=%d) -> Native Thread: %s",
                                    currentThread, currentJavaThreadId, nativeThreadId));
                        }

                        // Extract thread name
                        currentThread = line.substring(1, line.indexOf("\"", 1));
                        currentJavaThreadId = threadNameToId.get(currentThread);
                        nativeThreadId = null;
                    } else if (line.contains("nid=")) {
                        // Extract native thread ID
                        int nidIndex = line.indexOf("nid=");
                        if (nidIndex >= 0) {
                            int nidEnd = line.indexOf(" ", nidIndex);
                            if (nidEnd < 0) nidEnd = line.length();
                            nativeThreadId = line.substring(nidIndex + 4, nidEnd);
                        }
                    }
                }

                // Handle last thread
                if (currentThread != null && nativeThreadId != null) {
                    writer.println(String.format(
                            "Java Thread: \"%s\" (id=%d) -> Native Thread: %s",
                            currentThread, currentJavaThreadId, nativeThreadId));
                }
            }

            // Wait for jstack to complete
            jstackProcess.waitFor();

            // Now use 'top' to get CPU usage of these threads
            writer.println("\n--------------------------------------------------");
            writer.println("OS Thread CPU Usage");
            writer.println("--------------------------------------------------");

            // Execute top command in thread mode
            ProcessBuilder topPB = new ProcessBuilder("top", "-H", "-b", "-n", "1", "-p", pid);
            Process topProcess = topPB.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(topProcess.getInputStream()))) {

                String line;
                boolean threadsSection = false;

                while ((line = reader.readLine()) != null) {
                    // Skip until we reach thread listing
                    if (line.contains("PID")) {
                        threadsSection = true;
                        writer.println(line);
                        continue;
                    }

                    if (threadsSection) {
                        writer.println(line);
                    }
                }
            }

            // Wait for top to complete
            topProcess.waitFor();

        } catch (IOException | InterruptedException e) {
            System.err.println("Error correlating with OS threads: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Analyze thread bottlenecks and generate a report
     */
    public void analyzeBottlenecks() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String reportPath = String.format("%s%s/bottleneck_analysis_%s_%s.txt",
                BASE_PATH, baseScenario, agentId, timestamp);

        try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
            // Write header
            writer.println("Thread Bottleneck Analysis");
            writer.println("Timestamp: " + timestamp);
            writer.println("Agent: " + agentId);
            writer.println("--------------------------------------------------");

            // Calculate CPU time for each thread
            Map<Long, Long> totalCpuTime = new HashMap<>();
            Map<Long, String> threadNames = new HashMap<>();
            Map<Long, Double> avgCpuPercent = new HashMap<>();
            Map<Long, Integer> sampleCount = new HashMap<>();

            // Copy the maps to avoid concurrent modification
            Map<Long, Long> cpuTimesCopy;
            synchronized (previousCpuTimes) {
                cpuTimesCopy = new HashMap<>(previousCpuTimes);
            }

            for (Map.Entry<Long, Long> entry : cpuTimesCopy.entrySet()) {
                long threadId = entry.getKey();
                ThreadInfo info = threadMXBean.getThreadInfo(threadId);

                if (info != null) {
                    threadNames.put(threadId, info.getThreadName());
                    totalCpuTime.put(threadId, threadMXBean.getThreadCpuTime(threadId));

                    // Dummy values for avg CPU since we don't track history
                    avgCpuPercent.put(threadId, 0.0);
                    sampleCount.put(threadId, 1);
                }
            }

            // List top 10 threads by CPU time
            writer.println("\nTop Threads by CPU Time:");
            writer.println("--------------------------------------------------");

            List<Map.Entry<Long, Long>> sortedThreads = new ArrayList<>(totalCpuTime.entrySet());
            sortedThreads.sort(Map.Entry.<Long, Long>comparingByValue().reversed());

            int count = 0;
            for (Map.Entry<Long, Long> entry : sortedThreads) {
                if (count++ >= 10) break;

                long threadId = entry.getKey();
                long cpuTimeMs = entry.getValue() / 1_000_000;
                String threadName = threadNames.getOrDefault(threadId, "Unknown");

                writer.println(String.format(
                        "%d. \"%s\" (id=%d) - CPU Time: %d ms",
                        count, threadName, threadId, cpuTimeMs));
            }

            // Calculate bottleneck score (could be enhanced with more metrics)
            writer.println("\nThread State Distribution:");
            writer.println("--------------------------------------------------");

            Map<Thread.State, Integer> stateCount = new HashMap<>();
            for (long threadId : threadNames.keySet()) {
                ThreadInfo info = threadMXBean.getThreadInfo(threadId);
                if (info != null) {
                    stateCount.merge(info.getThreadState(), 1, Integer::sum);
                }
            }

            for (Map.Entry<Thread.State, Integer> entry : stateCount.entrySet()) {
                writer.println(String.format("%s: %d threads", entry.getKey(), entry.getValue()));
            }

            // Check for potential deadlocks
            writer.println("\nDeadlock Detection:");
            writer.println("--------------------------------------------------");

            long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
            if (deadlockedThreads != null && deadlockedThreads.length > 0) {
                writer.println("DEADLOCK DETECTED!");
                ThreadInfo[] deadlockInfos = threadMXBean.getThreadInfo(deadlockedThreads, true, true);
                for (ThreadInfo threadInfo : deadlockInfos) {
                    writer.println(String.format("\nThread \"%s\" (id=%d) is deadlocked",
                            threadInfo.getThreadName(), threadInfo.getThreadId()));
                    writer.println("Stack trace:");
                    for (StackTraceElement element : threadInfo.getStackTrace()) {
                        writer.println("\t" + element);
                    }
                }
            } else {
                writer.println("No deadlocks detected.");
            }

            writer.println("\nBottleneck Analysis Complete");

        } catch (IOException e) {
            System.err.println("Error analyzing bottlenecks: " + e.getMessage());
            e.printStackTrace();
        }
    }
}