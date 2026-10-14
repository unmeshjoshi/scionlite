package com.versioncontrol.scionlite.rpc;

import com.tickloom.messaging.MessageType;

/** Every Scion Lite call is one request and one reply; the method name travels inside the request. */
public final class RpcMessageTypes {
    public static final MessageType RPC = MessageType.of("SCION_RPC");
    public static final MessageType RPC_REPLY = MessageType.of("SCION_RPC_REPLY");

    private RpcMessageTypes() {
    }
}
