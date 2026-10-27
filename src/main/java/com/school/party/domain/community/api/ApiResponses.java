package com.school.party.domain.community.api;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import static com.school.party.domain.community.api.ApiModels.*;
public final class ApiResponses {
    private ApiResponses() {}
    public static <T> Envelope<T> success(Result<T> result, HttpServletRequest request) {
        return new Envelope<>(result.data(), meta(result.evaluatedAt(), request));
    }
    public static Failure failure(ErrorCode code, Instant at, Map<String,Object> details, HttpServletRequest request) {
        return new Failure(new ApiModels.Error(code.name(), code.message(), details), meta(at, request));
    }
    private static Meta meta(Instant at, HttpServletRequest request) { return new Meta(seoul(at), RequestMetadataFilter.requestId(request)); }
}
