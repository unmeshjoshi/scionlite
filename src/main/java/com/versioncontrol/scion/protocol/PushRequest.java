package com.versioncontrol.scion.protocol;

import java.util.List;

/**
 * Objects (canonical bytes) the server may lack, and the branch updates to make with them. All updates are applied
 * together or not at all. {@code force} allows an update that is not a fast-forward; the expected old value is still
 * checked.
 */
public record PushRequest(List<byte[]> objects, List<RefUpdate> updates, boolean force) {
}
