package com.veridoc.ai.common.trace;

/** MDC key names and correlation-id helpers. */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String REQUEST_ID = "requestId";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private TraceContext() {
    }

    public static String currentTraceId() {
        String id = org.slf4j.MDC.get(TRACE_ID);
        return id == null ? "unknown" : id;
    }

    public static String currentUserId() {
        String id = org.slf4j.MDC.get(USER_ID);
        return id == null ? "anonymous" : id;
    }
}