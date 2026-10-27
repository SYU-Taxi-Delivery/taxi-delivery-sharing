# 모집글 API — 기능정의서 v1.1 / ERD v4

작성일: 2026-10-12. 기능정의서 POST-001~004, DISC-001~004 및 PM의 v4 피드백을 기준으로 구현했다.

## 공통

모집글 API는 로그인 필수다. 팀 학교 이메일 회원가입으로 생성한 USERS 사용자가 `POST /api/auth/local/login`으로 로그인하면 서버가 `CampusPrincipal.userId()`를 담은 세션을 만든다. 작성자 ID는 body/header/query로 받지 않는다. 매 요청마다 DB에서 ACTIVE 계정인지 확인한다. 로그인·쿠키·CSRF 흐름은 [실행 및 인증 안내](running.md)를 참고한다. HTTP Basic 테스트 계정은 demo 프로필에서만 제공한다.

변경 요청은 `GET /api/csrf`에서 받은 토큰과 쿠키를 함께 전달한다. JSON 요청은 `Content-Type: application/json`이다. 알 수 없는 필드, 중복 JSON 키, 숫자 문자열이나 소수 정원 등 잘못된 타입을 거절한다.

성공 형식은 `{ "data": ..., "meta": { "evaluatedAt": "...+09:00", "requestId": "..." } }`, 오류 형식은 `{ "error": { "code": "...", "message": "...", "details": ... }, "meta": ... }`다. ID는 JSON 문자열이고 시간은 offset이 있는 ISO 8601이다. API 응답은 Asia/Seoul, DB는 UTC다. `referenceTime`은 초와 소수초가 0인 분 단위 값만 허용한다.

오류 응답은 `Content-Type: application/json`으로 반환한다. 응답 형식은 application/json이며, 인증·CSRF 검증을 통과한 요청의 Accept가 이를 허용하지 않으면 컨트롤러 실행 전에 406 NOT_ACCEPTABLE JSON을 반환한다. 이때 생성·수정은 DB에 반영되지 않는다. 입력/비즈니스 오류도 공통 error/meta 형식을 유지한다. 일반 `DELETE /api/posts/{postId}`는 제공하지 않아 405와 Allow를 반환한다. 관리자 soft delete는 현재 서비스 훅이며 관리자 삭제 HTTP API는 별도 구현 대상이다. [2026-10-28 재검증](revalidation/post-participation-2026-10-28.md)을 참고한다.

## 1. 작성 — POST /api/posts

정상 응답: **201 Created**, `Location: /api/posts/{postId}`, data는 상세 응답이다.

```json
{
  "type": "TAXI",
  "title": "석계역에서 학교까지 택시 동승",
  "content": "역 앞에서 만나요.",
  "capacity": 4,
  "referenceTime": "2026-10-12T18:00:00+09:00",
  "departure": "석계역",
  "destination": "삼육대학교 정문",
  "communicationUrl": "https://example.com/room"
}
```

예시 시각은 실제 호출 시 현재보다 10분 초과 뒤인 값으로 변경한다.

| 필드 | 필수 | 서버 검증 |
|---|---|---|
| type | 공통 | TAXI / DELIVERY |
| title | 공통 | 공백 제거 후 1~60자 |
| content | 공통 | 공백 제거 후 1~1,000자 |
| capacity | 공통 | 정수, TAXI 2~4 / DELIVERY 2~10, 작성자 포함 |
| referenceTime | 공통 | offset 포함, 분 단위, 서버 현재시각 + 10분보다 뒤 |
| communicationUrl | 선택 | HTTPS URL, 최대 500자, null 가능 |
| departure / destination | TAXI | 각각 1~100자 |
| restaurantName / pickupLocation | DELIVERY | 각각 1~100자 |
| orderGuide | DELIVERY 선택 | 최대 500자, null 가능 |

미사용 유형의 필드는 생략하거나 null이어야 한다. 선택 텍스트를 지울 때는 빈 문자열 대신 null을 사용한다. 생성 상태는 OPEN, 현재 인원은 1이며 작성자 PARTICIPATION을 생성하지 않는다.

BR-03: 동일 작성자·동일 분 기준시각에 실질 OPEN/FULL/CLOSED 모집이 있으면 유형과 관계없이 409 POST_TIME_CONFLICT. CANCELLED/FINISHED/REMOVED는 제외한다. 저장 상태가 OPEN이어도 시간이 도달한 모집은 FINISHED로 판단한다.

## 2. 수정 — PATCH /api/posts/{postId}

정상 응답: **200 OK**, data는 변경 후 상세다. 작성자 본인만 가능하다.

