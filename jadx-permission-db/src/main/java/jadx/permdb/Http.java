package jadx.permdb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client with a local cache.
 * <ul>
 * <li>immutable resources (URLs with a tag, commit or checksum) are downloaded only once</li>
 * <li>mutable resources are revalidated with {@code ETag} / {@code Last-Modified}</li>
 * <li>in offline mode only the cache is used</li>
 * </ul>
 */
final class Http {
	private static final Logger LOG = LoggerFactory.getLogger(Http.class);

	private static final int MAX_ATTEMPTS = 3;

	private final Path cacheDir;
	private final boolean offline;
	private final HttpClient client;
	private long downloaded;

	Http(Path cacheDir, boolean offline) {
		this.cacheDir = cacheDir;
		this.offline = offline;
		this.client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
	}

	Path getCacheDir() {
		return cacheDir;
	}

	boolean isOffline() {
		return offline;
	}

	/**
	 * Total bytes received from network
	 */
	long getDownloaded() {
		return downloaded;
	}

	byte[] get(String url, boolean immutable) throws IOException {
		Path body = cacheDir.resolve("http").resolve(sha256(url));
		Path validator = body.resolveSibling(body.getFileName() + ".validator");
		boolean cached = Files.isRegularFile(body);
		if (cached && (immutable || offline)) {
			return Files.readAllBytes(body);
		}
		if (offline) {
			throw new IOException("Offline mode and not in cache: " + url);
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5));
		String savedValidator = cached && Files.isRegularFile(validator) ? Files.readString(validator) : null;
		if (savedValidator != null) {
			if (savedValidator.startsWith("etag:")) {
				request.header("If-None-Match", savedValidator.substring(5));
			} else if (savedValidator.startsWith("lm:")) {
				request.header("If-Modified-Since", savedValidator.substring(3));
			}
		}
		HttpResponse<byte[]> response;
		try {
			response = send(request.build());
		} catch (IOException e) {
			if (cached) {
				LOG.warn("Request failed, using cached copy of {}: {}", url, e.getMessage());
				return Files.readAllBytes(body);
			}
			throw e;
		}
		if (response.statusCode() == 304 && cached) {
			LOG.debug("Not modified: {}", url);
			return Files.readAllBytes(body);
		}
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}
		byte[] data = response.body();
		downloaded += data.length;
		Files.createDirectories(body.getParent());
		Files.write(body, data);
		String newValidator = getValidator(response);
		if (newValidator != null) {
			Files.writeString(validator, newValidator);
		} else {
			Files.deleteIfExists(validator);
		}
		return data;
	}

	/**
	 * Read part of a remote file (not cached, see {@link RemoteZip} for caching)
	 */
	byte[] getRange(String url, long start, long length) throws IOException {
		if (offline) {
			throw new IOException("Offline mode and not in cache: " + url);
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(5))
				.header("Range", "bytes=" + start + '-' + (start + length - 1))
				.build();
		HttpResponse<byte[]> response = send(request);
		if (response.statusCode() != 206) {
			throw new IOException("Range request not supported, HTTP " + response.statusCode() + " for " + url);
		}
		byte[] data = response.body();
		downloaded += data.length;
		if (data.length != length) {
			throw new IOException("Unexpected range response length " + data.length + ", expected " + length + " for " + url);
		}
		return data;
	}

	long getContentLength(String url) throws IOException {
		if (offline) {
			throw new IOException("Offline mode: " + url);
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(1))
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<byte[]> response = send(request);
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " for HEAD " + url);
		}
		return response.headers().firstValueAsLong("Content-Length")
				.orElseThrow(() -> new IOException("No Content-Length for " + url));
	}

	private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
		IOException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() < 500) {
					return response;
				}
				last = new IOException("HTTP " + response.statusCode() + " for " + request.uri());
			} catch (IOException e) {
				last = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted", e);
			}
			LOG.debug("Attempt {} failed for {}: {}", attempt, request.uri(), last.getMessage());
			sleep(attempt * 2000L);
		}
		throw last;
	}

	private static void sleep(long ms) throws IOException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static @Nullable String getValidator(HttpResponse<?> response) {
		Optional<String> etag = response.headers().firstValue("ETag");
		if (etag.isPresent()) {
			return "etag:" + etag.get();
		}
		return response.headers().firstValue("Last-Modified").map(lm -> "lm:" + lm).orElse(null);
	}

	static String sha256(String str) {
		return sha256(str.getBytes(StandardCharsets.UTF_8));
	}

	static String sha256(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			StringBuilder sb = new StringBuilder(64);
			for (byte b : md.digest(data)) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
