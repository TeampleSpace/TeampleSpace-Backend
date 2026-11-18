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

기본값은 `compose.yaml`의 로컬 DB에 맞춰져 있어 로컬에서는 설정 없이 실행된다. 값을 바꾸려면 `.env.example`을 `.env`로 복사해 수정한 뒤 셸에 올린다.

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
