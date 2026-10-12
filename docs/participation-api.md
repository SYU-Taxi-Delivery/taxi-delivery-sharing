# Participation API 구현 명세

기능정의서 v1.1의 참여·취소·정원·명단 정책을 기준으로 구현했습니다. 모든 경로는 인증이 필요합니다. ID는 JSON 문자열이며 시간은 ISO 8601 `+09:00`입니다. 모든 응답은 `Cache-Control: no-store`, `X-Request-Id`를 제공합니다.

## 경로

| Method | Path | 기능 | 성공 |
|---|---|---|---|
| POST | `/api/posts/{postId}/participants` | 본인 참여/재참여 | 최초 201, 재참여 200 |
| DELETE | `/api/posts/{postId}/participants` | 본인 참여 취소 | 200 |
| GET | `/api/posts/{postId}/participants/me` | 본인 상태·버튼 가능 여부 | 200 |
| GET | `/api/posts/{postId}/participants` | 작성자·현재 참가자 명단 | 200 |
| GET | `/api/users/me/participations` | 본인 참여 내역 | 200 |
| GET | `/api/csrf` | 변경 요청용 토큰 | 200 |

POST/DELETE는 Body가 없습니다. 최초 생성의 Location은 `/api/posts/{postId}/participants/me`입니다. DELETE는 기록을 지우지 않고 CANCELLED로 변경합니다. 중복 신청/중복 취소는 409이며, 재참여는 같은 participationId를 유지합니다.

`postId`는 1 이상 signed BIGINT 범위의 십진 문자열이어야 합니다. 음수, 0, 선행 0, 문자열, 범위 초과는 400입니다.

## 참여 응답

```json
{
  "data": {
    "action": "INITIAL_JOIN",
    "participation": {
      "participationId": "1", "postId": "1", "status": "JOINED",
      "createdAt": "2026-10-06T17:00:00+09:00",
      "updatedAt": "2026-10-06T17:00:00+09:00", "cancelledAt": null
    },
    "post": {
      "postId": "1", "type": "TAXI", "postStatus": "OPEN",
      "referenceTime": "2026-10-06T18:00:00+09:00",
      "participationDeadline": "2026-10-06T17:50:00+09:00",
      "capacity": 4, "currentMemberCount": 2, "remainingSeats": 2,
      "joinable": true
    },
    "viewer": {
      "isAuthor": false, "myParticipationStatus": "JOINED",
      "canJoin": false, "canCancel": true
    }
  },
  "meta": {
    "evaluatedAt": "2026-10-06T17:00:00+09:00",
    "requestId": "server-generated-uuid"
  }
}
```

action은 `INITIAL_JOIN`, `REJOIN`, `CANCEL` 중 하나입니다. 취소 시 status=CANCELLED, cancelledAt/updatedAt=판정시각입니다. 재참여 시 createdAt과 ID는 유지하고 cancelledAt=null, updatedAt=재참여시각으로 바꿉니다. 요청 처리가 DB에 커밋된 뒤 응답합니다.

본인 조회 응답은 위 data에서 action을 제외한 구조입니다. 기록이 없으면 `participation:null`, `myParticipationStatus:"NONE"`이며 200입니다. 작성자도 NONE이며 isAuthor=true입니다. 모집이 CANCELLED/FINISHED여도 조회 가능하고, REMOVED이면 404입니다.

`joinable`은 모집 자체의 가능 여부입니다. `canJoin`은 작성자 여부와 기존 참여를 반영한 본인 버튼 상태입니다. `canCancel`은 본인이 JOINED이고 모집이 OPEN/FULL/CLOSED이며 마감 전일 때만 true입니다. 프론트엔드는 이 값을 사용하되 변경 요청의 서버 재검사를 최종 결과로 받아야 합니다.

## 참가자 명단

```json
{
  "data": {
    "postId": "1", "postStatus": "OPEN", "capacity": 4,
    "currentMemberCount": 2,
    "author": {"displayName": "author"},
    "participants": [{"participationId": "1", "displayName": "student1"}]
  },
  "meta": {"evaluatedAt": "2026-10-06T17:00:00+09:00", "requestId": "uuid"}
}
```

작성자는 author에 별도 반환하고 participants에는 JOINED만 포함합니다. 정렬은 최초 참여시각과 ID 오름차순입니다. 작성자 또는 현재 JOINED 회원만 접근할 수 있습니다. 취소하면 명단 권한을 잃습니다. FINISHED에서도 최종 JOINED 회원은 조회할 수 있습니다. 일반 관리자는 이 경로에서 명단 권한을 우회할 수 없습니다.

명단은 사용자 DB ID, 학번, 이메일, 전화번호, 오픈채팅 URL을 반환하지 않습니다. 소통 링크 접근은 모집 상세 모듈에서 같은 회원 정책을 별도로 적용해야 합니다.

## 내역 조회

`GET /api/users/me/participations?page=0&size=20&status=JOINED`

page는 0 이상, size는 1~50입니다. status는 생략하거나 대문자 JOINED/CANCELLED입니다. 잘못된 값 또는 offset 범위 초과는 400입니다. updatedAt DESC, id DESC로 정렬합니다.

