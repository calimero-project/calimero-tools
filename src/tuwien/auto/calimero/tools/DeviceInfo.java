/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2011, 2015 B. Malinowsky

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

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DeviceDescriptor;
import tuwien.auto.calimero.DeviceDescriptor.DD0;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.DPTXlatorBoolean;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.KNXNetworkLinkUsb;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClient;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;
import tuwien.auto.calimero.mgmt.PropertyAccess;
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;
import tuwien.auto.calimero.tools.Main.ShutdownHandler;

/**
 * A tool for Calimero showing device information of a device in a KNX network.
 * <p>
 * DeviceInfo is a {@link Runnable} tool implementation allowing a user to read information about a
 * KNX device.<br>
 * <br>
 * This tool supports KNX network access using a KNXnet/IP, USB, or FT1.2 connection. It uses the
 * {@link ManagementClient} functionality of the library to read KNX device description, properties,
 * and memory locations. It collects and shows device information similar to the ETS.
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
	private static final String sep = System.getProperty("line.separator");

	// Interface Object "Addresstable Object" in interface object server
	private static final int addresstableObject = 1;
	// Interface Object "Associationtable Object" in interface object server
	private static final int assoctableObject = 2;
	// Interface Object "Application Program Object" in interface object server
	private static final int appProgramObject = 3;
	// Interface Object "Interface Program Object" in interface object server
	private static final int interfaceProgramObject = 4;
	// Interface Object "KNXnet/IP Parameter Object" in interface object server
	private static final int knxnetipObject = 11;

	// property id to distinguish hardware types which are using the same
	// device descriptor mask version
	private static final int pidHardwareType = 78;

	// Indices of interface objects in interface object server
	private int deviceObjectIdx = -1;
	private int addresstableObjectIdx = -1;
	private int assoctableObjectIdx = -1;
	private int appProgramObjectIdx = -1;
	private int interfaceProgramObjectIdx = -1;
	private int knxnetipObjectIdx = -1;

	private static Logger out = LogService.getLogger("calimero.tools");

	private ManagementClient mc;
	private Destination d;

	private final Map<String, Object> options = new HashMap<>();

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
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--routing</code> use KNXnet/IP routing</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|p132|rf] (defaults to tp1)</li>
	 * </ul>
	 *
	 * @param args command line options for running the device info tool
	 */
	public static void main(final String[] args)
	{
		setLogVerbosity(Arrays.asList(args));

		out = LogService.getLogger("calimero.tools");
		try {
			final DeviceInfo d = new DeviceInfo(args);
			final ShutdownHandler sh = new ShutdownHandler().register();
			d.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.error("parsing options", t);
		}
	}

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run()
	{
		// ??? as with the other tools, maybe put this into the try block to also call onCompletion
		if (options.isEmpty()) {
			LogService.logAlways(out, tool + " - Read KNX device information");
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

		Exception thrown = null;
		boolean canceled = false;
		KNXNetworkLink link = null;
		try {
			link = createLink();
			mc = new ManagementClientImpl(link);
			final IndividualAddress device = (IndividualAddress) options.get("device");
			d = mc.createDestination(device, true);
			System.out.println("Reading data from device " + device
					+ ", might take some seconds ...");
			findInterfaceObjects();
			final String info = readDeviceInfo();
			onDeviceInformation(device, info);
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

	/**
	 * Invoked on successfully finishing reading the device information of a KNX device.
	 *
	 * @param device the KNX device address the information was read from
	 * @param info device information
	 */
	protected void onDeviceInformation(final IndividualAddress device, final String info)
	{
		System.out.println(info);
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.info("reading device info canceled");
		if (thrown != null)
			out.error("completed", thrown);
	}

	private void findInterfaceObjects() throws KNXException, InterruptedException
	{
		// check if there are any interface object at all, i.e., the Device Object
		if (readElements(0, PID.OBJECT_TYPE) == 0)
			return;
		deviceObjectIdx = 0;
		final int objects = readElements(deviceObjectIdx, PropertyAccess.PID.IO_LIST);
		if (objects == 0) {
			// device only has device- and cEMI server-object
//			final int cEmiObjectIdx = 1; // interface object type 8
			out.warn("Device implements only Device Object and cEMI Object");
			return;
		}

		final byte[] data = read(deviceObjectIdx, PropertyAccess.PID.IO_LIST, 1, objects);
		for (int i = 0; i < objects; ++i) {
			final int type = (data[2 * i] & 0xff) << 8 | data[2 * i + 1] & 0xff;
			if (type == addresstableObject)
				addresstableObjectIdx = i;
			else if (type == assoctableObject)
				assoctableObjectIdx = i;
			else if (type == appProgramObject)
				appProgramObjectIdx = i;
			else if (type == interfaceProgramObject)
				interfaceProgramObjectIdx = i;
			else if (type == knxnetipObject)
				knxnetipObjectIdx = i;
		}
	}

	// read device info similar to ETS
	private String readDeviceInfo() throws KNXException, InterruptedException
	{
		// order of output: general, application program, PEI program, group communication, KNX IP
		final StringBuffer info = new StringBuffer();

		// General information

		// Mask Version, Value of Device Descriptor Type 0
		byte[] data = mc.readDeviceDesc(d, 0);
		int maskVersion = 0;
		if (data != null) {
			final DD0 dd = DeviceDescriptor.DD0.fromType0(data);
			maskVersion = dd.getMaskVersion();
			info.append("Mask version ").append(dd).append("\n");
			info.append("Medium type: ").append(toMediumTypeString(dd.getMediumType()))
					.append("\n");
			info.append("Firmware type: ").append(toFirmwareTypeString(dd.getFirmwareType()))
					.append("\n");
			info.append("Firmware version ").append(dd.getFirmwareVersion()).append("\n");
			info.append("Subcode/Version ").append(dd.getSubcode()).append("\n");
		}

		if (deviceObjectIdx != -1) {
			// Manufacturer ID (Device Object)
			final String manufacturerId = readUnsignedFormatted(deviceObjectIdx,
					PID.MANUFACTURER_ID);
			info.append("Manufacturer ID ").append(manufacturerId).append("\n");

			// Order Info
			final String orderInfo = readUnsignedFormatted(deviceObjectIdx, PID.ORDER_INFO);
			info.append("Order info ").append(orderInfo).append("\n");

			// Serial Number
			data = read(deviceObjectIdx, PropertyAccess.PID.SERIAL_NUMBER);
			if (data != null) {
				final String serialNo = DataUnitBuilder.toHex(data, "");
				info.append("Serial number 0x").append(serialNo).append("\n");
			}

			// Physical PEI Type
			final String physicalPeiType = readUnsignedFormatted(deviceObjectIdx, PID.PEI_TYPE);
			info.append("Physical PEI type ").append(physicalPeiType).append("\n");
		}

		// Required PEI Type (Application Program Object)

		String requiredPeiType = "-";
		if (appProgramObjectIdx != -1)
			requiredPeiType = readUnsignedFormatted(appProgramObjectIdx, PID.PEI_TYPE);
		info.append("Required PEI type ").append(requiredPeiType).append("\n");

		if (deviceObjectIdx != -1) {
			// Hardware Type
			final byte[] hwData = read(deviceObjectIdx, pidHardwareType);
			final String hwType = hwData == null ? "-" : DataUnitBuilder.toHex(hwData, " ");
			info.append("Hardware type: ").append(hwType).append("\n");
			// validity check on mask and hardware type octets
			// AN059v3, AN089v3
			if ((maskVersion == 0x25 || maskVersion == 0x0705) && hwData[0] != 0) {
				info.append("manufacturer-specific device identification of hardware type "
						+ "should be 0 for this mask!").append("\n");
			}
		}

		// Programming Mode (memory address 0x60)
		data = mc.readMemory(d, 0x60, 1);
		if (data != null) {
			final DPTXlator x = new DPTXlatorBoolean(DPTXlatorBoolean.DPT_SWITCH);
			x.setData(data);
			info.append("Programming mode ").append(x.getValue()).append("\n");
		}

		if (deviceObjectIdx != -1) {
			// Firmware Revision
			final String firmwareRev = readUnsignedFormatted(deviceObjectIdx, PID.FIRMWARE_REVISION);
			info.append("Firmware revision ").append(firmwareRev).append("\n");
		}

		// System B has mask version 0x07B0 or 0x17B0 and provides error code property
		final boolean isSystemB = maskVersion == 0x07B0 || maskVersion == 0x17B0;

		// Application Program (Application Program Object)
		info.append("Application Program\n");
		readProgram(appProgramObjectIdx, info, isSystemB);

		// PEI Program (Interface Program Object)
		info.append("PEI Program\n");
		readProgram(interfaceProgramObjectIdx, info, isSystemB);

		// Group Communication
		info.append("Group Addresstable ");
		readLoadState(addresstableObjectIdx, info, isSystemB);
		info.append("Group Assoc.table ");
		readLoadState(assoctableObjectIdx, info, isSystemB);

		// if we have a KNX IP device, show KNX IP info
		final boolean knxip = maskVersion == 0x5705;
		if (knxip && knxnetipObjectIdx != -1) {
			info.append('\n');
			readKnxipInfo(info);
		}
		return info.toString();
	}

	private void readKnxipInfo(final StringBuffer info) throws KNXException, InterruptedException
	{
		// Device Name (friendly)
		info.append("Device name: ").append(readFriendlyName()).append('\n');
		// Device Capabilities Device State
		byte[] data = read(knxnetipObjectIdx, PropertyAccess.PID.KNXNETIP_DEVICE_CAPABILITIES);
		info.append("Capabilities:").append(toCapabilitiesString(data)).append('\n');
		final boolean supportsTunneling = (data[1] & 0x01) == 0x01;

		// MAC Address
		data = read(knxnetipObjectIdx, PropertyAccess.PID.MAC_ADDRESS);
		info.append("MAC address: ").append(DataUnitBuilder.toHex(data, " ")).append('\n');
		// Current IP Assignment
		data = read(knxnetipObjectIdx, PropertyAccess.PID.CURRENT_IP_ASSIGNMENT_METHOD);
		info.append("Current IP assignment method: ").append(toIPAssignmentString(data))
				.append('\n');
		final int currentIPAssignment = data[0] & 0x0f;
		// Bits (from LSB): Manual=0, BootP=1, DHCP=2, AutoIP=3
		final boolean dhcpOrBoot = (data[0] & 0x06) != 0;

		// Read currently set IP parameters
		// IP Address
		final byte[] currentIP = read(knxnetipObjectIdx, PropertyAccess.PID.CURRENT_IP_ADDRESS);
		info.append("\tIP ").append(toIP(currentIP)).append('\n');
		// Subnet Mask
		final byte[] currentMask = read(knxnetipObjectIdx, PropertyAccess.PID.CURRENT_SUBNET_MASK);
		info.append("\tSubnet mask ").append(toIP(currentMask)).append('\n');
		// Default Gateway
		final byte[] currentGw = read(knxnetipObjectIdx, PropertyAccess.PID.CURRENT_DEFAULT_GATEWAY);
		info.append("\tGateway ").append(toIP(currentGw)).append('\n');
		// DHCP Server (show only if current assignment method is DHCP or BootP)
		if (dhcpOrBoot) {
			data = read(knxnetipObjectIdx, PropertyAccess.PID.DHCP_BOOTP_SERVER);
			info.append("DHCP server ").append(toIP(data)).append('\n');
		}

		// IP Assignment Method (shown only if different from current IP assign. method)
		data = read(knxnetipObjectIdx, PropertyAccess.PID.IP_ASSIGNMENT_METHOD);
		final int ipAssignment = data[0] & 0x0f;
		if (ipAssignment != currentIPAssignment)
			info.append("Configured IP assignment method").append(ipAssignment).append('\n');

		// Read IP parameters for manual assignment
		// the following info is only shown if manual assignment method is enabled, and parameter
		// is different from current one
		final boolean manual = (ipAssignment & 0x01) == 0x01;
		if (manual) {
			info.append("Differing manual configuration:\n");
			// Manual IP Address
			final byte[] ip = read(knxnetipObjectIdx, PropertyAccess.PID.IP_ADDRESS);
			if (!Arrays.equals(currentIP, ip))
				info.append("\tIP ").append(toIP(ip)).append('\n');
			// Manual Subnet Mask
			final byte[] mask = read(knxnetipObjectIdx, PropertyAccess.PID.SUBNET_MASK);
			if (!Arrays.equals(currentMask, mask))
				info.append("\tSubnet mask ").append(toIP(mask)).append('\n');
			// Manual Default Gateway
			final byte[] gw = read(knxnetipObjectIdx, PropertyAccess.PID.DEFAULT_GATEWAY);
			if (!Arrays.equals(currentGw, gw))
				info.append("\tDefault gateway ").append(toIP(gw)).append('\n');
		}

		// Routing Multicast Address
		data = read(knxnetipObjectIdx, PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS);
		info.append("Routing multicast ").append(toIP(data)).append('\n');
		// Multicast TTL
		final String ttl = readUnsignedFormatted(knxnetipObjectIdx, PropertyAccess.PID.TTL);
		info.append("TTL ").append(ttl).append('\n');
		// Messages to Multicast Address
		final String txIP = readUnsignedFormatted(knxnetipObjectIdx, PID.MSG_TRANSMIT_TO_IP);
		info.append("Messages transmitted to IP: ").append(txIP).append('\n');

		// Additional Ind. Addresses (shown only if tunneling is implemented)
		if (supportsTunneling) {
			info.append("Additional individual addresses:");
			final int pid = PID.ADDITIONAL_INDIVIDUAL_ADDRESSES;
			final int elements = readElements(knxnetipObjectIdx, pid);
			info.append(" " + elements).append('\n');
			for (int i = 0; i < elements; i++) {
				data = read(knxnetipObjectIdx, pid);
				info.append('\t').append(new IndividualAddress(data)).append("  ");
			}
		}
	}

	private void readProgram(final int objectIdx, final StringBuffer info,
		final boolean hasErrorCode) throws InterruptedException
	{
		if (objectIdx == -1) {
			info.append("\tn/a\n");
			return;
		}
		// TODO can we show some ID of what program is installed?

		final String typeAndVersion = readUnsignedFormatted(objectIdx, PID.PROGRAM_VERSION);
		info.append("\tProgram version ").append(typeAndVersion).append("\n");
		info.append('\t');
		readLoadState(objectIdx, info, hasErrorCode);

		final String rs = getRunState(read(objectIdx, PropertyAccess.PID.RUN_STATE_CONTROL));
		info.append("\tRun state: ").append(rs).append("\n");
	}

	private void readLoadState(final int objectIdx, final StringBuffer info,
		final boolean hasErrorCode) throws InterruptedException
	{
		if (objectIdx == -1) {
			info.append("n/a\n");
			return;
		}
		byte[] data = read(objectIdx, PropertyAccess.PID.LOAD_STATE_CONTROL);
		final String ls = getLoadState(data);
		info.append("Load state: ").append(ls);
		// System B contains error code for load state "Error" (optional, but usually yes)
		if (data[0] == 3 && hasErrorCode) {
			data = read(objectIdx, PropertyAccess.PID.ERROR_CODE);
			if (data != null) {
				try {
					// enum ErrorClassSystem
					final DPTXlator t = TranslatorTypes.createTranslator(0, "20.011");
					t.setData(data);
					info.append(", error code ").append((int) t.getNumericValue()).append(": ")
							.append(t.getValue());
				}
				catch (final KNXException e) {
					// no translator
				}
			}
		}
		info.append("\n");
	}

	private String readFriendlyName() throws KNXException, InterruptedException
	{
		final char[] name = new char[30];
		int start = 0;
		while (true) {
			final byte[] data = mc.readProperty(d, knxnetipObjectIdx, PID.FRIENDLY_NAME, start + 1,
					10);
			for (int i = 0; i < 10 && data[i] != 0; ++i, ++start)
				name[start] = (char) (data[i] & 0xff);
			if (start >= 30 || data[9] == 0)
				return new String(name, 0, start);
		}
	}

	private int readElements(final int objectIndex, final int pid) throws InterruptedException
	{
		final byte[] elems = read(objectIndex, pid, 0, 1);
		return elems == null ? 0 : toUnsigned(elems);
	}

	private byte[] read(final int objectIndex, final int pid) throws InterruptedException
	{
		return read(objectIndex, pid, 1, 1);
	}

	private byte[] read(final int objectIndex, final int pid, final int start, final int elements)
		throws InterruptedException
	{
		try {
			// since we don't know the max. allowed APDU length, play it safe
			final ByteArrayOutputStream res = new ByteArrayOutputStream();
			for (int i = start; i < start + elements; i++) {
				final byte[] data = mc.readProperty(d, objectIndex, pid, i, 1);
				res.write(data, 0, data.length);
			}
			return res.toByteArray();
		}
		catch (final KNXException e) {
			out.warn("object index " + objectIndex + " property " + pid + " error, "
					+ e.getMessage());
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

		if (!options.containsKey("host")
				|| (options.containsKey("serial") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		if (!options.containsKey("device"))
			throw new KNXIllegalArgumentException("KNX device individual address missing");
	}

	// a helper in case slf4j simple logger is used
	private static void setLogVerbosity(final List<String> args)
	{
		// TODO problem: this overrules the log level from a simplelogger.properties file!!
		final String simpleLoggerLogLevel = "org.slf4j.simpleLogger.defaultLogLevel";
		if (!System.getProperties().containsKey(simpleLoggerLogLevel)) {
			final String lvl = args.contains("-v") || args.contains("--verbose") ? "debug"
					: "error";
			System.setProperty(simpleLoggerLogLevel, lvl);
		}
		out = LogService.getLogger("calimero.tools");
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("Usage: ").append(tool).append(" [options] <host|port> <KNX device address>")
				.append(sep);
		sb.append("Options:").append(sep);
		sb.append(" --help -h                show this help message").append(sep);
		sb.append(" --version                show tool/library version and exit").append(sep);
		sb.append(" --verbose -v             enable verbose status output").append(sep);
		sb.append(" --localhost <id>         local IP/host name").append(sep);
		sb.append(" --localport <number>     local UDP port (default system assigned)").append(sep);
		sb.append(" --port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append(" --nat -n                 enable Network Address Translation").append(sep);
		sb.append(" --serial -s              use FT1.2 serial communication").append(sep);
		sb.append(" --usb -u                 use KNX USB communication").append(sep);
		sb.append(" --routing                use KNXnet/IP routing").append(sep);
		sb.append(" --medium -m <id>         KNX medium [tp1|p110|p132|rf] (default tp1)").append(
				sep);
		LogService.logAlways(out, sb.toString());
	}

	private static void showVersion()
	{
		LogService.logAlways(out, Settings.getLibraryHeader(false));
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

	private static String toIP(final byte[] data)
	{
		try {
			if (data != null)
				return InetAddress.getByAddress(data).getHostAddress();
		}
		catch (final UnknownHostException e) {}
		return "n/a";
	}

	private static String toMediumTypeString(final int type)
	{
		switch (type) {
		case 0:
			return "Twisted Pair 1";
		case 1:
			return "Power-line 110";
		case 2:
			return "Radio Frequency";
		case 4:
			return "Power-line 132";
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

	private String getLoadState(final byte[] data)
	{
		final int state = data[0] & 0xff;
		switch (state) {
		case 0:
			return "Unloaded";
		case 1:
			return "Loaded";
		case 2:
			return "Loading";
		case 3:
			return "Error (during load process)";
		case 4:
			return "Unloading";
		case 5:
			return "Load Completing (Intermediate)";
		default:
			return "Invalid load status " + state;
		}
	}

	private String getRunState(final byte[] data)
	{
		final int state = data[0] & 0xff;
		switch (state) {
		case 0:
			return "Halted";
		case 1:
			return "Running";
		case 2:
			return "Ready";
		case 3:
			return "Terminated";
		case 4:
			return "Starting";
		case 5:
			return "Shutting down";
		default:
			return "Invalid run state " + state;
		}
	}

	private static String toIPAssignmentString(final byte[] data)
	{
		final int bitset = data[0] & 0xff;
		String s = "";
		final String div = ", ";
		if ((bitset & 0x01) != 0)
			s = "manual";
		if ((bitset & 0x02) != 0)
			s += (s.length() == 0 ? "" : div) + "Bootstrap Protocol";
		if ((bitset & 0x04) != 0)
			s += (s.length() == 0 ? "" : div) + "DHCP";
		if ((bitset & 0x08) != 0)
			s += (s.length() == 0 ? "" : div) + "Auto IP";
		return s;
	}

	private static String toCapabilitiesString(final byte[] data)
	{
		final StringBuilder sb = new StringBuilder();
		if ((data[1] & 0x01) == 0x01)
			sb.append(" Device Management,");
		if ((data[1] & 0x02) == 0x02)
			sb.append(" Tunneling,");
		if ((data[1] & 0x04) == 0x04)
			sb.append(" Routing,");
		if ((data[1] & 0x08) == 0x08)
			sb.append(" Remote Logging,");
		if ((data[1] & 0x10) == 0x10)
			sb.append(" Remote Configuration and Diagnosis,");
		if ((data[1] & 0x20) == 0x20)
			sb.append(" Object Server,");
		return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
	}
}
