package constants;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;

public class BlockConfigLoader {
    private static final String CONFIG_PATH = System.getProperty("user.dir") + "/agent_input/blocks.json";

    public static JSONObject loadConfig() {
        try {
            JSONParser parser = new JSONParser();
            return (JSONObject) parser.parse(new FileReader(CONFIG_PATH));
        } catch (Exception e) {
            System.err.println("Error loading block configuration: " + e.getMessage());
            e.printStackTrace();
            return new JSONObject(); // Return empty config as fallback
        }
    }
}