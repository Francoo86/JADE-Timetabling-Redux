package objetos;

import constants.enums.Day;

/**
 * Clase que representa la información del último bloque de un campus.
 * Usado por el behaviour de NegociarAsignaturaBehaviour.
 */
public class AssignationData {
    private Day ultimoDiaAsignado;
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

    public void assign(Day dia, String sala, int bloque) {
        ultimoDiaAsignado = dia;
        salaAsignada = sala;
        ultimoBloqueAsignado = bloque;
    }

    public Day getUltimoDiaAsignado() {
        if (ultimoDiaAsignado == null) {
            return null;
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
