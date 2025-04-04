package df;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import performance.PerformanceMonitor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DFCache {
    private static Map<String, List<DFAgentDescription>> agentCache = new ConcurrentHashMap<>();
    private static Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 2000; // 2 seconds should be enough for this use case

    public static List<DFAgentDescription> search(Agent agent, String serviceType, Property... properties) {
        String cacheKey = buildCacheKey(serviceType, properties);
        long startTime = System.nanoTime();
        long currentTime = System.currentTimeMillis();

        // Check cache validity
        if (agentCache.containsKey(cacheKey) &&
                currentTime - cacheTimestamps.get(cacheKey) < CACHE_DURATION) {

            if (agent != null) {
                PerformanceMonitor monitor = null;
                if (agent instanceof AgenteProfesor) {
                    monitor = ((AgenteProfesor) agent).getPerformanceMonitor();
                } else if (agent instanceof AgenteSala) {
                    monitor = ((AgenteSala) agent).getPerformanceMonitor();
                }

                if (monitor != null) {
                    monitor.recordDFOperation("cache_hit", startTime,
                            agentCache.get(cacheKey).size(), "success");
                }
            }

            return agentCache.get(cacheKey);
        }

        // Perform actual DF search
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceType);

            for (Property prop : properties) {
                sd.addProperties(prop);
            }
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(agent, template);
            List<DFAgentDescription> resultList = Arrays.asList(results);

            // Update cache
            agentCache.put(cacheKey, resultList);
            cacheTimestamps.put(cacheKey, currentTime);

            if (agent != null) {
                PerformanceMonitor monitor = null;
                if (agent instanceof AgenteProfesor) {
                    monitor = ((AgenteProfesor) agent).getPerformanceMonitor();
                } else if (agent instanceof AgenteSala) {
                    monitor = ((AgenteSala) agent).getPerformanceMonitor();
                }

                if (monitor != null) {
                    monitor.recordDFOperation("search", startTime,
                            results.length, "success");
                }
            }

            return resultList;
        } catch (FIPAException e) {
            if (agent != null) {
                PerformanceMonitor monitor = null;
                if (agent instanceof AgenteProfesor) {
                    monitor = ((AgenteProfesor) agent).getPerformanceMonitor();
                } else if (agent instanceof AgenteSala) {
                    monitor = ((AgenteSala) agent).getPerformanceMonitor();
                }

                if (monitor != null) {
                    monitor.recordDFOperation("search", startTime,
                            0, "error: " + e.getMessage());
                }
            }
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private static String buildCacheKey(String serviceType, Property[] properties) {
        StringBuilder key = new StringBuilder(serviceType);
        for (Property prop : properties) {
            key.append("-").append(prop.getName()).append(":").append(prop.getValue());
        }
        return key.toString();
    }

    public static void invalidateCache() {
        agentCache.clear();
        cacheTimestamps.clear();
    }
}