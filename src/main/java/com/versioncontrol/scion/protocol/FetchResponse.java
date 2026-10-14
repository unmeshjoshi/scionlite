package com.versioncontrol.scion.protocol;

import java.util.List;
import java.util.Map;

/** The committed branches (name to commit id) and the objects the client needs to have their whole history. */
public record FetchResponse(Map<String, String> refs, List<byte[]> objects) {
}
