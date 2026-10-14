package com.versioncontrol.scion.protocol;

import com.tickloom.messaging.MessageType;

public final class ScionMessageTypes {
    // Client to server
    public static final MessageType PUSH_REQUEST = MessageType.of("SCION_PUSH_REQUEST");
    public static final MessageType PUSH_RESPONSE = MessageType.of("SCION_PUSH_RESPONSE");
    public static final MessageType FETCH_REQUEST = MessageType.of("SCION_FETCH_REQUEST");
    public static final MessageType FETCH_RESPONSE = MessageType.of("SCION_FETCH_RESPONSE");
    public static final MessageType LIST_REFS_REQUEST = MessageType.of("SCION_LIST_REFS_REQUEST");
    public static final MessageType LIST_REFS_RESPONSE = MessageType.of("SCION_LIST_REFS_RESPONSE");

    // Primary to followers
    public static final MessageType APPEND_ENTRIES = MessageType.of("SCION_APPEND_ENTRIES");
    public static final MessageType APPEND_RESPONSE = MessageType.of("SCION_APPEND_RESPONSE");

    private ScionMessageTypes() {
    }
}
