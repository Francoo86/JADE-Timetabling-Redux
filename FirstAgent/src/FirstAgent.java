import jade.core.Agent;
import jade.core.AID;
import java.util.Iterator;

public class FirstAgent extends Agent {

    protected void setup() {
        // Obtén el nombre que se pasó como argumento (si existe).
        Object[] args = getArguments();
        String localname;
        if (args != null && args.length > 0) {
            localname = (String) args[0];
        } else {
            localname = "DefaultName"; // Nombre predeterminado si no se especifica.
        }

        // Cambia el nombre local del agente usando su AID (si es necesario).
        AID id = new AID(localname, AID.ISLOCALNAME);

        System.out.println("Hello world! I'm an agent!");
        System.out.println("My local name is " + getAID().getLocalName());
        System.out.println("My GUID is " + getAID().getName());
        System.out.println("My addresses are:");

        // Usar el método getAllAddresses() con java.util.Iterator.
        Iterator<String> it = getAID().getAllAddresses();
        while (it.hasNext()) {
            System.out.println("- " + it.next());
        }
    }
}
