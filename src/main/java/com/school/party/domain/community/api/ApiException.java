package com.school.party.domain.community.api;
import java.time.Instant;
import java.util.Map;
public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Instant evaluatedAt;
    private final Map<String, Object> details;
    public ApiException(ErrorCode code, Instant evaluatedAt) { this(code, evaluatedAt, Map.of()); }
    public ApiException(ErrorCode code, Instant evaluatedAt, Map<String, Object> details) {
        super(code.message()); this.code = code; this.evaluatedAt = evaluatedAt; this.details = details;
    }
    public ErrorCode code() { return code; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public Map<String, Object> details() { return details; }
}
