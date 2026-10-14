package com.versioncontrol.scionlite.rpc;

import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.PeerType;

import java.io.IOException;
import java.util.Map;

/**
 * A process that answers calls ({@link #serve}) and makes calls of its own ({@link #call}). Every tier of
 * Scion Lite is one of these.
 */
public abstract class RpcNode extends Process {
    protected final Rpc rpc;

    protected RpcNode(ProcessParams params) {
        super(params);
        this.rpc = new Rpc(id, PeerType.SERVER, messageBus, messageCodec, waitingList,
                () -> idGen.generateCorrelationId("rpc"));
    }

    /** Completes with the encoded result, or fails; the failure's message goes back to the caller. */
    protected abstract TickCompletableFuture<byte[]> serve(String method, byte[] body, ProcessId caller);

    protected <T> TickCompletableFuture<T> call(ProcessId target, String method, Object argument, Class<T> resultType) {
        return rpc.call(target, method, argument, resultType);
    }

    protected <T> T decode(byte[] body, Class<T> type) {
        return messageCodec.decode(body, type);
    }

    protected <T> TickCompletableFuture<byte[]> reply(TickCompletableFuture<T> result) {
        return result.thenApply(messageCodec::encode);
    }

    protected static <T> TickCompletableFuture<T> failed(String error) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        future.fail(new RpcException(error));
        return future;
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(
                RpcMessageTypes.RPC, this::handleRequest,
                RpcMessageTypes.RPC_REPLY, message -> rpc.onReply(message));
    }

    private void handleRequest(Message message) {
        RpcRequest request = messageCodec.decode(message.payload(), RpcRequest.class);
        TickCompletableFuture<byte[]> result;
        try {
            result = serve(request.method(), request.body(), message.source());
        } catch (RuntimeException e) {
            result = failed(e.getMessage());
        }
        result.whenComplete((body, error) -> {
            RpcReply reply = error == null
                    ? new RpcReply(true, null, body)
                    : new RpcReply(false, String.valueOf(error.getMessage()), null);
            try {
                messageBus.sendMessage(createResponseMessage(message, reply, RpcMessageTypes.RPC_REPLY));
            } catch (IOException e) {
                System.err.println(id + ": could not reply to " + message.source() + ": " + e.getMessage());
            }
        });
    }
}
