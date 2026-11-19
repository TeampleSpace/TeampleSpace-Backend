package com.teamplespace.materials;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.zip.GZIPInputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;
import com.teamplespace.processing.JobFailure;

// 링크 문서를 가져와 제목과 본문을 뽑는다. 내부망으로 향하는 요청은 보내지 않는다
@Component
public class LinkFetcher {

	public record Page(String url, String title, String text) {
	}

	static final int MAX_BYTES = 2 * 1024 * 1024;

	static final int MAX_REDIRECTS = 3;

	static final int URL_MAX = 2048;

	private final BiPredicate<InetAddress, Integer> allowed;

	private final Duration timeout;

	private final HttpClient client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	@Autowired
	public LinkFetcher() {
		this((address, port) -> isPublic(address) && (port == 80 || port == 443), Duration.ofSeconds(15));
	}

	// 테스트가 로컬 서버로 확인할 때만 허용 규칙을 바꾼다
	LinkFetcher(BiPredicate<InetAddress, Integer> allowed, Duration timeout) {
		this.allowed = allowed;
		this.timeout = timeout;
	}

	// 등록할 때 형식만 확인하고 정리한 주소를 돌려준다. 이름 풀이는 수집할 때 한다
	public static String normalize(String raw) {
		URI uri = parse(raw);
		if (uri == null) {
			throw ApiException.field(ErrorCode.INVALID_URL, "url", "INVALID", "http 또는 https 주소를 입력해 주세요.");
		}
		InetAddress literal = literal(uri.getHost());
		if (isLocalName(uri.getHost()) || (literal != null && !isPublic(literal))
				|| (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
			throw ApiException.field(ErrorCode.INVALID_URL, "url", "NOT_ALLOWED", "가져올 수 없는 주소예요.");
		}
		return uri.toString();
	}

	public Page fetch(String url) {
		URI uri = parse(url);
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			if (uri == null) {
				throw blocked();
			}
			resolve(uri);
			HttpResponse<InputStream> response = send(uri);
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				close(response);
				// 이동할 때마다 새 주소를 처음부터 다시 검사한다
				uri = redirect(uri, response.headers().firstValue("Location").orElse(null));
				continue;
			}
			if (status >= 500 || status == 429 || status == 408) {
				close(response);
				throw failed(true);
			}
			if (status < 200 || status >= 300) {
				close(response);
				throw failed(false);
			}
			return read(uri, response);
		}
		throw failed(false);
	}

	private static URI redirect(URI from, String location) {
		try {
			return location == null ? null : parse(from.resolve(location.strip()).toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	// 이름 풀이 결과가 모두 공개 주소여야 한다
	// ponytail: 검사와 연결 사이의 재풀이는 JVM의 이름 풀이 캐시 30초에 기댄다. 실행 환경의 외부 요청 제한과 함께 쓰고
	// 필요하면 검사한 주소로 직접 연결하는 클라이언트로 바꾼다
	private void resolve(URI uri) {
		int port = uri.getPort() != -1 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
		try {
			for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
				if (!allowed.test(address, port)) {
					throw blocked();
				}
			}
		}
		catch (UnknownHostException ex) {
			throw failed(true);
		}
	}

	private HttpResponse<InputStream> send(URI uri) {
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(timeout)
			.header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
			.header("Accept-Encoding", "gzip")
			.header("User-Agent", "TeampleSpace-LinkFetcher/1.0")
			.GET()
			.build();
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw failed(true);
		}
		catch (HttpTimeoutException ex) {
			throw failed(true);
		}
		catch (IOException ex) {
			throw failed(true);
		}
	}

	private Page read(URI uri, HttpResponse<InputStream> response) {
		String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
		boolean html = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml");
		if (!html && !contentType.startsWith("text/plain")) {
			close(response);
			throw new JobFailure("LINK_UNSUPPORTED_CONTENT", "글로 읽을 수 없는 문서예요.", false);
		}
		if (response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BYTES) {
			close(response);
			throw tooLarge();
		}
		byte[] body;
		// 본문이 오다 멈추면 제한 시간 뒤에 연결을 닫아 읽기를 끝낸다
		var watchdog = CompletableFuture.runAsync(() -> close(response),
				CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS));
		try (InputStream raw = response.body();
				InputStream input = response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")
						? new GZIPInputStream(raw) : raw) {
			// 압축을 푼 크기로 한도를 잰다
			body = input.readNBytes(MAX_BYTES + 1);
		}
		catch (IOException ex) {
			throw failed(true);
		}
		finally {
			watchdog.cancel(false);
		}
		if (body.length > MAX_BYTES) {
			throw tooLarge();
		}
		String charset = contentType.contains("charset=")
				? contentType.substring(contentType.indexOf("charset=") + 8).split("[;\\s]")[0].replace("\"", "") : null;
		String title = null;
		String text;
		if (html) {
			Document document;
			try {
				document = Jsoup.parse(new java.io.ByteArrayInputStream(body), supported(charset), uri.toString());
			}
			catch (IOException ex) {
				throw failed(false);
			}
			title = document.title().strip();
			title = title.isEmpty() ? null : title.substring(0, Math.min(title.length(), 255));
			document.select("script,style,noscript,template,svg,iframe,nav,header,footer,aside,form").remove();
			// 문단 경계에 표시를 넣어 한 줄로 합쳐지는 것을 막는다
			document.select("br").append("\\n");
			document.select("p,div,li,h1,h2,h3,h4,h5,h6,tr,blockquote,section,article,pre").prepend("\\n\\n");
			text = document.body() == null ? "" : document.body().text().replace("\\n", "\n");
		}
		else {
			text = new String(body, supported(charset) == null ? StandardCharsets.UTF_8 : Charset.forName(charset));
		}
		text = MaterialAnalysis.clean(text).replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("\\n{3,}", "\n\n");
		if (text.isBlank()) {
			throw new JobFailure("LINK_UNSUPPORTED_CONTENT", "글로 읽을 수 없는 문서예요.", false);
		}
		return new Page(uri.toString(), title, text);
	}

	// http와 https, 계정 정보 없는 주소만 받는다. 조각 표시는 떼어 낸다
	private static URI parse(String raw) {
		if (raw == null || raw.isBlank() || raw.strip().length() > URL_MAX) {
			return null;
		}
		try {
			URI uri = new URI(raw.strip()).normalize();
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank()
					|| uri.getRawUserInfo() != null) {
				return null;
			}
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			String rebuilt = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
					+ (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path
					+ (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
			// 한글 같은 글자는 퍼센트 표기로 바꿔 보낸다
			return URI.create(new URI(rebuilt).toASCIIString());
		}
		catch (java.net.URISyntaxException | IllegalArgumentException ex) {
			return null;
		}
	}

	private static boolean isLocalName(String host) {
		return host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
				|| host.endsWith(".internal") || !host.contains(".") && literal(host) == null;
	}

	// 숫자로 쓴 주소면 이름 풀이 없이 주소로 바꾼다
	private static InetAddress literal(String host) {
		String value = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
		if (!value.contains(":") && !value.matches("[0-9.]+")) {
			return null;
		}
		try {
			return InetAddress.getByName(value);
		}
		catch (UnknownHostException ex) {
			return null;
		}
	}

	// 로컬, 사설, 링크 로컬, 클라우드 메타데이터, 예약 대역이 아니어야 한다
	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		int first = bytes[0] & 0xff;
		int second = bytes[1] & 0xff;
		if (bytes.length == 4) {
			return !(first == 0 || (first == 100 && second >= 64 && second <= 127) || (first == 192 && second == 0)
					|| (first == 198 && (second == 18 || second == 19)) || first >= 240);
		}
		// 고유 로컬 주소와 IPv4를 담는 대역은 받지 않는다
		return !((first & 0xfe) == 0xfc || first == 0 || (first == 0x20 && second == 0x02));
	}

	private static String supported(String charset) {
		try {
			return charset != null && Charset.isSupported(charset) ? charset : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static void close(HttpResponse<InputStream> response) {
		try {
			response.body().close();
		}
		catch (IOException ex) {
			// 닫기 실패는 결과에 영향이 없다
		}
	}

	private static JobFailure blocked() {
		return new JobFailure("LINK_BLOCKED", "가져올 수 없는 주소예요.", false);
	}

	private static JobFailure failed(boolean retryable) {
		return new JobFailure("LINK_FETCH_FAILED", "문서를 가져오지 못했어요.", retryable);
	}

	private static JobFailure tooLarge() {
		return new JobFailure("LINK_TOO_LARGE", "문서가 너무 커요.", false);
	}

}
