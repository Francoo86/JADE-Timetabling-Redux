package json_stuff;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class JSONProcessor {
    public static JSONArray prepararParalelos(JSONArray profesoresJson) {
        System.out.println("\nIniciando procesamiento de paralelos...");
        
        for (int i = 0; i < profesoresJson.size(); i++) {
            JSONObject profesor = (JSONObject) profesoresJson.get(i);
            JSONArray asignaturas = (JSONArray) profesor.get("Asignaturas");

            if(asignaturas == null) {
                System.out.println("Profesor sin asignaturas: " + profesor.get("Nombre"));
                continue;
            }

            JSONArray processedAsignaturas = new JSONArray();

            for (Object asigObj : asignaturas) {
                JSONObject asignatura = (JSONObject) asigObj;
                Long vacantes = (Long) asignatura.get("Vacantes");

                if (vacantes != null && vacantes >= 70) {
                    System.out.println("Procesando asignatura con 70+ vacantes: " +
                                     asignatura.get("CodigoAsignatura") +
                                     " - Vacantes originales: " + vacantes);

                    int mitadVacantes = vacantes.intValue() / 2;

                    // Create two parallel sections
                    JSONObject paraleloA = createParalelo(asignatura, "A", mitadVacantes);
                    JSONObject paraleloB = createParalelo(asignatura, "B", mitadVacantes);

                    processedAsignaturas.add(paraleloA);
                    processedAsignaturas.add(paraleloB);

                    System.out.println("Creados paralelos A y B con 35 vacantes cada uno");
                } else {
                    processedAsignaturas.add(asignatura);
                }
            }

            // Actualizar directamente el objeto profesor original
            profesor.put("Asignaturas", processedAsignaturas);
        }
        
        System.out.println("Procesamiento de paralelos completado\n");
        return profesoresJson;
    }
    
    private static JSONObject createParalelo(JSONObject original, String paralelo, long vacantes) {
        JSONObject newParalelo = new JSONObject();
        newParalelo.put("CodigoAsignatura", original.get("CodigoAsignatura"));
        newParalelo.put("Nombre", original.get("Nombre"));
        newParalelo.put("Nivel", original.get("Nivel"));
        newParalelo.put("Paralelo", paralelo);
        newParalelo.put("Horas", original.get("Horas"));
        newParalelo.put("Vacantes", vacantes);
        newParalelo.put("Campus", original.get("Campus"));
        return newParalelo;
    }
}