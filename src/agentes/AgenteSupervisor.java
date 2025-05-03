package agentes;

import aplicacion.IterativeAplicacion;
import jade.core.Agent;
import jade.core.Runtime;
import jade.core.behaviours.TickerBehaviour;
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

        //String agentName = "Supervisor_" + getLocalName();
        //performanceMonitor = new AgentPerformanceMonitor(getLocalName(), "SUPERVISOR", scenarioName);
        //performanceMonitor.startMonitoring();

        addBehaviour(new MonitorBehaviour(this, CHECK_INTERVAL));
    }

    private class MonitorBehaviour extends TickerBehaviour {
        private static final int MAX_INACTIVITY = 12; // 1 minute with 5-second intervals
        private Map<Integer, Integer> inactivityCounters = new HashMap<>();
        private Map<Integer, Integer> lastKnownStates = new HashMap<>();

        public MonitorBehaviour(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            if (!isSystemActive) return;

            try {
                boolean allTerminated = true;
                Map<Integer, Integer> stateCount = new HashMap<>();

                // Monitor each professor's state
                for (int i = 0; i < profesoresControllers.size(); i++) {
                    AgentController profesor = profesoresControllers.get(i);

                    try {
                        int currentState = profesor.getState().getCode();
                        int previousState = lastKnownStates.getOrDefault(i, -1);

                        // Update state counts
                        stateCount.merge(currentState, 1, Integer::sum);

                        // Check if state has changed
                        if (currentState == previousState) {
                            inactivityCounters.merge(i, 1, Integer::sum);
                        } else {
                            inactivityCounters.put(i, 0);
                            lastKnownStates.put(i, currentState);
                        }

                        // Check different states
                        switch (currentState) {
                            case Agent.AP_ACTIVE:
                                allTerminated = false;
//                                if (inactivityCounters.getOrDefault(i, 0) >= MAX_INACTIVITY) {
//                                    System.out.println("[WARNING] Professor " + i + " appears stuck in ACTIVE state. " +
//                                            "Inactivity count: " + inactivityCounters.get(i));
//                                }
                                break;

                            case Agent.AP_WAITING:
                                allTerminated = false;
//                                if (inactivityCounters.getOrDefault(i, 0) >= MAX_INACTIVITY) {
//                                    System.out.println("[WARNING] Professor " + i + " appears stuck in WAITING state. " +
//                                            "Inactivity count: " + inactivityCounters.get(i));
//                                }
                                break;

                            case Agent.AP_SUSPENDED:
                                allTerminated = false;
                                System.out.println("[WARNING] Professor " + i + " is in SUSPENDED state.");
                                break;

                            case Agent.AP_IDLE:
                                allTerminated = false;
//                                if (inactivityCounters.getOrDefault(i, 0) >= MAX_INACTIVITY) {
//                                    System.out.println("[WARNING] Professor " + i + " appears stuck in IDLE state. " +
//                                            "Inactivity count: " + inactivityCounters.get(i));
//                                }
                                break;

                            case Agent.AP_DELETED:
                                // Consider this professor terminated
                                inactivityCounters.remove(i);
                                lastKnownStates.remove(i);
                                break;

                            case Agent.AP_INITIATED:
                                allTerminated = false;
                                System.out.println("[INFO] Professor " + i + " is still in INITIATED state.");
                                break;

                            default:
                                System.out.println("[WARNING] Professor " + i + " is in unknown state: " + currentState);
                                break;
                        }

                    } catch (StaleProxyException e) {
                        // Consider terminated if we can't get state
                        stateCount.merge(Agent.AP_DELETED, 1, Integer::sum);
                    }
                }

                // Regular status update
                if (getPeriod() % (CHECK_INTERVAL * 4) == 0) {
//                    System.out.println("\n[Supervisor] Status Report:");
//                    System.out.println("- Active: " + stateCount.getOrDefault(Agent.AP_ACTIVE, 0));
//                    System.out.println("- Waiting: " + stateCount.getOrDefault(Agent.AP_WAITING, 0));
//                    System.out.println("- Idle: " + stateCount.getOrDefault(Agent.AP_IDLE, 0));
//                    System.out.println("- Suspended: " + stateCount.getOrDefault(Agent.AP_SUSPENDED, 0));
//                    System.out.println("- Deleted: " + stateCount.getOrDefault(Agent.AP_DELETED, 0));
//                    System.out.println("- Initiated: " + stateCount.getOrDefault(Agent.AP_INITIATED, 0));
//                    System.out.println("Total Agents: " + profesoresControllers.size() + "\n");
                }

                if (allTerminated || stateCount.getOrDefault(Agent.AP_DELETED, 0) == profesoresControllers.size()) {
                    //System.out.println("[Supervisor] All professors have completed their work.");
                    finishSystem();
                }

            } catch (Exception e) {
                //System.err.println("[Supervisor] Error in monitoring: " + e.getMessage());
                e.printStackTrace();
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
                myAgent.doDelete();
            } catch (Exception e) {
                System.err.println("[Supervisor] Error finalizando sistema: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}