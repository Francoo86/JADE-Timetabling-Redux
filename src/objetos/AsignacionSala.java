package objetos;

public class AsignacionSala {
    private String nombreAsignatura;
    private int satisfaccion;
    private float capacidad;
    private String profesor;

    public AsignacionSala(String nombreAsignatura, int satisfaccion, float capacidad, String profesor) {
        this.nombreAsignatura = nombreAsignatura;
        this.satisfaccion = satisfaccion;
        this.capacidad = capacidad;
        this.profesor = profesor;
    }

    public String getNombreAsignatura() {
        return nombreAsignatura;
    }

    public int getSatisfaccion() {
        return satisfaccion;
    }

    public float getCapacidad() {
        return capacidad;
    }

    public String getProfesor() {
        return profesor;
    }
}
