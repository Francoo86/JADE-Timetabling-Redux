package objetos;

import java.util.Objects;

public class BloqueInfo {
    private String campus;
    private int bloque;
    
    public BloqueInfo(String campus, int bloque) {
        this.campus = campus;
        this.bloque = bloque;
    }
    
    // Getters
    public String getCampus() {
        return campus;
    }
    
    public int getBloque() {
        return bloque;
    }
    
    // Setters
    public void setCampus(String campus) {
        this.campus = campus;
    }
    
    public void setBloque(int bloque) {
        this.bloque = bloque;
    }
    
    @Override
    public String toString() {
        return String.format("BloqueInfo{campus='%s', bloque=%d}", campus, bloque);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        BloqueInfo that = (BloqueInfo) o;
        
        if (bloque != that.bloque) return false;
        return Objects.equals(campus, that.campus);
    }
    
    @Override
    public int hashCode() {
        int result = campus != null ? campus.hashCode() : 0;
        result = 31 * result + bloque;
        return result;
    }
}