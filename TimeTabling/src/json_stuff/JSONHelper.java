package json_stuff;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Clase para trabajar con archivos JSON.
 */
public class JSONHelper {
    private static final String RESOURCES_PATH = System.getProperty("user.dir") + "/agent_input/";
    private static final String OUTPUT_PATH = System.getProperty("user.dir") + "/agent_output/";

    private static String formatJsonString(String crudeJson) {
        ObjectMapper mapper = new ObjectMapper();
        String prettyJson = "";

        try {
            Object json = mapper.readValue(crudeJson, Object.class);
            prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return prettyJson;
    }

    public static JSONArray parseAsArray(String filePath) {
        JSONParser parser = new JSONParser();
        try {
            Object obj = parser.parse(new FileReader(RESOURCES_PATH + filePath));
            return (JSONArray) obj;
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Parsea un archivo JSON y lo convierte en un objeto JSONObject.
     * @param El archivo json (el archivo tiene que estar en la carpeta resources)
     * @return El objeto JSONObject
     */
    public static JSONObject parseJsonFile(String filePath) {
        JSONParser parser = new JSONParser();
        try {
            Object obj = parser.parse(new FileReader(RESOURCES_PATH + filePath));
            return (JSONObject) obj;
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void internalJsonWrite(String fileName, String jsonString) {
        if (!new java.io.File(OUTPUT_PATH).exists()) {
            new java.io.File(OUTPUT_PATH).mkdir();
        }

        try (FileWriter file = new FileWriter(OUTPUT_PATH + fileName)) {
            file.write(formatJsonString(jsonString));
            System.out.println("Archivo " + fileName + " generado exitosamente.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeJsonFile(String fileName, JSONArray jsonArray) {
        internalJsonWrite(fileName, jsonArray.toJSONString());
    }

    public static void writeJsonFile(String fileName, String jsonString) {
        internalJsonWrite(fileName, jsonString);
    }
}