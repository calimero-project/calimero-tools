/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2013, 2015 B. Malinowsky

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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.KNXNetworkLinkUsb;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.mgmt.ManagementProcedures;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;
import tuwien.auto.calimero.tools.Main.ShutdownHandler;

/**
 * A tool to list existing (and responsive) KNX devices on a KNX network, or checking whether a
 * specific KNX individual address is currently assigned to a KNX device.
 * <p>
 * ScanDevices is a {@link Runnable} tool implementation allowing a user to scan for KNX devices in
 * a KNX network. It provides a list of the assigned KNX device addresses in the scanned
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

	// TODO for use as runnable, we should get rid of the static tool logger
	private static Logger out;

	private final Map<String, Object> options = new HashMap<>();

	/**
	 * Entry point for running ScanDevices.
	 * <p>
	 * Syntax: ScanDevices [options] &lt;host|port&gt; &lt;area.line[.device]&gt;
	 * <p>
	 * The area and line are expected as numbers in the range [0..0x0F]; the (optional) device
	 * address part is in the range [0..0x0FF]. Accepted are decimal, hexadecimal (0x), or octal (0)
	 * representations.<br>
	 * To show usage message of the tool on the console, supply the command line option --help (or
	 * -h).<br>
	 * Command line options are treated case sensitive. Available options for connecting to the KNX
	 * device in question:
	 * <ul>
	 * <li>no arguments: only show short description and version info</li>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--verbose -v</code> enable verbose status output</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>port</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>port</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--routing</code> use KNXnet/IP routing</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|p132|rf] (defaults to
	 * tp1)</li>
	 * </ul>
	 *
	 * @param args command line options for running the device info tool
	 */
	public static void main(final String[] args)
	{
		setLogVerbosity(Arrays.asList(args));
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
		KNXNetworkLink link = null;
		IndividualAddress[] found = new IndividualAddress[0];
		try {
			// TODO onCompletion prints '0 network devices found' on showing help etc., either
			// suppress that or move the following out of the try (skipping onCompletion altogether)
			if (options.isEmpty()) {
				LogService.logAlways(out, " - Determine existing KNX devices on a KNX subnetwork");
				showVersion();
				LogService.logAlways(out, "Type --help for help message");
				return;
			}
			if (options.containsKey("help")) {
				showUsage();
				return;
			}
			if (options.containsKey("version")) {
				showVersion();
				return;
			}

			link = createLink();
			final ManagementProcedures mp = new ManagementProceduresImpl(link);

			final String[] range = ((String) options.get("range")).split("\\.");
			final int area = Integer.decode(range[0]).intValue();
			final int line = Integer.decode(range[1]).intValue();
			if (range.length == 3) {
				final int device = Integer.decode(range[2]).intValue();
				final IndividualAddress addr = new IndividualAddress(area, line, device);
				if (mp.isAddressOccupied(addr))
					found = new IndividualAddress[] { addr };
			}
			else {
				LogService.logAlways(out, "start scan of " + area + "." + line + ".[0..255] ...");
				// this call is interruptible (via exception), in which case we won't get any
				// result, even though some devices might have answered already
				// ??? think whether to refactor scanning into interruptible with partial result set
				found = mp.scanNetworkDevices(area, line);
			}
		}
		catch (final KNXException e) {
			thrown = e;
		}
		catch (final RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		finally {
			if (link != null)
				link.close();
			onCompletion(thrown, canceled, found);
		}
	}

	/**
	 * Creates the KNX network link to access the network specified in <code>options</code>.
	 * <p>
	 *
	 * @return the KNX network link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkLink createLink() throws KNXException, InterruptedException
	{
		final String host = (String) options.get("host");
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (options.containsKey("serial")) {
			// create FT1.2 network link
			try {
				return new KNXNetworkLinkFT12(Integer.parseInt(host), medium);
			}
			catch (final NumberFormatException e) {
				return new KNXNetworkLinkFT12(host, medium);
			}
		}
		if (options.containsKey("usb")) {
			// create USB network link
			return new KNXNetworkLinkUsb(host, medium);
		}
		// create local and remote socket address for network link
		final InetSocketAddress local = Main.createLocalSocket(
				(InetAddress) options.get("localhost"), (Integer) options.get("localport"));
		final InetSocketAddress remote = new InetSocketAddress(Main.parseHost(host),
				((Integer) options.get("port")).intValue());
		final int mode = options.containsKey("routing") ? KNXNetworkLinkIP.ROUTING
				: KNXNetworkLinkIP.TUNNELING;
		return new KNXNetworkLinkIP(mode, local, remote, options.containsKey("nat"), medium);
	}

	/**
	 * Called by this tool on completion.
	 * <p>
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 * @param devices array of KNX devices found by the scan, array size might be 0
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled,
		final IndividualAddress[] devices)
	{
		if (canceled)
			out.info("scanning for devices canceled");
		if (thrown != null)
			out.error("completed", thrown);

		System.out.println("found " + devices.length + " network devices");
		Arrays.stream(devices).forEach(System.out::println);
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
		options.put("port", new Integer(KNXnetIPConnection.DEFAULT_PORT));
		options.put("medium", TPSettings.TP1);

		int i = 0;
		for (; i < args.length; i++) {
			final String arg = args[i];
			if (Main.isOption(arg, "help", "h")) {
				options.put("help", null);
				return;
			}
			if (Main.isOption(arg, "version", null)) {
				options.put("version", null);
				return;
			}
			if (Main.isOption(arg, "verbose", "v"))
				options.put("verbose", null);
			else if (Main.isOption(arg, "localhost", null))
				options.put("localhost", Main.parseHost(args[++i]));
			else if (Main.isOption(arg, "localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "port", "p"))
				options.put("port", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "nat", "n"))
				options.put("nat", null);
			else if (Main.isOption(arg, "routing", null))
				options.put("routing", null);
			else if (Main.isOption(arg, "serial", "s"))
				options.put("serial", null);
			else if (Main.isOption(arg, "usb", "u"))
				options.put("usb", null);
			else if (Main.isOption(arg, "medium", "m"))
				options.put("medium", Main.getMedium(args[++i]));
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
	}

	// a helper in case slf4j simple logger is used
	private static void setLogVerbosity(final List<String> args)
	{
		// TODO problem: this overrules the log level from a simplelogger.properties file!!
		final String simpleLoggerLogLevel = "org.slf4j.simpleLogger.defaultLogLevel";
		if (!System.getProperties().containsKey(simpleLoggerLogLevel)) {
			final String lvl = args.contains("-v") || args.contains("--verbose") ? "trace" : "warn";
			System.setProperty(simpleLoggerLogLevel, lvl);
		}
		out = LogService.getLogger("calimero.tools");
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		// ??? accept several line requests
		sb.append("usage: ").append(tool).append(" [options] <host|port> <area.line[.device]>")
				.append(sep);
		sb.append("options:").append(sep);
		sb.append(" --help -h                show this help message").append(sep);
		sb.append(" --version                show tool/library version and exit").append(sep);
		sb.append(" --verbose -v             enable verbose status output").append(sep);
		sb.append(" --localhost <id>         local IP/host name").append(sep);
		sb.append(" --localport <port>       local UDP port (default system assigned)").append(sep);
		sb.append(" --port -p <port>         UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append(" --nat -n                 enable Network Address Translation").append(sep);
		sb.append(" --serial -s              use FT1.2 serial communication").append(sep);
		sb.append(" --usb -u                 use KNX USB communication").append(sep);
		sb.append(" --routing                use KNXnet/IP routing").append(sep);
		sb.append(" --medium -m <id>         KNX medium [tp1|p110|p132|rf] (default tp1)")
				.append(sep);
		sb.append("The area and line are given as numbers in the range [0..0x0F], e.g., 3.1")
				.append(sep);
		sb.append("The (optional) device address part is in the range [0..0x0FF]").append(sep);
		LogService.logAlways(out, sb.toString());
	}

	private static void showVersion()
	{
		LogService.logAlways(out, Settings.getLibraryHeader(false));
	}
}