```json
{ "title": "만나는 위치 안내 수정", "communicationUrl": null }
```

생략한 필드는 유지한다. 선택 필드의 명시적 null은 삭제한다. 필수 필드 null, 빈 객체, 비객체 body는 거절한다. type은 생성 후 불변이며 수정 요청에 넣을 수 없다. status/authorId/currentMemberCount/joinable 등 서버 관리 필드도 수정할 수 없다. 실제 값이 같은 핵심 필드를 다시 보내는 것은 핵심 조건 변경으로 취급하지 않는다.

| 실질 상태 / 참가 조건 | 허용 범위 |
|---|---|
| OPEN/FULL, JOINED 없음 | type 제외 허용 필드 |
| OPEN/FULL, JOINED 존재 | 제목·본문·외부 링크·배달 주문 안내 |
| CLOSED | 비핵심 필드만, JOINED 없어도 핵심 조건 잠금 |
| CANCELLED/FINISHED/REMOVED | 수정 불가 |

핵심 필드: capacity/referenceTime 및 TAXI departure/destination, DELIVERY restaurantName/pickupLocation. 취소 이력만 있으면 핵심 조건을 영구 잠그지 않는다. 변경된 referenceTime은 현재+10분 조건과 BR-03 중복을 다시 검증한다. 단순 제목 수정에는 기존 referenceTime의 생성 조건을 다시 요구하지 않는다.

이 API에서 모집 마감/취소/재개 상태 변경을 제공하지 않는다. 기존 `PostLifecycleService`의 내부 연동 기능을 유지하며 CLOSED/CANCELLED/FINISHED에서 OPEN 임의 복귀를 허용하지 않는다.

## 3. 상세 — GET /api/posts/{postId}

정상 응답: **200 OK**. 본문, 유형별 장소/주문 안내, 작성자 표시명, 시각, 상태, 정원과 다음 계산값을 반환한다.

```text
currentMemberCount = 1 + JOINED PARTICIPATION 수
remainingSeats = max(0, capacity - currentMemberCount)
joinable = 실질 상태 OPEN && 서버 현재시각 < referenceTime - 10분 && 인원 < 정원
```

DB에 위 계산값을 저장하지 않는다. `joinable`은 모집 자체의 조건이며, 본인의 추가 조건은 `viewer.canJoin`으로 표현한다.

| viewer 필드 | 의미 |
|---|---|
| isAuthor | 작성자 본인 여부 |
| myParticipationStatus | NONE / JOINED / CANCELLED |
| canJoin | joinable이며 작성자도 현재 JOINED도 아님 |
| canCancel | 본인 JOINED + OPEN/FULL/CLOSED + T-10 이전 |
| canEdit | 작성자 + OPEN/FULL/CLOSED |
| canEditCore | canEdit + CLOSED 아님 + JOINED 없음 |
| canViewCommunicationUrl | OPEN/FULL/CLOSED의 작성자 또는 현재 JOINED |

소통 링크는 허용된 사용자에게만 반환하고, 비참가자·참여 취소자·종료/취소 모집에서는 URL 필드 자체를 생략한다. 상세에 참가자 명단·이메일·학번·전화번호를 넣지 않는다. 명단은 기존 `/participants` API에서 권한을 검사한다. REMOVED는 404로 비노출한다.

FINISHED는 **조회/검증 시 계산**하는 구현을 선택했다. 별도 배치가 없어도 `현재시각 >= referenceTime`이면 FINISHED로 응답하며 수정·참여·취소를 막는다. CANCELLED/REMOVED는 시간만으로 FINISHED로 덮어쓰지 않는다.

## 4. 목록 — GET /api/posts

```http
GET /api/posts?type=TAXI&page=0&size=20
GET /api/posts?status=CLOSED
GET /api/posts?status=FINISHED
```

| 파라미터 | 기본값 | 범위 |
|---|---|---|
| page | 0 | 0 이상, page×size는 int 범위 |
| size | 20 | 1~50 |
| type | ALL | ALL / TAXI / DELIVERY |
| status | 기본 활성 영역 | OPEN / FULL / CLOSED / CANCELLED / FINISHED / ALL |

status 생략 시 기능정의서 DISC-001의 기본 활성 목록으로 **실질 OPEN/FULL만** 조회한다. CLOSED/취소/종료는 명시적 필터로 조회한다. ALL도 REMOVED는 항상 제외한다. BR-03의 유효 모집 OPEN/FULL/CLOSED와 기본 탐색 목록 범위를 구분한다.

