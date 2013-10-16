/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2013 B. Malinowsky

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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.PLSettings;
import tuwien.auto.calimero.link.medium.RFSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogLevel;
import tuwien.auto.calimero.log.LogManager;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogStreamWriter;
import tuwien.auto.calimero.log.LogWriter;
import tuwien.auto.calimero.mgmt.ManagementProcedures;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;

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
	private static final String version = "0.4a";
	private static final String sep = System.getProperty("line.separator");

	// TODO for use as runnable, we should get rid of the static tool logger
	private static LogService out = LogManager.getManager().getLogService("tools");
	// terminal writer
	private static LogWriter w;

	private final Map options = new HashMap();

	/**
	 * Entry point for running ScanDevices.
	 * <p>
	 * Syntax: ScanDevices [options] &lt;host|port&gt; &lt;area.line[.device]&gt;
	 * <p>
	 * The area and line are expected as numbers in the range [0..0x0F]; the (optional) device
	 * address part is in the range [0..0x0FF]. Accepted are decimal, hexadecimal (0x), or octal (0)
	 * representations.<br>
	 * To show usage message of the tool on the console, supply the command line option -help (or
	 * -h).<br>
	 * Command line options are treated case sensitive. Available options for connecting to the KNX
	 * device in question:
	 * <ul>
	 * <li>no arguments: only show short description and version info</li>
	 * <li><code>-help -h</code> show help message</li>
	 * <li><code>-version</code> show tool/library version and exit</li>
	 * <li><code>-verbose -v</code> enable verbose status output</li>
	 * <li><code>-localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>-localport</code> <i>port</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>-port -p</code> <i>port</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>-nat -n</code> enable Network Address Translation</li>
	 * <li><code>-serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>-routing</code> use KNXnet/IP routing</li>
	 * <li><code>-medium -m</code> <i>id</i> &nbsp;KNX medium [tp0|tp1|p110|p132|rf] (defaults to
	 * tp1)</li>
	 * </ul>
	 *
	 * @param args command line options for running the device info tool
	 */
	public static void main(final String[] args)
	{
		w = LogStreamWriter.newUnformatted(LogLevel.WARN, System.out, true, false);
		out.addWriter(w);
		try {
			final ScanDevices scan = new ScanDevices(args);
			w.setLogLevel(scan.options.containsKey("verbose") ? LogLevel.TRACE : LogLevel.WARN);
			final ShutdownHandler sh = new ShutdownHandler().register();
			scan.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.log(LogLevel.ERROR, "parsing options", t);
		}
		finally {
			LogManager.getManager().shutdown(true);
		}
	}

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
				out.log(LogLevel.ALWAYS, "A tool for scanning KNX devices in the network", null);
				showVersion();
				out.log(LogLevel.ALWAYS, "type -help for help message", null);
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
			if (w != null && options.containsKey("verbose")) {
				// XXX replace with instance specific name, and preferably access writers via out
				LogManager.getManager().addWriter(link.getName(), w);
				LogManager.getManager().addWriter("MgmtProc", w);
			}

			final String[] range = ((String) options.get("range")).split("\\.");
			final int area = Integer.decode(range[0]).intValue();
			final int line = Integer.decode(range[1]).intValue();
			if (range.length == 3) {
				final int device = Integer.decode(range[2]).intValue();
				final IndividualAddress addr = new IndividualAddress(area, line, device);
				if (mp.isAddressOccupied(addr))
					found = new IndividualAddress[] {addr};
			}
			else {
				out.log(LogLevel.ALWAYS, "start scan of " + area + "." + line + ".[0..255] ...",
						null);
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
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (options.containsKey("serial")) {
			// create FT1.2 network link
			final String p = (String) options.get("serial");
			try {
				return new KNXNetworkLinkFT12(Integer.parseInt(p), medium);
			}
			catch (final NumberFormatException e) {
				return new KNXNetworkLinkFT12(p, medium);
			}
		}
		// create local and remote socket address for network link
		final InetSocketAddress local = createLocalSocket((InetAddress) options.get("localhost"),
				(Integer) options.get("localport"));
		final InetSocketAddress host = new InetSocketAddress((InetAddress) options.get("host"),
				((Integer) options.get("port")).intValue());
		final int mode = options.containsKey("routing") ? KNXNetworkLinkIP.ROUTING
				: KNXNetworkLinkIP.TUNNELING;
		return new KNXNetworkLinkIP(mode, local, host, options.containsKey("nat"), medium);
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

		out.log(LogLevel.ALWAYS, "found " + devices.length + " network devices", null);
		for (int i = 0; i < devices.length; i++) {
			final IndividualAddress a = devices[i];
			out.log(LogLevel.ALWAYS, a.toString(), null);
		}
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
			if (isOption(arg, "-help", "-h")) {
				options.put("help", null);
				return;
			}
			if (isOption(arg, "-version", null)) {
				options.put("version", null);
				return;
			}
			if (isOption(arg, "-verbose", "-v"))
				options.put("verbose", null);
			else if (isOption(arg, "-localhost", null))
				parseHost(args[++i], true, options);
			else if (isOption(arg, "-localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (isOption(arg, "-port", "-p"))
				options.put("port", Integer.decode(args[++i]));
			else if (isOption(arg, "-nat", "-n"))
				options.put("nat", null);
			else if (isOption(arg, "-routing", null))
				options.put("routing", null);
			else if (isOption(arg, "-serial", "-s"))
				options.put("serial", null);
			else if (isOption(arg, "-medium", "-m"))
				options.put("medium", getMedium(args[++i]));
			else if (options.containsKey("serial") && options.get("serial") == null)
				// add argument as serial port number/identifier to serial option
				options.put("serial", arg);
			else if (!options.containsKey("host"))
				// otherwise add a host key with argument as host
				parseHost(arg, false, options);
			else if (!options.containsKey("range"))
				// otherwise save the range for scanning
				options.put("range", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (options.containsKey("host") == options.containsKey("serial"))
			throw new KNXIllegalArgumentException("specify either IP host or serial port");
		if (!options.containsKey("range"))
			throw new KNXIllegalArgumentException("Missing area.line range to scan for devices");
	}

	private static boolean isOption(final String arg, final String longOpt, final String shortOpt)
	{
		return arg.equals(longOpt) || shortOpt != null && arg.equals(shortOpt);
	}

	private static KNXMediumSettings getMedium(final String id)
	{
		// for now, the local device address is always left 0 in the
		// created medium setting, since there is no user cmd line option for this
		// so KNXnet/IP server will supply address
		if (id.equals("tp0"))
			return TPSettings.TP0;
		else if (id.equals("tp1"))
			return TPSettings.TP1;
		else if (id.equals("p110"))
			return new PLSettings(false);
		else if (id.equals("p132"))
			return new PLSettings(true);
		else if (id.equals("rf"))
			return new RFSettings(null);
		else
			throw new KNXIllegalArgumentException("unknown medium");
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		// ??? accept several line requests
		sb.append("usage: ").append(tool).append(" [options] <host|port> <area.line[.device]>")
				.append(sep);
		sb.append("options:").append(sep);
		sb.append(" -help -h                show this help message").append(sep);
		sb.append(" -version                show tool/library version and exit").append(sep);
		sb.append(" -verbose -v             enable verbose status output").append(sep);
		sb.append(" -localhost <id>         local IP/host name").append(sep);
		sb.append(" -localport <port>       local UDP port (default system assigned)").append(sep);
		sb.append(" -port -p <port>         UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append(" -nat -n                 enable Network Address Translation").append(sep);
		sb.append(" -serial -s              use FT1.2 serial communication").append(sep);
		sb.append(" -routing                use KNXnet/IP routing").append(sep);
		sb.append(" -medium -m <id>         KNX medium [tp0|tp1|p110|p132|rf] " + "(default tp1)")
				.append(sep);
		sb.append("The area and line are given as numbers in the range [0..0x0F], e.g., 3.1")
				.append(sep);
		sb.append("The (optional) device address part is in the range [0..0x0FF]").append(sep);
		out.log(LogLevel.ALWAYS, sb.toString(), null);
	}

	private static void showVersion()
	{
		out.log(LogLevel.ALWAYS,
				tool + " version " + version + " using " + Settings.getLibraryHeader(false), null);
	}

	private static InetSocketAddress createLocalSocket(final InetAddress host, final Integer port)
	{
		final int p = port != null ? port.intValue() : 0;
		try {
			return host != null ? new InetSocketAddress(host, p) : p != 0 ? new InetSocketAddress(
					InetAddress.getLocalHost(), p) : null;
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to get local host " + e.getMessage(), e);
		}
	}

	private static void parseHost(final String host, final boolean local, final Map options)
	{
		try {
			options.put(local ? "localhost" : "host", InetAddress.getByName(host));
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to read host " + host, e);
		}
	}

	private static final class ShutdownHandler extends Thread
	{
		private final Thread t = Thread.currentThread();

		ShutdownHandler register()
		{
			Runtime.getRuntime().addShutdownHook(this);
			return this;
		}

		void unregister()
		{
			Runtime.getRuntime().removeShutdownHook(this);
		}

		public void run()
		{
			t.interrupt();
		}
	}
}
