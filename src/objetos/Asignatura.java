package objetos;

import org.json.simple.JSONObject;

import java.util.Arrays;

public class Asignatura {
    private String nombre;
    private int nivel;
    private String paralelo;
    private int horas;
    private int vacantes;
    private String campus;
    private String codigoAsignatura;

    public Asignatura(String nombre, int nivel, String paralelo, int horas, int vacantes, String campus, String codigoAsignatura) {
        this.nombre = nombre;
        this.nivel = nivel;
        this.paralelo = paralelo;
        this.horas = horas;
        this.vacantes = vacantes;
        this.campus = campus;
        this.codigoAsignatura = codigoAsignatura;
    }

    // Getters existentes
    public String getNombre() { return nombre; }
    public int getVacantes() { return vacantes; }
    public int getHoras() { return horas; }
    public String getCampus() { return campus; }
    public String getCodigoAsignatura() { return codigoAsignatura; }
    public String getParalelo() { return paralelo; }
    public int getNivel() { return nivel; }

    @Override
    public String toString() {
        return String.format("%s,%d,%s,%d,%d,%s,%s", 
            nombre, nivel, paralelo, horas, vacantes, campus, codigoAsignatura);
    }

    public static Asignatura fromJson(JSONObject obj) {
        return new Asignatura(
            (String) obj.get("Nombre"),
            ((Number) obj.get("Nivel")).intValue(),
            (String) obj.get("Paralelo"),
            ((Number) obj.get("Horas")).intValue(),
            ((Number) obj.get("Vacantes")).intValue(),
            (String) obj.get("Campus"),
            (String) obj.get("CodigoAsignatura")
        );
    }

    public static Asignatura fromString(String str) {
        String[] parts = str.split(",");
        return new Asignatura(
            parts[0], // nombre
            Integer.parseInt(parts[1]), // nivel
            parts[2], // paralelo
            Integer.parseInt(parts[3]), // horas
            Integer.parseInt(parts[4]), // vacantes
            parts[5], // campus
            parts[6]  // codigoAsignatura
        );
    }

    public static Asignatura parseAsignaturaByNameCap(String crudeString) {
        String[] partes = crudeString.trim().split(",");
        // considerando que crudeString contiene todos los elementos necesarios
        return new Asignatura(
            partes[0], // nombre
            Integer.parseInt(partes[1]), // nivel
            partes[2], // paralelo
            Integer.parseInt(partes[3]), // horas
            Integer.parseInt(partes[4]), // vacantes
            partes[5], // campus
            partes[6]  // codigoAsignatura
        );
    }
}