package agentes;

import aplicacion.IterativeAplicacion;
import jade.core.Agent;
import jade.core.Runtime;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import json_stuff.ProfesorHorarioJSON;
import json_stuff.SalaHorarioJSON;
import performance.RTTLogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgenteSupervisor extends Agent {
    private List<AgentController> profesoresControllers;
    private Map<String, AgentController> salasControllers;
    private boolean isSystemActive = true;
    private static final int CHECK_INTERVAL = 5000; // 5 seconds
    private IterativeAplicacion myApp;
    private String scenario;
    public static final String AGENT_NAME = "SUPERVISOR";
    private Map<String, Boolean> professorStatus;
    private int totalProfessors;

    // In AgenteSupervisor.java
    //private AgentPerformanceMonitor performanceMonitor;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            profesoresControllers = (List<AgentController>) args[0];
            System.out.println("[Supervisor] Monitoring " + profesoresControllers.size() + " professors");

            if (args.length > 3 && args[3] instanceof IterativeAplicacion) {
                myApp = (IterativeAplicacion) args[3];
            }

            if (args.length > 4 && args[4] instanceof Map) {
                salasControllers = (Map<String, AgentController>) args[4];
                System.out.println("[Supervisor] Monitoring " + salasControllers.size() + " rooms");
            } else {
                System.out.println("[Supervisor] No room agents to monitor.");
            }
        }

        int iteration = args[1] != null ? (int) args[1] : 0;
        String scenarioName = args[2] != null ? (String) args[2] : "small";
        scenario = scenarioName;

        professorStatus = new HashMap<>();
        totalProfessors = profesoresControllers.size();

        // Inicializar estado de profesores
        for (AgentController prof : profesoresControllers) {
            try {
                professorStatus.put(prof.getName(), false);
            } catch (StaleProxyException e) {
                e.printStackTrace();
            }
        }

        addBehaviour(new ProfessorCompletionMonitor());

        //String agentName = "Supervisor_" + getLocalName();
        //performanceMonitor = new AgentPerformanceMonitor(getLocalName(), "SUPERVISOR", scenarioName);
        //performanceMonitor.startMonitoring();

        addBehaviour(new ShutdownBehaviour(this));

        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setName(AGENT_NAME);
            sd.setType(AGENT_NAME);

            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new RuntimeException(e);
        }
    }

    private void finishSystem() {
        try {
            isSystemActive = false;
            System.out.println("[Supervisor] Generando archivos JSON finales...");

            // Generar JSONs finales
            ProfesorHorarioJSON.getInstance().generarArchivoJSON();
            //SalaHorarioJSON.getInstance().generarArchivoJSON();

            if(salasControllers != null && !salasControllers.isEmpty()) {
                List<AgentController> salaList = List.copyOf(salasControllers.values());
                SalaHorarioJSON.getInstance().generateSupervisorFinalReport(salaList);
            }
            else {
                SalaHorarioJSON.getInstance().generarArchivoJSON();
            }

            if(myApp != null) {
                myApp.markSupervisorAsFinished();
            }

            // Esperar un momento para asegurar que los archivos se escriban
            Thread.sleep(1000);

            System.out.println("[Supervisor] Verificando archivos generados para el escenario: " + scenario);

            if (SalaHorarioJSON.getInstance().isJsonFileGenerated()) {
                System.out.println("[Supervisor] Horarios_salas.json generado correctamente");
            } else {
                System.out.println("[Supervisor] ERROR: Horarios_salas.json está vacío o no existe");
            }

            if (ProfesorHorarioJSON.getInstance().isJsonFileGenerated()) {
                System.out.println("[Supervisor] Horarios_asignados.json generado correctamente");
            } else {
                System.out.println("[Supervisor] ERROR: Horarios_asignados.json está vacío o no existe");
            }

                /*
                if (performanceMonitor != null) {
                    performanceMonitor.stopMonitoring();
                    //performanceMonitor.analyzeBottlenecks();
                    //performanceMonitor.generateThreadDump();
                    CentralizedMonitor.shutdown();
                }*/

            // kill all salas
            for (AgentController sala : salasControllers.values()) {
                try {
                    sala.kill();
                } catch (StaleProxyException e) {
                    System.err.println("[Supervisor] Error killing room agent: " + e.getMessage());
                }
            }

            //Runtime.instance().shutDown();
            RTTLogger.getInstance().stop();

            System.out.println("[Supervisor] Sistema finalizado.");
            doDelete();
        } catch (Exception e) {
            System.err.println("[Supervisor] Error finalizando sistema: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private class ProfessorCompletionMonitor extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchContent("PROFESSOR_FINISHED")
            );

            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                String profName = msg.getUserDefinedParameter("professorName");
                if (profName != null) {
                    professorStatus.put(profName, true);

                    long completed = professorStatus.values().stream()
                            .filter(status -> status)
                            .count();

                    System.out.printf("[Supervisor] Professor %s finished (%d/%d completed)%n",
                            profName, completed, totalProfessors);

                    // Si todos terminaron, finalizar sistema
                    if (completed >= totalProfessors) {
                        finishSystem();
                    }
                }
            } else {
                block();
            }
        }
    }

    private class ShutdownBehaviour extends CyclicBehaviour {
        public ShutdownBehaviour(Agent a) {
            super(a);
        }

        @Override
        public void action() {
            MessageTemplate template = MessageTemplate.MatchPerformative(ACLMessage.CANCEL);
            ACLMessage msg = myAgent.receive(template);

            if (msg != null) {
                System.out.println("[Supervisor] Received shutdown message.");
                finishSystem();
            } else {
                block();
            }
        }
    }
}