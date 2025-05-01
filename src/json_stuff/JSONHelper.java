package json_stuff;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Clase para trabajar con archivos JSON.
 */
public class JSONHelper {
    private static final String RESOURCES_PATH = System.getProperty("user.dir") + "/agent_input/";
    private static final String OUTPUT_PATH = System.getProperty("user.dir") + "/agent_output/";

    public static String getBaseOutputPath() {
        return OUTPUT_PATH;
    }

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
     * @param filePath El archivo json (el archivo tiene que estar en la carpeta resources)
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

    private static void internalJsonWrite(String fileName, String jsonString, String scenario) {
        /*
        if (!new java.io.File(OUTPUT_PATH).exists()) {
            new java.io.File(OUTPUT_PATH).mkdir();
        }*/
        String finalPath = OUTPUT_PATH + "/" + scenario + "/";

        try {
            Files.createDirectories(Paths.get(finalPath));
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (FileWriter file = new FileWriter(finalPath + fileName)) {
            file.write(formatJsonString(jsonString));
            System.out.println("Archivo " + fileName + " generado exitosamente.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeJsonFile(String fileName, JSONArray jsonArray, String scenario) {
        internalJsonWrite(fileName, jsonArray.toJSONString(), scenario);
    }

    public static void writeJsonFile(String fileName, String jsonString, String scenario) {
        internalJsonWrite(fileName, jsonString, scenario);
    }
}