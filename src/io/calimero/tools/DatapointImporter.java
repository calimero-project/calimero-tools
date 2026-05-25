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
*/

package io.calimero.tools;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collector;

import io.calimero.GroupAddress;
import io.calimero.GroupAddress.Presentation;
import io.calimero.KNXFormatException;
import io.calimero.datapoint.Datapoint;
import io.calimero.datapoint.DatapointMap;
import io.calimero.datapoint.StateDP;
import io.calimero.xml.KNXMLException;
import io.calimero.xml.XmlInputFactory;
import io.calimero.xml.XmlOutputFactory;
import io.calimero.xml.XmlReader;

import static io.calimero.tools.Main.out;

/**
 * Imports datapoint information from a KNX project (.knxproj) or group addresses file (in XML or CSV format) and writes
 * it as Calimero datapoint model in XML format. If no output file is provided, the datapoint model is written to the
 * standard output.
 */
public class DatapointImporter implements Runnable {
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
		try {
			final var datapoints = switch (fileExt(input).toLowerCase(Locale.ROOT)) {
				case ".xml" -> importAddressesFromXml();
				case ".knxproj" -> importAddressesFromKnxproj();
				default -> importAddressesFromCsv();
			};
			if (datapoints.stream().allMatch(map -> map.getDatapoints().isEmpty())) {
				out().log(Level.DEBUG, "no datapoints found");
				return;
			}

			if (freeStyle)
				GroupAddress.addressStyle(Presentation.FreeStyle);

			final var fac = XmlOutputFactory.newInstance();
			for (int i = 0; i < datapoints.size(); i++) {
				final var writer = switch (output) {
					case null -> fac.createXMLStreamWriter(System.out);
					default   -> fac.createXMLWriter(switch (datapoints.size()) {
						case 1  -> output;
						default -> { // insert numerator before .ext if > 1 installations
							final String ext = fileExt(output);
							yield output.substring(0, output.length() - ext.length()) + "-" + i + ext;
						}
					});
				};
				try (writer) {
					datapoints.get(i).save(writer);
				}
			}
		}
		catch (IOException | KNXMLException e) {
			out().log(Level.ERROR, "error importing '" + input + "'", e);
		}
	}

	private List<DatapointMap<StateDP>> importAddressesFromKnxproj() throws IOException {
		final var project = KnxProject.from(Path.of(input));
		if (project.encrypted()) {
			if (projectPwd.length == 0) {
				System.err.println("project file is encrypted, password required!");
				return List.of();
			}
			project.decrypt(projectPwd);
		}
		return project.installations().stream().map(KnxProject.Installation::datapoints).toList();
	}

	private List<DatapointMap<Datapoint>> importAddressesFromCsv() throws IOException {
		try (var lines = Files.lines(Path.of(input), StandardCharsets.UTF_8)) {
			final var map = lines.map(line -> line.split("\"[\t;]\""))
					.map(DatapointImporter::parseDatapoint)
					.flatMap(Optional::stream)
					.collect(Collector.of(DatapointMap::new, DatapointMap::add,
							(left, right) -> { throw new UnsupportedOperationException(); }));
			return List.of(map);
		}
	}

	private List<DatapointMap<StateDP>> importAddressesFromXml() {
		final String exportElement = "GroupAddress-Export";
		final String addressElement = "GroupAddress";

		final var datapoints = new DatapointMap<StateDP>();
		try (var reader = XmlInputFactory.newInstance().createXMLReader(input)) {
			if (reader.getEventType() != XmlReader.START_ELEMENT)
				reader.nextTag();
			if (reader.getEventType() != XmlReader.START_ELEMENT || !reader.getLocalName().equals(exportElement))
				throw new KNXMLException(exportElement + " element not found", reader);
			while (reader.next() != XmlReader.END_DOCUMENT) {
				if (reader.getEventType() == XmlReader.START_ELEMENT && reader.getLocalName().equals(addressElement))
					parseDatapoint(reader).ifPresent(datapoints::add);
			}
		}
		return List.of(datapoints);
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
			out().log(Level.TRACE, "import " + datapoint);
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
			main = Integer.parseUnsignedInt(mainSub[0]);
		if (mainSub.length == 2)
			dptId = String.format("%d.%03d", main, Integer.parseUnsignedInt(mainSub[1]));
		return new Object[] { main, dptId };
	}

	private static String fileExt(final String path) {
		if (path.length() > 1) {
			final int last = path.lastIndexOf('.');
			if (last > 0 && last < path.length() - 1)
				return path.substring(last);
		}
		return "";
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
}
