package com.versioncontrol.scion.protocol;

import java.util.Map;

public record ListRefsResponse(Map<String, String> refs) {
}