```json
{
  "data": {
    "items": [{
      "participationId": "1", "participationStatus": "JOINED",
      "createdAt": "2026-10-06T17:00:00+09:00",
      "updatedAt": "2026-10-06T17:00:00+09:00", "cancelledAt": null,
      "post": {
        "postId": "1", "postStatus": "OPEN", "isRemoved": false,
        "type": "TAXI", "title": "택시 동승 모집",
        "referenceTime": "2026-10-06T18:00:00+09:00"
      }
    }],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  },
  "meta": {"evaluatedAt": "2026-10-06T17:00:00+09:00", "requestId": "uuid"}
}
```

참여 기록의 현재 상태를 조회하는 목록입니다. 취소/재참여의 모든 이벤트를 저장하는 감사 로그가 아닙니다. 작성한 모집도 이 목록에 추가되지 않습니다.

관리자가 삭제한 모집은 본인 내역에서만 아래 최소 정보로 표시합니다. 제목·유형·시간 등은 필드 자체를 생략합니다.

```json
{"postId":"1","postStatus":"REMOVED","isRemoved":true,"message":"관리자에 의해 삭제된 모집입니다."}
```

## 정책과 동시성

- TAXI 정원 2~4, DELIVERY 2~10. 작성자 포함 `1 + JOINED 수`를 계산하며 count 캐시 컬럼은 사용하지 않습니다.
- 참여는 OPEN, 여석 있음, 작성자 아님, 이미 JOINED 아님, 현재시각 < 기준시각-10분일 때 가능합니다.
- 취소는 본인 JOINED, OPEN/FULL/CLOSED, 현재시각 < 기준시각-10분일 때 가능합니다. 정확히 T-10부터 거절합니다.
- 마지막 자리가 차면 OPEN → FULL, FULL에서 취소하면 OPEN, CLOSED에서 취소하면 CLOSED를 유지합니다.
- 기준시각에 도달하면 저장 상태가 OPEN이어도 API는 FINISHED로 판정합니다. CANCELLED/REMOVED 판정이 우선합니다.
- 모집 취소는 모든 JOINED를 CANCELLED로 바꿉니다. 관리자 삭제는 진행 전 모집만 일괄 취소하며 종료 모집의 참여 기록은 유지합니다.
- 변경은 READ_COMMITTED 트랜잭션에서 POST를 `SELECT FOR UPDATE`로 잠그고, **잠금 획득 후 시각을 읽어** 참여 조건을 검사합니다. 동일 모집의 참여/취소/모집 취소/삭제를 직렬화합니다.
- 변경 실패는 POST와 PARTICIPATION을 모두 롤백합니다. DB UNIQUE(post_id,user_id)와 CHECK로 중복/취소시각 불일치도 방지합니다.
- 조회는 REPEATABLE_READ 트랜잭션에서 명단과 모집 상태를 같은 스냅샷으로 읽습니다.

## 오류

```json
{
  "error": {
    "code": "JOIN_TIME_CLOSED", "message": "기준시각 10분 전부터 참여할 수 없습니다.",
    "details": {
      "postId": "1", "referenceTime": "2026-10-06T18:00:00+09:00",
      "participationDeadline": "2026-10-06T17:50:00+09:00"
    }
  },
  "meta": {"evaluatedAt": "2026-10-06T17:50:00+09:00", "requestId": "uuid"}
}
```

details가 없으면 생략합니다. 오류 응답에는 data가 없습니다.

| HTTP | code |
|---|---|
| 400 | INVALID_POST_ID, INVALID_REQUEST, INVALID_QUERY_PARAMETER |
| 401 | AUTHENTICATION_REQUIRED |
| 403 | SELF_PARTICIPATION_NOT_ALLOWED, AUTHOR_CANNOT_CANCEL_PARTICIPATION, PARTICIPANT_LIST_FORBIDDEN, FORBIDDEN_OPERATION, CSRF_INVALID |
| 404 | POST_NOT_FOUND, PARTICIPATION_NOT_FOUND, API_NOT_FOUND |
| 405 | METHOD_NOT_ALLOWED (Allow 헤더 포함) |
| 406 | NOT_ACCEPTABLE |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 409 | POST_FULL, POST_CLOSED, POST_CANCELLED, POST_FINISHED, ALREADY_JOINED, ALREADY_CANCELLED, JOIN_TIME_CLOSED, CANCEL_TIME_CLOSED |
| 503 | PARTICIPATION_TEMPORARILY_UNAVAILABLE |
| 500 | INTERNAL_SERVER_ERROR |

변경 요청에서는 Spring Security의 CSRF 검사도 수행됩니다. 인증/CSRF가 모두 없는 변경 요청은 CSRF 필터에서 403으로 먼저 거절될 수 있습니다. 시간 오류는 details에 기준시각과 참여 마감을 포함합니다.

join의 오류 우선순위는 사용자 확인 → POST 존재/삭제 → 취소/종료 → FULL/CLOSED → T-10 → 작성자 → 중복 참여 → 실제 인원 검사입니다. 따라서 FULL 모집의 기존 참가자 재신청은 POST_FULL입니다. cancel은 사용자/POST → 취소/종료 → T-10 → 작성자 → 본인 기록 → 이미 취소 순서입니다.

503 또는 응답 유실 시 먼저 `/participants/me`로 최종 상태를 확인합니다. 단순 재전송은 ALREADY_JOINED/ALREADY_CANCELLED를 반환할 수 있습니다. 별도 Idempotency-Key 저장 기능은 구현하지 않았습니다.
