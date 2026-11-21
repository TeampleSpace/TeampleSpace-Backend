package com.teamplespace.platform.ai;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OpenAiResponsesClient {

	public static final class Failure extends RuntimeException {
		private final boolean invalidResult;
		private final boolean retryable;

		private Failure(boolean invalidResult, boolean retryable) {
			this.invalidResult = invalidResult;
			this.retryable = retryable;
		}

		public boolean invalidResult() {
			return invalidResult;
		}

		public boolean retryable() {
			return retryable;
		}
	}

	private static final int MAX_RESPONSE_BYTES = 256 * 1024;

	private final ObjectMapper json;
	private final CloseableHttpClient http;
	private final RequestConfig defaults = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
		.setResponseTimeout(Timeout.ofSeconds(40)).build();

	public OpenAiResponsesClient(ObjectMapper json) {
		this.json = json;
		var connections = PoolingHttpClientConnectionManagerBuilder.create()
			.setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
					.setSocketTimeout(Timeout.ofSeconds(40)).build())
			.build();
		http = HttpClients.custom().setConnectionManager(connections)
			.setDefaultRequestConfig(defaults)
			.disableRedirectHandling().disableAutomaticRetries().build();
	}

	public ObjectNode structuredRequest(String model, String instructions, String input, int outputTokens,
			String format, String schema) {
		var request = json.createObjectNode().put("model", model).put("instructions", instructions).put("input", input)
			.put("store", false).put("max_output_tokens", outputTokens);
		request.putObject("text").putObject("format").put("type", "json_schema").put("name", format).put("strict", true)
			.set("schema", json.readTree(schema));
		return request;
	}

	public JsonNode generate(URI endpoint, String apiKey, ObjectNode request) {
		return generate(endpoint, apiKey, request, null);
	}

	// 응답이 긴 요청은 기본 대기 시간보다 길게 기다린다
	public JsonNode generate(URI endpoint, String apiKey, ObjectNode request, Duration responseTimeout) {
		var post = new HttpPost(endpoint);
		if (responseTimeout != null) {
			post.setConfig(RequestConfig.copy(defaults).setResponseTimeout(Timeout.of(responseTimeout)).build());
		}
		post.setHeader("Authorization", "Bearer " + apiKey);
		post.setHeader("Accept", "application/json");
		post.setEntity(new StringEntity(json.writeValueAsString(request), ContentType.APPLICATION_JSON));
		try {
			return http.execute(post, response -> {
				int status = response.getCode();
				if (status < 200 || status >= 300) {
					throw new Failure(false, status == 408 || status == 429 || status >= 500);
				}
				if (response.getEntity() == null) {
					throw new Failure(true, false);
				}
				byte[] body = response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
				if (body.length > MAX_RESPONSE_BYTES) {
					throw new Failure(true, false);
				}
				return output(json.readTree(body));
			});
		}
		catch (JacksonException ex) {
			throw new Failure(true, false);
		}
		catch (IOException ex) {
			throw new Failure(false, true);
		}
	}

	private JsonNode output(JsonNode response) {
		if (response != null && "completed".equals(response.path("status").asString(""))) {
			for (JsonNode message : response.path("output")) {
				if ("message".equals(message.path("type").asString(""))) {
					for (JsonNode part : message.path("content")) {
						if ("output_text".equals(part.path("type").asString("")) && part.path("text").isString()) {
							return json.readTree(part.path("text").asString());
						}
					}
				}
			}
		}
		throw new Failure(true, false);
	}

	public static boolean validEndpoint(URI uri) {
		if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			return false;
		}
		return "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()));
	}

	@PreDestroy
	void destroy() throws IOException {
		http.close();
	}
}
