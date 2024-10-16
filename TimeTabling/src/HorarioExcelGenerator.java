import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class HorarioExcelGenerator {
    private static HorarioExcelGenerator instance;
    private Map<String, Map<String, List<String>>> horarios;

    private HorarioExcelGenerator() {
        horarios = new ConcurrentHashMap<>();
    }

    public static synchronized HorarioExcelGenerator getInstance() {
        if (instance == null) {
            instance = new HorarioExcelGenerator();
        }
        return instance;
    }

    public void agregarHorarioSala(String nombreSala, Map<String, List<String>> horario) {
        horarios.put(nombreSala, horario);
        System.out.println("Horario agregado para la sala: " + nombreSala + ". Total de salas: " + horarios.size());
    }

    public void generarArchivoCSV(String nombreArchivo) {
        System.out.println("Iniciando generación de archivo CSV...");
        System.out.println("Número de salas a procesar: " + horarios.size());

        try (FileWriter writer = new FileWriter(nombreArchivo)) {
            for (Map.Entry<String, Map<String, List<String>>> entrada : horarios.entrySet()) {
                String nombreSala = entrada.getKey();
                Map<String, List<String>> horarioSala = entrada.getValue();

                System.out.println("Procesando sala: " + nombreSala);

                writer.append("Sala: ").append(nombreSala).append("\n");
                writer.append("Lunes,Martes,Miércoles,Jueves,Viernes\n");

                for (int bloque = 0; bloque < 5; bloque++) {
                    StringBuilder linea = new StringBuilder();
                    for (String dia : new String[]{"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"}) {
                        linea.append(horarioSala.get(dia).get(bloque)).append(",");
                    }
                    writer.append(linea.toString().trim()).append("\n");
                }
                writer.append("\n"); // Separador entre salas
            }
            System.out.println("Archivo CSV generado exitosamente: " + nombreArchivo);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}