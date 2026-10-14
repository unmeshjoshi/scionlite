package com.versioncontrol.scionlite.rpc;

/** The remote side answered the call with an error. */
public class RpcException extends RuntimeException {
    public RpcException(String message) {
        super(message);
    }
}
