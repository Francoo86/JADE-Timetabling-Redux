package objetos;

public class Propuesta {
    private String dia;
    private int bloque;
    private String codigo;
    private int capacidad;
    private int satisfaccion;

    //la parte mas importante.
    public int getSatisfaccion() {
        return satisfaccion;
    }

    public Propuesta(String dia, int bloque, String codigo, int capacidad, int satisfaccion) {
        this.dia = dia;
        this.bloque = bloque;
        this.codigo = codigo;
        this.capacidad = capacidad;
        this.satisfaccion = satisfaccion;
    }

    public static Propuesta parseFromString(String crudeParts) {
        String[] parts = crudeParts.split(",");
        return new Propuesta(parts[0], Integer.parseInt(parts[1]), parts[2],
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
    }
}
