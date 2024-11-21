package constants;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import java.util.*;

class CampusBlockStrategy {
    // Inicializar listas de bloques priorizados
    private final List<Integer> PRIMARY_MORNING_BLOCKS = Arrays.asList(1, 3);   // Bloques 1 y 3
    private final List<Integer> SECONDARY_MORNING_BLOCKS = Arrays.asList(2, 4); // Bloques 2 y 4
    private final List<Integer> PRIMARY_AFTERNOON_BLOCKS = Arrays.asList(5, 7); // Bloques 5 y 7
    private final List<Integer> SECONDARY_AFTERNOON_BLOCKS = Arrays.asList(6, 8, 9); // Bloques 6, 8 y 9
    private final Set<Integer> morningYears;
    private final Set<Integer> afternoonYears;

    //@SuppressWarnings("unchecked")
    public CampusBlockStrategy(JSONObject config) {
        morningYears = new HashSet<>();
        afternoonYears = new HashSet<>();

        // Cargar preferencias de años desde la configuración
        JSONObject yearPrefs = (JSONObject) config.get("year_preferences");
        JSONArray morningYearsArr = (JSONArray) yearPrefs.get("morning");
        JSONArray afternoonYearsArr = (JSONArray) yearPrefs.get("afternoon");
        
        morningYearsArr.forEach(year -> morningYears.add(((Long) year).intValue()));
        afternoonYearsArr.forEach(year -> afternoonYears.add(((Long) year).intValue()));
    }

    public BlockScore evaluateBlock(int nivel, int bloque, String dia, 
                                  Map<String, List<Integer>> asignaturasBloques) {
        int baseScore = 0;
        List<String> reasons = new ArrayList<>();

        boolean prefersMorning = morningYears.contains(nivel);
        boolean prefersAfternoon = afternoonYears.contains(nivel);

        // Evaluar consecutividad
        List<Integer> bloquesDelDia = asignaturasBloques.getOrDefault(dia, new ArrayList<>());
        if (!bloquesDelDia.isEmpty()) {
            int lastBlock = bloquesDelDia.get(bloquesDelDia.size() - 1);
            if (Math.abs(bloque - lastBlock) == 1) {
                baseScore += 100; // Incrementar el puntaje para dar mayor peso a la consecutividad
                reasons.add("Bloque consecutivo");
            }
        } else if ((prefersMorning && PRIMARY_MORNING_BLOCKS.contains(bloque)) ||
                (prefersAfternoon && PRIMARY_AFTERNOON_BLOCKS.contains(bloque))) {
            baseScore += 30;
            reasons.add("Primer bloque óptimo del día");
        }

        // Evaluar preferencia de horario
        if (prefersMorning) {
            if (PRIMARY_MORNING_BLOCKS.contains(bloque)) {
                baseScore += 50; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque primario mañana");
            } else if (SECONDARY_MORNING_BLOCKS.contains(bloque)) {
                baseScore += 30; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque secundario mañana");
            }
        } else if (prefersAfternoon) {
            if (PRIMARY_AFTERNOON_BLOCKS.contains(bloque)) {
                baseScore += 50; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque primario tarde");
            } else if (SECONDARY_AFTERNOON_BLOCKS.contains(bloque)) {
                baseScore += 30; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque secundario tarde");
            }
        }

        return new BlockScore(baseScore, String.join(", ", reasons));
    }
}