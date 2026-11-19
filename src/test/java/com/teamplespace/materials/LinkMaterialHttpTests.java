package com.teamplespace.materials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.teamplespace.contracts.ContractSchemas;
import com.teamplespace.processing.JobFailure;
import com.teamplespace.processing.JobQueue;
import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

// 링크 자료의 등록, 수집 결과 저장과 재조회, 실패 뒤 다시 시도를 HTTP 요청과 MySQL로 확인
// 외부 문서를 가져오는 부분만 대역으로 바꾼다. 실제 수집 규칙은 LinkFetcherTests가 확인한다
class LinkMaterialHttpTests extends HttpIntegrationSupport {

	private static final String S = "stage-2.schema.json#/$defs/";

	@Autowired
	JobQueue queue;

	@MockitoBean
	LinkFetcher fetcher;

	private void assertMatches(String schema, JsonNode value) {
		assertThat(ContractSchemas.validate(schema, json.writeValueAsString(value))).as(value.toString()).isEmpty();
	}

	private void runJobs() {
		for (var claim = queue.claim("test"); claim.isPresent(); claim = queue.claim("test")) {
			queue.run(claim.get());
		}
	}

	@Test
	void linkIsRegisteredOnceCollectedAndReadBackWithTitleAndSource() throws Exception {
		Browser owner = signup("링크 소유자");
		String projectId = createProject(owner, "링크 등록 " + UUID.randomUUID());
		String path = "/projects/" + projectId + "/materials/link";
		Map<String, Object> body = Map.of("url", "https://Example.com/reuse-cup#top", "description", "감축 사례 참고");
		String key = UUID.randomUUID().toString();

		JsonNode created = owner.request("POST", path, body, 201, key);
		assertMatches(S + "MaterialCreated", created);
		// 응답을 받지 못해 같은 키로 다시 보내도 자료와 작업은 하나다
		assertThat(owner.request("POST", path, body, 201, key)).isEqualTo(created);
		owner.request("POST", path, body, 400, null);
		JsonNode material = created.get("material");
		String id = material.get("id").asString();
		assertThat(material.get("status").asString()).isEqualTo("processing");
		assertThat(material.get("title").asString()).isEqualTo("example.com");
		assertThat(material.get("source").get("url").asString()).isEqualTo("https://example.com/reuse-cup");
		assertThat(material.get("description").asString()).isEqualTo("감축 사례 참고");
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").size()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM jobs WHERE target_id = UUID_TO_BIN(?)").param(id).query(Integer.class).single())
			.isEqualTo(1);

		when(fetcher.fetch("https://example.com/reuse-cup")).thenReturn(new LinkFetcher.Page(
				"https://example.com/reuse-cup", "대학의 일회용품 감축 사례", "여러 대학이 다회용컵 반납함을 늘렸다.\n\n반납 동선을 줄이자 이용이 늘었다."));
		runJobs();

		JsonNode ready = owner.get("/materials/" + id);
		assertMatches(S + "Material", ready);
		assertThat(ready.get("status").asString()).isEqualTo("ready");
		// 제목을 비워 등록했으므로 수집한 문서 제목을 쓴다
		assertThat(ready.get("title").asString()).isEqualTo("대학의 일회용품 감축 사례");
		assertThat(ready.get("pageCount").isNull()).isTrue();
		assertThat(ready.get("currentRevision").get("excerpt").get("text").asString()).startsWith("여러 대학이");
		assertThat(ready.get("currentRevision").get("excerpt").get("page").isNull()).isTrue();
		JsonNode source = owner.get("/materials/" + id + "/source");
		assertMatches(S + "MaterialSource", source);
		assertThat(source.get("link").get("url").asString()).isEqualTo("https://example.com/reuse-cup");
		assertThat(source.get("link").get("title").asString()).isEqualTo("대학의 일회용품 감축 사례");
		assertThat(source.get("link").get("text").asString()).contains("반납 동선을 줄이자");
		assertThat(owner.get("/projects/" + projectId + "/materials?kind=link").get("counts").get("link").asInt())
			.isEqualTo(1);
		// 링크 자료의 본문은 고칠 수 없다
		owner.request("PATCH", "/materials/" + id, Map.of("expectedVersion", ready.get("version").asInt(), "markdown", "수정"),
				422);
	}

	@Test
	void failedCollectionKeepsTheAddressAndRetriesTheSameMaterial() throws Exception {
		Browser owner = signup("링크 실패 소유자");
		String projectId = createProject(owner, "링크 실패 " + UUID.randomUUID());
		when(fetcher.fetch(anyString())).thenThrow(new JobFailure("LINK_FETCH_FAILED", "문서를 가져오지 못했어요.", true));
		JsonNode created = owner.post("/projects/" + projectId + "/materials/link",
				Map.of("url", "https://example.org/slow", "title", "내가 붙인 이름", "description", "나중에 확인"), 201);
		String id = created.get("material").get("id").asString();
		runJobs();

		JsonNode failed = owner.get("/materials/" + id);
		assertMatches(S + "Material", failed);
		assertThat(failed.get("status").asString()).isEqualTo("failed");
		assertThat(failed.get("job").get("attempt").asInt()).isEqualTo(3);
		assertThat(failed.get("job").get("error").get("code").asString()).isEqualTo("LINK_FETCH_FAILED");
		// 실패해도 입력한 주소와 설명, 작성자는 남는다
		assertThat(failed.get("source").get("url").asString()).isEqualTo("https://example.org/slow");
		assertThat(failed.get("description").asString()).isEqualTo("나중에 확인");
		assertThat(failed.get("createdBy").get("name").asString()).isEqualTo("링크 실패 소유자");

		org.mockito.Mockito.reset(fetcher);
		when(fetcher.fetch("https://example.org/slow"))
			.thenReturn(new LinkFetcher.Page("https://example.org/slow", "문서 제목", "다시 가져온 본문"));
		String key = UUID.randomUUID().toString();
		JsonNode retried = owner.request("POST", "/materials/" + id + "/reprocess", null, 201, key);
		assertThat(owner.request("POST", "/materials/" + id + "/reprocess", null, 201, key)).isEqualTo(retried);
		runJobs();
		JsonNode ready = owner.get("/materials/" + id);
		assertThat(ready.get("status").asString()).isEqualTo("ready");
		// 사용자가 붙인 이름은 문서 제목으로 바꾸지 않는다
		assertThat(ready.get("title").asString()).isEqualTo("내가 붙인 이름");
		assertThat(owner.get("/materials/" + id + "/source").get("link").get("title").asString()).isEqualTo("문서 제목");
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").size()).isEqualTo(1);
	}

	@Test
	void blockedAndInvalidAddressesAreRejected() throws Exception {
		Browser owner = signup("링크 검증 소유자");
		Browser stranger = signup("링크 외부인");
		String projectId = createProject(owner, "링크 검증 " + UUID.randomUUID());
		String path = "/projects/" + projectId + "/materials/link";
		for (String url : new String[] { "", "example.com", "ftp://example.com/a", "http://localhost/admin",
				"http://127.0.0.1:8080/", "http://192.168.0.10/", "http://169.254.169.254/latest/meta-data",
				"http://user:pass@example.com/", "http://[::1]/" }) {
			JsonNode error = owner.post(path, Map.of("url", url), 422);
			assertThat(error.get("error").get("code").asString()).as(url).isEqualTo("INVALID_URL");
			assertThat(error.get("error").get("fieldErrors").get(0).get("field").asString()).isEqualTo("url");
		}
		owner.post(path, Map.of("url", "https://example.com/", "title", "가".repeat(256)), 422);
		stranger.post(path, Map.of("url", "https://example.com/"), 404);
		assertThat(owner.get("/projects/" + projectId + "/materials").get("items").isEmpty()).isTrue();

		// 수집 중 이동한 곳이 막힌 주소면 다시 시도하지 않고 끝난다
		when(fetcher.fetch(anyString())).thenThrow(new JobFailure("LINK_BLOCKED", "가져올 수 없는 주소예요.", false));
		String id = owner.post(path, Map.of("url", "https://example.com/redirects-inside"), 201).get("material").get("id")
			.asString();
		runJobs();
		JsonNode job = owner.get("/materials/" + id).get("job");
		assertThat(job.get("error").get("code").asString()).isEqualTo("LINK_BLOCKED");
		assertThat(job.get("attempt").asInt()).isEqualTo(1);
		assertThat(job.get("error").get("retryable").asBoolean()).isFalse();
	}

}
