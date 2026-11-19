#!/usr/bin/env python3
# 실행 중인 검증용 백엔드에 실제 HTTP 요청을 보내 2단계 흐름을 확인한다
# 사용법: VERIFY_MAIL_DIR=로컬메일폴더 python3 scripts/verify-materials.py [PDF 경로]
# 검증용 메일 수신기와 API 서버가 필요하다. 새 QA 계정과 프로젝트만 만들며 기존 데이터는 초기화하지 않는다
import os, email, hashlib, http.cookiejar, json, pathlib, re, sys, time, urllib.error, urllib.request, uuid

BASE = os.environ.get("VERIFY_API_URL", "http://127.0.0.1:8085").rstrip("/")
ORIGIN = os.environ.get("VERIFY_FRONTEND_ORIGIN", "http://localhost:5181")
HERE = pathlib.Path(__file__).resolve().parent
MAIL_DIR = pathlib.Path(os.environ["VERIFY_MAIL_DIR"])
if urllib.parse.urlparse(BASE).hostname not in ("127.0.0.1", "localhost", "::1"):
    raise SystemExit("이 검사는 로컬 검증 서버에서만 실행합니다")
PDF = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parent / "experiments/export/output/presentation.pdf"
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("통과 " if ok else "실패 ") + name + (" | " + str(detail) if detail else ""), flush=True)


class Browser:
    def __init__(self, name):
        self.name = name
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        self.user_id = None

    def call(self, method, path, body=None, key=None, raw=None, headers=None, csrf=True):
        url = path if path.startswith("http") else BASE + path
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Origin", ORIGIN)
        if body is not None and raw is None:
            request.add_header("Content-Type", "application/json")
        if csrf and self.csrf and method != "GET":
            request.add_header("X-CSRF-Token", self.csrf)
        if key:
            request.add_header("Idempotency-Key", key)
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        try:
            with self.opener.open(request, timeout=30) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers

    def json(self, method, path, body=None, key=None, expect=200):
        status, payload, _ = self.call(method, path, body, key)
        value = json.loads(payload) if payload else None
        if status != expect:
            raise AssertionError(f"{self.name} {method} {path} {status} {payload[:300]!r}")
        return value

    def status(self, method, path, body=None, key=None):
        return self.call(method, path, body, key or (str(uuid.uuid4()) if method == "POST" else None))[0]

    def signup(self):
        self.csrf = self.json("GET", "/v1/auth/bootstrap")["csrfToken"]
        address = f"qa-{uuid.uuid4().hex[:12]}@example.test"
        challenge = self.json("POST", "/v1/auth/challenges", {"email": address, "purpose": "login"}, expect=202)
        code = None
        for _ in range(50):
            for path in sorted(MAIL_DIR.glob("*.eml"), reverse=True):
                message = email.message_from_bytes(path.read_bytes())
                if address in (message.get("To") or ""):
                    text = message.get_payload(decode=True).decode(message.get_content_charset() or "utf-8")
                    code = re.search(r"\d{6}", text).group(0)
                    break
            if code:
                break
            time.sleep(0.2)
        self.json("POST", "/v1/auth/sessions", {"challengeId": challenge["challengeId"], "code": code}, expect=201)
        self.csrf = self.json("GET", "/v1/auth/bootstrap")["csrfToken"]
        me = self.json("GET", "/v1/me")
        user = self.json("PATCH", "/v1/me", {"name": self.name, "version": me["version"],
                                              "consentVersions": {"terms": "local-v1", "privacy": "local-v1"}})
        self.user_id = user["id"]
        return self


def wait_job(browser, job_id, seconds=60):
    for _ in range(seconds * 2):
        job = browser.json("GET", f"/v1/jobs/{job_id}")
        if job["status"] in ("succeeded", "failed", "canceled"):
            return job
        time.sleep(0.5)
    return job


