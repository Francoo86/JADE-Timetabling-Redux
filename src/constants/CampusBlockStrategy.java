package constants;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import java.util.*;

class CampusBlockStrategy {
    private final List<Integer> morningBlocks;
    private final List<Integer> afternoonBlocks;
    private final Map<Integer, String> timeBlocks;

    public CampusBlockStrategy(JSONObject config) {
        // cargar configuraciones de los bloques (son los mismos).
        morningBlocks = parseJsonArray((JSONArray) config.get("morning_blocks"));
        afternoonBlocks = parseJsonArray((JSONArray) config.get("afternoon_blocks"));

        // cargar configuraciones de los bloques de tiempo.
        timeBlocks = new HashMap<>();
        JSONObject timeBlocksConfig = (JSONObject) config.get("time_blocks");
        for (Object key : timeBlocksConfig.keySet()) {
            String blockKey = (String) key;
            timeBlocks.put(Integer.parseInt(blockKey), (String) timeBlocksConfig.get(blockKey));
        }
    }

    private List<Integer> parseJsonArray(JSONArray jsonArray) {
        List<Integer> result = new ArrayList<>();
        for (Object item : jsonArray) {
            result.add(((Long) item).intValue());
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

        //primer, tercero y quinto año, durante la mañana preferentemente.
        boolean prefersMorning = nivel % 2 != 0;
        //segundo, cuarto y sexto año en las tardes.
        boolean prefersAfternoon = nivel % 2 == 0;

        //prioridad, evaluar consecutivdad segun la directriz de la universidad
        List<Integer> bloquesDelDia = asignaturasBloques.getOrDefault(dia, new ArrayList<>());
        if (!bloquesDelDia.isEmpty()) {
            int lastBlock = bloquesDelDia.get(bloquesDelDia.size() - 1);
            if (Math.abs(bloque - lastBlock) == 1) {
                baseScore += 100;
                reasons.add("Bloque consecutivo");
            }
        }

        //evaluar los bloques por la mañana y por la tarde
        if (prefersMorning) {
            if (morningBlocks.contains(bloque)) {
                baseScore += getMorningScore(bloque);
                reasons.add(String.format("Bloque de mañana óptimo para nivel %d (%s)",
                        nivel, timeBlocks.get(bloque)));
            } else {
                baseScore += 10; // Small score for non-preferred time blocks
                reasons.add(String.format("Bloque de tarde no óptimo para nivel %d (%s)",
                        nivel, timeBlocks.get(bloque)));
            }
        } else if (prefersAfternoon) {
            if (afternoonBlocks.contains(bloque)) {
                baseScore += getAfternoonScore(bloque);
                reasons.add(String.format("Bloque de tarde óptimo para nivel %d (%s)",
                        nivel, timeBlocks.get(bloque)));
            } else {
                baseScore += 10;
                reasons.add(String.format("Bloque de mañana no óptimo para nivel %d (%s)",
                        nivel, timeBlocks.get(bloque)));
            }
        }

        // Add bonus for optimal first block of the day
        if (bloquesDelDia.isEmpty()) {
            if ((prefersMorning && bloque == morningBlocks.get(0)) ||
                    (prefersAfternoon && bloque == afternoonBlocks.get(0))) {
                baseScore += 30;
                reasons.add("Primer bloque óptimo del día");
            }
        }

        return new BlockScore(baseScore, String.join(", ", reasons));
    }

    /**
     * Obtiene un puntaje (peso) para poder evaluar un bloque de la mañana.
     *
     * @param bloque El bloque de la mañana.
     * @return {@link int} puntaje.
     */
    private int getMorningScore(int bloque) {
        int index = morningBlocks.indexOf(bloque);
        // Earlier morning blocks get higher scores
        return 50 - (index * 10);
    }


    /**
     * Obtiene un puntaje (peso) para poder evaluar un bloque de la tarde.
     *
     * @param bloque bloque (de la tarde).
     * @return {@link int} puntaje.
     */
    private int getAfternoonScore(int bloque) {
        int index = afternoonBlocks.indexOf(bloque);
        return 50 - (index * 10);
    }
}