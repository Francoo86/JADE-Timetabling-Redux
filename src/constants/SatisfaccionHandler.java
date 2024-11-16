package constants;

public class SatisfaccionHandler {
    public static int getSatisfaccion(int capacidad, int vacantes) {
        if (capacidad == vacantes) return Satisfaccion.ALTA.getValor();
        else if (capacidad > vacantes) return Satisfaccion.MEDIA.getValor();

        return Satisfaccion.BAJA.getValor();
    }
}