정렬은 referenceTime ASC, id ASC로 고정한다. data는 `items/page/size/totalElements/totalPages`. items는 제목, 유형, 장소, 실질 상태, 기준/참여마감시각, 정원/인원/여석/joinable이며 본문·소통 링크·개인정보는 포함하지 않는다. 페이지의 참여 인원은 묶어서 집계하며 모집별 개별 count 쿼리를 반복하지 않는다. 이번 범위에 전문 검색·유사 추천 API는 추가하지 않았다.

## 주요 오류

| HTTP | code | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | JSON 형식/타입/알 수 없는 작성 필드/중복 키 |
| 400 | INVALID_POST_FIELD | 필수값, 길이, 유형별 필드, URL, 분 단위 위반 |
| 400 | POST_REFERENCE_TIME_TOO_EARLY | 생성/시간 변경 시 10분 초과 조건 위반 |
| 400 | POST_TYPE_IMMUTABLE | 수정 요청에 type 포함 |
| 400 | INVALID_POST_ID / INVALID_QUERY_PARAMETER | ID/목록 조건 오류 |
| 401 | AUTHENTICATION_REQUIRED | 인증 누락 또는 신뢰된 principal/DB 사용자 없음 |
| 403 | FORBIDDEN_OPERATION / CSRF_INVALID | 타인 수정/CSRF 오류 |
| 404 | POST_NOT_FOUND | 없는 모집 또는 REMOVED |
| 409 | POST_TIME_CONFLICT | 같은 작성자·기준시각 유효 모집 |
| 409 | POST_CORE_FIELDS_LOCKED | 잠긴 핵심 조건 변경 |
| 409 | POST_CANCELLED / POST_FINISHED | 수정 불가능 상태 |

## 동시성 / DB 적용

작성과 수정은 READ COMMITTED 쓰기 트랜잭션이다. 작성자 USERS 행을 먼저 잠그므로 첫 모집의 동시 생성도 직렬화된다. 수정은 작성자 → POST 순서로 잠근다. 참여/취소는 기존 POST 잠금을 유지하고 작성자 행을 잠그지 않는다. 필요한 잠금을 획득한 다음 서버 시각·상태·참가 조건·중복을 검사하고 저장한다. 실패 시 전체 롤백한다.

POST 잠금 후에는 JPA 1차 캐시의 이전 값까지 refresh하여 최신 DB 상태로 검증한다. 상위 트랜잭션을 사용하는 경우에도 실제 READ_COMMITTED 쓰기 조건을 확인하며, 다른 격리 수준/읽기 전용의 상위 트랜잭션으로 쓰기를 진행하지 않는다. [2026-10-15 재검증 및 통합 계약](revalidation/concurrency-transaction-2026-10-15.md)을 참고한다.

상세/목록은 REPEATABLE READ 읽기 트랜잭션의 일관된 snapshot에서 상태와 인원을 계산한다. 한 응답의 FINISHED/joinable 판정은 같은 evaluatedAt을 사용한다.

새 저장소의 기존 `User` 엔티티와 `UserRepository`를 사용한다. **V1__team_user_schema.sql**은 새 DB용 팀 사용자 스키마, **V2__post_participation.sql**은 모집글/참여 스키마다. 기존 사용자 DB는 스키마를 검토하고 V1으로 명시적으로 baseline한 뒤 V2를 적용한다. 자동 baseline이나 자동 테이블 변경은 사용하지 않는다. 이전 저장소의 V1/V2 이력을 이 저장소로 그대로 가져오지 않는다. 자세한 절차는 [실행 안내](running.md)를 참고한다.

DB만으로 시간에 따른 유효 상태 UNIQUE를 표현하지 않으므로 동일 작성자에 대한 생성/시간 변경 경로는 모두 위 작성자 잠금을 따라야 한다. 외부 코드가 POST를 직접 저장하면 이 보장을 우회한다. PARTICIPATION의 UNIQUE(post_id,user_id)는 그대로 유지한다.

생성과 기준시각 변경은 유형과 관계없이 같은 중복 검사 쿼리를 사용한다. 저장 상태 OPEN/FULL/CLOSED여도 기준시각이 현재 이하이면 실질 FINISHED로 제외한다. [유형별·경계별·작성자 락 경합 재검증](revalidation/duplicate-recruitment-2026-10-15.md)을 참고한다.

호출 예제: `requests/post.http`. 실행 검증: `scripts/smoke-post-demo.ps1`. 테스트: `PostContract`, H2/PostIntegrationTest, 전용 InnoDB/PostExternalDatabaseTest, MySQL 8.4/PostMySqlIT.
