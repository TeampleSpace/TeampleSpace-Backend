# TeampleSpace Backend

Spring Boot 4, Java 21, MySQL 8.4 기반 API 서버

## 준비물

- Java 21
- MySQL 8.4. Docker가 있으면 `compose.yaml`로 실행하고, 없으면 직접 설치한 MySQL을 사용한다

Gradle은 저장소의 wrapper를 사용하므로 따로 설치하지 않는다.

## 로컬 실행

1. DB 실행

   ```bash
   docker compose up -d --wait
   ```

   개발용 `teamplespace`와 테스트용 `teamplespace_test` DB, `teamplespace` 계정이 만들어진다.
   3306 포트를 이미 쓰고 있으면 `DB_PORT=3307 docker compose up -d --wait`처럼 포트를 바꾸고 `DB_URL`, `TEST_DB_URL`의 포트도 함께 바꾼다.

   Docker 없이 직접 설치한 MySQL을 쓸 때는 관리자 계정으로 아래를 한 번 실행한다.

   ```sql
   CREATE DATABASE teamplespace;
   CREATE DATABASE teamplespace_test;
   CREATE USER 'teamplespace'@'localhost' IDENTIFIED BY 'teamplespace';
   GRANT ALL PRIVILEGES ON teamplespace.* TO 'teamplespace'@'localhost';
   GRANT ALL PRIVILEGES ON teamplespace_test.* TO 'teamplespace'@'localhost';
   ```

2. 서버 실행

   ```bash
   ./gradlew bootRun
   ```

   기동할 때 Flyway가 마이그레이션을 적용한다.

## 테스트

```bash
./gradlew test
```

테스트는 H2 같은 대체 DB가 아니라 실제 MySQL의 `teamplespace_test` DB에 연결한다. DB가 떠 있지 않으면 실패한다.

## 환경변수

기본값은 `compose.yaml`의 로컬 DB에 맞춰져 있어 로컬에서는 설정 없이 실행된다. 값을 바꾸려면 아래 환경변수 표를 참고해 `.env`를 직접 만든 뒤 셸에 올린다. 실제 비밀값은 저장소에 커밋하지 않는다.

```bash
set -a && source .env && set +a
```

| 이름 | 용도 | 기본값 |
| --- | --- | --- |
| `DB_URL` | 서버의 JDBC 주소 | `jdbc:mysql://localhost:3306/teamplespace` 와 UTC 옵션 |
| `DB_USERNAME` | DB 계정 | `teamplespace` |
| `DB_PASSWORD` | DB 비밀번호 | `teamplespace` |
| `TEST_DB_URL` | 테스트의 JDBC 주소 | `jdbc:mysql://localhost:3306/teamplespace_test` 와 UTC 옵션 |
| `DB_PORT` | compose가 노출할 로컬 포트 | `3306` |

기본 계정 값은 로컬 전용이다. 배포 환경에서는 반드시 환경변수로 실제 값을 넣고 저장소에 커밋하지 않는다.
마이그레이션 계정을 서버 계정과 분리하려면 Spring Boot 기본 설정인 `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`를 사용한다.

## DB 규칙

- 연결 시간대는 UTC, 쓰기 격리 수준은 READ COMMITTED
- 스키마는 Flyway만 변경한다. Hibernate는 `validate`로 검사만 한다
- 마이그레이션 파일은 `src/main/resources/db/migration`에 `V번호__설명.sql` 형식으로 추가한다
- 이미 적용된 파일은 고치지 않고 새 파일을 추가한다
- 테이블은 기능을 개발하는 단계에서 필요한 만큼만 추가한다

## 현재 구현 범위

- 요소 수정 저장 경로는 `ElementPatchService`에 있다. 문서 변경, 변경 이력, 이벤트, 멱등 결과를 한 트랜잭션으로 저장한다
- 연속 저장은 `ChangeGroups`가 하나의 변경으로 묶는다. 2초 동안 입력이 없거나 30초가 지나면 닫으며 값은 `teamplespace.edit-group.idle-timeout`, `teamplespace.edit-group.max-duration`으로 바꿀 수 있다
- `PresentationSnapshots`는 문서와 이벤트 커서를 같은 시점에서 읽고 `ProjectEvents`는 커서 다음 이벤트를 순서대로 돌려준다
- 모든 HTTP 요청에 요청 식별자를 붙여 로그, `X-Request-Id` 응답 헤더, 오류 본문에 같은 값으로 남긴다. 오류는 계약의 공통 오류 형식으로 응답한다
- 운영 인증이 아직 없어 HTTP 경로로 노출하지 않았다. 사용자와 편집 세션 식별자는 테스트가 만들어 넘긴다
- 요청과 응답 형식은 `contracts` 폴더의 계약을 따른다
- PPTX와 PDF 출력은 실험만 했다. 방법과 결과는 `experiments/export/README.md`에 있다
- 0단계에서 끝낸 것과 남은 확인 사항, 실행한 검증은 `docs/stage-0-result.md`에 있다

