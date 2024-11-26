package objetos;

/**
 * Clase que representa la información del último bloque de un campus.
 * Usado por el behaviour de NegociarAsignaturaBehaviour.
 */
public class AssignationData {
    private String ultimoDiaAsignado;
    private String salaAsignada;
    private int ultimoBloqueAsignado;

    public AssignationData(){
        this.clear();
    }

    public void clear() {
        ultimoDiaAsignado = null;
        salaAsignada = null;
        ultimoBloqueAsignado = -1;
    }

    public void assign(String dia, String sala, int bloque) {
        ultimoDiaAsignado = dia;
        salaAsignada = sala;
        ultimoBloqueAsignado = bloque;
    }

    public String getUltimoDiaAsignado() {
        if (ultimoDiaAsignado == null) {
            return "";
        }

        return ultimoDiaAsignado;
    }

    public String getSalaAsignada() {
        if (salaAsignada == null) {
            return "";
        }

        return salaAsignada;
    }

    public boolean hasSalaAsignada() {
        return salaAsignada != null;
    }

    public void setSalaAsignada(String salaAsignada) {
        this.salaAsignada = salaAsignada;
    }

    public int getUltimoBloqueAsignado() {
        return ultimoBloqueAsignado;
    }
}
