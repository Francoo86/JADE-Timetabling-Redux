import constants.MTAgentStatus;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MonitorAgent extends Agent {
    private List<AgentController> profesoresControllers;
    private boolean isSystemActive = true;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            profesoresControllers = (List<AgentController>) args[0];
        }

        addBehaviour(new MonitorBehaviour(this, 5000));
    }

    private class MonitorBehaviour extends TickerBehaviour {
        private static final int MAX_INACTIVITY = 60;
        private Map<Integer, Integer> inactivityCounters = new HashMap<>();

        public MonitorBehaviour(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            if (!isSystemActive) return;

            try {
                // Monitor each professor's state
                for (int i = 0; i < profesoresControllers.size(); i++) {
                    AgentController profesor = profesoresControllers.get(i);

                    try {
                        int state = profesor.getState().getCode();

                        if (state == MTAgentStatus.ACTIVE.getCode()) {
                            int currentCount = inactivityCounters.getOrDefault(i, 0);
                            inactivityCounters.put(i, currentCount + 1);

                            if (currentCount >= MAX_INACTIVITY) {
                                System.out.println("WARNING: Professor " + i + " seems stuck. Consider investigation.");
                            }
                        } else if (state == MTAgentStatus.TERMINATED.getCode()) {
                            inactivityCounters.remove(i);
                        }
                    } catch (StaleProxyException e) {
                        // Agent might be already terminated
                        inactivityCounters.remove(i);
                    }
                }

                // Check if all professors are done
                boolean allTerminated = true;
                for (AgentController profesor : profesoresControllers) {
                    try {
                        if (profesor.getState().getCode() != MTAgentStatus.TERMINATED.getCode()) {
                            allTerminated = false;
                            break;
                        }
                    } catch (StaleProxyException e) {
                        // Consider terminated if we can't get state
                        continue;
                    }
                }

                if (allTerminated) {
                    System.out.println("All professors have completed their work.");
                    finishSystem();
                }

            } catch (Exception e) {
                System.err.println("Error in monitoring: " + e.getMessage());
            }
        }

        private void finishSystem() {
            try {
                isSystemActive = false;
                System.out.println("Generating final JSON files...");
                ProfesorHorarioJSON.getInstance().generarArchivoJSON();
                SalaHorarioJSON.getInstance().generarArchivoJSON();
                myAgent.doDelete();
            } catch (Exception e) {
                System.err.println("Error finishing system: " + e.getMessage());
            }
        }
    }
}