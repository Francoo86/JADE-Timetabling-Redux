package constants;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import java.util.*;

class CampusBlockStrategy {
    private final List<Integer> primaryMorningBlocks;   // Bloques 1 y 3
    private final List<Integer> secondaryMorningBlocks; // Bloques 2 y 4
    private final List<Integer> primaryAfternoonBlocks; // Bloques 5 y 7
    private final List<Integer> secondaryAfternoonBlocks; // Bloques 6, 8 y 9
    private final Set<Integer> morningYears;
    private final Set<Integer> afternoonYears;

    @SuppressWarnings("unchecked")
    public CampusBlockStrategy(JSONObject config) {
        // Inicializar listas de bloques priorizados
        primaryMorningBlocks = Arrays.asList(1, 3);
        secondaryMorningBlocks = Arrays.asList(2, 4);
        primaryAfternoonBlocks = Arrays.asList(5, 7);
        secondaryAfternoonBlocks = Arrays.asList(6, 8, 9);

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
        } else if ((prefersMorning && primaryMorningBlocks.contains(bloque)) ||
                (prefersAfternoon && primaryAfternoonBlocks.contains(bloque))) {
            baseScore += 30;
            reasons.add("Primer bloque óptimo del día");
        }

        // Evaluar preferencia de horario
        if (prefersMorning) {
            if (primaryMorningBlocks.contains(bloque)) {
                baseScore += 50; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque primario mañana");
            } else if (secondaryMorningBlocks.contains(bloque)) {
                baseScore += 30; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque secundario mañana");
            }
        } else if (prefersAfternoon) {
            if (primaryAfternoonBlocks.contains(bloque)) {
                baseScore += 50; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque primario tarde");
            } else if (secondaryAfternoonBlocks.contains(bloque)) {
                baseScore += 30; // Reducir el puntaje para dar menor peso a la preferencia de horario
                reasons.add("Bloque secundario tarde");
            }
        }

        return new BlockScore(baseScore, String.join(", ", reasons));
    }
}