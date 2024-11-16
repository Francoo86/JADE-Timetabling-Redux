package constants;

public enum Satisfaccion {
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

