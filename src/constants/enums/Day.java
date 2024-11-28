package constants.enums;

import constants.Commons;

//TODO: Implementar y cambiar las llaves de String a Days.
public enum Day {
    LUNES,
    MARTES,
    MIERCOLES,
    JUEVES,
    VIERNES;

    public String getDayName() {
        return Commons.DAYS[this.ordinal()];
    }
}
