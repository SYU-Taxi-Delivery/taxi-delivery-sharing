# 재검증 4: 인증 및 CSRF

검증일: 2026-10-15. 기준: 7f6d944 및 재검증 1·2·3의 누적 로컬 변경. 이번 항목에서는 운영 인증 로직을 변경하지 않고 실제 HTTP 통합 테스트 12건을 추가했다. 아직 커밋/푸시하지 않은 변경이다.

## 확인 결과

| 검증 항목 | 결과 및 근거 |
|---|---|
| Basic 인증 활성 범위 | SecurityConfiguration에서 demo가 활성화된 경우에만 Basic 필터를 등록. demo의 정상 계정은 200, 잘못된 비밀번호는 401 |
| demo 이외 프로필 | test+prod에서 Basic 필터가 없고, 정상 비밀번호와 UserDetailsService를 준비해도 Basic 요청은 401. 같은 사용자의 로컬 로그인은 성공 |
| 로그인 전 세션이 존재하는 경우 | 실제 Tomcat이 발급한 JSESSIONID가 로그인 후 변경됨. 이전 ID로 API 재접근 시 401 |
| 로그인 전 세션이 없는 경우 | 로그인 성공 시 새 JSESSIONID 발급. 같은 세션으로 재로그인할 때도 ID를 다시 변경 |
| 로그인 시 CSRF 갱신 | 로그인 후 GET /api/csrf로 받는 XSRF-TOKEN의 원본 값이 변경됨. 재로그인 후에도 변경 |
| CSRF 오류 차단 | 로그인 토큰 누락/위조와 갱신된 쿠키에 이전 헤더 토큰을 사용한 모집 생성 요청은 403 CSRF_INVALID. 새 토큰으로 생성하면 201 |
| 로그아웃 | 토큰 누락은 403이며 로그인 상태 유지. 정상 POST /logout은 302, CSRF 쿠키 삭제, 세션 무효화 |
| 로그아웃 후 재접근 | 로그아웃 이전 JSESSIONID를 직접 다시 보내도 조회와 유효한 CSRF 쌍을 포함한 생성 요청 모두 401. 모집 생성 없음 |
| 비활성 계정 로그인 | SUSPENDED / WITHDRAWN 각각 401, 인증 세션 발급 없음 |
| 기존 세션의 계정 비활성화 | 두 상태 각각 모집글 작성·수정·상세·목록 및 참여 신청·취소·명단·내 상태·내역 모두 401. 모집 수정과 참여 저장 없음 |
| demo Basic의 추가 검사 | Basic 인증을 사용해도 변경 요청의 CSRF 검사는 유지. 정지된 demo 계정의 API 접근도 401 |

비활성 계정 확인 범위는 이번에 구현한 모집글·참여 API다. 매 요청 CurrentUser가 DB의 현재 ACTIVE 상태를 확인한다. 계정 비활성화는 해당 API 접근을 차단하며, 세션 자체의 무효화는 로그아웃에서 검증했다.

## 검증 방식

기존 LocalSessionIntegrationTest 7건은 유지했다. 해당 테스트는 MockMvc와 테스트용 csrf()를 사용하므로 쿠키 원본 갱신에 대한 증거를 추가할 필요가 있었다.

추가한 SessionSecurityHttpTest 9건과 DemoBasicSecurityHttpTest 3건은 RANDOM_PORT로 실제 내장 Tomcat을 실행한다. Java HTTP 클라이언트가 Set-Cookie를 보관하고 Cookie/CSRF 헤더를 전송한다. 새 테스트는 인증 주입이나 csrf()를 사용하지 않고 실제 LOCAL_CREDENTIAL 비밀번호 검사와 서버 세션 저장을 통과한다. demo의 Basic 인증은 실제 시드 계정으로 검사한다.

