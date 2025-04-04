package objetos;

import constants.enums.Actividad;
import objetos.helper.ActividadHandler;
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
    private Actividad actividad;

    public Asignatura(
            String nombre,
            int nivel,
            String paralelo,
            int horas,
            int vacantes,
            String campus,
            String codigoAsignatura,
            Actividad actividad
    ) {
        this.nombre = nombre;
        this.nivel = nivel;
        this.paralelo = paralelo;
        this.horas = horas;
        this.vacantes = vacantes;
        this.campus = campus;
        this.codigoAsignatura = codigoAsignatura;
        this.actividad = actividad;
    }

    // Getters existentes
    public String getNombre() { return nombre; }
    public int getVacantes() { return vacantes; }
    public int getHoras() { return horas; }
    public String getCampus() { return campus; }
    public String getCodigoAsignatura() { return codigoAsignatura; }
    public String getParalelo() { return paralelo; }
    public int getNivel() { return nivel; }
    public Actividad getActividad() { return actividad; }

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
            (String) obj.get("CodigoAsignatura"),
                ActividadHandler.translateFromActividad((String) obj.get("Actividad")
                )
        );
    }
}