/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2019, 2021 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package io.calimero.tools;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import io.calimero.GroupAddress;
import io.calimero.KNXFormatException;
import io.calimero.datapoint.DatapointMap;
import io.calimero.datapoint.StateDP;
import io.calimero.log.LogService;
import io.calimero.secure.KnxSecureException;
import io.calimero.xml.KNXMLException;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * Represents a KNX project resource.
 */
public final class KnxProject {
	private static final String knxproj = ".knxproj";

	private final Path project;
	private final String name;
	private volatile Document document; // assigned once with non-null

	public static List<Path> list(final Path dir) throws IOException {
		try (var list = Files.list(dir)) {
			return list.filter(path -> path.toString().endsWith(knxproj)).collect(Collectors.toList());
		}
	}

	public static KnxProject from(final Path project) {
		try {
			Path root = project;
			// extract zipped project
			if (project.toString().endsWith(knxproj)) {
				final var extractDir = project.getFileName().toString().replace(knxproj, "");
				final Path to = project.resolveSibling(extractDir);
				unzip(project, to);
				root = to;
			}

			try (var stream = Files.list(root)) {
				final var name = root.getFileName().toString();

				final var path = stream.filter(p -> p.getFileName().toString().startsWith("P-"))
						.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip")).findFirst()
						.orElseThrow(() -> new FileNotFoundException("KNX project does not contain project folder"));
				root = path;

				Document document = null;
				// check password protected project
				if (path.toString().endsWith(".zip") && isProjectEncrypted(path))
					; // delay parsing until decryption
				else if (!Files.isDirectory(path))
					throw new FileNotFoundException("no root directory found for parsing");
				else
					document = parse(path);

				return new KnxProject(root, name, document);
			}
		}
		catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (final Throwable e) {
			throw new KNXMLException("parsing " + project, e);
		}
	}

	private KnxProject(final Path project, final String name, final Document document) {
		this.project = project;
		this.name = name;
		this.document = document;
	}

	public String name() { return name; }

	public URI uri() { return project.getParent().toUri(); }

	public boolean encrypted() { return document == null; }

	public void decrypt(final char[] projectPassword) {
		if (document != null)
			return;

		final var to = Path.of(project.toString().replace(".zip", ""));
		try {
			unzip(project, to, projectPassword);
			document = parse(to);
		}
		catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (final Throwable t) {
			throw new KNXMLException("loading encrypted project file \"" + project + "\"", t);
		}
		finally {
			Arrays.fill(projectPassword, (char) 0);
			deleteRecursively(to);
		}
	}

	public DatapointMap<StateDP> datapoints() throws KNXFormatException {
		if (encrypted())
			throw new KnxSecureException("project \"" + this + "\" is encrypted");

		final var datapoints = new DatapointMap<StateDP>();
		final var groupAddresses = document.getElementsByTagName("GroupAddress");
		final int length = groupAddresses.getLength();
		for (int i = 0; i < length; i++) {
			final var node = groupAddresses.item(i);
			final var attributes = node.getAttributes();

//			final var id = attribute(attributes, "Id", "");
			final var address = new GroupAddress(attribute(attributes, "Address", ""));
			final var name = attribute(attributes, "Name", "");
//			final var description = attribute(attributes, "Description", "");
			final var dpt = parseDpt(attribute(attributes, "DatapointType", ""));

			final var dp = new StateDP(address, name, (int) dpt[0], (String) dpt[1]);
			datapoints.add(dp);
		}
		return datapoints;
	}

	@Override
	public String toString() { return name(); }

