/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2015, 2019 B. Malinowsky

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.ManagementProcedures;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;

/**
 * ProgMode lists the current KNX devices in programming mode, and allows to set the programming mode of a device. The
 * tool supports network access using KNXnet/IP, KNX IP, USB, FT1.2, and TP-UART.
 * <p>
 * Run the tool by using either {@link ProgMode#main(String[])} or {@link Runnable#run()}.
 */
public class ProgMode implements Runnable
{
	private static final String tool = "ProgMode";
	private static final String sep = System.getProperty("line.separator");

	/** Contains tool options after parsing command line. */
	private final Map<String, Object> options = new HashMap<>();

	/**
	 * Creates a new ProgMode object using the supplied arguments.
	 *
	 * @param args options for the tool, see {@link #main(String[])}
	 * @throws KNXIllegalArgumentException on missing or wrong formatted option value
	 */
	public ProgMode(final String[] args)
	{
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
	 * Entry point for running the ProgMode tool from the command line.
	 * <p>
	 * A communication device, host, or port identifier has to be supplied to specify the endpoint for KNX network
	 * access.<br>
	 * To show the usage message of this tool, use the command line option --help (or -h).<br>
	 * Command line options are treated case sensitive. Available options are:
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
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected that directly
	 * communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX individual address shall be unique
	 * in a network, and the subnetwork address (area and line) should be set to match the network configuration.
	 * <p>
	 * Supported commands:
	 * <ul>
	 * <li><code>on</code> <i>device</i> &nbsp; switch programming mode on</li>
	 * <li><code>off</code> <i>device</i> &nbsp; switch programming mode off</li>
	 * </ul>
	 *
	 * @param args command line arguments for the tool
	 */
	public static void main(final String[] args)
	{
		try {
			new ProgMode(args).run();
		}
		catch (final Throwable t) {
			out("error parsing arguments (use --help): " + t);
		}
	}

	@Override
	public void run()
	{
		if (options.containsKey("about")) {
			((Runnable) options.get("about")).run();
			return;
		}

		Exception thrown = null;
		boolean canceled = false;
		try (KNXNetworkLink link = createLink(); ManagementProcedures mgmt = new ManagementProceduresImpl(link)) {
			final String cmd = (String) options.get("command");
			if ("status".equals(cmd)) {
				System.out.print("Device(s) in programming mode ...");
				System.out.flush();
				while (true)
					devicesInProgMode(mgmt.readAddress());
			}
			else
				mgmt.setProgrammingMode((IndividualAddress) options.get("device"), "on".equals(cmd));
		}
		catch (KNXException | RuntimeException e) {
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

	protected void devicesInProgMode(final IndividualAddress... devices)
	{
		final String output = devices.length == 0 ? "none"
				: new TreeSet<>(Arrays.asList(devices)).stream().map(Objects::toString).collect(Collectors.joining(", "));
		System.out.print("\33[2K\rDevice(s) in programming mode: " + output + "\t\t");
		System.out.flush();
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
			out(tool + " got canceled");
		if (thrown != null)
			out(tool + " error", thrown);
	}

	private KNXNetworkLink createLink() throws KNXException, InterruptedException
	{
		return Main.newLink(options);
	}

	private void parseOptions(final String[] args) throws KNXFormatException
	{
		if (args.length == 0) {
			options.put("about", (Runnable) ProgMode::showToolInfo);
			return;
		}
		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", TPSettings.TP1);
		// default subnetwork address for TP1 and unregistered device
		options.put("knx-address", new IndividualAddress(0, 0x02, 0xff));
		options.put("command", "status");

		boolean setmode = false;
		int i = 0;
		for (; i < args.length; i++) {
			final String arg = args[i];
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) ProgMode::showUsage);
				return;
			}
			if (Main.isOption(arg, "version", null)) {
				options.put("about", (Runnable) Main::showVersion);
				return;
			}
			if (Main.parseCommonOption(args, i, options))
				;
			else if (Main.isOption(arg, "verbose", "v"))
				options.put("verbose", null);
			else if (Main.isOption(arg, "localhost", null))
				options.put("localhost", Main.parseHost(args[++i]));
			else if (Main.isOption(arg, "localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "port", "p"))
				options.put("port", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "nat", "n"))
				options.put("nat", null);
			else if (Main.isOption(arg, "ft12", "f"))
				options.put("ft12", null);
			else if (Main.isOption(arg, "usb", "u"))
				options.put("usb", null);
			else if (Main.isOption(arg, "tpuart", null))
				options.put("tpuart", null);
			else if (Main.isOption(arg, "medium", "m"))
				options.put("medium", Main.getMedium(args[++i]));
			else if (Main.isOption(arg, "domain", null))
				options.put("domain", Long.decode(args[++i]));
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(args[++i]));
			else if (arg.equals("on") || arg.equals("off")) {
				options.put("command", arg);
				setmode = true;
			}
			else if (setmode)
				options.put("device", new IndividualAddress(arg));
			else if (Main.parseSecureOption(args, i, options))
				++i;
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				options.put("device", new IndividualAddress(arg));
		}
		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("no communication device/host specified");
		if (options.containsKey("ft12") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("--remote option is mandatory with --ft12");
		if (setmode != options.containsKey("device"))
			throw new KNXIllegalArgumentException("setting programming mode requires mode and KNX device address");
		Main.setDomainAddress(options);
	}

	private static void showToolInfo()
	{
		out(tool + " - Check/set device(s) in programming mode");
		Main.showVersion();
		out("Use --help for help message");
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port> [on|off <device address>]");
		Main.printCommonOptions(joiner);
		Main.printSecureOptions(joiner);
		joiner.add("Commands:");
		joiner.add("  on  <device address>    switch programming mode on");
		joiner.add("  off <device address>    switch programming mode off");
		out(joiner.toString());
	}

	private static void out(final CharSequence s, final Throwable... t)
	{
		if (t.length > 0 && t[0] != null) {
			System.out.print(s + ": ");
			t[0].printStackTrace();
		}
		else
			System.out.println(s);
	}
}
