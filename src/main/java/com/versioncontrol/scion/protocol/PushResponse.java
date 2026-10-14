package com.versioncontrol.scion.protocol;

import java.util.Map;

/** The outcome, a human-readable detail, and the server's current value of every branch the push named. */
public record PushResponse(PushStatus status, String detail, Map<String, String> refs) {
    public boolean succeeded() {
        return status == PushStatus.OK;
    }
}
