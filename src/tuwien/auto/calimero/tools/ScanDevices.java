/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2013, 2019 B. Malinowsky

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.ManagementProcedures;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;
import tuwien.auto.calimero.tools.Main.ShutdownHandler;

/**
 * A tool to list existing (and responsive) KNX devices on a KNX network, or checking whether a
 * specific KNX individual address is currently assigned to a KNX device.
 * <p>
 * ScanDevices is a {@link Runnable} tool implementation allowing a user to scan for KNX devices in
 * a KNX network. It provides a list of existing KNX devices in the scanned <code>area</code> or
 * <code>area.line</code> of the network.<br>
 * Alternatively, ScanDevices allows to check whether a specific KNX individual address is currently
 * assigned to a device, i.e, occupied in the KNX network.<br>
 * This tool shows the necessary interaction with the Calimero 2 API for management procedures. The
 * main part of this tool implementation interacts with the type {@link ManagementProcedures} in the
 * library.<br>
 * When running this tool from the terminal, the <code>main</code>- method of this class is invoked,
 * otherwise execute it like a {@link Runnable}.
 * <p>
 * To cancel a running scanning procedure from the terminal, use a user interrupt for termination,
 * for example, <code>^C</code>.<br>
 * When executed in a terminal, the device list, as well as errors and problems during the scan are
 * written to <code>System.out</code>.
 *
 * @author B. Malinowsky
 */
public class ScanDevices implements Runnable
{
	private static final String tool = "ScanDevices";
	private static final String sep = System.getProperty("line.separator");

	private static Logger out = LoggerFactory.getLogger("calimero.tools");

	private final Map<String, Object> options = new HashMap<>();

	/**
	 * Entry point for running ScanDevices.
	 * <p>
	 * Syntax: ScanDevices [options] &lt;host|port&gt; &lt;area[.line[.device]]&gt;
	 * <p>
	 * The area and line are expected as numbers in the range [0..0x0F]; the (optional) device address part is in the
	 * range [0..0x0FF]. Accepted are decimal, hexadecimal (0x), or octal (0) representations.<br>
	 * To show usage message of the tool on the console, supply the command line option --help (or -h).<br>
	 * Command line options are treated case sensitive. Available options for connecting to the KNX device in question:
	 * <ul>
	 * <li>no arguments: only show short description and version info</li>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>port</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>port</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF, defaults to broadcast
	 * domain)</li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local endpoint</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected that directly
	 * communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX individual address shall be unique
	 * in a network, and the subnetwork address (area and line) should be set to match the network configuration.
	 *
	 * @param args command line options for running the device info tool
	 */
	public static void main(final String[] args)
	{
		try {
			final ScanDevices scan = new ScanDevices(args);
			final ShutdownHandler sh = new ShutdownHandler().register();
			scan.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.error("parsing options", t);
		}
	}

	/**
	 * Constructs a new ScanDevices.
	 * <p>
	 *
	 * @param args tool arguments
	 */
	public ScanDevices(final String[] args)
	{
		// read in user-supplied command line options
		try {
			parseOptions(args);
		}
		catch (final KNXIllegalArgumentException e) {
			throw e;
		}
		catch (final RuntimeException e) {
			throw new KNXIllegalArgumentException(e.getMessage(), e);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			if (options.isEmpty()) {
				out(tool + " - Determine existing KNX devices on a KNX subnetwork");
				Main.showVersion();
				out("Type --help for help message");
				return;
			}
			if (options.containsKey("about")) {
				((Runnable) options.get("about")).run();
				return;
			}

			try (KNXNetworkLink link = createLink();
					ManagementProcedures mp = new ManagementProceduresImpl(link)) {

				final String[] range = ((String) options.get("range")).split("\\.", 0);
				final int area = Integer.decode(range[0]).intValue();
				final int[] lines = range.length > 1 ? new int[] { Integer.decode(range[1]).intValue() } : IntStream.range(0, 16).toArray();

				if (range.length == 3) {
					final int device = Integer.decode(range[2]).intValue();
					final IndividualAddress addr = new IndividualAddress(area, lines[0], device);
					if (mp.isAddressOccupied(addr)) {
						onDeviceFound(addr);
					}
				}
				else {
					for (final int line : lines) {
						out("start scan of " + area + "." + line + ".[0..255] ...");
						mp.scanNetworkDevices(area, line, this::onDeviceFound);
					}
				}
			}
		}
		catch (final KNXException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		finally {
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Creates the KNX network link to access the network specified in <code>options</code>.
	 *
	 * @return the KNX network link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkLink createLink() throws KNXException, InterruptedException {
		return Main.newLink(options);
	}

	/**
	 * Called on receiving a device response during the scan.
	 *
	 * @param device device address of the responding device
	 */
	protected void onDeviceFound(final IndividualAddress device)
	{
		System.out.println(device);
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception, <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.info("scanning for devices canceled");
		if (thrown != null)
			out.error("completed", thrown);
	}

	/**
	 * Reads all command line options, and puts those options into the options map.
	 * <p>
	 * Default option values are added; on unknown options, a KNXIllegalArgumentException is thrown.
	 *
	 * @param args array with command line options
	 */
	private void parseOptions(final String[] args)
	{
		if (args.length == 0)
			return;

		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", TPSettings.TP1);
		// default subnetwork address for TP1 and unregistered device
		options.put("knx-address", new IndividualAddress(0, 0x02, 0xff));

		for (final var i = List.of(args).iterator(); i.hasNext(); ) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) ScanDevices::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (!options.containsKey("host"))
				// otherwise add a host key with argument as host
				options.put("host", arg);
			else if (!options.containsKey("range"))
				// otherwise save the range for scanning
				options.put("range", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		if (!options.containsKey("range"))
			throw new KNXIllegalArgumentException("Missing area.line range to scan for devices");
		Main.setDomainAddress(options);
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port> <area[.line[.device]]>");
		joiner.add("The area and line are given as numbers in the range [0..15], e.g., 3.1");
		joiner.add("The (optional) device address part is in the range [0..255], e.g., 1.1.209");
		Main.printCommonOptions(joiner);
		Main.printSecureOptions(joiner);
		out(joiner.toString());
	}

	private static void out(final String s)
	{
		System.out.println(s);
	}
}
