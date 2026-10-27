# 모집글·참여 API 주간 재검증

검증일: 2026-10-28 (Asia/Seoul). 기준: 최신 origin/main `b79ca2a66a0a4475a349e11695896bee680878ef`. 작업 브랜치: `codex/revalidate-post-participation-20261028`.

최신 main의 모집글·참여 코드는 기존 제출 `c8bc537`과 동일하며 파일 차이는 Dockerfile 추가였다. 닫힌 PR을 재사용하지 않고 새 작업으로 검증했다. 다른 인증 브랜치를 가져오거나 인증 인계 작업을 재개하지 않았다.

## 요약

기존 기본 테스트 167건은 통과했지만, 추가 검증에서 지원하지 않는 Accept 헤더가 오류 JSON을 누락시키고, 변경 요청은 406을 반환하면서 DB에 반영되는 문제를 발견했다. 실제 내장 Tomcat에서도 재현한 뒤 공통 오류 처리기와 모집글·참여 컨트롤러의 응답 형식 선언을 수정했다. HTTP 계약·참여 데이터 정합성·취소 경계·동시 요청에 대한 회귀 사례 25건을 추가했다.

일반 사용자에게 모집글 물리 DELETE API를 제공하지 않는 것은 기능정의서 POST-007의 정책이다. DELETE 요청 차단과 데이터 보존을 검증한다. 관리자 soft delete는 서비스 수준에서 검증하며, 연결된 관리자 삭제 HTTP API는 현재 미구현이므로 완료로 표시하지 않는다. 마감/모집 취소 역시 서비스 훅이 있고 별도 HTTP 경로는 없다.

| 실행 환경 | 실제 실행·성공 | 실패 / 오류 | 별도 범위 |
|---|---:|---:|---|
| 기본 H2 + 실제 HTTP 보안 테스트 | 192 | 0 / 0 | 모집글 83 + 참여 85 + 세션/보안/기존 사용자·컨텍스트 24 |
| 전용 MariaDB 11.4.10 / InnoDB | 168 | 0 / 0 | 모집글 83 + 참여 85, skip 0 |
| 실행 JAR 빌드 | bootJar 성공 | 없음 | 서버 배포는 수행하지 않음 |

기본 실행의 조건부 외부 DB/마이그레이션 skip 134건은 성공 192건에 포함하지 않았다. 외부 DB API 계약은 위의 별도 MariaDB 실행에서 수행했다. 마이그레이션 선택 테스트는 이번에 실행하지 않았다. 두 DB의 공통 계약은 동일 사례를 반복 검증한 것이므로 서로 다른 기능 360개를 의미하지 않는다.

## 1. 모집글·참여 API 테스트 결과

| 검증 | 기대 결과 / 확인 내용 |
|---|---|
| POST /api/posts | 201, Location, 문자열 ID, 숫자 정원, boolean 파생 값, DB 저장 및 작성자 포함 인원 |
| GET /api/posts 및 /api/posts/{id} | 200, 목록 필터·페이지·기준시각 정렬, 상세 필드 및 관계별 소통 링크 공개 |
| PATCH /api/posts/{id} | 작성자 수정, 비핵심/핵심 조건 구분, 생략/null 처리, 잘못된 수정 시 저장값 유지 |
| DELETE /api/posts/{id} | 작성자/다른 참가자/관리자 모두 일반 경로는 405 + Allow. 모집·참여 데이터 유지. 관리자 전용 삭제 API 검증을 대신하지 않음 |
| 요청 데이터 형식 | 알 수 없는 필드·ID 위조·중복 JSON 키·잘못된 숫자/enum·깨진 JSON을 거절 |
| 응답·오류 형식 | data/error 구분, HTTP 상태 및 error.code, Content-Type, Cache-Control, 응답 헤더와 meta.requestId 일치 |
| 잘못된 미디어 형식 | Content-Type 오류 415, 지원하지 않는 Accept는 컨트롤러 실행 전 406. 생성·수정·참여·취소에서 DB 변경 없음. 오류는 application/json으로 반환 |
| 참여 신청 | 최초 201 및 Location, 정상 JOINED, 인원 증가, 작성자는 PARTICIPATION에 생성하지 않음 |
| 취소·재참여 | 취소 200/CANCELLED, 재참여 200/REJOIN. 기존 ID·createdAt 보존, updatedAt 갱신, cancelledAt 초기화, DB 한 행 유지 |
| 참여 내역 | 본인 내역만 제공, 현재 JOINED/CANCELLED 필터가 각 변경 후 DB 상태와 일치 |
| 존재하지 않는 모집 | 참여·취소·본인 상태·명단 모두 404 POST_NOT_FOUND, 기존 데이터 유지 |
| 인증·CSRF·소유권 | 로그인 없음 401, CSRF 없음/권한 없음 403. 위조된 사용자 ID로 접근 불가 |

