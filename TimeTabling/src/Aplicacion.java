import agentes.AgenteProfesor;
import agentes.AgenteSala;
import agentes.Supervisor;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import json_stuff.JSONHelper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Aplicacion {
    private static Map<String, AgentController> salasControllers = new HashMap<>();
    private static List<AgentController> profesoresControllers = new ArrayList<>();

    public static void main(String[] args) {
        // Set DF max results before container creation
        System.setProperty("jade_domain_df_maxresult", "-1");

        try {
            Runtime rt = Runtime.instance();
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, "localhost");
            profile.setParameter(Profile.GUI, "true");

            AgentContainer mainContainer = rt.createMainContainer(profile);

            // Load data from JSON files
            JSONArray profesoresJson = JSONHelper.parseAsArray("profesores.json");
            JSONArray salasJson = JSONHelper.parseAsArray("salas.json");

            System.out.println("Creating room agents...");
            initializeSalas(mainContainer, salasJson);
            Thread.sleep(2000);

            int totalSubjects = calculateTotalSubjects(profesoresJson);
            configureSalasRequests(totalSubjects);

            System.out.println("Creating professor agents...");
            initializeProfesores(mainContainer, profesoresJson);
            Thread.sleep(2000);

            if (!profesoresControllers.isEmpty()) {
                profesoresControllers.get(0).start();
                System.out.println("First professor started");
            }

            createMonitorAgent(mainContainer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void initializeSalas(AgentContainer container, JSONArray salasJson) throws StaleProxyException {
        for (Object obj : salasJson) {
            JSONObject salaJson = (JSONObject) obj;
            String codigo = (String) salaJson.get("Codigo");
            String jsonString = salaJson.toJSONString();

            Object[] salaArgs = {jsonString};
            AgentController sala = container.createNewAgent(
                    "Sala" + codigo,
                    AgenteSala.class.getName(),
                    salaArgs
            );
            sala.start();
            salasControllers.put(codigo, sala);
            System.out.println("Room agent " + codigo + " created and started with JSON: " + jsonString);
        }
    }

    private static void initializeProfesores(AgentContainer container, JSONArray profesoresJson) throws StaleProxyException {
        for (int i = 0; i < profesoresJson.size(); i++) {
            JSONObject profesorJson = (JSONObject) profesoresJson.get(i);
            String nombre = (String) profesorJson.get("Nombre");

            JSONObject profesorCompleto = new JSONObject();
            profesorCompleto.put("Nombre", nombre);
            profesorCompleto.put("RUT", profesorJson.get("RUT"));
            profesorCompleto.put("Asignaturas", profesorJson.get("Asignaturas"));

            String jsonString = profesorCompleto.toJSONString();
            Object[] profesorArgs = {jsonString, i};

            String agentName = AgenteProfesor.AGENT_NAME + i;
            AgentController profesor = container.createNewAgent(
                    agentName,
                    AgenteProfesor.class.getName(),
                    profesorArgs
            );
            profesoresControllers.add(profesor);
            System.out.println("Professor agent created: " + agentName + ", order=" + i + ", name=" + nombre);
        }
    }

    private static int calculateTotalSubjects(JSONArray profesoresJson) {
        int total = 0;
        for (Object obj : profesoresJson) {
            JSONObject profesor = (JSONObject) obj;
            JSONArray asignaturas = (JSONArray) profesor.get("Asignaturas");
            total += asignaturas.size();
        }
        System.out.println("Total subjects to assign: " + total);
        return total;
    }

    private static void configureSalasRequests(int totalSubjects) {
        for (Map.Entry<String, AgentController> entry : salasControllers.entrySet()) {
            try {
                SalaInterface salaInterface = entry.getValue().getO2AInterface(SalaInterface.class);
                if (salaInterface != null) {
                    salaInterface.setTotalSolicitudes(totalSubjects);
                }
            } catch (StaleProxyException e) {
                System.out.println("Error configuring requests for room: " + entry.getKey());
                e.printStackTrace();
            }
        }
    }

    private static void createMonitorAgent(AgentContainer container) throws StaleProxyException {
        Object[] monitorArgs = {profesoresControllers};
        container.createNewAgent(
                "agentes.Supervisor",
                Supervisor.class.getName(),
                monitorArgs
        ).start();
    }
}