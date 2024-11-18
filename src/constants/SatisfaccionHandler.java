package constants;

public class SatisfaccionHandler {
    public static int getSatisfaccion(int capacidad, int vacantes) {
        if (capacidad == vacantes) return Satisfaccion.ALTA.getValor();
        else if (capacidad > vacantes) return Satisfaccion.MEDIA.getValor();

        return Satisfaccion.BAJA.getValor();
    }

    private enum Satisfaccion {
        ALTA(10),
        MEDIA(5),
        BAJA(3);

        private final int valor;
        Satisfaccion(int valor) {
            this.valor = valor;
        }
        public int getValor() {
            return valor;
        }
    }
}
