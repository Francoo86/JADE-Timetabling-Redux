package agentes;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import json_stuff.ProfesorHorarioJSON;
import json_stuff.SalaHorarioJSON;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgenteSupervisor extends Agent {
    private List<AgentController> profesoresControllers;
    private boolean isSystemActive = true;
    private static final int CHECK_INTERVAL = 5000; // 5 seconds

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            profesoresControllers = (List<AgentController>) args[0];
            System.out.println("[Supervisor] Monitoring " + profesoresControllers.size() + " professors");
        }

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
            System.out.println(myAgent.getLocalName() + "MSG Pendientes: " + myAgent.getCurQueueSize());

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
                SalaHorarioJSON.getInstance().generarArchivoJSON();
                
                // Esperar un momento para asegurar que los archivos se escriban
                Thread.sleep(1000);
                
                System.out.println("[Supervisor] Verificando archivos generados...");
                
                // Verificar que los archivos se hayan generado correctamente
                java.io.File horariosSalas = new java.io.File("agent_output/Horarios_salas.json");
                java.io.File horariosProf = new java.io.File("agent_output/Horarios_asignados.json");
                
                if (horariosSalas.exists() && horariosSalas.length() > 0) {
                    System.out.println("[Supervisor] Horarios_salas.json generado correctamente");
                } else {
                    System.out.println("[Supervisor] ERROR: Horarios_salas.json está vacío o no existe");
                }
                
                if (horariosProf.exists() && horariosProf.length() > 0) {
                    System.out.println("[Supervisor] Horarios_asignados.json generado correctamente");
                } else {
                    System.out.println("[Supervisor] ERROR: Horarios_asignados.json está vacío o no existe");
                }
                
                System.out.println("[Supervisor] Sistema finalizado.");
                myAgent.doDelete();
            } catch (Exception e) {
                System.err.println("[Supervisor] Error finalizando sistema: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}