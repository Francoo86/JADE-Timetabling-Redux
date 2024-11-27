package constants;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import java.util.*;

public class CampusBlockStrategy {
    private final List<Integer> primaryMorningBlocks;
    private final List<Integer> secondaryMorningBlocks;
    private final List<Integer> primaryAfternoonBlocks;
    private final List<Integer> secondaryAfternoonBlocks;
    private final Map<Integer, String> timeBlocks;
    private final Set<Integer> morningYears;
    private final Set<Integer> afternoonYears;

    @SuppressWarnings("unchecked")
    public CampusBlockStrategy(JSONObject config) {
        // Load blocks configuration from BlocksInfo
        JSONObject blocksInfo = (JSONObject) config.get("BlocksInfo");
        if (blocksInfo != null) {
            JSONObject morning = (JSONObject) blocksInfo.get("Morning");
            JSONObject afternoon = (JSONObject) blocksInfo.get("Afternoon");

            primaryMorningBlocks = parseJsonArray((JSONArray) morning.get("Primary"));
            secondaryMorningBlocks = parseJsonArray((JSONArray) morning.get("Secondary"));
            primaryAfternoonBlocks = parseJsonArray((JSONArray) afternoon.get("Primary"));
            secondaryAfternoonBlocks = parseJsonArray((JSONArray) afternoon.get("Secondary"));
        } else {
            // Fallback to default values if BlocksInfo is not present
            primaryMorningBlocks = Arrays.asList(1, 3);
            secondaryMorningBlocks = Arrays.asList(2, 4);
            primaryAfternoonBlocks = Arrays.asList(5, 7);
            secondaryAfternoonBlocks = Arrays.asList(6, 8, 9);
        }

        // Load time blocks configuration
        timeBlocks = new HashMap<>();
        JSONObject timeBlocksConfig = (JSONObject) config.get("time_blocks");
        if (timeBlocksConfig != null) {
            for (Object key : timeBlocksConfig.keySet()) {
                String blockKey = (String) key;
                timeBlocks.put(Integer.parseInt(blockKey), (String) timeBlocksConfig.get(blockKey));
            }
        }

        morningYears = new HashSet<>();
        afternoonYears = new HashSet<>();
        JSONObject yearPrefs = (JSONObject) config.get("year_preferences");
        if (yearPrefs != null) {
            JSONArray morningYearsArr = (JSONArray) yearPrefs.get("morning");
            JSONArray afternoonYearsArr = (JSONArray) yearPrefs.get("afternoon");

            if (morningYearsArr != null) {
                morningYearsArr.forEach(year -> morningYears.add(((Long) year).intValue()));
            }
            if (afternoonYearsArr != null) {
                afternoonYearsArr.forEach(year -> afternoonYears.add(((Long) year).intValue()));
            }
        }
    }

    private List<Integer> parseJsonArray(JSONArray jsonArray) {
        List<Integer> result = new ArrayList<>();
        if (jsonArray != null) {
            for (Object item : jsonArray) {
                if (item instanceof Long) {
                    result.add(((Long) item).intValue());
                }
            }
        }
        return result;
    }

    /*
     * Cada cohorte debe tener un horario intercalado: primero, tercero y quinto año, durante la mañana preferentemente.
     * Segundo, cuarto y sexto año en las tardes. Esto de manera de evitar los choques de horario para aquellos estudiantes
     * que presenten atrasos en su avance curricular.
     *
     * La mejor regla para esto es la regla del modulo. Si el modulo de 2 del nivel es 0, entonces es un nivel par,
     */
    public BlockScore evaluateBlock(int nivel, int bloque, String dia,
                                    Map<String, List<Integer>> asignaturasBloques) {
        int baseScore = 0;
        List<String> reasons = new ArrayList<>();

        List<Integer> bloquesDelDia = asignaturasBloques.getOrDefault(dia, new ArrayList<>());
        if (!bloquesDelDia.isEmpty()) {
            int lastBlock = bloquesDelDia.get(bloquesDelDia.size() - 1);
            if (Math.abs(bloque - lastBlock) == 1) {
                baseScore += 100;
                reasons.add("Bloque consecutivo");
            }
        }

        boolean prefersMorning = morningYears.contains(nivel) || (!afternoonYears.contains(nivel) && nivel % 2 != 0);
        boolean prefersAfternoon = afternoonYears.contains(nivel) || (!morningYears.contains(nivel) && nivel % 2 == 0);

        // Evaluate morning preference
        if (prefersMorning) {
            if (primaryMorningBlocks.contains(bloque)) {
                baseScore += 50;
                reasons.add("Bloque primario mañana");
            } else if (secondaryMorningBlocks.contains(bloque)) {
                baseScore += 30;
                reasons.add("Bloque secundario mañana");
            }
        }
        // Evaluate afternoon preference
        else if (prefersAfternoon) {
            if (primaryAfternoonBlocks.contains(bloque)) {
                baseScore += 50;
                reasons.add("Bloque primario tarde");
            } else if (secondaryAfternoonBlocks.contains(bloque)) {
                baseScore += 30;
                reasons.add("Bloque secundario tarde");
            }
        }

        if (bloquesDelDia.isEmpty()) {
            if ((prefersMorning && primaryMorningBlocks.contains(bloque)) ||
                    (prefersAfternoon && primaryAfternoonBlocks.contains(bloque))) {
                baseScore += 30;
                reasons.add("Primer bloque óptimo del día");
            }
        }

        String timeInfo = timeBlocks.get(bloque);
        if (timeInfo != null) {
            reasons.add(String.format("Horario: %s", timeInfo));
        }

        if (bloque == Commons.MAX_BLOQUE_DIURNO) {
            baseScore -= 20;
            reasons.add("Bloque 9 (usar solo si es necesario)");
        }

        return new BlockScore(baseScore, String.join(", ", reasons));
    }
}