package com.versioncontrol.scion.client;

import com.versioncontrol.scion.protocol.FetchRequest;
import com.versioncontrol.scion.protocol.FetchResponse;
import com.versioncontrol.scion.protocol.ListRefsRequest;
import com.versioncontrol.scion.protocol.ListRefsResponse;
import com.versioncontrol.scion.protocol.Primary;
import com.versioncontrol.scion.protocol.PushRequest;
import com.versioncontrol.scion.protocol.PushResponse;
import com.versioncontrol.scion.protocol.ScionMessageTypes;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;

import java.util.List;
import java.util.Map;

/**
 * The network side of a Scion client. Pushes go to the primary. Fetches go to the primary by default, which always
 * has the latest committed branches; {@link #fetchFrom} reads from any replica, possibly behind. A request that gets
 * no answer within the request timeout fails.
 */
public class ScionClient extends ClusterClient {
    private final ProcessId primary;

    public ScionClient(List<ProcessId> replicaEndpoints, ProcessParams processParams) {
        super(replicaEndpoints, processParams);
        this.primary = Primary.of(replicaEndpoints);
    }

    public ProcessId primary() {
        return primary;
    }

    public TickCompletableFuture<PushResponse> push(PushRequest request) {
        return sendRequest(request, primary, ScionMessageTypes.PUSH_REQUEST);
    }

    public TickCompletableFuture<FetchResponse> fetch(FetchRequest request) {
        return fetchFrom(primary, request);
    }

    public TickCompletableFuture<FetchResponse> fetchFrom(ProcessId replica, FetchRequest request) {
        return sendRequest(request, replica, ScionMessageTypes.FETCH_REQUEST);
    }

    public TickCompletableFuture<ListRefsResponse> listRefs() {
        return listRefsFrom(primary);
    }

    public TickCompletableFuture<ListRefsResponse> listRefsFrom(ProcessId replica) {
        return sendRequest(new ListRefsRequest(""), replica, ScionMessageTypes.LIST_REFS_REQUEST);
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(
                ScionMessageTypes.PUSH_RESPONSE, m -> respond(m, PushResponse.class),
                ScionMessageTypes.FETCH_RESPONSE, m -> respond(m, FetchResponse.class),
                ScionMessageTypes.LIST_REFS_RESPONSE, m -> respond(m, ListRefsResponse.class));
    }

    private void respond(Message message, Class<?> type) {
        handleResponse(message.correlationId(), deserialize(message.payload(), type), message.source());
    }
}
