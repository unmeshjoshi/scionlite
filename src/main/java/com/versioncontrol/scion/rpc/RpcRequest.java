package com.versioncontrol.scion.rpc;

/** A call: the method name and its JSON-encoded argument. */
public record RpcRequest(String method, byte[] body) {
}
