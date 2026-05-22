/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2019, 2026 B. Malinowsky

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.calimero.GroupAddress;
import io.calimero.KNXFormatException;
import io.calimero.KnxRuntimeException;
import io.calimero.datapoint.DatapointMap;
import io.calimero.datapoint.StateDP;
import io.calimero.log.LogService;
import io.calimero.secure.KnxSecureException;
import io.calimero.xml.KNXMLException;
import io.calimero.xml.XmlInputFactory;
import io.calimero.xml.XmlReader;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * Represents a KNX project resource.
 */
public final class KnxProject {
	private static final String knxproj = ".knxproj";
	private static final String projectNamespace = "http://knx.org/xml/project/20";

	private static final Logger logger = LogService.getLogger(MethodHandles.lookup().lookupClass());

	private final Path project;
	private final String name;
	private volatile DatapointMap<StateDP> datapoints; // assigned once with non-null

	public static List<Path> list(final Path dir) throws IOException {
		try (var list = Files.list(dir)) {
			return list.filter(path -> path.toString().endsWith(knxproj)).toList();
		}
	}

	public static KnxProject from(final Path project) throws IOException {
		try {
			Path root = project;
			// extract zipped project
			if (project.toString().endsWith(knxproj)) {
				final var extractDir = project.getFileName().toString().replace(knxproj, "");
				Path to = project.resolveSibling(extractDir);
				if (!Files.isWritable(to.toAbsolutePath().getParent()))
					to = Files.createTempDirectory(project.getFileName().toString());
				unzip(project, to);
				root = to;
			}

			try (var stream = Files.list(root)) {
				final var name = root.getFileName().toString();

				final var path = stream.filter(p -> p.getFileName().toString().startsWith("P-"))
						.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip")).findFirst()
						.orElseThrow(() -> new FileNotFoundException("KNX project does not contain project folder"));
				root = path;

				DatapointMap<StateDP> datapoints = null;
				// check password protected project
				if (path.toString().endsWith(".zip") && isProjectEncrypted(path))
					; // delay parsing until decryption
				else if (!Files.isDirectory(path))
					throw new FileNotFoundException("no root directory found for parsing");
				else
					datapoints = resolveAndParse(path);

				return new KnxProject(root, name, datapoints);
			}
		}
		catch (final KnxRuntimeException e) {
			throw e;
		}
		catch (final RuntimeException e) {
			throw new KnxRuntimeException("failed to load project '" + project + "'", e);
		}
	}

	private KnxProject(final Path project, final String name, final DatapointMap<StateDP> datapoints) {
		this.project = project;
		this.name = name;
		this.datapoints = datapoints;
	}

	public String name() { return name; }

	public URI uri() { return project.getParent().toUri(); }

	public boolean encrypted() { return datapoints == null; }

	public void decrypt(final char[] projectPassword) throws IOException {
		if (datapoints != null)
			return;

		final var to = Path.of(project.toString().replace(".zip", ""));
		try {
			unzip(project, to, projectPassword);
			datapoints = resolveAndParse(to);
		}
		catch (final KnxRuntimeException e) {
			throw e;
		}
		catch (final RuntimeException e) {
			throw new KnxRuntimeException("failed to load project '" + to + "'", e);
		}
		finally {
			Arrays.fill(projectPassword, (char) 0);
			deleteRecursively(to);
		}
	}

	public DatapointMap<StateDP> datapoints() {
		if (encrypted())
			throw new KnxSecureException("project '" + this + "' is encrypted");
		return datapoints;
	}

	@Override
	public String toString() { return name(); }

	private static void unzip(final Path project, final Path to) throws IOException {
		logger.log(Level.DEBUG, "unzip project into directory {0}", to);
		try (var zis = new ZipInputStream(Files.newInputStream(project))) {
			for (var entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
				final var target = createPath(to, entry);
				if (!entry.isDirectory()) {
					logger.log(Level.TRACE, "extract {0}", entry.getName());
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
			if (fileHeader == null)
				throw new FileNotFoundException("missing required file '0.xml' in archive '" + protectedFile + "'");
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

	private static DatapointMap<StateDP> resolveAndParse(final Path path) {
		final Path file = path.resolve("0.xml");
		try {
			return parse(file);
		}
		catch (final KNXMLException e) {
			throw e;
		}
		catch (final KNXFormatException | RuntimeException e) {
			throw new KNXMLException("failed to parse project file '" + file + "'", e);
		}
	}

	private static DatapointMap<StateDP> parse(final Path path) throws KNXFormatException {
		try (var reader = XmlInputFactory.newInstance().createXMLReader(path.toString())) {
			reader.nextTag();
			final var namespace = reader.getNamespaceURI();
			if (!projectNamespace.equals(namespace))
				throw new KNXMLException("project '" + path + "' with unsupported namespace '" + namespace + "'");

			requireElement("Project", reader, path);
			requireElement("Installations", reader, path);
			requireElement("Installation", reader, path);

			final var datapoints = new DatapointMap<StateDP>();
			boolean inInstallation = false;
			boolean inGroupAddresses = false;
			for (; reader.getEventType() != XmlReader.END_DOCUMENT; reader.next()) {
				switch (reader.getEventType()) {
					case XmlReader.START_ELEMENT -> {
						switch (reader.getLocalName()) {
							case "Installation" -> {
								inInstallation = true;
								final var instName = reader.getAttributeValue(null, "Name");
								logger.log(Level.DEBUG, "read installation ''{0}''", instName);
							}
							case "GroupAddresses" -> inGroupAddresses = inInstallation;
							case "GroupAddress" -> {
								if (inGroupAddresses) {
									final var address = new GroupAddress(reader.getAttributeValue(null, "Address"));
									final var dpName = attribute(reader, "Name", "");
									final var dpt = parseDpt(attribute(reader, "DatapointType", ""));

									final var dp = new StateDP(address, dpName, (int) dpt[0], (String) dpt[1]);
									datapoints.add(dp);
								}
							}
						}
					}
					case XmlReader.END_ELEMENT -> {
						switch (reader.getLocalName()) {
							case "Installation" -> inInstallation = false;
							case "GroupAddresses" -> {
								inGroupAddresses = false;
								logger.log(Level.DEBUG, "found {0} group addresses", datapoints.getDatapoints().size());
							}
						}
					}
				}
			}
			return datapoints;
		}
	}

	private static void requireElement(String name, XmlReader reader, Path path) {
		reader.nextTag();
		if (!name.equals(reader.getLocalName()))
			throw new KNXMLException("project '" + path + "' requires '" + name + "' element");
	}

	private static String attribute(final XmlReader reader, final String name, final String defaultValue) {
		return Optional.ofNullable(reader.getAttributeValue(null, name)).orElse(defaultValue);
	}

	private static Object[] parseDpt(final String dpt) {
		final var mainSub = dpt.replace("DPT-", "").replace("DPST-", "").split("-", 0);
		int main = 0;
		var dptId = "";
		if (mainSub.length >= 1 && !mainSub[0].isEmpty())
			main = Integer.parseUnsignedInt(mainSub[0]);
		if (mainSub.length == 2)
			dptId = String.format("%d.%03d", main, Integer.parseUnsignedInt(mainSub[1]));
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
