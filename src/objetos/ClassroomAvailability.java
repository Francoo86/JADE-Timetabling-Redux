package objetos;

import java.io.Serializable;
import java.util.*;

// First, create a serializable class to hold all availability data
public class ClassroomAvailability implements Serializable {
    private final String codigo;
    private final String campus;
    private final int capacidad;
    private final Map<String, List<Integer>> availableBlocks; // day -> list of available blocks
    private final int satisfactionScore;

    public ClassroomAvailability(String codigo, String campus, int capacidad,
                                 Map<String, List<Integer>> availableBlocks,
                                 int satisfactionScore) {
        this.codigo = codigo;
        this.campus = campus;
        this.capacidad = capacidad;
        this.availableBlocks = availableBlocks;
        this.satisfactionScore = satisfactionScore;
    }

    // Add getters
    public String getCodigo() { return codigo; }
    public String getCampus() { return campus; }
    public int getCapacidad() { return capacidad; }
    public Map<String, List<Integer>> getAvailableBlocks() { return availableBlocks; }
    public int getSatisfactionScore() { return satisfactionScore; }
}