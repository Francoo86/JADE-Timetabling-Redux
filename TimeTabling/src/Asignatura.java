// Asignatura.java
public class Asignatura {
    String nombre;
    int nivel;
    int semestre;
    int horas;
    int vacantes;

    public Asignatura(String nombre, int nivel, int semestre, int horas, int vacantes) {
        this.nombre = nombre;
        this.nivel = nivel;
        this.semestre = semestre;
        this.horas = horas;
        this.vacantes = vacantes;
    }

    @Override
    public String toString() {
        return nombre + "," + nivel + "," + semestre + "," + horas + "," + vacantes;
    }

    public static Asignatura fromString(String str) {
        String[] parts = str.split(",");
        return new Asignatura(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
    }
}