package objetos;

public class AsignacionSala {
    private String nombreAsignatura;
    private int valoracion;

    public AsignacionSala(String nombreAsignatura, int valoracion) {
        this.nombreAsignatura = nombreAsignatura;
        this.valoracion = valoracion;
    }

    public String getNombreAsignatura() {
        return nombreAsignatura;
    }

    public int getValoracion() {
        return valoracion;
    }
}
