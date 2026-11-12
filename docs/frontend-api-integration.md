# 모집글·참여 프론트엔드 연동 계약

검증일: 2026-11-11. 관련 Issue: #20. 프론트엔드 저장소 `SYU-Taxi-Delivery/taxi-delivery-sharing-fe`의 커밋 `f7bd91c3be2277dc025c2667033763ced12bb161`에 있는 실제 API 어댑터를 기준으로 대조했다.

계약 정정일: 2026-11-12. PR #18·#19가 반영된 main `f45d831`의 현재 가입 DTO와 도메인 정책을 확인했다. 아래 가입 계약은 이 최신 백엔드 기준이며, 11월 11일의 고정 프론트 소스 검증 이력과 구분한다.

## 경로와 응답

모집글·참여 API는 `{data, meta: {evaluatedAt, requestId}}` JSON을 반환한다. 실패는 `{error: {code, message, details?}, meta}`이며 성공 data가 없다. ID는 문자열, 인원은 숫자, 가능 여부는 boolean, 시각은 offset을 포함한 ISO 8601이다. `referenceTime`은 분 단위, 응답 시각은 +09:00이며 DB에는 UTC로 저장한다.

| 화면 동작 | 서버 요청 | 정상 응답 |
|---|---|---|
| 목록 | GET /api/posts?page=0&size=20&type=TAXI | 200, data.items/page/size/totalElements/totalPages |
| 상세 | GET /api/posts/{id} | 200, data는 상세 객체 |
| 작성 | POST /api/posts | 201, Location과 data.postId |
| 수정 | PATCH /api/posts/{id} | 200, data는 갱신한 상세 객체 |
| 최초 참여 | POST /api/posts/{id}/participants, body 없음 | 201, action=INITIAL_JOIN |
| 재참여 | 같은 POST, body 없음 | 200, action=REJOIN, 기존 participationId 유지 |
| 참여 취소 | DELETE /api/posts/{id}/participants, body 없음 | 200, action=CANCEL |
| 본인 참여 상태 | GET /api/posts/{id}/participants/me | 200, participation/post/viewer |
| 명단 | GET /api/posts/{id}/participants | 200, author.displayName과 participants[].displayName |
| 참여 내역 | GET /api/users/me/participations?page=0&size=50 | 200, items[].participationStatus 및 post 요약 |

명단은 작성자 또는 현재 JOINED만 조회할 수 있다. 참여 취소 후에는 403이다. 소통 링크는 유효 모집의 작성자/JOINED에게만 제공한다. 작성자는 PARTICIPATION에 저장하지 않으며 currentMemberCount는 1 + JOINED 수다.

## 화면 모델과 DTO 매핑

| 프론트 화면 모델 | 서버 DTO | 어댑터 처리 |
|---|---|---|
| description | content | 작성·수정 전 변환, 상세 응답에서 복원 |
| id / status | postId / postStatus | 목록·상세 응답에서 이름 변환 |
| content / number | items / page | 목록 페이지 객체 변환. 서버 page는 0부터 |
| serverTime | meta.evaluatedAt | 서버 시각으로 T-10/T 경계 판단 |
| authorName | author.displayName | 상세 작성자 이름 |
| relation | viewer.isAuthor / myParticipationStatus | AUTHOR 또는 NONE/JOINED/CANCELLED |

생성에는 type을 포함하고 수정에서는 제외한다. 선택 필드의 null은 값 삭제를 뜻하며 필수값 null은 거절한다. 작성 시 반대 유형 필드를 보내지 않는다. 상세에는 개인정보 ID/메일/학번을 추가 노출하지 않는다.

## 세션·CSRF와 프록시

1. GET /api/csrf의 data.headerName/token과 반환 쿠키를 보관한다. 쿠키 원본을 헤더에 그대로 복사하지 않는다.
2. 실제 로그인은 POST /api/auth/local/login에 identifier/password를 전송한다. **로그인 응답은 공통 envelope가 아닌 `{userId, name, role}`**이다.
3. 로그인 후 JSESSIONID/CSRF가 갱신되므로 CSRF를 다시 조회한 뒤 변경 요청을 보낸다.
4. 모든 API 호출은 세션 쿠키를 포함하고 Accept: application/json을 사용한다. JSON body에만 Content-Type: application/json을 붙인다.
5. CSRF_INVALID이면 토큰을 갱신하고 한 번 재시도할 수 있다. 네트워크 오류/응답 유실은 서버 쓰기 실패를 뜻하지 않으므로 무조건 재전송하지 말고 최신 상태를 조회한다.

