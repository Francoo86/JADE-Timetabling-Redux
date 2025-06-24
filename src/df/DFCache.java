package df;

import agentes.AgenteProfesor;
import agentes.AgenteSala;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;

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

        if (agentCache.containsKey(cacheKey) &&
                currentTime - cacheTimestamps.get(cacheKey) < CACHE_DURATION) {
            return agentCache.get(cacheKey);
        }

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

            return resultList;
        } catch (FIPAException e) {
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