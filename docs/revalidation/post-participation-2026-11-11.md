# 모집글·참여 API 전체 검증 및 프론트 계약 결과

검증일: 2026-11-11 (Asia/Seoul). 관련 Issue: #20. 브랜치: fix/20-post-participation-validation.

2026-11-12 정정: 최신 main `f45d831`에 반영된 가입 계약은 학번(studentNo)·이름(name)과 학교 이메일·비밀번호이며 기본 도메인에 @syuin.ac.kr와 @syu.ac.kr를 모두 허용한다. 아래 테스트 수치와 11월 11일 당시의 관찰은 기존 기준 버전의 검증 이력이다. 현재 계약은 [프론트 연동 명세](../frontend-api-integration.md)의 가입 계약을 따른다.

같은 날 최신 main으로 리베이스한 PR #21의 전체 기본/실제 HTTP/선택 어댑터 테스트는 220건, 실패·오류 0건으로 통과했고 test bootJar도 성공했다. Node 선택 테스트의 실제 어댑터 18개 시나리오/44회 HTTP 요청을 포함한다. 프론트 소스가 없는 일반/CI 실행은 219건을 실행한다. 외부 DB/마이그레이션 skip 134건은 제외했고 이번 정정에서 MariaDB/MySQL을 다시 실행하지 않았다.

최신 origin/main `b79ca2a66a0a4475a349e11695896bee680878ef`에서 새 작업 브랜치를 만들었다. 프론트는 taxi-delivery-sharing-fe의 `f7bd91c3be2277dc025c2667033763ced12bb161`을 고정해 실제 API 어댑터를 확인했다. 모집글·참여와 연결되는 계약만 다루며 인증 인계·가입 도메인 변경은 재개하지 않았다.

## 결과

| 검증 | 실행 결과 | 범위 |
|---|---|---|
| main의 기존 기본 테스트 | 167건 통과 | 변경 전 기준선 |
| main에 미디어 협상 회귀 검사 추가 | 5건 모두 실패 | 지원하지 않는 Accept의 오류 본문/실제 HTTP 응답 문제 재현 |
| 수정 후 전체 H2/실제 HTTP/프론트 어댑터 | 195건 통과, 실패·오류 0 | 모집글 83 + 참여 85 + 기존 보안·사용자·컨텍스트 24 + 신규 HTTP/어댑터 3 |
| 전용 MariaDB 11.4.10/InnoDB | 168건 통과, 실패·오류·skip 0 | 모집글 83 + 참여 85, 실제 행 잠금 대기·동시성·롤백 포함 |
| 실제 프론트 어댑터 | 18개 시나리오, HTTP 요청 44회 성공 | 위 H2 전체 중 선택 테스트 1건에 포함. 별도 가산하지 않음 |
| test bootJar | 성공 | 실행 JAR 태스크 확인. 소스가 같은 JAR은 UP-TO-DATE로 재사용 |

기본 실행의 외부 DB/마이그레이션 조건부 skip 134건은 통과 건수에 포함하지 않았다. 프론트 소스 환경변수가 없는 일반/CI 실행에서는 Node 어댑터 테스트 1건도 skip되며 194건이 실행된다. MariaDB는 별도 전용 스키마에 접속해 전체 168건을 실제 실행했다. 공통 사례를 두 DB에서 실행한 결과를 서로 다른 기능 수로 합산하지 않는다.

## 수정과 테스트 보완

- 두 컨트롤러에 produces=application/json을 선언해 지원하지 않는 Accept를 실행 전에 거절한다. 406 오류 뒤 생성·수정·참여·취소가 저장되지 않는지 검증한다.
- 공통 오류 처리기에 JSON Content-Type을 명시해 빈 응답과 오류 envelope 누락을 막는다. Allow 등 프로토콜 헤더는 유지한다.
- 기존 PR #16에 있던 위 운영 수정 4개 파일 및 회귀 사례 25건을 이번 최신 main 기반 브랜치에도 포함했다. 기존 PR과 동일한 부분은 중복 검토 대상임을 PR에 명시한다. 닫힌 PR을 갱신하거나 다른 브랜치를 병합하지 않았다.
- 새 FrontendContractHttpTest에서 TAXI/DELIVERY 각각 실제 세션 로그인 → 작성 → 목록/상세 → 권한 거절 → 참여/중복 → 수정 제한 → 취소/재참여 → FULL → T-10 취소 거절 → 내역까지 검증한다.
- SecurityHttpClient는 프론트와 동일한 Accept: application/json을 기본 사용한다.
- scripts/verify-frontend-contract.mjs가 고정된 프론트 소스의 실제 어댑터·응답 검증 함수를 실행한다. 프론트 운영 파일이나 API 구현을 모의 함수로 교체하지 않는다.
- API 명세의 HTTP 오류와 필드 매핑을 보완하고 [프론트 연동 계약](../frontend-api-integration.md)을 추가했다. DB 스키마·잠금 규약·시간 정책·기존 인증 코드는 변경하지 않았다.

## 업무별 확인

