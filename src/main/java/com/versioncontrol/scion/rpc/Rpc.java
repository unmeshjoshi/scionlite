package com.versioncontrol.scion.rpc;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.RequestCallback;
import com.tickloom.messaging.RequestWaitingList;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * The calling side of the RPC envelope, shared by server processes ({@link RpcNode}) and the scn client. A call
 * registers in the process's request waiting list, so it fails if no reply arrives within the request timeout.
 */
public final class Rpc {
    private final ProcessId self;
    private final PeerType peerType;
    private final MessageBus bus;
    private final MessageCodec codec;
    private final RequestWaitingList<String, Object> waitingList;
    private final Supplier<String> correlationIds;

    public Rpc(ProcessId self, PeerType peerType, MessageBus bus, MessageCodec codec,
               RequestWaitingList<String, Object> waitingList, Supplier<String> correlationIds) {
        this.self = self;
        this.peerType = peerType;
        this.bus = bus;
        this.codec = codec;
        this.waitingList = waitingList;
        this.correlationIds = correlationIds;
    }

    public <T> TickCompletableFuture<T> call(ProcessId target, String method, Object argument, Class<T> resultType) {
        return callRaw(target, method, codec.encode(argument)).thenApply(body -> codec.decode(body, resultType));
    }

    /** Sends an already-encoded argument and completes with the encoded result. Used to forward calls upstream. */
    public TickCompletableFuture<byte[]> callRaw(ProcessId target, String method, byte[] body) {
        TickCompletableFuture<byte[]> result = new TickCompletableFuture<>();
        String correlationId = correlationIds.get();
        waitingList.add(correlationId, new RequestCallback<>() {
            @Override
            public void onResponse(Object response, ProcessId fromNode) {
                RpcReply reply = (RpcReply) response;
                if (reply.success()) {
                    result.complete(reply.body());
                } else {
                    result.fail(new RpcException(reply.error()));
                }
            }

            @Override
            public void onError(Exception error) {
                result.fail(error);
            }
        });
        Message message = Message.of(self, target, peerType, RpcMessageTypes.RPC,
                codec.encode(new RpcRequest(method, body)), correlationId);
        try {
            bus.sendMessage(message);
        } catch (IOException e) {
            waitingList.handleError(correlationId, e);
        }
        return result;
    }

    /** Hands a reply to the call waiting for it. */
    public void onReply(Message message) {
        waitingList.handleResponse(message.correlationId(), codec.decode(message.payload(), RpcReply.class),
                message.source());
    }
}
