package objetos;

import java.util.Arrays;

public class Asignatura {
    private String nombre;
    private int nivel;
    private int semestre;
    private int horas;
    private int vacantes;

    public Asignatura(String nombre, int nivel, int semestre, int horas, int vacantes) {
        this.nombre = nombre;
        this.nivel = nivel;
        this.semestre = semestre;
        this.horas = horas;
        this.vacantes = vacantes;
    }

    public String getNombre() {
        return nombre;
    }

    public int getVacantes() {
        return vacantes;
    }

    public int getHoras() {
        return horas;
    }

    @Override
    public String toString() {
        return nombre + "," + nivel + "," + semestre + "," + horas + "," + vacantes;
    }

    public static Asignatura fromString(String str) {
        System.out.println("Parsing: " + str);
        String[] parts = str.split(",");
        System.out.println(Arrays.toString(parts));
        return new Asignatura(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
    }

    public static Asignatura parseAsignaturaByNameCap(String crudeString) {
        String [] partes = crudeString.trim().split(",");
        //considerando que crudeString solo contiene el nombre de la asignatura y la capacidad
        return new Asignatura(partes[0], 0, 0, 0, Integer.parseInt(partes[1]));
    }
}