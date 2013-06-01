/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2011 B. Malinowsky

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

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.DPTXlatorBoolean;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXFormatException;
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
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClient;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;
import tuwien.auto.calimero.mgmt.PropertyAccess;

/**
 * A tool for Calimero showing device information of a device in a KNX network.
 * <p>
 * DeviceInfo is a {@link Runnable} tool implementation allowing a user to read information about a
 * KNX device.<br>
 * <br>
 * This tool supports KNX network access using a KNXnet/IP connection or FT1.2 connection. It uses
 * the {@link ManagementClient} functionality of the library to read KNX device description,
 * properties, and memory locations. It collects and shows device information similar to the ETS.
 * <p>
 * When running this tool from the console, the <code>main</code>- method of this class is invoked,
 * otherwise use this class in the context appropriate to a {@link Runnable}.<br>
 * In console mode, the KNX device information, as well as errors and problems during its execution
 * are written to <code>System.out</code>.
 *
 * @author B. Malinowsky
 */
public class DeviceInfo implements Runnable
{
	private static final String tool = "DeviceInfo";
	private static final String version = "1.0";
	private static final String sep = System.getProperty("line.separator");

	// Interface Object "Application Program Object" in interface object server
	private static final int applicationProgramObject = 3;
	// property id to distinguish hardware types which are using the same
	// device descriptor mask version
	private static final int pidHardwareType = 78;
	// Interface Object "Device Object" in interface object server
	private static final int deviceObjectIdx = 0;

	private static LogService out = LogManager.getManager().getLogService("tools");

	private ManagementClient mc;
	private Destination d;

	private final Map options = new HashMap();

