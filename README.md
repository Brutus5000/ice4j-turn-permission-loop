# ice4j TURN relay bugs

Minimal reproductions of two [ice4j](https://github.com/jitsi/ice4j) TURN client bugs:

1. [CreatePermission loop](#createpermission-loop): refused TURN permissions are re-sent in a tight loop ([jitsi/ice4j#319](https://github.com/jitsi/ice4j/issues/319)).
2. [`Agent.free()` deadlock](#agentfree-deadlock): freeing an agent with a busy relayed candidate can hang forever.

## CreatePermission loop

When a TURN server refuses a
`CreatePermission` request, ice4j sends it again straight away, with no backoff and no limit, until the agent is
freed. This results in thousands of requests per second per refused peer address.

### Run it

Requires Docker with Compose.

```sh
./run.sh
```

Expected output (ice4j `3.2-22-gcb4d152`, coturn 4.6.2):

```
ICE state after 10s: Failed
coturn received 68725 CreatePermission requests (68725 refused with 403 Forbidden IP)
```

Another ice4j version can be tested with `ICE4J_VERSION=3.0-66-g1c60acc ./run.sh`.

### What the reproduction does

[`Repro.java`](src/main/java/repro/Repro.java) uses only public ice4j API:

1. Creates an `Agent` with a `TurnCandidateHarvester` using long-term credentials and gathers a relayed candidate from
   coturn.
2. Adds a single remote host candidate on a private address (`10.1.2.3`), as a peer behind a home router would offer.
3. Starts connectivity establishment and waits 10 seconds.

ICE pairs the relayed candidate with the private remote address and sends a connectivity check through the relay.
coturn is configured with `denied-peer-ip` for private ranges, which is common hardening and what public TURN
providers such as Cloudflare and Xirsys enforce, so it answers `403 Forbidden IP`. ice4j re-sends the request
immediately. This keeps going after ICE has already failed, until the agent is freed.

### Cause

In `org.ice4j.socket.RelayedCandidateDatagramSocket`:

1. `TurnCandidateHarvest.processErrorOrFailure` passes the failure to
   `RelayedCandidateDatagramSocket.processErrorOrFailure`, which calls `setChannelBound(request, false)`.
2. `Channel.setBound` clears `bindingTransactionID`, and `packetsToSend.notifyAll()` wakes the send thread.
3. `runInSendThread` still has the packet for that peer queued. `channel.isBinding()` is now false, so it calls
   `channel.bind()`, which sends a new `CreatePermission`.
4. The next error response starts the cycle again, at the speed of the network round trip.

Queued packets for a peer are only removed once the permission succeeds, so a peer that is never permitted keeps the
loop running for the lifetime of the socket. The same happens on transaction timeouts (`processFailure`).

### Impact

In production (FAForever, ~thousands of concurrent ICE agents) this caused terabytes of traffic against Cloudflare
TURN, error floods in coturn logs, and relayed connections that never got established.

### Possible fix

- Don't retry permanent errors (400, 401 after the credentials challenge, 403, 443). Drop the queued packets for that
  peer instead.
- Retry other failures with exponential backoff and a maximum number of attempts.
- Optionally, don't pair relayed candidates with non-routable remote addresses.

A workaround from outside ice4j (subclassing `TurnCandidateHarvester` / `TurnCandidateHarvest`) is in
[FAForever/java-ice-adapter#95](https://github.com/FAForever/java-ice-adapter/pull/95).

## `Agent.free()` deadlock

`Agent.free()` can hang forever when the agent has a relayed candidate whose send thread is busy at the same time.

### Run it

```sh
./run.sh deadlock
```

Expected output (ice4j `3.2-22-gcb4d152`, coturn 4.6.2). The exact count varies between runs:

```
Freeing 40 agents with agent.free()
iteration 9: agent.free() did not return within 10000 ms (deadlocked)
...
RESULT: 4 of 40 frees hung
```

With the workaround (`FREE_MODE=remove-stream-first ./run.sh deadlock`):

```
Freeing 40 agents with agent.removeStream(stream) + agent.free()
RESULT: 0 of 40 frees hung
```

### What the reproduction does

[`FreeDeadlock.java`](src/main/java/repro/FreeDeadlock.java) repeats the following steps:

1. Allocates a relayed candidate from coturn.
2. Sends one application datagram through it to a peer coturn refuses. This keeps the relayed socket's send thread busy with CreatePermission and ChannelBind requests (the loop above).
3. Calls `agent.free()` on a separate thread after 3 seconds.

A free that doesn't return within 10 seconds counts as hung. Sending application data needs the relayed socket, which is not public API, so it is obtained with reflection.

### Cause

There is a lock-order inversion between `NetAccessManager`'s connector map and `RelayedCandidateDatagramSocket.packetsToSend`:

- **`Agent.free()`** calls `StunStack.shutDown()` before it removes the streams. `NetAccessManager.stop()` then calls `connector.stop()` on every connector while holding `udpConnectors`. For the relayed candidate, this closes the `RelayedCandidateDatagramSocket`, which needs `packetsToSend`.
- **`RelayedCandidateDatagramSocket.runInSendThread()`** holds `packetsToSend` for its whole loop. In that loop it sends TURN requests and Send indications, which need `udpConnectors` in `NetAccessManager.getConnector()`.

`NetAccessManager.removeSocket()` already does this safely: it removes the connector under the lock and stops it after releasing the lock.

The CreatePermission loop makes the deadlock far more likely, but ordinary relay traffic (permission refreshes, Send indications) takes the same path.

### Possible fix

- In `NetAccessManager.stop()`, copy the connectors while holding the lock, then stop them after releasing it, as `removeSocket()` does. Or:
- In `Agent.free()`, remove the streams before `getStunStack().shutDown()`.

**Workaround:** call `agent.removeStream(stream)` before `agent.free()`. See
[FAForever/java-ice-adapter#97](https://github.com/FAForever/java-ice-adapter/pull/97).