def main():
    owner, member, stranger = Browser("QA 소유자").signup(), Browser("QA 팀원").signup(), Browser("QA 외부인").signup()
    project = owner.json("POST", "/v1/projects", {"name": "2단계 실제 연동 " + time.strftime("%H:%M:%S")}, key=str(uuid.uuid4()), expect=201)
    pid = project["id"]
    invite = owner.json("POST", f"/v1/projects/{pid}/invitations", {"rotate": False})["url"].rsplit("/", 1)[1]
    member.json("POST", f"/v1/invitations/{invite}/accept")
    check("빈 프로젝트의 자료 목록", owner.json("GET", f"/v1/projects/{pid}/materials")["counts"]["all"] == 0)

    # 마크다운 자료와 팀 메모
    key = str(uuid.uuid4())
    body = {"title": "다회용컵 사용 인터뷰", "markdown": "## 인터뷰 요약\n\n- 반납함이 멀어서 쓰지 않는다는 답이 많았다"}
    memo = member.json("POST", f"/v1/projects/{pid}/materials/memo", body, key=key, expect=201)["material"]
    again = member.json("POST", f"/v1/projects/{pid}/materials/memo", body, key=key, expect=201)["material"]
    check("마크다운 자료 재시도에 중복 생성 없음", memo["id"] == again["id"] and owner.json("GET", f"/v1/projects/{pid}/materials")["counts"]["memo"] == 1)
    edited = owner.json("PATCH", f"/v1/materials/{memo['id']}", {"expectedVersion": memo["version"], "markdown": "## 인터뷰 요약\n\n- 반납 장소를 늘려 달라는 요청"})
    check("마크다운 수정이 새 개정으로 저장", edited["currentRevision"]["sourceVersion"] == 2 and edited["createdBy"]["name"] == "QA 팀원")
    check("오래된 버전 저장은 충돌", owner.status("PATCH", f"/v1/materials/{memo['id']}", {"expectedVersion": memo["version"], "title": "덮어쓰기"}) == 409)
    owner.json("PUT", f"/v1/materials/{memo['id']}/note", {"expectedVersion": 0, "markdown": "반납함 동선을 함께 확인"})
    note = member.json("GET", f"/v1/materials/{memo['id']}/note")
    check("팀 메모 저장과 재조회", note["markdown"] == "반납함 동선을 함께 확인" and note["updatedByName"] == "QA 소유자" and note["version"] == 1)
    check("팀 메모 버전 충돌", member.status("PUT", f"/v1/materials/{memo['id']}/note", {"expectedVersion": 0, "markdown": "늦은 저장"}) == 409)

    # 실제 PDF 업로드와 분석
    data = PDF.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    ticket = owner.json("POST", f"/v1/projects/{pid}/uploads", {"kind": "material_pdf", "fileName": PDF.name, "mimeType": "application/pdf", "sizeBytes": len(data), "checksumSha256": digest}, expect=201)
    put = owner.call("PUT", ticket["uploadUrl"], raw=data, headers=ticket["uploadHeaders"], csrf=False)[0]
    check("파일 전송", put == 204, put)
    key = str(uuid.uuid4())
    created = owner.json("POST", f"/v1/projects/{pid}/materials/file", {"uploadId": ticket["uploadId"]}, key=key, expect=201)
    replay = owner.json("POST", f"/v1/projects/{pid}/materials/file", {"uploadId": ticket["uploadId"]}, key=key, expect=201)
    file_id = created["material"]["id"]
    check("PDF 등록 직후 처리 중", created["material"]["status"] == "processing" and created["job"]["status"] in ("queued", "running"))
    check("PDF 등록 재시도에 중복 생성 없음", replay["material"]["id"] == file_id and replay["job"]["id"] == created["job"]["id"])
    job = wait_job(owner, created["job"]["id"])
    check("실행 풀이 분석 작업을 끝냄", job["status"] == "succeeded", job.get("error"))
    material = owner.json("GET", f"/v1/materials/{file_id}")
    excerpt = (material["currentRevision"] or {}).get("excerpt") or {}
    check("분석 뒤 준비 완료와 쪽수", material["status"] == "ready" and material["pageCount"] >= 1, f"{material['pageCount']}쪽")
    check("실제 파일에서 뽑은 발췌와 쪽 번호", bool(excerpt.get("text")) and 1 <= (excerpt.get("page") or 0) <= material["pageCount"], f"{excerpt.get('page')}쪽 {str(excerpt.get('text'))[:40]}")
    source = owner.json("GET", f"/v1/materials/{file_id}/source")
    status, payload, headers = owner.call("GET", source["file"]["url"])
    check("원문 주소로 받은 파일이 올린 파일과 같음", status == 200 and hashlib.sha256(payload).hexdigest() == digest and source["file"]["pageCount"] == material["pageCount"])
    status, payload, _ = owner.call("GET", source["file"]["url"], headers={"Range": "bytes=0-4"})
    check("원문 범위 요청", status == 206 and payload == b"%PDF-", status)
    check("새로고침 뒤 재조회가 같음", owner.json("GET", f"/v1/materials/{file_id}")["currentRevision"] == material["currentRevision"])

    # 실제 링크 수집
    key = str(uuid.uuid4())
    link_body = {"url": "https://example.com/", "description": "수집 확인용"}
    link = owner.json("POST", f"/v1/projects/{pid}/materials/link", link_body, key=key, expect=201)
    owner.json("POST", f"/v1/projects/{pid}/materials/link", link_body, key=key, expect=201)
    link_job = wait_job(owner, link["job"]["id"])
    link_material = owner.json("GET", f"/v1/materials/{link['material']['id']}")
    if link_job["status"] == "succeeded":
        link_source = owner.json("GET", f"/v1/materials/{link['material']['id']}/source")["link"]
        check("실제 링크 수집과 제목, 출처 재조회", link_material["status"] == "ready" and link_material["source"]["url"] == "https://example.com/" and bool(link_source["text"]), f"제목 {link_material['title']}")
    else:
        check("실제 링크 수집", False, link_job.get("error"))
    check("링크 재시도에 중복 생성 없음", owner.json("GET", f"/v1/projects/{pid}/materials")["counts"]["link"] == 1)
    check("내부망 주소 거절", owner.status("POST", f"/v1/projects/{pid}/materials/link", {"url": "http://127.0.0.1:8085/v1/capabilities"}) == 422)

    # 검색, 이름 변경, 삭제 권한
    listing = owner.json("GET", f"/v1/projects/{pid}/materials")
    check("목록의 유형별 개수", listing["counts"] == {"all": 3, "file": 1, "link": 1, "memo": 1}, listing["counts"])
    found = owner.json("GET", f"/v1/projects/{pid}/materials?q=" + urllib.request.quote("인터뷰"))
    check("검색", [item["id"] for item in found["items"]] == [memo["id"]])
    renamed = member.json("PATCH", f"/v1/materials/{file_id}", {"expectedVersion": material["version"], "title": "교내 일회용품 조사.pdf"})
    check("팀원의 이름 변경", renamed["title"] == "교내 일회용품 조사.pdf" and renamed["currentRevision"]["id"] == material["currentRevision"]["id"])
    check("작성자도 소유자도 아닌 팀원의 삭제 거절", member.status("DELETE", f"/v1/materials/{file_id}", {"expectedVersion": renamed["version"]}) == 403)
    check("외부인의 자료와 원문 접근 거절", stranger.status("GET", f"/v1/materials/{file_id}") == 404 and stranger.call("GET", source["file"]["url"])[0] == 404 and stranger.status("GET", f"/v1/projects/{pid}/materials") == 404)

    # 네트워크 실패와 보관한 주소로 재시도
    failed_link = owner.json("POST", f"/v1/projects/{pid}/materials/link", {"url": "https://teamplespace-verification.invalid/", "title": "실패 재시도 검증"}, key=str(uuid.uuid4()), expect=201)
    failed_job = wait_job(owner, failed_link["job"]["id"])
    check("실제 이름 풀이 실패가 재시도 가능한 작업 실패로 기록", failed_job["status"] == "failed" and failed_job["error"]["retryable"])
    retry_key = str(uuid.uuid4())
    retry_path = f"/v1/materials/{failed_link['material']['id']}/reprocess"
    retried = owner.json("POST", retry_path, key=retry_key, expect=201)
    replay = owner.json("POST", retry_path, key=retry_key, expect=201)
    check("분석 재시도 중복 요청이 같은 작업을 반환", retried["job"]["id"] == replay["job"]["id"] and retried["job"]["id"] != failed_job["id"])
    check("다른 팀원의 작업 취소 거절", member.status("POST", f"/v1/jobs/{retried['job']['id']}/cancel") == 403)
    canceled = owner.json("POST", f"/v1/jobs/{retried['job']['id']}/cancel")
    settled = wait_job(owner, canceled["id"])
    check("명시적인 작업 취소와 상태 재조회", settled["status"] == "canceled", settled["status"])
    check("취소 요청 반복은 같은 종료 상태 유지", owner.json("POST", f"/v1/jobs/{settled['id']}/cancel")["status"] == "canceled")
    jobs = owner.json("GET", f"/v1/projects/{pid}/jobs?targetType=material&targetId={failed_link['material']['id']}")
    check("대상 자료의 작업 이력 조회", {item["id"] for item in jobs["items"]} == {failed_job["id"], settled["id"]})

    # 팀원 제거 뒤 기존 주소 재접근
    member_source = member.json("GET", f"/v1/materials/{file_id}/source")["file"]["url"]
    members = owner.json("GET", f"/v1/projects/{pid}/members")["items"]
    version = [m["membershipVersion"] for m in members if m["userId"] == member.user_id][0]
    owner.json("DELETE", f"/v1/projects/{pid}/members/{member.user_id}", {"expectedMembershipVersion": version, "clientMutationId": str(uuid.uuid4())}, key=str(uuid.uuid4()))
    check("제거된 팀원의 자료, 원문, 팀 메모 재접근 거절", member.status("GET", f"/v1/materials/{file_id}") == 403 and member.call("GET", member_source)[0] == 403 and member.status("GET", f"/v1/materials/{memo['id']}/note") == 403)
    check("제거된 팀원이 만든 자료와 작성자 표시 유지", owner.json("GET", f"/v1/materials/{memo['id']}")["createdBy"]["name"] == "QA 팀원")

    # 삭제
    deleted = owner.json("DELETE", f"/v1/materials/{memo['id']}", {"expectedVersion": edited["version"]})
    check("소유자의 삭제와 삭제 뒤 안내", deleted["tombstone"]["resourceType"] == "material" and owner.status("GET", f"/v1/materials/{memo['id']}") == 410 and owner.json("GET", f"/v1/projects/{pid}/materials")["counts"]["memo"] == 0)

    failed = [name for name, ok in results if not ok]
    print(f"\n결과 {len(results) - len(failed)}개 통과, {len(failed)}개 실패. 프로젝트 {pid}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
