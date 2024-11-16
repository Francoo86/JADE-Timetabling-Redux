package objetos;

public class AsignacionSala {
    private String nombreAsignatura;
    private int satisfaccion;
    private float capacidad;

    public AsignacionSala(String nombreAsignatura, int satisfaccion, float capacidad) {
        this.nombreAsignatura = nombreAsignatura;
        this.satisfaccion = satisfaccion;
        this.capacidad = capacidad;
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
}
