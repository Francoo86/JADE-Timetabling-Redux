package objetos;

import jade.lang.acl.ACLMessage;

public class Propuesta {
    private String dia;
    private int bloque;
    private String codigo;
    private int capacidad;
    private int satisfaccion;

    public ACLMessage getMensaje() {
        return mensaje;
    }

    public void setMensaje(ACLMessage mensaje) {
        this.mensaje = mensaje;
    }

    private ACLMessage mensaje;

    public int getCapacidad() {
        return capacidad;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDia() {
        return dia;
    }

    public int getBloque() {
        return bloque;
    }

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

    public static Propuesta parse(String crudeParts) {
        String[] parts = crudeParts.split(",");
        return new Propuesta(parts[0], Integer.parseInt(parts[1]), parts[2],
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
    }
}
