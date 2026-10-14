package com.versioncontrol.scion.repo;

import java.util.List;
import java.util.Map;

/**
 * One durable change to a local repository: new objects, refs set (name to commit id), refs deleted, and the
 * current branch if it changed (else null).
 */
public record LocalRecord(List<byte[]> objects, Map<String, String> setRefs, List<String> deletedRefs, String head) {
}