	/**
	 * Creates a new DeviceInfo instance using the supplied options.
	 * <p>
	 * Mandatory arguments are an IP host/address or a FT1.2 port identifier, depending on the type
	 * of connection to the KNX network, and the KNX device individual address ("area.line.device").
	 * See {@link #main(String[])} for the list of options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public DeviceInfo(final String[] args)
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

	/**
	 * Entry point for running DeviceInfo.
	 * <p>
	 * Syntax: DeviceInfo [options] &lt;host|port&gt; &lt;KNX device address&gt;
	 * <p>
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
	 * <li><code>-localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>-host</code> <i>id</i> &nbsp;remote IP/host name</li>
	 * <li><code>-port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
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
		final LogWriter w = LogStreamWriter.newUnformatted(LogLevel.WARN, System.out, true, false);
		out.addWriter(w);
		try {
			final DeviceInfo d = new DeviceInfo(args);
			w.setLogLevel(d.options.containsKey("verbose") ? LogLevel.TRACE : LogLevel.WARN);
			final ShutdownHandler sh = new ShutdownHandler().register();
			d.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.log(LogLevel.ERROR, "parsing options", t);
		}
		finally {
			LogManager.getManager().shutdown(true);
		}
	}

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run()
	{
		// ??? as with the other tools, maybe put this into the try block to also call onCompletion
		if (options.isEmpty()) {
			out.log(LogLevel.ALWAYS, "A tool for reading KNX device information", null);
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

		Exception thrown = null;
		boolean canceled = false;
		KNXNetworkLink link = null;
		try {
			link = createLink();
			mc = new ManagementClientImpl(link);
			final IndividualAddress device = (IndividualAddress) options.get("device");
			d = mc.createDestination(device, true);
			final String info = readDeviceInfo();
			System.out.println(info);
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
			onCompletion(thrown, canceled);
		}
	}

	private String readDeviceInfo() throws KNXException, InterruptedException
	{
		// AN089v03
		// this sequence creates device info of a device as ETS does

		final StringBuffer info = new StringBuffer();
		// Physical PEI Type
		final String physicalPeiType = readUnsignedFormatted(deviceObjectIdx,
				PropertyAccess.PID.PEI_TYPE);
		info.append("Physical PEI type ").append(physicalPeiType).append("\n");

		final int appProgamObjectIdx = findAppProgamObjectIdx();
		// Required PEI Type (Application Program Object)
		final String requiredPeiType = readUnsignedFormatted(appProgamObjectIdx,
				PropertyAccess.PID.PEI_TYPE);
		info.append("Required PEI type ").append(requiredPeiType).append("\n");

		// Manufacturer ID (Device Object)
		final String manufacturerId = readUnsignedFormatted(deviceObjectIdx,
				PropertyAccess.PID.MANUFACTURER_ID);
		info.append("Manufacturer ID ").append(manufacturerId).append("\n");

		// Mask Version, Value of Device Descriptor Type 0
		byte[] data = mc.readDeviceDesc(d, 0);
		int maskVersion = 0;
		if (data != null) {
			info.append("Mask version 0x");
			maskVersion = toUnsigned(data);
			if (maskVersion < 0xff)
				info.append("00");
			info.append(Integer.toHexString(maskVersion)).append("\n");
			info.append("Medium type ").append(toMediumTypeString(maskVersion >> 12 & 0xf))
					.append("\n");
			info.append("Firmware type ").append(toFirmwareTypeString(maskVersion >> 8 & 0xf))
					.append("\n");
			info.append("Firmware version ").append(maskVersion >> 4 & 0xf).append("\n");
			info.append("Subcode/Version ").append(maskVersion & 0xf).append("\n");
		}
		// Programming Mode (memory address 0x60)
		data = mc.readMemory(d, 0x60, 1);
		if (data != null) {
			final DPTXlator x = new DPTXlatorBoolean(DPTXlatorBoolean.DPT_SWITCH);
			x.setData(data);
			info.append("Programming mode ").append(x.getValue()).append("\n");
		}
		// Run State (Application Program Object)
		final String runState = readUnsignedFormatted(appProgamObjectIdx,
				PropertyAccess.PID.RUN_STATE_CONTROL);
		info.append("Run state ").append(runState).append("\n");

		// Firmware Revision
		final String firmwareRev = readUnsignedFormatted(deviceObjectIdx,
				PropertyAccess.PID.FIRMWARE_REVISION);
		info.append("Firmware revision ").append(firmwareRev).append("\n");

		// Hardware Type
		final byte[] hwData = read(deviceObjectIdx, pidHardwareType);
		final String hwType = DataUnitBuilder.toHex(hwData, " ");
		info.append("Hardware type ").append(hwType).append("\n");
		// validity check on mask and hardware type octets
		// AN059v3, AN089v3
		if ((maskVersion == 0x25 || maskVersion == 0x0705) && hwData[0] != 0) {
			info.append("manufacturer-specific device identification of hardware type should " +
					"be 0 for this mask!").append("\n");
		}
		// Serial Number
		data = read(deviceObjectIdx, PropertyAccess.PID.SERIAL_NUMBER);
		if (data != null) {
			final String serialNo = DataUnitBuilder.toHex(data, "");
			info.append("Serial number 0x").append(serialNo).append("\n");
		}
		// Order Info
		final String orderInfo = readUnsignedFormatted(deviceObjectIdx,
				PropertyAccess.PID.ORDER_INFO);
		info.append("Order info ").append(orderInfo).append("\n");

		// Application ID (Application Program Object)
		final String appId = readUnsignedFormatted(appProgamObjectIdx,
				PropertyAccess.PID.PROGRAM_VERSION);
		info.append("Application ID ").append(appId).append("\n");
		return info.toString();
	}

	private int findAppProgamObjectIdx() throws KNXException, InterruptedException
	{
		for (int i = 1; i < 100; ++i) {
			final int type = toUnsigned(mc.readProperty(d, i, PropertyAccess.PID.OBJECT_TYPE, 1, 1));
			if (type == applicationProgramObject)
				return i;
		}
		throw new KNXException("no application program interface object found");
	}

	private byte[] read(final int objectIndex, final int pid) throws InterruptedException
	{
		try {
			return mc.readProperty(d, objectIndex, pid, 1, 1);
		}
		catch (final KNXException e) {
			out.log(LogLevel.WARN, "object index " + objectIndex + " property " + pid
					+ " error, " + e.getMessage(), null);
		}
		return null;
	}

	private String readUnsignedFormatted(final int objectIndex, final int pid)
		throws InterruptedException
	{
		final byte[] data = read(objectIndex, pid);
		if (data == null)
			return "-";
		return "" + toUnsigned(data);
	}

	/**
	 * Called by this tool on completion.
	 * <p>
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.log(LogLevel.INFO, "reading device info canceled", null);
		if (thrown != null)
			out.log(LogLevel.ERROR, "completed", thrown);
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
				parseHost(args[++i], false, options);
			else if (!options.containsKey("device"))
				// otherwise create the KNX device address from the argument
				try {
					options.put("device", new IndividualAddress(arg));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("KNX device " + e.toString(), e);
				}
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (options.containsKey("host") == options.containsKey("serial"))
			throw new KNXIllegalArgumentException("specify either IP host or serial port");
		if (!options.containsKey("device"))
			throw new KNXIllegalArgumentException("KNX device individual address missing");
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

	private static boolean isOption(final String arg, final String longOpt, final String shortOpt)
	{
		return arg.equals(longOpt) || shortOpt != null && arg.equals(shortOpt);
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("usage: ").append(tool).append(" [options] <host|port> <KNX device address>")
				.append(sep);
		sb.append("options:").append(sep);
		sb.append(" -help -h                show this help message").append(sep);
		sb.append(" -version                show tool/library version and exit").append(sep);
		sb.append(" -verbose -v             enable verbose status output").append(sep);
		sb.append(" -localhost <id>         local IP/host name").append(sep);
		sb.append(" -localport <number>     local UDP port (default system assigned)").append(sep);
		sb.append(" -port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		// sb.append(" -host <id>              remote IP/host name").append(sep);
		sb.append(" -nat -n                 enable Network Address Translation").append(sep);
		sb.append(" -serial -s              use FT1.2 serial communication").append(sep);
		sb.append(" -routing                use KNXnet/IP routing").append(sep);
		sb.append(" -medium -m <id>         KNX medium [tp0|tp1|p110|p132|rf] " + "(default tp1)")
				.append(sep);
		out.log(LogLevel.ALWAYS, sb.toString(), null);
	}

	private static void showVersion()
	{
		out.log(LogLevel.ALWAYS,
				tool + " version " + version + " using " + Settings.getLibraryHeader(false), null);
	}

	private static int toUnsigned(final byte[] data)
	{
		int value = data[0] & 0xff;
		if (data.length == 1)
			return value;
		value = value << 8 | data[1] & 0xff;
		if (data.length == 2)
			return value;
		value = value << 16 | data[2] & 0xff << 8 | data[3] & 0xff;
		return value;
	}

	private static String toMediumTypeString(final int type)
	{
		switch (type) {
		case 0:
			return "Twisted Pair 1";
		case 1:
			return "Powerline 110";
		case 2:
			return "Radio Frequency";
		case 3:
			return "Twisted Pair 0";
		case 4:
			return "Powerline 132";
		default:
			return "Type " + type;
		}
	}

	private static String toFirmwareTypeString(final int type)
	{
		switch (type) {
		case 0:
			return "BCU 1, BCU 2, BIM M113";
		case 1:
			return "Unidirectional devices";
		case 3:
			return "Property based device management";
		case 7:
			return "BIM M112";
		case 8:
			return "IR Decoder, TP1 legacy";
		case 9:
			return "Repeater, Coupler";
		default:
			return "Type " + type;
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
