package repro;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RelayedCandidate;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

/**
 * Reproduction of a deadlock in {@link Agent#free()} with relayed candidates.
 *
 * <p>Each iteration allocates a relayed candidate, sends one application datagram through it to a peer the TURN server
 * refuses (which keeps the relayed socket's send thread busy with CreatePermission + ChannelBind requests), and then
 * frees the agent on a separate thread. A free that does not return within the timeout is counted as hung; the
 * thread dump of such a JVM shows the lock-order inversion between NetAccessManager's connector map and
 * RelayedCandidateDatagramSocket.packetsToSend.
 *
 * <p>With {@code FREE_MODE=remove-stream-first}, the media stream is removed before {@code agent.free()} as a
 * workaround.
 */
public class FreeDeadlock {

    public static void main(String[] args) throws Exception {
        String turnHost = env("TURN_HOST", "coturn");
        int turnPort = Integer.parseInt(env("TURN_PORT", "3478"));
        String turnUser = env("TURN_USER", "test");
        String turnPassword = env("TURN_PASSWORD", "secret");
        String peerHost = env("REMOTE_CANDIDATE_IP", "10.1.2.3");
        int iterations = Integer.parseInt(env("ITERATIONS", "40"));
        long trafficMillis = Long.parseLong(env("TRAFFIC_MILLIS", "3000"));
        long hangTimeoutMillis = Long.parseLong(env("HANG_TIMEOUT_MILLIS", "10000"));
        boolean removeStreamFirst = env("FREE_MODE", "plain").equals("remove-stream-first");

        // The relayed socket is not reachable through public API; it is what the ICE component socket sends through.
        Method relayedSocketGetter = RelayedCandidate.class.getDeclaredMethod("getRelayedCandidateDatagramSocket");
        relayedSocketGetter.setAccessible(true);

        System.out.println("Freeing " + iterations + " agents with "
                + (removeStreamFirst ? "agent.removeStream(stream) + agent.free()" : "agent.free()"));
        int hung = 0;
        for (int i = 0; i < iterations; i++) {
            Agent agent = new Agent();
            agent.addCandidateHarvester(new TurnCandidateHarvester(
                    new TransportAddress(turnHost, turnPort, Transport.UDP),
                    new LongTermCredential(turnUser, turnPassword)));
            IceMediaStream stream = agent.createMediaStream("data");
            // A distinct local port per iteration, so that the TURN server sees a new 5-tuple for each allocation
            int port = 7000 + i * 5;
            Component component = agent.createComponent(stream, port, port, port + 4);

            LocalCandidate relay = component.getLocalCandidates().stream()
                    .filter(c -> c.getType() == CandidateType.RELAYED_CANDIDATE)
                    .findFirst()
                    .orElse(null);
            if (relay == null) {
                System.out.println("iteration " + i + ": no relayed candidate gathered, check the TURN server");
                System.exit(2);
            }

            byte[] data = "application data".getBytes();
            DatagramSocket relayedSocket = (DatagramSocket) relayedSocketGetter.invoke(relay);
            relayedSocket.send(new DatagramPacket(data, data.length, new InetSocketAddress(peerHost, 5000)));
            TimeUnit.MILLISECONDS.sleep(trafficMillis);

            Thread freeThread = Thread.ofPlatform().daemon().name("free-" + i).start(() -> {
                if (removeStreamFirst) {
                    agent.removeStream(stream);
                }
                agent.free();
            });
            freeThread.join(hangTimeoutMillis);
            if (freeThread.isAlive()) {
                hung++;
                System.out.println("iteration " + i + ": agent.free() did not return within " + hangTimeoutMillis
                        + " ms (deadlocked)");
            }
        }

        System.out.println("RESULT: " + hung + " of " + iterations + " frees hung");
        System.exit(0);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
