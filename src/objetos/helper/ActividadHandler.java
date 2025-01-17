package objetos.helper;

import constants.enums.Actividad;

public class ActividadHandler {
    public static Actividad translateFromActividad(String actividad) {
        if (actividad == null) {
            throw new IllegalArgumentException("La abreviatura no puede ser null");
        }

        return switch (actividad.toLowerCase()) {
            case "teo" -> Actividad.TEORIA;
            case "lab" -> Actividad.LABORATORIO;
            case "pra" -> Actividad.PRACTICA;
            case "tal" -> Actividad.TALLER;
            case "ayu" -> Actividad.AYUDANTIA;
            case "tut" -> Actividad.TUTORIA;
            //default -> throw new IllegalArgumentException("Abreviatura no válida: " + abreviatura);
            //asumir que es teoria si no se especifica
            default -> Actividad.TEORIA;
        };
    }
}
