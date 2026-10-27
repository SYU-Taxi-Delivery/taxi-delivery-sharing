package com.school.party.domain.community.api;

import org.springframework.http.HttpStatus;
import static org.springframework.http.HttpStatus.*;

public enum ErrorCode {
    INVALID_POST_ID(BAD_REQUEST, "모집 ID의 형식 또는 범위가 올바르지 않습니다."),
    INVALID_REQUEST(BAD_REQUEST, "요청 Body가 올바르지 않습니다."),
    INVALID_POST_FIELD(BAD_REQUEST, "모집 입력값이 올바르지 않습니다."),
    POST_REFERENCE_TIME_TOO_EARLY(BAD_REQUEST, "기준시각은 현재시각보다 10분 초과로 뒤여야 합니다."),
    POST_TYPE_IMMUTABLE(BAD_REQUEST, "생성 후 모집 유형은 변경할 수 없습니다."),
    POST_TIME_CONFLICT(CONFLICT, "같은 기준시각의 유효한 모집을 이미 작성했습니다."),
    POST_CORE_FIELDS_LOCKED(CONFLICT, "현재 모집의 핵심 조건은 변경할 수 없습니다."),
    INVALID_QUERY_PARAMETER(BAD_REQUEST, "조회 조건이 올바르지 않습니다."),
    AUTHENTICATION_REQUIRED(UNAUTHORIZED, "로그인이 필요합니다."),
    SELF_PARTICIPATION_NOT_ALLOWED(FORBIDDEN, "작성자는 자신의 모집에 추가 참여할 수 없습니다."),
    AUTHOR_CANNOT_CANCEL_PARTICIPATION(FORBIDDEN, "작성자는 모집 취소 기능을 사용해야 합니다."),
    PARTICIPANT_LIST_FORBIDDEN(FORBIDDEN, "작성자 또는 현재 참가자만 명단을 볼 수 있습니다."),
    FORBIDDEN_OPERATION(FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
    CSRF_INVALID(FORBIDDEN, "CSRF 토큰이 없거나 올바르지 않습니다."),
    POST_NOT_FOUND(NOT_FOUND, "모집을 찾을 수 없습니다."),
    PARTICIPATION_NOT_FOUND(NOT_FOUND, "본인의 참여 기록을 찾을 수 없습니다."),
    API_NOT_FOUND(NOT_FOUND, "API 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    POST_FULL(CONFLICT, "모집 정원이 모두 찼습니다."),
    POST_CLOSED(CONFLICT, "작성자가 모집을 마감했습니다."),
    POST_CANCELLED(CONFLICT, "취소된 모집입니다."),
    POST_FINISHED(CONFLICT, "기준시각이 지난 모집입니다."),
    ALREADY_JOINED(CONFLICT, "이미 참여한 모집입니다."),
    JOIN_TIME_CLOSED(CONFLICT, "기준시각 10분 전부터 참여할 수 없습니다."),
    CANCEL_TIME_CLOSED(CONFLICT, "기준시각 10분 전부터 참여를 취소할 수 없습니다."),
    ALREADY_CANCELLED(CONFLICT, "이미 취소된 참여입니다."),
    PARTICIPATION_TEMPORARILY_UNAVAILABLE(SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 본인 참여 상태를 확인해 주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 처리 중 오류가 발생했습니다.");
    private final HttpStatus status;
    private final String message;
    ErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    public HttpStatus status() { return status; }
    public String message() { return message; }
}
