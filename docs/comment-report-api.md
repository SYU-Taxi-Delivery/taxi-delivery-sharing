# 댓글·신고 API (#53)

모집글의 공개 댓글과 신고. 규칙은 [모집글 비즈니스 규칙](post-business-rules.md) 11장을 따른다. 모든 요청은 로그인이 필요하고(비로그인 401 `AUTHENTICATION_REQUIRED`), 쓰기 요청은 `GET /api/csrf`의 토큰이 필요하다(없으면 403 `CSRF_INVALID`). 응답 형식은 다른 API와 같은 `data`/`error`/`meta`이고 시각은 `+09:00`이다.

| Method | Path | 설명 | 성공 |
|---|---|---|---|
| GET | `/api/posts/{postId}/comments` | 댓글 목록 | 200 |
| POST | `/api/posts/{postId}/comments` | 댓글 작성 | 201 |
| DELETE | `/api/posts/{postId}/comments/{commentId}` | 본인 댓글 삭제 | 204 |
| POST | `/api/posts/{postId}/reports` | 모집 신고 | 201 |

## 댓글

### 목록

```json
{ "data": { "content": [
  { "commentId": "12", "authorName": "홍길동", "content": "저도 석계역이에요!", "createdAt": "2026-11-26T12:00:00.123456+09:00", "isMine": true }
] }, "meta": { "evaluatedAt": "...", "requestId": "..." } }
```

- 오래된 순(`createdAt` ASC, 같으면 `commentId` ASC). 페이지 나누기 없이 전체를 준다.
- 삭제된 댓글은 목록에 없다.
- 참여 여부와 관계없이 로그인 사용자 누구나 본다. OPEN/FULL/CLOSED/CANCELLED/FINISHED 모두 조회할 수 있고, REMOVED(관리자 숨김)와 없는 글은 404 `POST_NOT_FOUND`.

### 작성

요청 `{ "content": "..." }`. 응답은 목록 항목과 같은 댓글 하나이고 `Location` 헤더에 댓글 경로가 있다.

- 내용은 NFC 정규화 후 양끝 공백을 지우고 1~500자(Unicode 코드 포인트, 이모지 1자). 내부 줄바꿈·공백은 그대로 저장한다. HTML은 일반 텍스트로 저장하므로 화면도 텍스트로 표시한다.
- 참여하지 않아도 쓸 수 있다. 모집이 OPEN/FULL/CLOSED이고 기준시각 전이어야 한다(참여 마감 T-10과 다르다).
- 같은 모집의 참여·취소·마감과 같은 모집 행 잠금을 잡아, 먼저 커밋된 모집 취소 뒤에 댓글이 들어가지 않는다.

### 삭제

본문 없이 보낸다. 본인 댓글만 지울 수 있고(작성자도 남의 댓글은 못 지움), 기록은 남기고 목록에서만 빠진다(soft delete). 취소·종료된 모집에서도 본인 댓글은 지울 수 있다. 이미 지운 댓글, 다른 모집의 댓글 ID는 404 `COMMENT_NOT_FOUND`.

응답이 끊긴 뒤 같은 작성 요청을 자동으로 다시 보내지 않는다. 목록을 다시 조회해 확인한다. 삭제를 다시 보내 404 `COMMENT_NOT_FOUND`를 받으면 이미 지워진 것이다.

### 오류

| 상태 | 코드 | 경우 |
|---|---|---|
| 400 | INVALID_POST_ID / INVALID_COMMENT_ID | 경로 ID 형식 |
| 400 | INVALID_COMMENT | 정규화 후 비었거나 500자 초과, `content` 없음 |
| 400 | INVALID_REQUEST | JSON 형식 오류, `content`가 문자열이 아님 |
| 403 | COMMENT_FORBIDDEN | 남의 댓글 삭제 |
| 404 | POST_NOT_FOUND | 없는 모집, REMOVED |
| 404 | COMMENT_NOT_FOUND | 없는·삭제된 댓글, 다른 모집의 댓글 |
| 409 | COMMENT_CLOSED | CANCELLED/FINISHED 또는 기준시각 이후에 작성 |

## 신고

요청 `{ "reason": "FALSE_RECRUITMENT", "detail": "출발지가 달라요" }`. 응답 `{ "reportId", "postId", "reason", "createdAt" }`.

- `reason`: `INAPPROPRIATE`(부적절한 내용), `AD_PROMOTION`(광고·홍보), `ABUSE`(욕설·비방), `FALSE_RECRUITMENT`(허위 모집), `OTHER`(기타). 대소문자까지 정확히 일치해야 한다.
- `detail`: 선택. 정규화 후 비면 저장하지 않고, 최대 500자.
- 남의 모집이면 OPEN/FULL/CLOSED/CANCELLED/FINISHED 모두 신고할 수 있다. 사용자당 모집당 1건이고 동시에 여러 번 보내도 1건만 저장된다.
- 신고가 쌓여도 모집을 자동으로 숨기지 않는다. 처리는 관리자 몫이며 이 API 범위 밖이다.
- 모집 상세 `viewer.reported`가 이미 신고했는지를 알려 준다. 신고 버튼 비활성화에 쓴다.

| 상태 | 코드 | 경우 |
|---|---|---|
| 400 | INVALID_REPORT | `reason` 없음·목록 밖, `detail` 500자 초과 |
| 400 | INVALID_REQUEST | JSON 형식 오류, 문자열이 아닌 값 |
| 403 | SELF_REPORT_NOT_ALLOWED | 본인 모집 |
| 404 | POST_NOT_FOUND | 없는 모집, REMOVED |
| 409 | DUPLICATE_REPORT | 이미 신고함 |

## DB

V3 마이그레이션이 `post_comment`(soft delete `deleted_at`, `(post_id, created_at, id)` 인덱스)와 `post_report`(`(post_id, reporter_id)` UNIQUE, 사유 CHECK)를 추가한다. 기존 테이블은 바꾸지 않는다.

## 검증

`CommentReportContract` 16개를 H2(`CommentReportIntegrationTest`)와 MySQL 8.4 Testcontainers(`CommentReportMySqlIT`, Flyway V1~V3 스키마)에서 같은 내용으로 실행한다.
