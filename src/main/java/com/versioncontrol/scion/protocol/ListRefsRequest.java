package com.versioncontrol.scion.protocol;

/** Lists branches whose name starts with the prefix ("" for all). */
public record ListRefsRequest(String prefix) {
}
