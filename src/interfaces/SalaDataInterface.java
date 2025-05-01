package interfaces;
import constants.enums.Day;
import objetos.AsignacionSala;
import java.util.List;
import java.util.Map;

public interface SalaDataInterface {
    String getCodigo();
    String getCampus();
    Map<Day, List<AsignacionSala>> getHorarioOcupado();
}