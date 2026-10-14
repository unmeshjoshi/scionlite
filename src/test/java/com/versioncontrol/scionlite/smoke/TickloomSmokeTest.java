package com.versioncontrol.scionlite.smoke;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.Replica;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import com.tickloom.testkit.Cluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Proves the tickloom library and testkit resolve and a request/response works on the simulated network. */
class TickloomSmokeTest {
    static final MessageType ECHO = MessageType.of("SMOKE_ECHO");
    static final MessageType ECHO_REPLY = MessageType.of("SMOKE_ECHO_REPLY");

    record Echo(String text) {}

    static class EchoReplica extends Replica {
        EchoReplica(List<ProcessId> peers, ProcessParams params) {
            super(peers, params);
        }

        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of(ECHO, m -> send(createResponseMessage(m, deserializePayload(m.payload(), Echo.class), ECHO_REPLY)));
        }
    }

    static class EchoClient extends ClusterClient {
        EchoClient(List<ProcessId> replicas, ProcessParams params) {
            super(replicas, params);
        }

        TickCompletableFuture<Echo> echo(String text) {
            return sendRequest(new Echo(text), replicaEndpoints.get(0), ECHO);
        }

        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of(ECHO_REPLY, (Message m) -> handleResponse(m.correlationId(), deserialize(m.payload(), Echo.class), m.source()));
        }
    }

    @Test
    void echoesOverTheSimulatedNetwork() throws Exception {
        try (Cluster cluster = Cluster.createSimulated(3, EchoReplica::new, 1)) {
            EchoClient client = cluster.newClient(ProcessId.of("client"), EchoClient::new);
            assertEquals("hello", cluster.tickUntilComplete(client.echo("hello")).text());
        }
    }
}
