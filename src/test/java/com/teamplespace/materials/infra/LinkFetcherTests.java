package com.teamplespace.materials.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.processing.service.JobFailure;

// 링크 수집의 주소 검사, 이동, 크기와 시간 제한, 본문 추출을 로컬 서버로 확인
class LinkFetcherTests {

	HttpServer server;

	String base;

	final AtomicInteger internalHits = new AtomicInteger();

	// 테스트 서버가 있는 로컬 주소만 허용한다. 그 밖의 규칙은 운영과 같다
	LinkFetcher local;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		int port = server.getAddress().getPort();
		base = "http://127.0.0.1:" + port;
		local = new LinkFetcher((address, requested) -> address.isLoopbackAddress() && requested == port,
				Duration.ofSeconds(2));
		server.createContext("/page", exchange -> respond(exchange, 200, "text/html; charset=utf-8", """
				<html><head><title> 대학의 일회용품 감축 사례 </title><style>p{color:red}</style></head>
				<body><nav>메뉴</nav><h1>감축 사례</h1><p>여러 대학이 다회용컵 반납함을 늘렸다.</p>
				<script>alert('숨은 스크립트')</script><p>반납 동선을 줄이자<br>이용이 늘었다.</p></body></html>
				""".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/euc-kr", exchange -> respond(exchange, 200, "text/html",
				"<html><head><meta charset=\"euc-kr\"><title>한글 문서</title></head><body><p>완성형 한글 본문</p></body></html>"
					.getBytes(Charset.forName("EUC-KR"))));
		server.createContext("/plain", exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
				"첫 줄\n둘째 줄".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/gzip", exchange -> {
			ByteArrayOutputStream packed = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
				gzip.write("<html><body><p>압축한 본문</p></body></html>".getBytes(StandardCharsets.UTF_8));
			}
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			respond(exchange, 200, "text/html; charset=utf-8", packed.toByteArray());
		});
		server.createContext("/bomb", exchange -> {
			// 압축을 풀면 한도를 넘는 응답
			ByteArrayOutputStream packed = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
				gzip.write(new byte[LinkFetcher.MAX_BYTES + 1024]);
			}
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			respond(exchange, 200, "text/html", packed.toByteArray());
		});
		server.createContext("/large", exchange -> respond(exchange, 200, "text/html",
				"가".repeat(LinkFetcher.MAX_BYTES).getBytes(StandardCharsets.UTF_8)));
		server.createContext("/image", exchange -> respond(exchange, 200, "image/png", new byte[] { 1, 2, 3 }));
		server.createContext("/empty", exchange -> respond(exchange, 200, "text/html",
				"<html><body><script>render()</script></body></html>".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/missing", exchange -> respond(exchange, 404, "text/html", new byte[0]));
		server.createContext("/broken", exchange -> respond(exchange, 503, "text/html", new byte[0]));
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(4000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			respond(exchange, 200, "text/html", "<p>늦은 응답</p>".getBytes(StandardCharsets.UTF_8));
		});
		server.createContext("/stall", exchange -> {
			// 헤더와 본문 일부만 보내고 멈춘다
			exchange.getResponseHeaders().add("Content-Type", "text/html");
			exchange.sendResponseHeaders(200, 100_000);
			exchange.getResponseBody().write("<p>앞부분".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		server.createContext("/hop", exchange -> {
			int left = Integer.parseInt(exchange.getRequestURI().getQuery());
			redirect(exchange, left == 0 ? "/page" : "/hop?" + (left - 1));
		});
		server.createContext("/to-metadata", exchange -> redirect(exchange, "http://169.254.169.254/latest/meta-data"));
		server.createContext("/to-private", exchange -> redirect(exchange, "http://10.0.0.7/admin"));
		server.createContext("/to-other-port", exchange -> redirect(exchange, "http://127.0.0.1:1/internal"));
		server.createContext("/to-file", exchange -> redirect(exchange, "file:///etc/passwd"));
		server.createContext("/internal", exchange -> {
			internalHits.incrementAndGet();
			respond(exchange, 200, "text/html", "<p>내부 문서</p>".getBytes(StandardCharsets.UTF_8));
		});
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws java.io.IOException {
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			exchange.getResponseBody().write(body);
		}
		exchange.close();
	}

	private static void redirect(HttpExchange exchange, String location) throws java.io.IOException {
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private JobFailure failure(LinkFetcher fetcher, String url) {
		return catchThrowableOfType(JobFailure.class, () -> fetcher.fetch(url));
	}

	@Test
	void dnsLookupCannotHoldTheWorkerPastTheTotalDeadline() {
		var release = new java.util.concurrent.CountDownLatch(1);
		LinkFetcher slowDns = new LinkFetcher((address, port) -> true, Duration.ofMillis(200), host -> {
			boolean done = false;
			while (!done) {
				try { release.await(); done = true; } catch (InterruptedException ignored) { }
			}
			return new InetAddress[] { InetAddress.getLoopbackAddress() };
		});
		try {
			org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
				assertThat(failure(slowDns, "https://slow-dns.example/").code()).isEqualTo("LINK_FETCH_FAILED"));
		} finally { release.countDown(); }
	}

	@Test
	void normalizedUrlMustStillFitTheStoredLimit() {
		String shortUrl = LinkFetcher.normalize("https://example.com/" + "한".repeat(200));
		assertThat(shortUrl.length()).isLessThanOrEqualTo(LinkFetcher.URL_MAX);
		assertThat(LinkFetcher.normalize(shortUrl)).isEqualTo(shortUrl);
		ApiException error = catchThrowableOfType(ApiException.class,
				() -> LinkFetcher.normalize("https://example.com/" + "한".repeat(230)));
		assertThat(error).isNotNull();
		assertThat(error.code()).isEqualTo(ErrorCode.INVALID_URL);
	}

	@Test
	void totalDeadlineAlsoStopsSlowResponseHeaders() throws Exception {
		try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread peer = Thread.ofVirtual().start(() -> {
				try (var connection = socket.accept()) {
					for (byte value : "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<p>late</p>".getBytes(StandardCharsets.US_ASCII)) {
						connection.getOutputStream().write(value);
						connection.getOutputStream().flush();
						Thread.sleep(40);
					}
				} catch (Exception ignored) { }
			});
			LinkFetcher shortBudget = new LinkFetcher((address, port) -> address.isLoopbackAddress(), Duration.ofMillis(250));
			org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
				assertThat(failure(shortBudget, "http://127.0.0.1:" + socket.getLocalPort()).code()).isEqualTo("LINK_FETCH_FAILED"));
			peer.interrupt();
		}
	}

	@Test
	void errorsAndOversizedChunkedBodiesCloseWithoutDraining() {
		server.createContext("/endless-error", exchange -> streamForever(exchange, 503, false));
		server.createContext("/endless-large", exchange -> streamForever(exchange, 200, true));
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
			assertThat(failure(local, base + "/endless-error").code()).isEqualTo("LINK_FETCH_FAILED");
			JobFailure large = failure(local, base + "/endless-large");
			assertThat(large.code()).isEqualTo("LINK_TOO_LARGE");
			assertThat(large.retryable()).isFalse();
		});
	}

	private static void streamForever(HttpExchange exchange, int status, boolean large) throws java.io.IOException {
		exchange.getResponseHeaders().add("Content-Type", "text/html");
		exchange.sendResponseHeaders(status, 0);
		try {
			if (large) exchange.getResponseBody().write(new byte[LinkFetcher.MAX_BYTES + 4096]);
			for (int i = 0; i < 100; i++) {
				exchange.getResponseBody().write('a');
				exchange.getResponseBody().flush();
				Thread.sleep(40);
			}
		} catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
		finally { exchange.close(); }
	}

	@Test
	void redirectHopsShareOneDeadline() {
		server.createContext("/delayed-hop", exchange -> {
			try { Thread.sleep(180); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
			int left = Integer.parseInt(exchange.getRequestURI().getQuery());
			redirect(exchange, left == 0 ? "/page" : "/delayed-hop?" + (left - 1));
		});
		LinkFetcher shortBudget = new LinkFetcher((address, port) -> address.isLoopbackAddress(), Duration.ofMillis(300));
		assertThat(failure(shortBudget, base + "/delayed-hop?2").code()).isEqualTo("LINK_FETCH_FAILED");
	}

	@Test
	void connectsOnlyToTheAlreadyCheckedAddress() throws Exception {
		AtomicInteger resolutions = new AtomicInteger();
		int port = server.getAddress().getPort();
		LinkFetcher pinned = new LinkFetcher((address, requested) -> address.isLoopbackAddress() && requested == port,
				Duration.ofSeconds(2), host -> resolutions.incrementAndGet() == 1
						? new InetAddress[] { InetAddress.getByName("127.0.0.1") }
						: new InetAddress[] { InetAddress.getByName("10.0.0.7") });
		assertThat(pinned.fetch("http://rebind.example:" + port + "/page").title()).isEqualTo("대학의 일회용품 감축 사례");
		assertThat(resolutions).hasValue(1);
	}

	@Test
	void rejectsMixedPublicAndPrivateAnswersBeforeConnecting() {
		LinkFetcher mixed = new LinkFetcher((address, port) -> LinkFetcher.isPublic(address), Duration.ofSeconds(2),
				host -> new InetAddress[] { InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1") });
		assertThat(failure(mixed, "http://rebind.example/internal").code()).isEqualTo("LINK_BLOCKED");
		assertThat(internalHits).hasValue(0);
	}

	@Test
	void extractsTitleAndReadableTextFromHtml() {
		LinkFetcher.Page page = local.fetch(base + "/page");
		assertThat(page.title()).isEqualTo("대학의 일회용품 감축 사례");
		assertThat(page.text()).contains("여러 대학이 다회용컵 반납함을 늘렸다.").contains("반납 동선을 줄이자\n이용이 늘었다.");
		// 메뉴, 스타일, 스크립트는 본문에 넣지 않는다
		assertThat(page.text()).doesNotContain("메뉴").doesNotContain("숨은 스크립트").doesNotContain("color");
		assertThat(page.url()).isEqualTo(base + "/page");
	}

	@Test
	void decodesKoreanCharsetsPlainTextAndCompressedBodies() {
		LinkFetcher.Page legacy = local.fetch(base + "/euc-kr");
		assertThat(legacy.title()).isEqualTo("한글 문서");
		assertThat(legacy.text()).isEqualTo("완성형 한글 본문");
		LinkFetcher.Page plain = local.fetch(base + "/plain");
		assertThat(plain.title()).isNull();
		assertThat(plain.text()).isEqualTo("첫 줄\n둘째 줄");
		assertThat(local.fetch(base + "/gzip").text()).isEqualTo("압축한 본문");
	}

	@Test
	void collectsMaximumSizePlainTextWithLongWhitespaceWithoutStalling() {
		String body = "x" + " ".repeat(LinkFetcher.MAX_BYTES - 2) + "y";
		server.createContext("/long-spaces", exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
				body.getBytes(StandardCharsets.UTF_8)));
		server.createContext("/spaced-lines", exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
				"  첫 줄 \t\r\n \t둘째 줄 \n \n\t\n 끝  ".getBytes(StandardCharsets.UTF_8)));
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
			assertThat(local.fetch(base + "/long-spaces").text()).isEqualTo(body);
			assertThat(local.fetch(base + "/spaced-lines").text()).isEqualTo("첫 줄\n둘째 줄\n\n끝");
		});
	}

	@Test
	void followsAFewRedirectsAndChecksEachHop() {
		assertThat(local.fetch(base + "/hop?2").url()).isEqualTo(base + "/page");
		assertThat(failure(local, base + "/hop?3").code()).isEqualTo("LINK_FETCH_FAILED");
		// 이동한 곳이 내부망이면 요청을 보내지 않는다
		assertThat(failure(local, base + "/to-metadata").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(local, base + "/to-private").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(local, base + "/to-other-port").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(local, base + "/to-file").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(local, base + "/to-file").retryable()).isFalse();
	}

	@Test
	void productionRulesNeverConnectToLocalAddresses() {
		LinkFetcher strict = new LinkFetcher();
		assertThat(failure(strict, base + "/internal").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(strict, "http://localhost:" + server.getAddress().getPort() + "/internal").code())
			.isEqualTo("LINK_BLOCKED");
		assertThat(failure(strict, "http://[::1]/internal").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(strict, "http://169.254.169.254/latest/meta-data").code()).isEqualTo("LINK_BLOCKED");
		assertThat(failure(strict, "ftp://example.com/file").code()).isEqualTo("LINK_BLOCKED");
		assertThat(internalHits.get()).isZero();
	}

	@Test
	void limitsSizeTimeAndContentType() {
		assertThat(failure(local, base + "/large").code()).isEqualTo("LINK_TOO_LARGE");
		assertThat(failure(local, base + "/bomb").code()).isEqualTo("LINK_TOO_LARGE");
		assertThat(failure(local, base + "/image").code()).isEqualTo("LINK_UNSUPPORTED_CONTENT");
		assertThat(failure(local, base + "/empty").code()).isEqualTo("LINK_UNSUPPORTED_CONTENT");
		JobFailure slow = failure(local, base + "/slow");
		assertThat(slow.code()).isEqualTo("LINK_FETCH_FAILED");
		assertThat(slow.retryable()).isTrue();
		assertThat(failure(local, base + "/stall").code()).isEqualTo("LINK_FETCH_FAILED");
		// 서버 오류는 다시 시도하고 없는 문서는 다시 시도하지 않는다
		assertThat(failure(local, base + "/broken").retryable()).isTrue();
		assertThat(failure(local, base + "/missing").retryable()).isFalse();
	}

	@Test
	void normalizeAcceptsOnlyPublicHttpAddresses() {
		assertThat(LinkFetcher.normalize("  HTTPS://Example.com/reuse-cup?page=1#section  "))
			.isEqualTo("https://example.com/reuse-cup?page=1");
		assertThat(LinkFetcher.normalize("https://example.com")).isEqualTo("https://example.com/");
		assertThat(LinkFetcher.normalize("https://ko.wikipedia.org/wiki/한글")).endsWith("/wiki/%ED%95%9C%EA%B8%80");
		assertThat(LinkFetcher.normalize("https://example.com:443/a%2Fb?q=%26")).isEqualTo("https://example.com:443/a%2Fb?q=%26");
		for (String rejected : new String[] { "", "   ", "example.com", "ftp://example.com/a", "file:///etc/passwd",
				"javascript:alert(1)", "http://user:pass@example.com/", "http://localhost/", "http://app.localhost/",
				"http://printer.local/", "http://intranet/", "http://127.0.0.1/", "http://10.0.0.5/", "http://172.16.0.1/",
				"http://192.168.0.1/", "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/", "http://0.0.0.0/",
				"http://[::1]/", "http://[fd00::1]/", "http://[::ffff:10.0.0.1]/", "http://example.com:8080/",
				"https://example.com/" + "a".repeat(2048), "http://exa mple.com/" }) {
			ApiException error = catchThrowableOfType(ApiException.class, () -> LinkFetcher.normalize(rejected));
			assertThat(error).as(rejected).isNotNull();
			assertThat(error.code()).as(rejected).isEqualTo(ErrorCode.INVALID_URL);
		}
	}

	@Test
	void publicAddressCheckCoversReservedRanges() throws Exception {
		for (String address : new String[] { "127.0.0.1", "10.1.2.3", "172.31.255.255", "192.168.1.1", "169.254.1.1",
				"100.64.0.1", "0.0.0.0", "192.0.0.8", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::",
				"fe80::1", "fc00::1", "fd12:3456::1", "2002:7f00:1::1" }) {
			assertThat(LinkFetcher.isPublic(InetAddress.getByName(address))).as(address).isFalse();
		}
		for (String address : new String[] { "8.8.8.8", "93.184.216.34", "172.32.0.1", "2606:4700:4700::1111" }) {
			assertThat(LinkFetcher.isPublic(InetAddress.getByName(address))).as(address).isTrue();
		}
	}

}