전체 가입/메일/외부 프론트 연동은 이번 주간 모집글·참여 API 검증의 대상이 아니다. MockMvc 검증은 MVC·Security·서비스·DB를 연결하며 인증 주체는 테스트 fixture를 사용한다. Accept 오류의 실제 HTTP 검증은 정상 비밀번호 로그인 후 실제 쿠키로 요청한다.

## 2. 비즈니스 규칙 검증 결과

| 규칙 | 검증 내용 |
|---|---|
| 작성자 포함 인원 | 1 + JOINED 수. 작성자 참여 행 없음, 취소 인원 제외 |
| 정원 | TAXI 2~4, DELIVERY 2~10. 마지막 자리에서 FULL, 추가 참여 거절 |
| 중복 참여 | 순차/동시 중복 요청 모두 논리 참여 한 건. DB UNIQUE(post_id,user_id)도 직접 위반 시 거절 |
| 재참여 | CANCELLED → JOINED는 기존 레코드 재활성화. 순차/동시 재참여 모두 ID와 createdAt 유지 |
| 상태 전이 | 마지막 자리 OPEN → FULL, FULL 취소 → OPEN, FULL → CLOSED 수동 마감, CLOSED 취소 후 CLOSED 유지. 정의되지 않은 복귀 거절 |
| 취소 시간 경계 | 기준시각까지 601초 남으면 허용, 600초/599초는 409 CANCEL_TIME_CLOSED. 실패 시 FULL·JOINED 및 시각 컬럼 유지 |
| 기준시각 | 비핵심 수정 시 기존 시각 생성 조건 재검증 없음. 시각 변경 시 +10분 초과와 중복 재검증 |
| 모집 중복 | 같은 작성자·기준시각에서 TAXI/DELIVERY 통합 검사. 종료/삭제 및 실질 FINISHED 제외 |
| 삭제·이력 | 관리자 삭제는 soft delete. 진행 중 참여는 일괄 취소, 이미 종료된 모집의 JOINED 이력은 보존. 일반 조회 숨김 및 본인 내역 최소 tombstone 표시 |

## 3. 트랜잭션·동시성 검증 결과

쓰기 경로는 POST 행 잠금, 잠금 후 최신 상태/서버 시각 재검증, READ_COMMITTED 쓰기 트랜잭션 및 DB UNIQUE/FK/CHECK를 함께 사용한다. 작성과 시간 변경은 공통 작성자 행 잠금으로 직렬화한다.

| 경합·실패 상황 | 기대 결과 / 확인 내용 |
|---|---|
| 마지막 한 자리, 8명 동시 참여 | 1명만 성공, 작성자 포함 정원 유지, POST=FULL |
| 같은 사용자 동시 최초 참여 | 하나만 성공, 참여 행 한 건 |
| 같은 사용자 동시 취소 | 하나만 성공, 다른 요청은 ALREADY_CANCELLED. 인원이 두 번 감소하지 않음 |
| 같은 사용자 동시 재참여 | 하나만 성공, 다른 요청은 ALREADY_JOINED. 기존 참여 ID·생성시각 보존 |
| 취소가 커밋되기를 기다린 새 참여 | FULL로 미리 읽었어도 잠금 후 최신 OPEN/인원을 확인해 빈자리를 채움. 최종 FULL·JOINED 1명 |
| 참여 ↔ 모집 취소 / 관리자 삭제 | 양쪽 처리 순서를 강제로 재현, 최종 POST/PARTICIPATION 일관성 유지 |
| 잠금 대기 중 T-10/T 도달 | 잠금 획득 후 시각으로 참여·취소·상태 변경 제한 재검증 |
| DB 제약 실패 | 양쪽 테이블 변경을 flush한 뒤 CHECK 오류를 유도. POST/PARTICIPATION 및 시각/삭제 메타데이터 전체 롤백 |
| 잘못된 외부 트랜잭션 | 읽기 전용·격리 수준 불일치에서 쓰기 차단 |