현재 프론트 next.config.ts는 /api/* 및 /logout을 BACKEND_ORIGIN으로 전달한다. 브라우저와 Next.js를 같은 origin으로 사용하고, 세션/CSRF 쿠키가 first-party로 유지되도록 다음 값을 설정한다.

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://localhost:3000
BACKEND_ORIGIN=http://localhost:8080
```

첫 값은 브라우저가 호출하는 프론트 origin, 두 번째는 Next 서버가 접근하는 Spring 주소다. 배포 주소와 HTTPS/프록시/쿠키 설정은 배포 환경에서 다시 확인한다. NEXT_PUBLIC_*에는 비밀값을 넣지 않는다.

## 현재 학교 가입 계약

회원가입은 **학번(studentNo) + 이름(name)** 기준이다. 최종 POST /api/auth/school-email/register에는 이메일 인증을 완료한 뒤 다음 필드를 전송한다. 닉네임은 별도 가입 필드가 아니며 name과 임의로 같은 의미로 취급하지 않는다.

| 요청 필드 | 의미 |
|---|---|
| studentNo | 학번 |
| name | 이름 |
| schoolEmail | 인증을 완료한 학교 이메일 |
| password | 가입 비밀번호 |

학교 이메일은 기본 설정에서 **@syuin.ac.kr를 허용하며, 기존 @syu.ac.kr도 허용**한다. 공백 제거·소문자 변환 후 도메인을 정확히 비교한다. 허용 목록은 app.auth.school-email.allowed-domains로 변경할 수 있으므로 배포 설정이 별도로 있으면 확인한다.

근거는 [SchoolRegisterRequest](../src/main/java/com/school/party/domain/user/dto/SchoolRegisterRequest.java)와 [SchoolEmailPolicy](../src/main/java/com/school/party/domain/user/service/SchoolEmailPolicy.java)다. 프론트 가입 화면의 학번·이름 필드 및 signupOperations 연결은 이 계약에 맞춰 확인하며, 백엔드가 @syu.ac.kr만 허용해서 가입을 연결할 수 없다는 이전 설명은 적용하지 않는다. 이 문서 정정만으로 프론트 가입·실제 메일 발송의 E2E가 완료됐다고 표시하지 않는다.

## 오류 처리

| 상황 | 서버 결과 | 프론트 동작 |
|---|---|---|
| 세션 없음/만료 | 401 AUTHENTICATION_REQUIRED | 로그인 상태 재확인 |
| 타인 수정/작성자 직접 참여 | 403 FORBIDDEN_OPERATION / SELF_PARTICIPATION_NOT_ALLOWED | 권한 안내 |
| CSRF 누락/위조 | 403 CSRF_INVALID | 토큰 갱신, 안전한 1회 재시도 |
| 없는 모집/잘못된 ID | 404 POST_NOT_FOUND / 400 INVALID_POST_ID | 없는 모집 안내 |
| 중복/정원/마감/취소 시각 | 409 해당 error.code | 최신 상세/참여 상태 재조회 |
| 잘못된 입력 | 400 INVALID_REQUEST / INVALID_POST_FIELD | 입력 또는 details.field에 대응하는 필드 안내 |
| 응답 형식 거절 | 406 NOT_ACCEPTABLE | application/json을 허용하는지 확인. 이 경우 컨트롤러 실행 전 거절 |

오류 우선순위를 적용하므로 FULL 모집의 기존 참가자가 다시 신청하면 ALREADY_JOINED 대신 POST_FULL이다. 정확히 T-10부터 참여·취소를 막고, T 이후는 FINISHED로 판정한다. 비핵심 필드 수정에는 기존 referenceTime의 생성 조건을 재검증하지 않는다.

## 실제 어댑터 검증 재현

기본 Gradle test는 외부 프론트 소스/Node 없이 두 유형의 실제 HTTP 흐름을 검증한다. 추가로 Node 24.16.0과 위 커밋의 프론트 소스가 있는 경우:

```powershell
$env:FRONTEND_CONTRACT_SOURCE = '프론트_소스의_절대경로'
./gradlew.bat test --tests '*FrontendContractHttpTest' --no-daemon --console=plain
Remove-Item Env:FRONTEND_CONTRACT_SOURCE
```

테스트가 H2 전용 fixture와 실제 Spring HTTP 서버를 만들고 scripts/verify-frontend-contract.mjs를 호출한다. 실제 프론트 TypeScript에서 타입만 제거하고 로컬 import 경로를 임시 디렉터리에 연결한다. 어댑터의 요청/응답 처리 코드는 교체하지 않는다. Node fetch에 쿠키 보관 기능을 붙여 세션과 CSRF를 전달한다. 별도 npm 의존성 설치는 필요 없다. 필요하면 FRONTEND_CONTRACT_NODE에 Node 실행 파일을 지정한다.

실제 어댑터로 18개 시나리오/44회 HTTP 요청을 검증했다. Node 및 소스가 없으면 해당 선택 테스트 한 건은 skip된다. 이 검증은 브라우저 UI·Next 프록시·CORS·배포 E2E 또는 실제 SMTP/학교 인증 운영 검증을 대체하지 않는다.

## 다른 담당자와 확인할 연동 항목

- 학교 가입: 위의 학번·이름 DTO와 @syuin.ac.kr 허용 정책이 현재 백엔드 계약이다. 프론트 signupOperations의 실제 연결 상태와 화면 필드 매핑은 별도 확인 항목이며 도메인 허용 여부와 구분한다. 11월 11일 고정 프론트 소스에서 가입 어댑터가 notConnected였다는 검증 이력으로 현재 백엔드의 미지원 여부를 판단하지 않는다.
- 현재 사용자 전용 API가 없어 프론트는 저장한 최소 사용자 정보와 참여 내역 인증 조회로 세션을 확인한다.
- 작성자 마감·모집 전체 취소·관리자 삭제, 신고·댓글, 내 작성글 이력은 연결 HTTP API가 없다. 서비스 훅 테스트나 화면 미리보기 통과를 API 연동 완료로 표시하지 않는다.
