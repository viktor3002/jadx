package jadx.permdb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.Nullable;

/**
 * Read single entries of a large remote zip file with HTTP range requests.
 * <br>
 * Only the central directory and the requested entries are downloaded (an SDK platform zip has
 * ~65 MB, the needed entries less than 10 MB). Extracted entries are cached by archive checksum,
 * so they are downloaded only once.
 */
final class RemoteZip {

	interface Source {
		long length() throws IOException;

		byte[] read(long start, int length) throws IOException;
	}

	private static final int EOCD_SIG = 0x06054b50;
	private static final int ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;
	private static final int ZIP64_EOCD_SIG = 0x06064b50;
	private static final int CEN_SIG = 0x02014b50;
	private static final int LOC_SIG = 0x04034b50;
	private static final int EOCD_SEARCH = 0xFFFF + 22;

	private static final class Entry {
		final String name;
		final int method;
		final long compressedSize;
		final long size;
		final long localHeaderOffset;

		Entry(String name, int method, long compressedSize, long size, long localHeaderOffset) {
			this.name = name;
			this.method = method;
			this.compressedSize = compressedSize;
			this.size = size;
			this.localHeaderOffset = localHeaderOffset;
		}
	}

	private final Source source;
	private final @Nullable Path cacheDir;
	private @Nullable List<Entry> entries;

	/**
	 * @param cacheDir directory for extracted entries, must be unique for the archive content
	 *                 (e.g. contain its checksum)
	 */
	RemoteZip(Source source, @Nullable Path cacheDir) {
		this.source = source;
		this.cacheDir = cacheDir;
	}

	static RemoteZip http(Http http, String url, String checksum) {
		Source source = new Source() {
			private long length = -1;

			@Override
			public long length() throws IOException {
				if (length == -1) {
					length = http.getContentLength(url);
				}
				return length;
			}

			@Override
			public byte[] read(long start, int len) throws IOException {
				return http.getRange(url, start, len);
			}
		};
		return new RemoteZip(source, http.getCacheDir().resolve("zip").resolve(checksum));
	}

	static RemoteZip bytes(byte[] data) {
		return new RemoteZip(new Source() {
			@Override
			public long length() {
				return data.length;
			}

			@Override
			public byte[] read(long start, int length) {
				byte[] result = new byte[length];
				System.arraycopy(data, (int) start, result, 0, length);
				return result;
			}
		}, null);
	}

	/**
	 * @return content of the first entry whose name ends with {@code nameSuffix}, or null if not
	 *         found
	 */
	@Nullable
	byte[] read(String nameSuffix) throws IOException {
		Path cached = null;
		Path missing = null;
		if (cacheDir != null) {
			String fileName = nameSuffix.replaceAll("[^A-Za-z0-9._-]", "_");
			cached = cacheDir.resolve(fileName);
			missing = cacheDir.resolve(fileName + ".missing");
			if (Files.isRegularFile(cached)) {
				return Files.readAllBytes(cached);
			}
			if (Files.isRegularFile(missing)) {
				return null;
			}
		}
		Entry entry = findEntry(nameSuffix);
		byte[] data = entry == null ? null : extract(entry);
		if (cacheDir != null) {
			Files.createDirectories(cacheDir);
			if (data == null) {
				Files.write(missing, new byte[0]);
			} else {
				Files.write(cached, data);
			}
		}
		return data;
	}

	private @Nullable Entry findEntry(String nameSuffix) throws IOException {
		for (Entry entry : getEntries()) {
			if (entry.name.endsWith(nameSuffix)) {
				return entry;
			}
		}
		return null;
	}

	private List<Entry> getEntries() throws IOException {
		if (entries == null) {
			entries = readCentralDirectory();
		}
		return entries;
	}

