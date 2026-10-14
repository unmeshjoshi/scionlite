package com.versioncontrol.scionlite.rpc;

/** A call: the method name and its JSON-encoded argument. */
public record RpcRequest(String method, byte[] body) {
}
