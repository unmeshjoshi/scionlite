package com.versioncontrol.scion.protocol;

import java.util.List;

/** Commit ids the client already has; the server leaves out everything reachable from them. */
public record FetchRequest(List<String> haves) {
}
