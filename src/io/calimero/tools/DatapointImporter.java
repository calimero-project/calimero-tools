/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2019, 2023 B. Malinowsky

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
*/

package io.calimero.tools;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.StringJoiner;

import io.calimero.GroupAddress;
import io.calimero.GroupAddress.Presentation;
import io.calimero.KNXFormatException;
import io.calimero.datapoint.DatapointMap;
import io.calimero.datapoint.StateDP;
import io.calimero.xml.KNXMLException;
import io.calimero.xml.XmlInputFactory;
import io.calimero.xml.XmlOutputFactory;
import io.calimero.xml.XmlReader;
import io.calimero.xml.XmlWriter;

/**
 * Imports datapoint information from a KNX project (.knxproj) or group addresses file (in XML or CSV format) and writes
 * it as Calimero datapoint model in XML format. If no output file is provided, the datapoint model is written to the
 * standard output.
 */
public class DatapointImporter implements Runnable {

	private final DatapointMap<StateDP> datapoints = new DatapointMap<>();

	private final String input;
	private final String output;
	private boolean freeStyle;
	private char[] projectPwd = {};

	/**
	 * Entry point for running importer.
	 * Command line options are treated case-sensitive. Available options are:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--pwd</code> password for encrypted KNX projects</li>
	 * <li><code>--freestyle</code> use unformatted KNX address presentation in the output</li>
	 * </ul>
	 *
	 * @param args command line options for running this tool
	 */
	public static void main(final String... args) {
		if (args.length == 0) {
			showToolInfo();
			return;
		}
		final var arg = args[0];
		if ("-h".equals(arg) || "--help".equals(arg))
			showUsage();
		else if ("--version".equals(arg))
			Main.showVersion();
		else
			new DatapointImporter(args).run();
	}

	public DatapointImporter(final String... args) {
		int i = 0;
		while (args[i].startsWith("--")) {
			if ("--freestyle".equals(args[i])) {
				freeStyle = true;
				i++;
			}
			else if ("--pwd".equals(args[i])) {
				projectPwd = args[i + 1].toCharArray();
				i += 2;
			}
			else
				break;
		}
		input = args[i++];
		output = args.length > i ? args[i] : null;
	}

	@Override
	public void run() {
		final String ext = input.substring(input.lastIndexOf('.') + 1);
		if (ext.equalsIgnoreCase("xml"))
			importAddressesFromXml(input);
		else if (ext.equals("knxproj")) {
			final var project = KnxProject.from(Path.of(input));
			if (project.encrypted()) {
				if (projectPwd.length == 0) {
					System.err.println("project file is encrypted, password required!");
					return;
				}
				project.decrypt(projectPwd);
			}
			try {
				project.datapoints().getDatapoints().forEach(datapoints::add);
			}
			catch (final KNXFormatException e) {
				e.printStackTrace();
			}
		}
		else {
			try {
				importAddressesFromCsv(input);
			}
			catch (final IOException e) {
				e.printStackTrace();
			}
		}

		if (datapoints.getDatapoints().isEmpty()) {
			out("no datapoints found");
			return;
		}

		if (freeStyle)
			GroupAddress.addressStyle(Presentation.FreeStyle);

		try (var writer = createXmlWriter()) {
			datapoints.save(writer);
		}
	}

	private XmlWriter createXmlWriter() {
		final var fac = XmlOutputFactory.newInstance();
		return output != null ? fac.createXMLWriter(output) : fac.createXMLStreamWriter(System.out);
	}

	private void importAddressesFromCsv(final String file) throws IOException {
		Files.lines(Path.of(file), StandardCharsets.UTF_8).map(line -> line.split("\"[\t;]\""))
				.map(DatapointImporter::parseDatapoint).flatMap(Optional::stream).forEach(datapoints::add);
	}

	private void importAddressesFromXml(final String uri) {
		final String exportElement = "GroupAddress-Export";
		final String addressElement = "GroupAddress";

		try (var reader = XmlInputFactory.newInstance().createXMLReader(uri)) {
			if (reader.getEventType() != XmlReader.START_ELEMENT)
				reader.nextTag();
			if (reader.getEventType() != XmlReader.START_ELEMENT || !reader.getLocalName().equals(exportElement))
				throw new KNXMLException(exportElement + " element not found", reader);
			while (reader.next() != XmlReader.END_DOCUMENT) {
				if (reader.getEventType() == XmlReader.START_ELEMENT && reader.getLocalName().equals(addressElement))
					parseDatapoint(reader).ifPresent(datapoints::add);
			}
		}
	}

	private static Optional<StateDP> parseDatapoint(final XmlReader reader) {
		final var address = reader.getAttributeValue(null, "Address");
		final var name = reader.getAttributeValue(null, "Name");
		final var dpt = Optional.ofNullable(reader.getAttributeValue(null, "DPTs")).orElse("");
		return parseDatapoint(address, name, dpt);
	}

	private static Optional<StateDP> parseDatapoint(final String[] columns) {
		return parseDatapoint(columns[1], columns[0].substring(1), columns[5]);
	}

	private static Optional<StateDP> parseDatapoint(final String address, final String name, final String dpt) {
		try {
			final var group = new GroupAddress(address);
			final var types = parseDpt(dpt);
			final var datapoint = new StateDP(group, name, (int) types[0], (String) types[1]);
			System.out.println("import " + datapoint);
			return Optional.of(datapoint);
		}
		catch (final KNXFormatException e) {
			return Optional.empty();
		}
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

	private static void showToolInfo() {
		final var name = MethodHandles.lookup().lookupClass().getSimpleName();
		out(name + " - Import datapoints from a KNX project (.knxproj) or group addresses file (.xml|.csv)");
		Main.showVersion();
		out("Use --help for help message");
	}

	private static void showUsage() {
		final var name = MethodHandles.lookup().lookupClass().getSimpleName();
		final String usage = """
				Usage: %s [options] <project.knxproj or group addresses file [.xml|.csv]> [<output file (xml)>]
				       if no output file is specified, imported datapoints are written to the standard output
				Options:
				  -h --help                  show this help and exit
				  --version                  show tool/library version and exit
				  --pwd                      password for encrypted KNX projects
				  --freestyle                use unformatted KNX address presentation in the output"""
				.formatted(name);
		out(usage);
	}

	private static void out(final Object o) {
		System.out.println(o);
	}
}
