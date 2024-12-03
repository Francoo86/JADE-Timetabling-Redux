package behaviours;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

public class NegociarAsignaturasRedux extends CyclicBehaviour {
    @Override
    public void action() {
        ACLMessage msg = myAgent.receive();
        if(msg != null) {
            switch (msg.getPerformative()) {
                case ACLMessage.PROPOSE:
                    break;
                case ACLMessage.ACCEPT_PROPOSAL:
                    break;
                case ACLMessage.REJECT_PROPOSAL:
                    break;
            }
        } else {
            block();
        }
    }
}
