package com.versioncontrol.scion.rpc;

/** The JSON-encoded result, or the error message when {@code success} is false. */
public record RpcReply(boolean success, String error, byte[] body) {
}
