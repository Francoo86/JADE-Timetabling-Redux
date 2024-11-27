package constants;

import json_stuff.JSONHelper;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;

public class BlockConfigLoader {
    public static JSONObject loadConfig() {
        try {
            JSONObject blockData = JSONHelper.parseJsonFile("blocks.json");
            System.out.println("Configuración de bloques cargada exitosamente");
            return blockData;
        } catch (Exception e) {
            System.err.println("Error loading block configuration: " + e.getMessage());
            //e.printStackTrace();
            System.err.println("ASUMIENDO CONFIGURACIÓN VACÍA...");
            return new JSONObject(); // Return empty config as fallback
        }
    }
}