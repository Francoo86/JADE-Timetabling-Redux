package constants;

import org.json.simple.JSONObject;
import java.util.*;

public class BlockOptimization {
    private static BlockOptimization instance;
    private final Map<String, CampusBlockStrategy> campusStrategies;

    private BlockOptimization() {
        campusStrategies = new HashMap<>();
        loadStrategies();
    }

    //FIXME: Quitar singleton porque no es necesario.
    public static synchronized BlockOptimization getInstance() {
        if (instance == null) {
            instance = new BlockOptimization();
        }
        return instance;
    }

    private void loadStrategies() {
        try {
            JSONObject config = BlockConfigLoader.loadConfig();
            JSONObject campuses = (JSONObject) config.get("Campuses");
            
            for (Object key : campuses.keySet()) {
                String campusName = (String) key;
                JSONObject campusConfig = (JSONObject) campuses.get(campusName);
                campusStrategies.put(campusName, new CampusBlockStrategy(campusConfig));
            }
        } catch (Exception e) {
            System.err.println("Error loading block optimization strategies: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public BlockScore evaluateBlock(String campus, int nivel, int bloque, String dia, 
                                  Map<String, List<Integer>> asignaturasBloques) {
        CampusBlockStrategy strategy = campusStrategies.get(campus);

        //en este caso no se podría evaluar un campus.
        if(strategy == null) {
            return new BlockScore(0, "No campus strategy found");
        }

        //evaluar el bloque con la estrategia del campus.
        return strategy.evaluateBlock(nivel, bloque, dia, asignaturasBloques);
    }
}