package com.versioncontrol.scion.spanner;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.PeerType;
import com.versioncontrol.scion.rpc.Rpc;
import com.versioncontrol.scion.rpc.RpcMessageTypes;

import java.util.List;
import java.util.Map;

import static com.versioncontrol.scion.spanner.SpannerApi.*;

/** A test client that talks straight to the Spanner node, as only scion-servers do in the real tiers. */
class SpannerClient extends ClusterClient {
    private final ProcessId spanner;
    private final Rpc rpc;

    SpannerClient(List<ProcessId> spanner, ProcessParams params) {
        super(spanner, params);
        this.spanner = spanner.get(0);
        this.rpc = new Rpc(id, PeerType.CLIENT, messageBus, messageCodec, waitingList,
                () -> idGen.generateCorrelationId("spanner-client"));
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(RpcMessageTypes.RPC_REPLY, message -> rpc.onReply(message));
    }

    TickCompletableFuture<Session> createSession() {
        return rpc.call(spanner, CREATE_SESSION, CreateSessionRequest.of(DATABASE), Session.class);
    }

    TickCompletableFuture<ResultSet> read(ReadRequest request) {
        return rpc.call(spanner, READ, request, ResultSet.class);
    }

    TickCompletableFuture<ResultSet> readRef(String session, TransactionSelector transaction, String name) {
        return read(new ReadRequest(session, transaction, REFS, Schema.REFS_COLUMNS, KeySet.key(name), 0));
    }

    TickCompletableFuture<CommitResponse> commit(CommitRequest request) {
        return rpc.call(spanner, COMMIT, request, CommitResponse.class);
    }

    TickCompletableFuture<Transaction> begin(String session) {
        return rpc.call(spanner, BEGIN_TRANSACTION,
                new BeginTransactionRequest(session, TransactionOptions.optimisticReadWrite()), Transaction.class);
    }

    TickCompletableFuture<Empty> rollback(String session, String transactionId) {
        return rpc.call(spanner, ROLLBACK, new RollbackRequest(session, transactionId), Empty.class);
    }
}
