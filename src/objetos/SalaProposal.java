package objetos;

import constants.enums.Day;
import java.io.Serializable;

/**
 * Represents a classroom assignment proposal that can be serialized for message passing.
 * Message format: "dia,bloque,nombreAsignatura,satisfaccion,salaConfirmada,vacantesAsignatura"
 */
public class SalaProposal implements Serializable {
    private static final long serialVersionUID = 1L;

    private Day dia;
    private int bloque;
    private String nombreAsignatura;
    private int satisfaccion;
    private String salaConfirmada;
    private int vacantesAsignatura;

    // Default constructor for serialization
    public SalaProposal() {}

    public SalaProposal(Day dia, int bloque, String nombreAsignatura,
                        int satisfaccion, String salaConfirmada, int vacantesAsignatura) {
        this.dia = dia;
        this.bloque = bloque;
        this.nombreAsignatura = nombreAsignatura;
        this.satisfaccion = satisfaccion;
        this.salaConfirmada = salaConfirmada;
        this.vacantesAsignatura = vacantesAsignatura;
    }

    // Static factory method to create from message string
    public static SalaProposal fromString(String message) {
        String[] datos = message.split(",");
        if (datos.length < 6) {
            throw new IllegalArgumentException("Invalid message format");
        }

        return new SalaProposal(
                Day.fromString(datos[0]),
                Integer.parseInt(datos[1]),
                datos[2],
                Integer.parseInt(datos[3]),
                datos[4],
                Integer.parseInt(datos[5])
        );
    }

    // Convert to message string format
    public String toMessageString() {
        return String.format("%s,%d,%s,%d,%s,%d",
                dia.toString(),
                bloque,
                nombreAsignatura,
                satisfaccion,
                salaConfirmada,
                vacantesAsignatura
        );
    }

    // Getters and setters
    public Day getDia() {
        return dia;
    }

    public void setDia(Day dia) {
        this.dia = dia;
    }

    public int getBloque() {
        return bloque;
    }

    public void setBloque(int bloque) {
        this.bloque = bloque;
    }

    public String getNombreAsignatura() {
        return nombreAsignatura;
    }

    public void setNombreAsignatura(String nombreAsignatura) {
        this.nombreAsignatura = nombreAsignatura;
    }

    public int getSatisfaccion() {
        return satisfaccion;
    }

    public void setSatisfaccion(int satisfaccion) {
        this.satisfaccion = satisfaccion;
    }

    public String getSalaConfirmada() {
        return salaConfirmada;
    }

    public void setSalaConfirmada(String salaConfirmada) {
        this.salaConfirmada = salaConfirmada;
    }

    public int getVacantesAsignatura() {
        return vacantesAsignatura;
    }

    public void setVacantesAsignatura(int vacantesAsignatura) {
        this.vacantesAsignatura = vacantesAsignatura;
    }

    @Override
    public String toString() {
        return "SalaProposal{" +
                "dia=" + dia +
                ", bloque=" + bloque +
                ", nombreAsignatura='" + nombreAsignatura + '\'' +
                ", satisfaccion=" + satisfaccion +
                ", salaConfirmada='" + salaConfirmada + '\'' +
                ", vacantesAsignatura=" + vacantesAsignatura +
                '}';
    }
}