| 업무 | 확인한 내용 |
|---|---|
| 모집글 목록·상세·작성·수정 | 필터·페이지·기본 정렬, 유형별 필수/선택 값, 생성 201/Location, 수정 권한/필드 제한, 응답과 저장값 일치 |
| 참여·취소·재참여 | 최초 201/재참여 200, 기존 ID/createdAt 보존, 상태/인원/취소 시각 갱신, 명단 권한 및 링크 비공개 |
| 중복·정원 | 작성자 1 + JOINED 수, 작성자 참여 금지, 순차/동시 중복과 정원 초과 차단, DB UNIQUE/FK/CHECK |
| 작성자/수정 권한 | 세션 사용자 기준 작성자 연결, 위조 body ID 거절, 타인 수정 403, 참가 이후 핵심 수정 409/비핵심 수정 허용 |
| 마감·예외 | OPEN/FULL/CLOSED 및 종료/취소/삭제 상태별 정책, T-10 601/600/599초 경계, 잘못된 JSON/필드/ID/페이지·미인증/CSRF·없는 모집 |
| 동시성 | 마지막 자리 8명 경쟁, 중복 참여/취소/재참여, 모집 취소/관리자 삭제 경합, 잠금 후 재검증 및 DB 실패 전체 롤백 |
| 프론트 계약 | description↔content, id/status↔postId/postStatus, 목록 페이지, 표시명, 서버 시각, 세션/CSRF, null 선택값, 응답 오류 변환 |

동시성은 Java 작업을 겹치게 하고 처리 순서를 제어하는 정합성 검증이다. 대규모 HTTP 부하, TPS/지연/최대 동시접속 성능을 측정한 테스트는 아니다. 수동 마감·모집 취소·관리자 삭제는 서비스 훅의 동작과 경합을 검증했으며 연결 HTTP API는 없다.

## 실제 프론트 어댑터 18개 시나리오

TAXI 작성/문자열 ID, DELIVERY 작성/null 선택값, 목록 변환/기본 정렬, 상세 변환/작성자 명단, 수정 화면 로드, type을 제외한 PATCH, 잘못된 JSON 타입, 비참여자 정보 비공개, 타인 수정 거절, 참여/명단, 중복 참여 거절, 취소/정보 재조회, 기존 행 재참여, 참여 이력 변환, FULL 정원 유지, 위조 CSRF 거절, 없는 모집 오류 변환, 참가 후 핵심 수정 거절.

실제 Spring HTTP와 JSESSIONID/CSRF를 사용했으며 Node fetch에 쿠키 저장 기능을 붙였다. TypeScript의 타입과 로컬 import 경로만 임시 변환했다. 브라우저 엔진·화면·Next 프록시·배포 네트워크를 실행한 E2E 검증으로 표시하지 않는다.

## 남은 항목 및 검증 한계

- 실제 브라우저 UI, Next 프록시, 배포 URL/HTTPS/쿠키, 실제 팀 DB/SMTP/학교 인증 운영은 별도 검증 대상이다.
- 11월 11일 검증 당시의 main b79ca2a 및 고정 프론트 소스에서는 가입 필드/도메인 차이와 미연결 어댑터를 관찰했다. 이후 최신 main은 @syuin.ac.kr를 허용하며 학번·이름 기준 가입 DTO를 제공한다. 현재 프론트의 DTO 매핑·연결 및 실제 가입 E2E는 이 최신 계약을 기준으로 별도 확인한다.
- 수동 마감/모집 전체 취소/관리자 삭제, 신고/댓글, 내 작성글 이력의 HTTP API는 미구현이다. 기존 동작을 임의 경로로 확장하지 않았다.
- Docker가 없어 MySQL 8.4 Testcontainers는 미실행이다. MariaDB 결과를 MySQL 검증 결과로 대신하지 않는다. 기존 DB 마이그레이션 선택 테스트도 이번 범위가 아니다.
- 이슈 본문에 In Progress를 기록했다. GitHub Projects 연결 필드 조회는 권한/연결 확인이 필요해 실제 보드 상태 변경 완료로 표시하지 않는다.
- main 직접 push, 병합, 자동 머지, 배포, 기존 열린 PR 닫기 및 작업 브랜치 삭제는 수행하지 않는다. 검토/병합은 팀 담당자의 영역이며 이슈를 Done으로 변경하지 않는다.

## 재현

```powershell
# Node 24.16.0과 고정 커밋의 프론트 소스가 있을 때 선택 어댑터 테스트도 활성화
$env:FRONTEND_CONTRACT_SOURCE = '프론트_소스의_절대경로'
./gradlew.bat test bootJar --no-daemon --console=plain
Remove-Item Env:FRONTEND_CONTRACT_SOURCE

# 별도 빈 검증 DB에 PARTICIPATION_TEST_DB_URL/USERNAME/PASSWORD 설정 후
./gradlew.bat test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --no-daemon --console=plain
```

이번 전용 스키마는 post_participation_issue20_20261111이다. 테스트는 fixture 데이터를 삭제하므로 공유 DB에 실행하지 않는다. 원시 JUnit/Node 기록, 고정 소스와 결과 JSON은 Git에서 제외된 로컬 work/issue20-*에 보관하고 PR에는 이 결과 요약을 공유한다.