## 작업 처리

오래 걸리는 처리는 요청과 분리해 `jobs` 테이블에 등록하고 API 프로세스 안의 실행 풀이 처리한다. 경로와 상태 전이는 `contracts/stage-2.md`에 있다

- `JobQueue`가 등록, 임대 선점, 임대 연장, 완료, 실패, 취소를 맡는다. 잠금은 프로젝트, 작업 순서로 잡는다
- 실행자는 `FOR UPDATE SKIP LOCKED`로 대기 작업을 선점하고 60초 임대를 15초마다 연장한다
- 임대가 만료되면 같은 작업이 대기로 돌아가고 시도 횟수가 오른다. 3번을 넘으면 실패로 끝난다
- 결과는 시도 번호와 임대 토큰이 지금 임대와 같을 때만 반영한다. 취소가 요청된 작업의 결과는 버린다
- 작업 종류마다 `JobHandler`를 구현한다. 실행은 트랜잭션 밖에서, 결과 저장은 완료 트랜잭션 안에서 한다

| 이름 | 용도 | 기본값 |
| --- | --- | --- |
| `JOBS_WORKER_ENABLED` | 이 프로세스에서 실행 풀을 돌릴지 | `true` |
| `JOBS_CONCURRENCY` | 동시에 처리할 작업 수 | `2` |

## 자료와 팀 메모

경로와 권한, 상태 전이는 `contracts/stage-2.md`에 있다

- `MaterialService`가 자료 목록과 상세, 이름과 본문 변경, 삭제, 마크다운 자료, 팀 메모를 맡는다
- 자료, 원문, 팀 메모 요청은 자료가 속한 프로젝트의 활성 팀원인지 요청마다 확인한다. 쓰기는 프로젝트를 잠근 뒤 자료를 잠근다
- 자료의 이름과 설명 변경은 버전만 올리고 마크다운 본문 변경은 바뀌지 않는 새 개정을 만든다
- 삭제는 소유자와 자료 작성자만 할 수 있다. 목록에서 숨기고 개정과 팀 메모는 지우지 않는다
- 팀 메모는 자료마다 하나이며 버전을 비교해 저장한다. 자료의 버전과 개정을 바꾸지 않는다

## PDF 업로드와 분석

- 업로드 준비, 파일 전송, 자료 등록, 분석을 나눠 처리한다. 등록할 때 실제 크기, 체크섬, PDF 시작 표시를 검사하고 통과한 파일만 바뀌지 않는 위치로 복사해 확정한다
- 분석 작업은 PDFBox로 쪽수와 쪽별 본문을 추출해 새 개정과 본문 조각으로 저장한다. 요약 모델이 정해지지 않아 요약은 비워 둔다
- 분석이 실패하면 보관한 원본으로 다시 시도한다. 파일을 다시 올리지 않는다
- 파일 저장소 제공자가 정해지지 않아 `STORAGE_ROOT` 폴더에 보관하고 서버가 서명한 주소로 주고받는다
- 원문 주소는 5분 동안 유효하다. 서버가 파일을 직접 내려 주므로 요청마다 세션과 프로젝트 참여 여부, 자료 삭제 여부를 다시 확인한다
- 악성 파일 검사와 파서 격리 실행은 하지 않는다. 운영 구성을 정할 때 추가한다

| 이름 | 용도 | 기본값 |
| --- | --- | --- |
| `STORAGE_ROOT` | 원본 파일을 보관할 폴더 | `data/storage` |
| `MATERIAL_PDF_MAX_BYTES` | PDF 한 개의 최대 크기. 기능 지원 정보로 알린다 | `20971520` |

## 링크 자료

