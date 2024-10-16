// Asignatura.java
public class Asignatura {
    String nombre;
    int nivel;
    int semestre;
    int horas;
    int vacantes;

    public Asignatura(String nombre, int nivel, int semestre, int horas, int vacantes) {    // Se crea un constructor con los atributos de la asignatura
        this.nombre = nombre;
        this.nivel = nivel;
        this.semestre = semestre;
        this.horas = horas;
        this.vacantes = vacantes;
    }

    @Override
    public String toString() {   // Se sobreescribe el método toString
        return nombre + "," + nivel + "," + semestre + "," + horas + "," + vacantes;    // Se retorna un string con los atributos de la asignatura
    }

    public static Asignatura fromString(String str) {   // Se crea un método para convertir un string en una asignatura
        String[] parts = str.split(",");
        return new Asignatura(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), 
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
    }
}