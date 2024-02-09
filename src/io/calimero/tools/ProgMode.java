/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2015, 2023 B. Malinowsky

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.medium.TPSettings;
import io.calimero.mgmt.ManagementProceduresImpl;
import io.calimero.mgmt.PropertyAccess;
import io.calimero.mgmt.PropertyClient;

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
	 * <li><code>--local</code> &nbsp;use local device management</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected that directly
	 * communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX individual address shall be unique
	 * in a network, and the subnetwork address (area and line) should be set to match the network configuration.
	 * <p>
	 * Supported commands (the device address is not needed with local device management):
	 * <ul>
	 * <li><code>on</code> <i>device address</i> &nbsp; switch programming mode on</li>
	 * <li><code>off</code> <i>device address</i> &nbsp; switch programming mode off</li>
	 * </ul>
	 *
	 * @param args command line arguments for the tool
	 */
	public static void main(final String... args)
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
		try {
			if (options.containsKey("localDevMgmt"))
				knxipServerProgMode();
			else
				progMode();
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

	private void knxipServerProgMode() throws KNXException, InterruptedException {
		try (var devmgmt = Main.newLocalDeviceMgmtIP(options, closed -> {});
			 var pc = new PropertyClient(devmgmt)) {

			final String cmd = (String) options.get("command");
			if ("status".equals(cmd)) {
				final var subnetAddr = pc.getProperty(0, PropertyAccess.PID.SUBNET_ADDRESS, 1, 1);
				final var deviceAddr = pc.getProperty(0, PropertyAccess.PID.DEVICE_ADDRESS, 1, 1);
				final var server = new IndividualAddress(new byte[] { subnetAddr[0], deviceAddr[0] });

				while (true) {
					final var progmode = pc.getProperty(0, PropertyAccess.PID.PROGMODE, 1, 1);
					if (progmode[0] == 1)
						devicesInProgMode(server);
					else
						devicesInProgMode();
					Thread.sleep(3000);
				}
			}
			else {
				final int progmode = "on".equals(cmd) ? 1 : 0;
				pc.setProperty(0, PropertyAccess.PID.PROGMODE, 1, 1, (byte) progmode);
				final var check = pc.getProperty(0, PropertyAccess.PID.PROGMODE, 1, 1);
				out("Programming mode " + (check[0] == 1 ? "active" : "inactive"));
			}
		}
	}

	private void progMode() throws KNXException, InterruptedException {
		try (var link = createLink();
			 var mgmt = new ManagementProceduresImpl(link)) {

			final String cmd = (String) options.get("command");
			if ("status".equals(cmd)) {
				if (!options.containsKey("json"))
					out("Device(s) in programming mode ...", false);
				while (true)
					devicesInProgMode(mgmt.readAddress());
			}
			else {
				final var device = (IndividualAddress) options.get("device");
				mgmt.setProgrammingMode(device, "on".equals(cmd));
			}
		}
	}

	protected void devicesInProgMode(final IndividualAddress... devices)
	{
		if (options.containsKey("json")) {
			record JsonDevices(IndividualAddress... devices) implements Json {}
			out(new JsonDevices(devices).toJson());
			return;
		}
		final String output = devices.length == 0 ? "none"
				: new TreeSet<>(Arrays.asList(devices)).stream().map(Objects::toString).collect(Collectors.joining(", "));
		out("\33[2K\rDevice(s) in programming mode: " + output + "\t\t", false);
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
		options.put("medium", new TPSettings());

		boolean setmode = false;
		for (final var i = List.of(args).iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) ProgMode::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "local", null))
				options.put("localDevMgmt", null);
			else if (arg.equals("on") || arg.equals("off")) {
				options.put("command", arg);
				setmode = true;
			}
			else if (setmode) {
				options.put("device", new IndividualAddress(arg));
				setmode = false;
			}
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				options.put("device", new IndividualAddress(arg));
		}
		// we allow a default usb config where the first found knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("no communication device/host specified");
		if (options.containsKey("ft12") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("--remote option is mandatory with --ft12");
		if (options.containsKey("localDevMgmt")) {
			if (options.containsKey("device"))
				throw new KNXIllegalArgumentException("local device management does not need a KNX device address");
		}
		else if (options.containsKey("command") != options.containsKey("device"))
			throw new KNXIllegalArgumentException("setting programming mode requires mode and KNX device address");
		options.putIfAbsent("command", "status");
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
		joiner.add("Usage: " + tool + " [options] <host|port> [on|off [<device address>]]");
		joiner.add(Main.printCommonOptions());
		joiner.add("  --local                    use local device management");
		joiner.add(Main.printSecureOptions());
		joiner.add("Commands (local device management does not need a device address):");
		joiner.add("  on  [<device address>]     switch programming mode on");
		joiner.add("  off [<device address>]     switch programming mode off");
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

	private static void out(final CharSequence s, final boolean newline) {
		if (newline)
			System.out.println(s);
		else {
			System.out.print(s);
			System.out.flush();
		}
	}
}