- 등록할 때 주소 형식만 확인하고 문서 수집은 분석 작업이 한다. 실패해도 입력한 주소와 설명은 자료에 남는다
- http와 https, 80과 443 포트만 받는다. 이름 풀이 결과와 이동할 때마다의 주소가 로컬, 사설, 링크 로컬, 예약 대역이면 요청을 보내지 않는다
- 응답 15초, 본문 2MB(압축을 푼 크기), 이동 3번으로 제한한다. HTML과 일반 글만 읽는다
- 제목을 비워 등록하면 수집한 문서 제목을 쓴다
- 검사와 연결 사이의 이름 재풀이는 JVM의 이름 풀이 캐시에 기댄다. 운영에서는 실행 환경의 외부 요청 제한을 함께 둔다

## CI

`.github/workflows/ci.yml`이 PR과 기본 브랜치 푸시마다 `compose.yaml`의 MySQL 8.4를 띄우고 `./gradlew build`로 빌드와 테스트를 실행한다.

## 아직 연결하지 않은 외부 설정

아래 항목은 제공자와 정책이 정해지지 않아 코드와 설정에 넣지 않았다.

- 배포 환경과 운영 MySQL
- 파일 저장소
- 메일 발송, 소셜 로그인 제공자
- AI 모델 제공자

## 인증과 계정

`contracts/stage-1.md`에 요청과 응답, 로그인 복귀와 설정 경계를 정리했다

- 이메일 코드 발급과 확인, 가입 동의, 프로필 조회와 수정, 로그아웃, 재인증과 탈퇴를 제공한다
- 쿠키를 사용하는 쓰기 요청은 세션 초기 정보의 CSRF 토큰과 설정한 프론트 Origin이 필요하다
- Google, 카카오, 네이버 로그인은 각각 클라이언트 설정을 넣은 뒤 제공자의 콜백 주소를 등록해야 사용할 수 있다
- 메일이나 약관 설정이 없으면 가짜 완료를 만들지 않고 해당 작업을 거절한다
- 운영 환경은 HTTPS 주소와 Secure 쿠키를 사용한다. 로컬 HTTP 시험에서만 AUTH_SECURE_COOKIE=false를 명시한다
- 이메일과 소셜 로그인의 추가 계정 연결은 아직 제공하지 않는다
- 탈퇴는 사용자와 이력의 참조를 보존하고 표시 이름과 이메일을 지운다. 데이터 영구 삭제와 보존 정책은 별도 운영 과제다

로컬 Java 프로세스의 포트 열기가 제한되어 Gradle과 실제 서버 실행은 이 환경에서 검증하지 못했다
캐시된 의존성으로 Java 21 컴파일을 확인했으며 GitHub Actions에서 실제 MySQL 8.4와 HTTP 서버를 사용하는 통합 테스트를 실행한다
실제 외부 메일함 수신과 제공자 계정의 OAuth 왕복은 운영 설정이 없어 확인하지 못했다

소셜 재인증은 일반 로그인과 다른 제공자 요청을 사용한다
카카오는 prompt=login을 사용하며 재인증을 지원하지 않는 카카오톡 인앱 브라우저에서는 거절한다
네이버는 auth_type=reauthenticate를 사용한다
Google은 계정 재인증 요청을 지원하지 않아 소셜 재인증 진입을 거절한다
Google이 확인한 이메일이 있으면 해당 계정과 세션에 결합한 이메일 코드로 본인 확인할 수 있다. 이메일 로그인 수단을 새로 연결하지 않는다

재인증 요청 근거는 각 제공자의 공식 문서를 따른다
- Google 계정 재인증 요청 미지원: https://developers.google.com/identity/siwg/security-bundle
- 카카오 재인증 요청과 인앱 제한: https://developers.kakao.com/docs/ko/kakaologin/rest-api
- 네이버 재인증: https://developers.naver.com/docs/login/devguide/devguide.md

사용자 응답의 canEmailReauth가 true인 경우에만 계정에 이미 검증된 이메일로 본인 확인할 수 있다
네이버 프로필 이메일은 별도 검증 여부를 제공하지 않으므로 이메일 재인증에 사용하지 않는다

## 협업 규칙

상위 TeampleSpace 프로젝트 루트의 `AGENTS.md`를 공통으로 사용한다. 이 저장소에는 별도 규칙 파일을 두지 않는다.