세션 고정 공격 방지 검증을 위해 테스트 전용 컨트롤러로 로그인 전 익명 세션을 하나 만든다. 이 컨트롤러와 Basic 검증용 UserDetailsService는 테스트 설정에만 등록되며 운영 소스/실행 JAR에는 포함되지 않는다. 각 HTTP 테스트 컨텍스트는 기존 계약 테스트와 별도의 H2 DB를 사용한다.

### CSRF 비교 기준

GET /api/csrf 응답 토큰은 기본 XOR 마스킹으로 매 요청 달라질 수 있다. 로그인 전 같은 쿠키로 두 번 조회하면 원본 쿠키는 같고 응답 마스킹 값만 달라지는 것을 먼저 확인했다. 이후 로그인 전후 원본 XSRF-TOKEN 값을 비교해 실제 교체를 확인했다.

클라이언트는 GET /api/csrf가 반환한 headerName과 token을 함께 사용하고 쿠키를 유지한다. 로그인 성공 및 로그아웃 후에는 다시 조회한다. 이전 토큰 차단 사례는 브라우저가 갱신된 쿠키를 유지한 상태에서 이전 헤더 토큰을 보내는 경우다. 현재 저장 방식은 CookieCsrfTokenRepository이며, CSRF 토큰의 서버 측 폐기 목록을 검증한 결과로 해석하지 않는다.

Spring Security 7.1.1의 [CSRF 공식 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)와 [CsrfAuthenticationStrategy 소스](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CsrfAuthenticationStrategy.java)를 대조했다. 기본 마스킹 및 로그인 시 토큰 제거 후 재조회 흐름이 현재 구현·테스트와 일치한다.

## 추가한 테스트 12건

| 테스트 | 사례 수 |
|---|---:|
| non-demo 정상 Basic 자격증명 거절 및 세션 로그인 성공 | 1 |
| 익명 세션 → 로그인 ID/CSRF 교체, 이전 ID/토큰 거절 | 1 |
| 최초 세션 생성 및 재로그인 시 ID/CSRF 재교체 | 1 |
| 응답 마스킹과 원본 쿠키 구분, 로그인 CSRF 누락/위조 거절 | 1 |
| 로그아웃 CSRF 필수, 쿠키 삭제 및 이전 세션 재사용 차단 | 1 |
| 비활성 계정 로그인 차단 | 2 |
| 기존 세션에서 계정 비활성화 후 API 9개 경로 차단 | 2 |
| demo 정상/오류 Basic 인증 | 1 |
| demo Basic 변경 요청 CSRF 필수 | 1 |
| demo 비활성 계정 접근 차단 | 1 |

## 실행

```powershell
./gradlew.bat test --tests '*SessionSecurityHttpTest' --tests '*DemoBasicSecurityHttpTest' --no-daemon --console=plain
./gradlew.bat test --no-daemon --console=plain
```

추가 HTTP 테스트 12건과 누적 전체 167건(모집글 72 + 참여 72 + 기존 세션 7 + 신규 HTTP 보안 12 + 기존 사용자/컨텍스트 4)이 통과했다. 실패·오류는 0건이며 외부 DB 조건부 테스트는 건너뛰었다. 전체 회귀 결과는 [검증 기록](../verification.md)에도 기록했다. H2를 사용한 로컬 비밀번호·세션 인증 검증이며 실제 SMTP 발송, 별도 학교 SSO 공급자 및 배포 환경의 프록시/HTTPS 쿠키 설정은 이번 검증 범위에 포함하지 않았다. 이번 항목에서 MariaDB/MySQL 및 GitHub CI를 다시 실행하지 않았다.

## 변경 범위

- 운영 인증·CSRF 코드: 변경 없음. 현재 구현에서 요청한 동작을 확인했다.
- 테스트: SecurityHttpClient, SessionSecurityHttpTest, DemoBasicSecurityHttpTest 추가.
- 문서: 실행 안내와 검증 기록에 로그인/로그아웃 후 토큰 재조회 및 이번 검증 근거 반영.
- 이전 재검증의 운영 코드 변경과 테스트는 그대로 유지. 커밋·푸시·PR 갱신·머지 없음.