MariaDB 검증에서는 INNODB_LOCK_WAITS/INNODB_LOCKS를 조회해 실제 POST/USERS PRIMARY 행의 대기를 관찰한다. 서비스 밖의 중복·FK·상태 CHECK 위반도 DB에서 거절하는지 확인한다. H2 통과만으로 실제 InnoDB 동시성 완료를 판정하지 않는다.

## 발견·수정한 오류

첫 재현 요청은 로그인 후 `GET /api/posts`에 `Accept: text/plain`을 보낸 경우다. 수정 전 406이지만 Content-Type과 error.code/meta가 없는 빈 응답이 실제 HTTP에서도 확인됐다. 같은 Accept를 포함한 잘못된 ID·깨진 JSON 요청에서도 오류 응답을 쓰지 못하거나 예외가 전파됐다.

오류 JSON을 수정한 뒤 DB 상태까지 확인하자, 정상적인 생성·수정·참여·취소 요청에 지원하지 않는 Accept를 붙이면 **서비스 트랜잭션이 커밋된 후 응답 변환에서 406이 발생**하는 문제가 추가로 드러났다. 실제 로그인 세션의 HTTP 생성 요청에서도 오류 응답 뒤 모집글 한 건이 저장되는 것을 재현했다. 이 상태에서 클라이언트가 실패로 판단해 재시도하면 중복/상태 충돌을 겪을 수 있다.

- ProtocolExceptionHandler: 404/405/406/415 오류에 JSON Content-Type을 명시하고 Allow 등 기존 프로토콜 헤더를 유지한다.
- ApiExceptionHandler: 비즈니스/입력/일시적/내부 오류에도 JSON Content-Type을 명시한다.
- PostController / ParticipationController: `produces = application/json`을 선언해 지원하지 않는 Accept를 컨트롤러 실행 전에 거절한다. 인증·CSRF 검증은 기존처럼 먼저 적용되며, 이를 통과한 요청에서 입력/비즈니스 검증보다 미디어 형식 판정이 앞선다.
- 생성·수정·참여·취소별로 406 후 저장 건수·제목·모집 상태·참여 상태가 보존되는지 확인한다. 실제 HTTP에서도 406 JSON과 생성 건수 0을 검증한다.
- 정상 API의 응답 형식, 시간 정책, 참여 규칙, 잠금/DB 스키마는 유지한다.

근거: PostContract.REV_HTTP_protocolErrorsUseTheDocumentedJsonEnvelope, PostContract.REV_HTTP_unsupportedAcceptMustNotCommitPostMutation, ParticipationContract.REV_HTTP_unsupportedAcceptMustNotCommitParticipationMutation 및 SessionSecurityHttpTest.REV_realHttpUnsupportedAcceptCannotWriteAndReturnsJsonError. 오류 반환과 미디어 협상은 [Spring MVC 공식 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)를 함께 대조했다.

## 재현 명령과 검증 범위

```powershell
./gradlew.bat test bootJar --no-daemon --console=plain
```

별도 비어 있는 검증 DB에 PARTICIPATION_TEST_DB_URL/USERNAME/PASSWORD를 설정한 뒤:

```powershell
./gradlew.bat test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --no-daemon --console=plain
```

외부 DB 계약은 fixture 데이터를 삭제하므로 반드시 전용 DB로 실행한다. 이번 실제 DB 제품은 MariaDB 11.4.10/InnoDB이며 MySQL 8.4, 팀 운영 DB, 배포 서버 및 실제 프론트에서 실행한 결과로 간주하지 않는다. 현재 환경에는 Docker가 없어 MySQL Testcontainers는 실행하지 않는다.

새 브랜치에서 커밋·푸시 후 새 PR로 검토를 요청하는 작업이다. 닫힌 PR의 갱신, main 병합, 자동 머지 또는 배포는 수행하지 않는다.
