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

package tuwien.auto.calimero.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.knxnetip.KNXConnectionClosedException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;

/**
 * Restart performs a basic restart or master reset of a KNX interface or KNX device. The tool supports network access
 * using KNXnet/IP, KNX IP, USB, FT1.2, and TP-UART.
 * <p>
 * Run the tool by using either {@link Restart#main(String[])} or {@link Runnable#run()}.
 */
public class Restart implements Runnable {
	private static final String tool = "Restart";
	private static final String sep = System.getProperty("line.separator");

	private final Map<String, Object> options = new HashMap<>();

	/**
	 * Creates a new Restart object using the supplied arguments.
	 *
	 * @param args options for the tool, see {@link #main(String[])}
	 * @throws KNXIllegalArgumentException on missing or wrong formatted option value
	 */
	public Restart(final String[] args) {
		try {
			parseOptions(args);
		}
		catch (final KNXIllegalArgumentException e) {
			throw e;
		}
		catch (KNXFormatException | RuntimeException e) {
			throw new KNXIllegalArgumentException(e.getMessage(), e);
		}
	}

	/**
	 * Entry point for running this tool from the command line. A communication device, host, or port identifier has to
	 * be supplied to specify the endpoint for KNX network access.<br>
	 * To show the usage message of this tool, use the command line option --help (or -h).<br>
	 * Command line options are treated case sensitive. Available options are (see below for restart type options):
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|rf|knxip] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local endpoint</li>
	 * <li><code>--yes -y</code> &nbsp;automatic yes to reset confirmation</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected that directly
	 * communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX individual address shall be unique
	 * in a network, and the subnetwork address (area and line) should be set to match the network configuration.
	 * <p>
	 * The supported restart types are (select at most one):
	 * <ul>
	 * <li><code>--basic</code> &nbsp;basic restart without confirmation [default]</li>
	 * <li><code>--confirmed</code> &nbsp;basic restart with confirmation</li>
	 * <li><code>--factory-reset</code> &nbsp;factory reset (used with channel)</li>
	 * <li><code>--reset-address</code> &nbsp;reset device address to its default</li>
	 * <li><code>--reset-app</code> &nbsp;application program memory to default application</li>
	 * <li><code>--reset-params</code> &nbsp;reset application parameter memory (used with channel)</li>
	 * <li><code>--reset-links</code> &nbsp;reset links (used with channel)</li>
	 * <li><code>--factory-keep-addr</code> &nbsp;factory reset without resetting device addresses (used with
	 * channel)</li>
	 * </ul>
	 *
	 * @param args command line arguments for the tool
	 */
	public static void main(final String[] args) {
		try {
			new Restart(args).run();
		}
		catch (final Throwable t) {
			out("error parsing arguments (use --help): " + t);
		}
	}

	@Override
	public void run() {
		if (options.containsKey("about")) {
			((Runnable) options.get("about")).run();
			return;
		}

		Exception thrown = null;
		boolean canceled = false;
		final IndividualAddress remote = (IndividualAddress) options.get("device");
		try {
			if (remote != null)
				remoteDeviceRestart(remote);
			else
				localDeviceMgmtReset();
		}
		catch (KNXException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			final var msg = e.getMessage();
			if (msg != null)
				out(msg);
			else
				canceled = true;
		}
		finally {
			onCompletion(thrown, canceled);
		}
	}

	private void remoteDeviceRestart(final IndividualAddress remote) throws KNXException, InterruptedException {
		try (var link = Main.newLink(options);
			 var mgmt = new ManagementClientImpl(link)) {
			final var destination = mgmt.createDestination(remote, false);

			final int restartType = (Integer) options.get("restart-type");
			if (restartType > 0) {
				final boolean yes = options.containsKey("yes") || restartType == 1 || confirm(destination);
				if (yes) {
					final int channel = (Integer) options.getOrDefault("channel", 0);
					final int time = mgmt.restart(destination, restartType, channel);
					System.out.format("restart takes %d seconds%n", time > 0 ? time : 5);
				}
			}
			else
				mgmt.restart(destination);
		}
	}

