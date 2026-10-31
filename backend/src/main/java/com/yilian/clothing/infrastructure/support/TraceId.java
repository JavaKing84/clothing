package com.yilian.clothing.infrastructure.support;

import org.slf4j.MDC;

/** traceId 读取门面。放在 support 包，避免每个类都 import org.slf4j.MDC。 */
public final class TraceId {

    private TraceId() {
    }

    public static String current() {
        String id = MDC.get("traceId");
        return id == null ? "" : id;
    }
}
