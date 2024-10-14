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
}