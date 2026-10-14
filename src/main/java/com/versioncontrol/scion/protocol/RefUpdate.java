package com.versioncontrol.scion.protocol;

/**
 * Move a branch from {@code expectedOld} (null: the branch must not exist yet) to {@code newId}. Ids are hex
 * strings so the record serialises as plain JSON.
 */
public record RefUpdate(String branch, String expectedOld, String newId) {
}