	private static void unzip(final Path project, final Path to) throws IOException {
		final Logger logger = LogService.getLogger(MethodHandles.lookup().lookupClass());
		logger.log(Level.DEBUG, "unzip project into directory {0}", to);
		try (var zis = new ZipInputStream(Files.newInputStream(project))) {
			for (var entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
				final var target = createPath(to, entry);
				if (!entry.isDirectory()) {
					logger.log(Level.DEBUG, "extract {0}", entry.getName());
					Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static boolean isProjectEncrypted(final Path path) throws IOException {
		try (var zipFile = new ZipFile(path.toString())) {
			return zipFile.isEncrypted();
		}
	}

	private static void unzip(final Path protectedFile, final Path to, final char[] pwd) throws IOException {
		try (var zipFile = new ZipFile(protectedFile.toString())) {
			final var fileHeader = zipFile.getFileHeader("0.xml");
			final var enc = fileHeader.getEncryptionMethod();
			final var key = enc == EncryptionMethod.AES ? createAesKey(pwd) : pwd;
			zipFile.setPassword(key);
			zipFile.extractAll(to.toString());
		}
	}

	private static Path createPath(final Path baseDir, final ZipEntry zipEntry) throws IOException {
		final var path = baseDir.resolve(zipEntry.getName()).normalize();
		Files.createDirectories(path.getParent());
		return path;
	}

	private static void deleteRecursively(final Path path) {
		try (var files = Files.walk(path)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
	}

	private static Document parse(final Path path) throws Exception {
		final Path parse = path.resolve("0.xml");

		final var builderFactory = DocumentBuilderFactory.newInstance();
		final var builder = builderFactory.newDocumentBuilder();
		return builder.parse(parse.toFile());
	}

	private static String attribute(final NamedNodeMap attributes, final String name, final String defaultValue) {
		return Optional.ofNullable(attributes.getNamedItem(name)).map(Node::getNodeValue).orElse(defaultValue);
	}

	private static Object[] parseDpt(final String dpt) {
		final var mainSub = dpt.replace("DPT-", "").replace("DPST-", "").split("-", 0);
		int main = 0;
		var dptId = "";
		if (mainSub.length >= 1 && !mainSub[0].isEmpty())
			main = Integer.parseInt(mainSub[0]);
		if (mainSub.length == 2)
			dptId = String.format("%d.%03d", main, Integer.parseInt(mainSub[1]));
		return new Object[] { main, dptId };
	}

	private static final byte[] zipAesEncryptionSalt = "21.project.ets.knx.org".getBytes(StandardCharsets.UTF_8);

	// ETS6
	private static char[] createAesKey(final char[] pwd) {
		try {
			final byte[] key = deriveKey(pwd, zipAesEncryptionSalt, 65_536, 32);
			return Base64.getEncoder().encodeToString(key).toCharArray();
		}
		catch (InvalidKeyException | NoSuchAlgorithmException e) {
			throw new KnxSecureException("creating AES key for zip decryption", e);
		}
	}

	private static byte[] deriveKey(final char[] pwd, final byte[] salt, final int iterations, final int size)
			throws NoSuchAlgorithmException, InvalidKeyException {
		final var mac = hmac("HmacSHA256", macKey(pwd));

		mac.update(salt);
		final byte[] blockIdx = new byte[] { 0, 0, 0, 1 };
		byte[] input = mac.doFinal(blockIdx);
		final byte[] output = new byte[size];
		for (int i = 0; i < iterations; ++i) {
			for (int s = 0; s < size; ++s)
				output[s] ^= input[s];
			input = mac.doFinal(input);
		}
		return output;
	}

	private static byte[] macKey(final char[] pwd) {
		final var buffer = StandardCharsets.UTF_16LE.encode(CharBuffer.wrap(pwd));
		final int len = buffer.remaining();
		final byte[] macKey = new byte[len];
		buffer.get(macKey);
		buffer.clear().put(new byte[len]);
		return macKey;
	}

	private static Mac hmac(final String algorithm, final byte[] key)
			throws NoSuchAlgorithmException, InvalidKeyException {
		final var mac = Mac.getInstance(algorithm);
		mac.init(new SecretKeySpec(key, algorithm));
		return mac;
	}
}
