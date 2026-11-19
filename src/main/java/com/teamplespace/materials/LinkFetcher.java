package com.teamplespace.materials;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.zip.GZIPInputStream;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
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

	// 시스템 DNS가 인터럽트를 무시해도 분석 작업을 막지 않고 조회 수를 제한한다
	private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon(true).name("link-dns-", 0).factory(),
			new ThreadPoolExecutor.AbortPolicy());

	private final BiPredicate<InetAddress, Integer> allowed;

	private final Duration timeout;

	@FunctionalInterface
	interface Addresses {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	private final Addresses addresses;

	@Autowired
	public LinkFetcher() {
		this((address, port) -> isPublic(address) && (port == 80 || port == 443), Duration.ofSeconds(15));
	}

	// 테스트가 로컬 서버로 확인할 때만 허용 규칙을 바꾼다
	LinkFetcher(BiPredicate<InetAddress, Integer> allowed, Duration timeout) {
		this(allowed, timeout, InetAddress::getAllByName);
	}

	LinkFetcher(BiPredicate<InetAddress, Integer> allowed, Duration timeout, Addresses addresses) {
		this.allowed = allowed;
		this.timeout = timeout;
		this.addresses = addresses;
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
		long deadline = System.nanoTime() + timeout.toNanos();
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			if (uri == null) throw blocked();
			InetAddress[] pinned = resolve(uri, deadline);
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw failed(true);
			String host = uri.getHost();
			// 검사한 주소만 연결에 사용하며 TLS 인증과 Host는 원래 호스트를 유지한다
			DnsResolver resolver = new DnsResolver() {
				public InetAddress[] resolve(String name) throws UnknownHostException {
					if (!host.equals(name)) throw new UnknownHostException(name);
					return pinned.clone();
				}
				public String resolveCanonicalHostname(String name) { return name; }
			};
			var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
				.setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
					.setSocketTimeout(Timeout.ofMilliseconds(timeout.toMillis())).build()).build();
			HttpGet request = new HttpGet(uri);
			request.setHeader("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9");
			request.setHeader("Accept-Encoding", "gzip");
			request.setHeader("User-Agent", "TeampleSpace-LinkFetcher/1.0");
			// 헤더와 본문, 모든 리다이렉트를 하나의 시간 예산으로 제한한다
			var watchdog = CompletableFuture.runAsync(request::cancel,
					CompletableFuture.delayedExecutor(remaining, TimeUnit.NANOSECONDS));
			try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
					.disableAutomaticRetries().disableContentCompression()
					.setDefaultRequestConfig(RequestConfig.custom()
						.setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis())).build()).build();
				ClassicHttpResponse response = client.executeOpen(null, request, null)) {
				int status = response.getCode();
				if (status >= 300 && status < 400) {
					uri = redirect(uri, header(response, "Location"));
					request.cancel();
					continue;
				}
				if (status >= 500 || status == 429 || status == 408) { request.cancel(); throw failed(true); }
				if (status < 200 || status >= 300) { request.cancel(); throw failed(false); }
				return read(uri, response, request);
			}
			catch (IOException ex) { throw failed(true); }
			finally { request.cancel(); watchdog.cancel(false); manager.close(); }
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

	// 이름 풀이 결과를 검사한 뒤 그대로 연결에 넘긴다
	private InetAddress[] resolve(URI uri, long deadline) {
		if (deadline <= System.nanoTime()) throw failed(true);
		java.util.concurrent.Future<InetAddress[]> task;
		try { task = DNS.submit(() -> resolve(uri)); }
		catch (RejectedExecutionException ex) { throw failed(true); }
		try {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw failed(true);
			return task.get(remaining, TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw failed(true); }
		catch (TimeoutException ex) { throw failed(true); }
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof JobFailure failure) throw failure;
			throw failed(true);
		}
		finally { task.cancel(true); DNS.purge(); }
	}

	private InetAddress[] resolve(URI uri) {
		int port = uri.getPort() != -1 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
		try {
			InetAddress[] resolved = addresses.resolve(uri.getHost());
			if (resolved.length == 0) throw failed(true);
			for (InetAddress address : resolved) {
				if (!allowed.test(address, port)) throw blocked();
			}
			return resolved;
		}
		catch (UnknownHostException ex) { throw failed(true); }
	}

	private static String header(ClassicHttpResponse response, String name) {
		var value = response.getFirstHeader(name);
		return value == null ? "" : value.getValue();
	}

	private Page read(URI uri, ClassicHttpResponse response, HttpGet request) {
		String contentType = header(response, "Content-Type").toLowerCase(Locale.ROOT);
		boolean html = contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml");
		if (!html && !contentType.startsWith("text/plain")) {
			request.cancel();
			throw new JobFailure("LINK_UNSUPPORTED_CONTENT", "글로 읽을 수 없는 문서예요.", false);
		}
		if ((response.getEntity() == null ? 0 : response.getEntity().getContentLength()) > MAX_BYTES) {
			request.cancel();
			throw tooLarge();
		}
		byte[] body;
		try (InputStream raw = response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent();
				InputStream input = header(response, "Content-Encoding").equalsIgnoreCase("gzip")
						? new GZIPInputStream(raw) : raw) {
			// 압축을 푼 크기로 한도를 잰다
			body = input.readNBytes(MAX_BYTES + 1);
			if (body.length > MAX_BYTES) { request.cancel(); throw tooLarge(); }
		}
		catch (IOException ex) {
			throw failed(true);
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
		text = MaterialAnalysis.clean(text).replaceAll("\\n[ \\t]+", "\n").replaceAll("\\n{3,}", "\n\n");
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
			String encoded = new URI(rebuilt).toASCIIString();
			return encoded.length() <= URL_MAX ? URI.create(encoded) : null;
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
