/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2019, 2020 B. Malinowsky

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

package tuwien.auto.calimero.tools;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.StringJoiner;

import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.GroupAddress.Presentation;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.datapoint.DatapointMap;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.xml.KNXMLException;
import tuwien.auto.calimero.xml.XmlInputFactory;
import tuwien.auto.calimero.xml.XmlOutputFactory;
import tuwien.auto.calimero.xml.XmlReader;

/** Imports ETS group addresses in XML or CSV format and stores them as Calimero datapoint model in XML format. */
public class GroupAddressImporter implements Runnable {

	private final DatapointMap<StateDP> datapoints = new DatapointMap<>();

	private final String input;
	private final String output;
	private boolean freeStyle;

	/**
	 * Entry point for running importer.
	 * Command line options are treated case sensitive. Available options are:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
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
		else if (args.length < 2)
			showToolInfo();
		else
			new GroupAddressImporter(args).run();
	}

	public GroupAddressImporter(final String... args) {
		int i = 0;
		if ("--freestyle".equals(args[0])) {
			freeStyle = true;
			i++;
		}
		input = args[i++];
		output = args[i++];
	}

	@Override
	public void run() {
		final String ext = input.substring(input.lastIndexOf('.') + 1);
		if (ext.equalsIgnoreCase("xml"))
			importAddressesFromXml(input);
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

		try (var writer = XmlOutputFactory.newInstance().createXMLWriter(output)) {
			datapoints.save(writer);
		}
	}

	private void importAddressesFromCsv(final String file) throws IOException {
		Files.lines(Path.of(file), StandardCharsets.UTF_8).map(line -> line.split("\"[\t;]\""))
				.map(GroupAddressImporter::parseDatapoint).flatMap(Optional::stream).forEach(datapoints::add);
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
		out(name + " - Import ETS group addresses from csv or xml format");
		Main.showVersion();
		out("Use --help for help message");
	}

	private static void showUsage() {
		final var name = MethodHandles.lookup().lookupClass().getSimpleName();
		final var joiner = new StringJoiner(System.getProperty("line.separator"));
		joiner.add("Usage: " + name + " [options] <group addresses file [.xml|csv]> <output file (xml)>");
		joiner.add("Options:");
		joiner.add("  -h --help                  show this help and exit");
		joiner.add("  --version                  show tool/library version and exit");
		joiner.add("  --freestyle                use unformatted KNX address presentation in the output");
		System.out.println(joiner);
	}

	private static void out(final Object o) {
		System.out.println(o);
	}
}
