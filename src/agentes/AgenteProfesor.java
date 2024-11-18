package agentes;

import constants.Messages;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import json_stuff.ProfesorHorarioJSON;
import objetos.Asignatura;
import objetos.Propuesta;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import java.util.*;

public class AgenteProfesor extends Agent {
    public static final String AGENT_NAME = "Profesor";
    private String nombre;
    private String rut;
    private int turno;
    private List<Asignatura> asignaturas;
    private int asignaturaActual = 0;
    private Map<String, Set<Integer>> horarioOcupado; // dia -> bloques
    private int orden;
    private JSONObject horarioJSON;
    private static final long TIMEOUT_PROPUESTA = 5000; // 5 segundos para recibir propuestas
    private boolean isRegistered = false;
    private boolean isCleaningUp = false;
    private boolean negociacionIniciada = false;
    private Map<String, Map<String, List<Integer>>> bloquesAsignadosPorDia; // dia -> (bloque -> asignatura)

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 1) {
            String jsonString = (String) args[0];
            orden = (Integer) args[1];
            cargarDatos(jsonString);
        }

        horarioOcupado = new HashMap<>();
        horarioJSON = new JSONObject();
        horarioJSON.put("Asignaturas", new JSONArray());
        registrarEnDF();

        // Add periodic state verification
        addBehaviour(new TickerBehaviour(this, 5000) { // Every 5 seconds
            protected void onTick() {
                addBehaviour(new MessageQueueDebugBehaviour());
            }
        });

        if (orden == 0) {
            iniciarNegociacion();
        } else {
            addBehaviour(new EsperarTurnoBehaviour());
        }

        bloquesAsignadosPorDia = new HashMap<>();
        for (String dia : new String[]{"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"}) {
            bloquesAsignadosPorDia.put(dia, new HashMap<>());
        }

        System.out.println("Profesor " + nombre + " (orden " + orden + ") iniciado");
    }

    private void iniciarNegociacion() {
        if (!negociacionIniciada) {
            negociacionIniciada = true;
            System.out.println("Profesor " + nombre + " iniciando proceso de negociación");
            addBehaviour(new NegociarAsignaturasBehaviour());
        }
    }

    private void registrarEnDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("profesor");
            sd.setName(AGENT_NAME + orden);
            sd.addProperties(new Property("orden", orden));

            dfd.addServices(sd);
            DFService.register(this, dfd);
            isRegistered = true;
            System.out.println("Profesor " + nombre + " registrado en DF");
        } catch (FIPAException fe) {
            System.err.println("Error registrando profesor " + nombre + " en DF: " + fe.getMessage());
            fe.printStackTrace();
        }
    }

    private void cargarDatos(String jsonString) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            rut = (String) jsonObject.get("RUT");
            nombre = (String) jsonObject.get("Nombre");
            turno = ((Number) jsonObject.get("Turno")).intValue();

            asignaturas = new ArrayList<>();
            JSONArray asignaturasJson = (JSONArray) jsonObject.get("Asignaturas");
            for (Object obj : asignaturasJson) {
                JSONObject asignaturaJson = (JSONObject) obj;
                Asignatura parsedSubject = Asignatura.fromJson(asignaturaJson);
                asignaturas.add(parsedSubject);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class EsperarTurnoBehaviour extends CyclicBehaviour {
        public void action() {
            // Match any INFORM message with START content
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchContent(Messages.START)
            );

            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                String nextOrdenStr = msg.getUserDefinedParameter("nextOrden");
                int nextOrden = Integer.parseInt(nextOrdenStr);

                System.out.println("[DEBUG] " + nombre + " received START message. My orden=" +
                        orden + ", nextOrden=" + nextOrden);

                // Only act if this is the target profesor
                if (nextOrden == orden) {
                    System.out.println("Profesor " + nombre + " (orden " + orden +
                            ") activating on START signal");
                    iniciarNegociacion();
                    myAgent.removeBehaviour(this);
                } else {
                    System.out.println("[DEBUG] Ignoring START message (not for me)");
                }
            } else {
                block(500); // Short block to avoid CPU spinning
                System.out.println("[DEBUG] " + nombre + " (orden=" + orden +
                        ") waiting for START signal");
            }
        }
    }

    private class NegociarAsignaturasBehaviour extends Behaviour {
        private int step = 0;
        private List<Propuesta> propuestas;
        private boolean finished = false;
        private long tiempoInicio;
        private int intentos = 0;
        private static final int MAX_INTENTOS = 3;
        private int bloquesPendientes = 0;
        private Set<String> bloquesProgramados;
        private String ultimoDiaAsignado = null;
        private int ultimoBloqueAsignado = -1;
        private String salaAsignada = null;

        public void action() {
            switch (step) {
                case 0: // Iniciar negociación
                    if (asignaturaActual < asignaturas.size()) {
                        Asignatura asignaturaActualObj = asignaturas.get(asignaturaActual);
                        bloquesPendientes = asignaturaActualObj.getHoras();
                        bloquesProgramados = new HashSet<>();
                        salaAsignada = null;
                        ultimoDiaAsignado = null;
                        ultimoBloqueAsignado = -1;
                        
                        System.out.println("Profesor " + nombre + " iniciando negociación para " +
                                asignaturaActualObj.getNombre() + " (" + bloquesPendientes + " horas)");
                        
                        solicitarPropuestas();
                        propuestas = new ArrayList<>();
                        tiempoInicio = System.currentTimeMillis();
                        step = 1;
                    } else {
                        finished = true;
                    }
                    break;

                case 1: // Recolectar propuestas
                    MessageTemplate mt = MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                            MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                    );

                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        if (reply.getPerformative() == ACLMessage.PROPOSE) {
                            Propuesta propuesta = Propuesta.parse(reply.getContent());
                            propuesta.setMensaje(reply);
                            propuestas.add(propuesta);
                        }
                    }

                    if (System.currentTimeMillis() - tiempoInicio > TIMEOUT_PROPUESTA) {
                        if (!propuestas.isEmpty()) {
                            step = 2;
                        } else {
                            manejarTimeoutPropuestas();
                        }
                    } else {
                        block(100);
                    }
                    break;

                case 2: // Evaluar propuestas
                    boolean asignacionExitosa = procesarPropuestas();
                    if (asignacionExitosa) {
                        if (bloquesPendientes == 0) {
                            // Asignatura completamente programada
                            intentos = 0;
                            asignaturaActual++;
                            step = 0;
                        } else {
                            // Continuar con los bloques restantes
                            propuestas.clear();
                            solicitarPropuestas();
                            tiempoInicio = System.currentTimeMillis();
                            step = 1;
                        }
                    } else {
                        manejarFalloPropuesta();
                    }
                    break;
            }
        }

        private void solicitarPropuestas() {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("sala");
                template.addServices(sd);
                DFAgentDescription[] result = DFService.search(myAgent, template);

                if (result.length > 0) {
                    ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                    for (DFAgentDescription dfd : result) {
                        cfp.addReceiver(dfd.getName());
                    }

                    Asignatura asignatura = asignaturas.get(asignaturaActual);
                    String solicitudInfo = String.format("%s,%d,%s,%s,%d",
                            asignatura.getNombre(),
                            asignatura.getVacantes(),
                            salaAsignada != null ? salaAsignada : "",
                            ultimoDiaAsignado != null ? ultimoDiaAsignado : "",
                            ultimoBloqueAsignado);

                    cfp.setContent(solicitudInfo);
                    cfp.setConversationId("neg-" + nombre + "-" + asignaturaActual + "-" + bloquesPendientes);
                    myAgent.send(cfp);

                    System.out.println("Profesor " + nombre + " solicitando propuestas para " +
                            asignatura.getNombre() + " (bloques pendientes: " + bloquesPendientes +
                            ", sala previa: " + salaAsignada + ")");
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }

        private boolean procesarPropuestas() {
            if (propuestas.isEmpty()) return false;

            // Ordenar propuestas priorizando la continuidad
            ordenarPropuestas();

            for (Propuesta propuesta : propuestas) {
                if (intentarAsignarPropuesta(propuesta)) {
                    return true;
                }
            }

            return false;
        }

        private void ordenarPropuestas() {
            propuestas.sort((p1, p2) -> {
                // Si ya tenemos bloques asignados en un día, priorizar completar ese día
                if (ultimoDiaAsignado != null) {
                    boolean p1MismoDia = p1.getDia().equals(ultimoDiaAsignado);
                    boolean p2MismoDia = p2.getDia().equals(ultimoDiaAsignado);
                    
                    if (p1MismoDia && !p2MismoDia) return -1;
                    if (!p1MismoDia && p2MismoDia) return 1;
                    
                    // Si ambas son del mismo día, priorizar bloques consecutivos
                    if (p1MismoDia && p2MismoDia) {
                        boolean p1Consecutivo = esConsecutivo(p1);
                        boolean p2Consecutivo = esConsecutivo(p2);
                        if (p1Consecutivo && !p2Consecutivo) return -1;
                        if (!p1Consecutivo && p2Consecutivo) return 1;
                    }
                }

                // Si no hay día previo, priorizar días con más bloques disponibles consecutivos
                if (ultimoDiaAsignado == null) {
                    int bloquesConsecutivosP1 = contarBloquesConsecutivosDisponibles(p1);
                    int bloquesConsecutivosP2 = contarBloquesConsecutivosDisponibles(p2);
                    if (bloquesConsecutivosP1 != bloquesConsecutivosP2) {
                        return bloquesConsecutivosP2 - bloquesConsecutivosP1;
                    }
                }

                // Si todo lo demás es igual, ordenar por satisfacción
                return p2.getSatisfaccion() - p1.getSatisfaccion();
            });
        }

        private int contarBloquesConsecutivosDisponibles(Propuesta propuesta) {
            String dia = propuesta.getDia();
            int bloqueInicial = propuesta.getBloque();
            int count = 1;
            
            // Contar bloques consecutivos disponibles hacia adelante
            int bloque = bloqueInicial + 1;
            while (bloque <= 9 && esDisponible(dia, bloque) && count < bloquesPendientes) {
                count++;
                bloque++;
            }
            
            // Contar bloques consecutivos disponibles hacia atrás
            bloque = bloqueInicial - 1;
            while (bloque >= 1 && esDisponible(dia, bloque) && count < bloquesPendientes) {
                count++;
                bloque--;
            }
            
            return count;
        }

        private boolean esDisponible(String dia, int bloque) {
            if (horarioOcupado.containsKey(dia) && horarioOcupado.get(dia).contains(bloque)) {
                return false;
            }
            
            String nombreAsignatura = asignaturas.get(asignaturaActual).getNombre();
            Map<String, List<Integer>> asignaturasEnDia = bloquesAsignadosPorDia.get(dia);
            List<Integer> bloquesEnDia = asignaturasEnDia.getOrDefault(nombreAsignatura, new ArrayList<>());
            
            return bloquesEnDia.size() < 2;
        }

        private boolean esConsecutivo(Propuesta propuesta) {
            if (!propuesta.getDia().equals(ultimoDiaAsignado)) return false;
            int bloque = propuesta.getBloque();
            return Math.abs(bloque - ultimoBloqueAsignado) == 1;
        }

        private boolean intentarAsignarPropuesta(Propuesta propuesta) {
            String dia = propuesta.getDia();
            int bloque = propuesta.getBloque();
            String sala = propuesta.getCodigo();
            String nombreAsignatura = asignaturas.get(asignaturaActual).getNombre();
            
            // Verificar el número total de días utilizados para esta asignatura
            Set<String> diasUtilizados = new HashSet<>();
            for (Map.Entry<String, Map<String, List<Integer>>> entry : bloquesAsignadosPorDia.entrySet()) {
                if (entry.getValue().containsKey(nombreAsignatura)) {
                    diasUtilizados.add(entry.getKey());
                }
            }
            
            // Si estamos intentando usar un nuevo día y ya usamos 2 días, rechazar
            if (!diasUtilizados.contains(dia) && diasUtilizados.size() >= 2) {
                return false;
            }

            // Si es un nuevo día y quedan más de 2 bloques, verificar si hay suficientes bloques consecutivos
            if (!diasUtilizados.contains(dia) && bloquesPendientes > 1) {
                int bloquesConsecutivos = contarBloquesConsecutivosDisponibles(propuesta);
                if (bloquesConsecutivos < Math.min(2, bloquesPendientes)) {
                    return false;
                }
            }

            // Verificar si el bloque está disponible y cumple con las restricciones
            if (esDisponible(dia, bloque)) {
                if (enviarAceptacionPropuesta(propuesta)) {
                    // Actualizar registros como antes
                    actualizarRegistrosAsignacion(dia, bloque, sala, propuesta.getSatisfaccion());
                    return true;
                }
            }
            
            return false;
        }

        private void actualizarRegistrosAsignacion(String dia, int bloque, String sala, int satisfaccion) {
            String nombreAsignatura = asignaturas.get(asignaturaActual).getNombre();
            
            // Actualizar horario ocupado
            horarioOcupado.computeIfAbsent(dia, k -> new HashSet<>()).add(bloque);
            
            // Actualizar bloques por día
            bloquesAsignadosPorDia.computeIfAbsent(dia, k -> new HashMap<>())
                .computeIfAbsent(nombreAsignatura, k -> new ArrayList<>())
                .add(bloque);
            
            // Actualizar estado de negociación
            bloquesPendientes--;
            ultimoDiaAsignado = dia;
            ultimoBloqueAsignado = bloque;
            salaAsignada = sala;
            
            // Actualizar JSON
            actualizarHorarioJSON(dia, sala, bloque, satisfaccion);
            
            System.out.println(String.format("Profesor %s: Asignado bloque %d del día %s en sala %s para %s " +
                    "(quedan %d horas)", nombre, bloque, dia, sala, nombreAsignatura, bloquesPendientes));
        }


        private boolean enviarAceptacionPropuesta(Propuesta propuesta) {
            try {
                ACLMessage accept = propuesta.getMensaje().createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(String.format("%s,%d,%s,%d,%s,%d",
                        propuesta.getDia(),
                        propuesta.getBloque(),
                        asignaturas.get(asignaturaActual).getNombre(),
                        propuesta.getSatisfaccion(),
                        propuesta.getCodigo(),
                        asignaturas.get(asignaturaActual).getVacantes()));
                myAgent.send(accept);

                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchSender(propuesta.getMensaje().getSender()),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                );
                ACLMessage confirm = myAgent.blockingReceive(mt, 5000);
                return confirm != null;
            } catch (Exception e) {
                System.err.println("Error enviando aceptación: " + e.getMessage());
                return false;
            }
        }

        private void manejarTimeoutPropuestas() {
            intentos++;
            if (intentos >= MAX_INTENTOS) {
                if (bloquesPendientes == asignaturas.get(asignaturaActual).getHoras()) {
                    // Si no hemos podido asignar ningún bloque, pasar a la siguiente asignatura
                    System.out.println("Profesor " + nombre + " no pudo obtener propuestas para " +
                            asignaturas.get(asignaturaActual).getNombre() +
                            " después de " + MAX_INTENTOS + " intentos");
                    asignaturaActual++;
                    intentos = 0;
                } else {
                    // Si ya asignamos algunos bloques pero no todos, intentar con otra sala
                    System.out.println("Profesor " + nombre + " buscando nueva sala para bloques restantes de " +
                            asignaturas.get(asignaturaActual).getNombre());
                    salaAsignada = null;
                    intentos = 0;
                }
                step = 0;
            } else {
                System.out.println("Profesor " + nombre + ": Reintentando solicitud de propuestas. " +
                        "Intento " + (intentos + 1) + " de " + MAX_INTENTOS);
                solicitarPropuestas();
                tiempoInicio = System.currentTimeMillis();
            }
        }

        private void manejarFalloPropuesta() {
            intentos++;
            if (intentos >= MAX_INTENTOS) {
                if (salaAsignada != null) {
                    // Intentar con otra sala si la actual no tiene más espacios disponibles
                    System.out.println("Profesor " + nombre + ": Buscando otra sala para los bloques restantes");
                    salaAsignada = null;
                    intentos = 0;
                } else {
                    // Si ya probamos con todas las salas, pasar a la siguiente asignatura
                    System.out.println("Profesor " + nombre + " no pudo completar la asignación de " +
                            asignaturas.get(asignaturaActual).getNombre());
                    asignaturaActual++;
                    intentos = 0;
                }
                step = 0;
            } else {
                propuestas.clear();
                solicitarPropuestas();
                tiempoInicio = System.currentTimeMillis();
                step = 1;
            }
        }

        public boolean done() {
            if (finished) {
                System.out.println("Profesor " + nombre + " completó proceso de negociación");
                finalizarNegociaciones();
            }
            return finished;
        }
    }

    private void actualizarHorarioJSON(String dia, String sala, int bloque, int satisfaccion) {
        JSONObject asignatura = new JSONObject();
        asignatura.put("Nombre", asignaturas.get(asignaturaActual).getNombre());
        asignatura.put("Sala", sala);
        asignatura.put("Bloque", bloque);
        asignatura.put("Dia", dia);
        asignatura.put("Satisfaccion", satisfaccion);
        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignatura);

        System.out.println("Profesor " + nombre + ": Asignada " +
                asignaturas.get(asignaturaActual).getNombre() +
                " en sala " + sala + ", día " + dia +
                ", bloque " + bloque);
    }

    private void finalizarNegociaciones() {
        try {
            if (isCleaningUp) {
                return;
            }
            isCleaningUp = true;

            // Guardar horario final
            ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(
                    nombre, horarioJSON, asignaturas.size());

            // Notificar al siguiente profesor antes de hacer cleanup
            notificarSiguienteProfesor();

            // Realizar cleanup y terminar
            cleanup();

        } catch (Exception e) {
            System.err.println("Error finalizando negociaciones para profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void notificarSiguienteProfesor() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("profesor");
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(this, template);

            System.out.println("[DEBUG] Current profesor: orden=" + orden +
                    ", localName=" + getAID().getLocalName());

            // Look for next professor
            for (DFAgentDescription dfd : result) {
                String targetName = dfd.getName().getLocalName();
                System.out.println("[DEBUG] Found professor: " + targetName);

                //print all services
                for (jade.util.leap.Iterator it = dfd.getAllServices(); it.hasNext(); ) {
                    ServiceDescription service = (ServiceDescription) it.next();
                    System.out.println("[DEBUG] Service: " + service.getName());
                }

                // Send message to ALL other professors (they'll filter by orden)
                if (!targetName.equals(getAID().getLocalName())) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(dfd.getName());
                    msg.setContent(Messages.START);
                    msg.addUserDefinedParameter("nextOrden", Integer.toString(orden + 1));
                    send(msg);
                    System.out.println("[DEBUG] Sent START message to: " + targetName);
                }
            }
        } catch (Exception e) {
            System.err.println("Error notificando siguiente profesor: " + e.getMessage());
        }
    }

    private synchronized void cleanup() {
        try {
            if (isRegistered) {
                // Verificar si aún estamos registrados antes de hacer deregister
                DFAgentDescription dfd = new DFAgentDescription();
                dfd.setName(getAID());
                DFAgentDescription[] result = DFService.search(this, dfd);

                if (result != null && result.length > 0) {
                    DFService.deregister(this);
                    System.out.println("Profesor " + nombre + " eliminado del DF");
                }
                isRegistered = false;
            }

            // Esperar un momento para asegurar que la notificación se envió
            Thread.sleep(1000);
            doDelete();

        } catch (Exception e) {
            System.err.println("Error durante cleanup de profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
        }

        isCleaningUp = false;
    }

    @Override
    protected void takeDown() {
        // Mostrar resumen final
        int asignadas = ((JSONArray) horarioJSON.get("Asignaturas")).size();
        System.out.println("Profesor " + nombre + " finalizado con " +
                asignadas + "/" + asignaturas.size() + " asignaturas asignadas");
    }

    private class MessageQueueDebugBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("\n=== DEBUG: Message Queue Status for " + myAgent.getLocalName() + " ===");
            System.out.println("Queue Size: " + myAgent.getCurQueueSize());

            // Create a template that matches ALL messages
            MessageTemplate mt = MessageTemplate.MatchAll();

            // Store messages to put them back later
            List<ACLMessage> messages = new ArrayList<>();

            // Retrieve and inspect all messages
            ACLMessage msg;
            int msgCount = 0;
            while ((msg = myAgent.receive(mt)) != null) {
                msgCount++;
                System.out.println("\nMessage #" + msgCount + ":");
                System.out.println("  Performative: " + ACLMessage.getPerformative(msg.getPerformative()));
                System.out.println("  Sender: " + (msg.getSender() != null ? msg.getSender().getLocalName() : "null"));
                System.out.println("  Receiver(s):");
                for (jade.util.leap.Iterator it = msg.getAllReceiver(); it.hasNext(); ) {
                    AID receiver = (AID) it.next();
                    System.out.println("    - " + receiver.getLocalName());
                }
                System.out.println("  Content: " + msg.getContent());
                System.out.println("  Conversation ID: " + msg.getConversationId());
                System.out.println("  Protocol: " + msg.getProtocol());
                System.out.println("  Reply-To:");
                Iterator<?> replyTo = msg.getAllReplyTo();
                while (replyTo.hasNext()) {
                    AID aid = (AID) replyTo.next();
                    System.out.println("    - " + aid.getLocalName());
                }
                System.out.println("  Reply-By: " + msg.getReplyByDate());
                System.out.println("  Language: " + msg.getLanguage());
                System.out.println("  Ontology: " + msg.getOntology());
                System.out.println("  User-Defined Parameters:");
                for (Object param : msg.getAllUserDefinedParameters().keySet()) {
                    System.out.println("    - " + param + ": " + msg.getUserDefinedParameter((String) param));
                }

                // Store the message to put it back in queue
                messages.add(msg);
            }

            // Put all messages back in the queue
            for (ACLMessage m : messages) {
                myAgent.postMessage(m);
            }

            System.out.println("\nTotal messages inspected: " + msgCount);
            System.out.println("=== End Message Queue Debug ===\n");
        }
    }

}