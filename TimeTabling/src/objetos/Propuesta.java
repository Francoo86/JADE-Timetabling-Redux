package objetos;

public class Propuesta {
    private String dia;
    private int bloque;
    private String codigo;
    private int capacidad;

    public Propuesta(String dia, int bloque, String codigo, int capacidad) {
        this.dia = dia;
        this.bloque = bloque;
        this.codigo = codigo;
        this.capacidad = capacidad;
    }

    public static Propuesta parseFromString(String crudeParts) {
        String[] parts = crudeParts.split(",");
        return new Propuesta(parts[0], Integer.parseInt(parts[1]), parts[2], Integer.parseInt(parts[3]));
    }
}