	private void localDeviceMgmtReset()
			throws KNXConnectionClosedException, KNXTimeoutException, InterruptedException, KNXException {
		try (var mgmt = Main.newLocalDeviceMgmtIP(options, __ -> {})) {
			final int restartType = (Integer) options.get("restart-type");
			if (restartType != 0)
				System.out.println("Using local device management, ignore restart type");
			mgmt.reset();
		}
	}

	private static boolean confirm(final Destination destination) {
		System.out.format("Reset device %s? [y/N] ", destination.getAddress());
		try {
			final String input = new BufferedReader(new InputStreamReader(System.in)).readLine();
			return "yes".equalsIgnoreCase(input) || "y".equalsIgnoreCase(input);
		}
		catch (final IOException e) {
			return false;
		}
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception, <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled) {
		if (canceled)
			out(tool + " got canceled");
		if (thrown != null)
			out(tool + " error", thrown);
	}

	private void parseOptions(final String[] args) throws KNXFormatException {
		if (args.length == 0) {
			options.put("about", (Runnable) Restart::showToolInfo);
			return;
		}
		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", TPSettings.TP1);
		// default subnetwork address for TP1 and unregistered device
		options.put("knx-address", new IndividualAddress(0, 0x02, 0xff));
		options.put("restart-type", 0); // basic restart

		int restartType = -1;
		for (final var i = List.of(args).iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) Restart::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (restartType == -1 && (restartType = restartType(arg)) >= 0) {
				options.put("restart-type", restartType);
				if (restartType == 2 || restartType == 5 || restartType == 6 || restartType == 7)
					options.put("channel", Integer.parseUnsignedInt(i.next()));
			}
			else if (restartType >= 0 && (restartType(arg) >= 0))
				throw new KNXIllegalArgumentException("specify at most 1 restart type");
			else if (Main.isOption(arg, "yes", "y"))
				options.put("yes", null);
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				options.put("device", new IndividualAddress(arg));
		}
		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("no communication device/host specified");
		if (options.containsKey("ft12") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("--remote option is mandatory with --ft12");
		Main.setDomainAddress(options);
	}

	private static int restartType(final String option) {
		final var code = option.length() < 2 ? option : option.substring(2);
		switch (code) {
		case "basic": return 0;
		case "confirmed": return 1;

		case "factory-reset":
		case "reset-factory": return 2;

		case "reset-address": return 3;
		case "reset-app": return 4;
		case "reset-params": return 5;
		case "reset-links": return 6;
		case "factory-keep-addr": return 7;
		default: return -1;
		}
	}

	private static void showToolInfo() {
		out(tool + " - Restart a KNX interface or KNX device");
		Main.showVersion();
		out("Use --help for help message");
	}

	private static void showUsage() {
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port> [<KNX device address>]");
		Main.printCommonOptions(joiner);
		joiner.add("  --yes -y                 automatic yes to reset confirmation");
		joiner.add("If a device address is supplied, the supported restart types are (select at most one):");
		joiner.add("  --basic                  basic restart without confirmation [default]");
		joiner.add("  --confirmed              basic restart with confirmation");
		joiner.add("  --factory-reset          factory reset (used with channel)");
		joiner.add("  --reset-address          reset device address to its default");
		joiner.add("  --reset-app              reset application program memory to default application");
		joiner.add("  --reset-params           reset application parameter memory (used with channel)");
		joiner.add("  --reset-links            reset links (used with channel)");
		joiner.add("  --factory-keep-addr      factory reset without resetting device addresses (used with channel)");
		Main.printSecureOptions(joiner);

		out(joiner.toString());
	}

	private static void out(final CharSequence s, final Throwable... t) {
		if (t.length > 0 && t[0] != null) {
			System.out.print(s + ": ");
			t[0].printStackTrace();
		}
		else
			System.out.println(s);
	}
}
