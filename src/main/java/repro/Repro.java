package repro;

import java.util.concurrent.TimeUnit;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

/**
 * Minimal reproduction of the ice4j TURN CreatePermission loop.
 *
 * <p>A single ICE agent gathers a relayed candidate from a TURN server (long-term credentials) and is then given one
 * remote host candidate on a private address, exactly like a remote peer behind a home router would offer. ICE pairs
 * the relayed candidate with that private address and sends a connectivity check through the relay. The TURN server
 * refuses the permission (403 Forbidden IP), and ice4j immediately re-sends the CreatePermission request, forever.
 *
 * <p>Count the requests in the coturn log (see run.sh).
 */
public class Repro {

    public static void main(String[] args) throws Exception {
        String turnHost = env("TURN_HOST", "coturn");
        int turnPort = Integer.parseInt(env("TURN_PORT", "3478"));
        String turnUser = env("TURN_USER", "test");
        String turnPassword = env("TURN_PASSWORD", "secret");
        String remoteHost = env("REMOTE_CANDIDATE_IP", "10.1.2.3");
        int seconds = Integer.parseInt(env("DURATION_SECONDS", "10"));

        Agent agent = new Agent();
        agent.setControlling(true);
        agent.addCandidateHarvester(new TurnCandidateHarvester(
                new TransportAddress(turnHost, turnPort, Transport.UDP),
                new LongTermCredential(turnUser, turnPassword)));

        IceMediaStream stream = agent.createMediaStream("data");
        Component component = agent.createComponent(stream, 7000, 7000, 7100);

        System.out.println("Local candidates:");
        component.getLocalCandidates().forEach(c -> System.out.println("  " + c));
        boolean hasRelay = component.getLocalCandidates().stream()
                .map(LocalCandidate::getType)
                .anyMatch(CandidateType.RELAYED_CANDIDATE::equals);
        if (!hasRelay) {
            System.out.println("No relayed candidate gathered, check the TURN server/credentials.");
            System.exit(2);
        }

        // The remote peer offers a host candidate on its private LAN address.
        stream.setRemoteUfrag("remoteufrag");
        stream.setRemotePassword("remotepasswordremotepassword");
        component.addRemoteCandidate(new RemoteCandidate(
                new TransportAddress(remoteHost, 5000, Transport.UDP),
                component,
                CandidateType.HOST_CANDIDATE,
                "1",
                2130706431L,
                null));
        System.out.println("Added remote host candidate " + remoteHost + ":5000, starting ICE for " + seconds + "s");

        agent.startConnectivityEstablishment();
        TimeUnit.SECONDS.sleep(seconds);

        System.out.println("ICE state after " + seconds + "s: " + agent.getState());
        agent.free();
        System.exit(0);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
