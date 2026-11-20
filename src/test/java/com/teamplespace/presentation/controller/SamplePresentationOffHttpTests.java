package com.teamplespace.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.teamplespace.support.HttpIntegrationSupport;

import tools.jackson.databind.JsonNode;

// 기본 설정에서는 예제 문서를 만들 수 없다
class SamplePresentationOffHttpTests extends HttpIntegrationSupport {

	@Test
	void samplePresentationIsNotFoundUnlessEnabled() throws Exception {
		Browser owner = signup("소유자");
		String project = createProject(owner, "운영 설정");

		JsonNode error = owner.post("/projects/" + project + "/sample-presentation", null, 404);
		assertThat(error.get("error").get("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(owner.get("/projects/" + project + "/sync-snapshot?scope=editor").get("presentation").isNull())
			.isTrue();
	}

}