	private List<Entry> readCentralDirectory() throws IOException {
		long length = source.length();
		int tailLen = (int) Math.min(length, EOCD_SEARCH);
		long tailStart = length - tailLen;
		ByteBuffer tail = le(source.read(tailStart, tailLen));
		int eocd = -1;
		for (int i = tailLen - 22; i >= 0; i--) {
			if (tail.getInt(i) == EOCD_SIG) {
				eocd = i;
				break;
			}
		}
		if (eocd == -1) {
			throw new IOException("Zip end of central directory not found");
		}
		long cdSize = Integer.toUnsignedLong(tail.getInt(eocd + 12));
		long cdOffset = Integer.toUnsignedLong(tail.getInt(eocd + 16));
		if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
			int locator = eocd - 20;
			if (locator < 0 || tail.getInt(locator) != ZIP64_EOCD_LOCATOR_SIG) {
				throw new IOException("Zip64 end of central directory locator not found");
			}
			long zip64Eocd = tail.getLong(locator + 8);
			ByteBuffer z = le(source.read(zip64Eocd, 56));
			if (z.getInt(0) != ZIP64_EOCD_SIG) {
				throw new IOException("Zip64 end of central directory not found");
			}
			cdSize = z.getLong(40);
			cdOffset = z.getLong(48);
		}
		ByteBuffer cd = le(source.read(cdOffset, Math.toIntExact(cdSize)));
		List<Entry> list = new ArrayList<>();
		int pos = 0;
		while (pos + 46 <= cdSize && cd.getInt(pos) == CEN_SIG) {
			int method = Short.toUnsignedInt(cd.getShort(pos + 10));
			long compressedSize = Integer.toUnsignedLong(cd.getInt(pos + 20));
			long size = Integer.toUnsignedLong(cd.getInt(pos + 24));
			int nameLen = Short.toUnsignedInt(cd.getShort(pos + 28));
			int extraLen = Short.toUnsignedInt(cd.getShort(pos + 30));
			int commentLen = Short.toUnsignedInt(cd.getShort(pos + 32));
			long localOffset = Integer.toUnsignedLong(cd.getInt(pos + 42));
			byte[] nameBytes = new byte[nameLen];
			cd.position(pos + 46);
			cd.get(nameBytes);
			String name = new String(nameBytes, StandardCharsets.UTF_8);
			if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
				// zip64 extended information extra field
				int extra = pos + 46 + nameLen;
				int extraEnd = extra + extraLen;
				while (extra + 4 <= extraEnd) {
					int id = Short.toUnsignedInt(cd.getShort(extra));
					int len = Short.toUnsignedInt(cd.getShort(extra + 2));
					if (id == 0x0001) {
						int p = extra + 4;
						if (size == 0xFFFFFFFFL) {
							size = cd.getLong(p);
							p += 8;
						}
						if (compressedSize == 0xFFFFFFFFL) {
							compressedSize = cd.getLong(p);
							p += 8;
						}
						if (localOffset == 0xFFFFFFFFL) {
							localOffset = cd.getLong(p);
						}
						break;
					}
					extra += 4 + len;
				}
			}
			list.add(new Entry(name, method, compressedSize, size, localOffset));
			pos += 46 + nameLen + extraLen + commentLen;
		}
		return list;
	}

	private byte[] extract(Entry entry) throws IOException {
		ByteBuffer loc = le(source.read(entry.localHeaderOffset, 30));
		if (loc.getInt(0) != LOC_SIG) {
			throw new IOException("Bad local header for zip entry " + entry.name);
		}
		int nameLen = Short.toUnsignedInt(loc.getShort(26));
		int extraLen = Short.toUnsignedInt(loc.getShort(28));
		long dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen;
		byte[] raw = source.read(dataStart, Math.toIntExact(entry.compressedSize));
		switch (entry.method) {
			case 0:
				return raw;
			case 8:
				return inflate(raw, entry);
			default:
				throw new IOException("Unsupported compression method " + entry.method + " for zip entry " + entry.name);
		}
	}

	private static byte[] inflate(byte[] raw, Entry entry) throws IOException {
		Inflater inflater = new Inflater(true);
		try {
			inflater.setInput(raw);
			ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(entry.size, Integer.MAX_VALUE - 8));
			byte[] buf = new byte[64 * 1024];
			while (!inflater.finished()) {
				int n = inflater.inflate(buf);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				out.write(buf, 0, n);
			}
			if (out.size() != entry.size) {
				throw new IOException("Unexpected size of zip entry " + entry.name + ": " + out.size() + ", expected " + entry.size);
			}
			return out.toByteArray();
		} catch (DataFormatException e) {
			throw new IOException("Failed to inflate zip entry " + entry.name, e);
		} finally {
			inflater.end();
		}
	}

	private static ByteBuffer le(byte[] data) {
		return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
	}
}
