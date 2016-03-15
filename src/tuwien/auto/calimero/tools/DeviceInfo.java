/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2011, 2016 B. Malinowsky

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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DeviceDescriptor;
import tuwien.auto.calimero.DeviceDescriptor.DD0;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.DPTXlatorBoolean;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXFormatException;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.KNXNetworkLinkTpuart;
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
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;

/**
 * A tool for Calimero showing device information of a device in a KNX network.
 * <p>
 * DeviceInfo is a {@link Runnable} tool implementation allowing a user to read information about a
 * KNX device.<br>
 * <br>
 * This tool supports KNX network access using a KNXnet/IP, KNX IP, FT1.2, or TP-UART connection. It
 * uses the {@link ManagementClient} functionality of the library to read KNX device description,
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
	 * <li><code>-port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>-nat -n</code> enable Network Address Translation</li>
	 * <li><code>-serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>-routing</code> use KNXnet/IP routing</li>
	 * <li><code>-tpuart</code> use TP-UART communication</li>
	 * <li><code>-medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|rf] (defaults to
	 * tp1)</li>
	 * <li><code>-knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local
	 * endpoint</li>
	 * </ul>
	 * The <code>-knx-address</code> option is only necessary if an access protocol is selected that
	 * directly communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX
	 * individual address shall be unique in a network, and the subnetwork address (area and line)
	 * should be set to match the network configuration.
	 *
	 * @param args command line options for running the device info tool
	 */
	public static void main(final String[] args)
	{
		final LogWriter w = LogStreamWriter.newUnformatted(LogLevel.WARN, System.out, true, false);
		LogManager.getManager().addWriter("", w);
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
			out.log(LogLevel.ALWAYS, tool + " - Read KNX device information", null);
			showVersion();
			out.log(LogLevel.ALWAYS, "Type -help for help message", null);
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
		if (data == null)
			return;
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
		final DD0 dd = DeviceDescriptor.DD0.fromType0(data);
		if (data != null) {
			maskVersion = dd.getMaskVersion();
			info.append("Mask version ").append(dd).append("\n");
			info.append("Medium type: ").append(toMediumTypeString(dd.getMediumType()))
					.append("\n");
			info.append("Firmware type: ").append(toFirmwareTypeString(dd.getFirmwareType()))
					.append("\n");
			info.append("Firmware version ").append(dd.getFirmwareVersion()).append("\n");
			info.append("Subcode/Version ").append(dd.getSubcode()).append("\n");
		}

		// check for PL110 BCU1
		if (dd == DD0.TYPE_1013)
			readPL110Bcu1(info);
		else if (dd == DD0.TYPE_0010 || dd == DD0.TYPE_0011 || dd == DD0.TYPE_0012)
			;
		else if (dd == DD0.TYPE_0020 || dd == DD0.TYPE_0021)
			;
		else
			findInterfaceObjects();

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
		info.append("Application PEI type ").append(requiredPeiType).append("\n");

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
		try {
			data = null;
			data = mc.readMemory(d, 0x60, 1);
		}
		catch (final KNXException e) {
			out.error("reading memory location 0x60", e);
		}
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
		if (addresstableObjectIdx != -1) {
			info.append("Group Addresstable ");
			readLoadState(addresstableObjectIdx, info, isSystemB);
		}
		if (assoctableObjectIdx != -1) {
			info.append("Group Assoc.table ");
			readLoadState(assoctableObjectIdx, info, isSystemB);
		}
		// if we have a KNX IP device, show KNX IP info
		final boolean knxip = maskVersion == 0x5705;
		if (knxip && knxnetipObjectIdx != -1) {
			info.append('\n');
			readKnxipInfo(info);
		}
		return info.toString();
	}

	private void readPL110Bcu1(final StringBuffer info) throws InterruptedException
	{
		final int addrDoA = 0x0102; // length 2
		final int addrManufact = 0x0104;
		final int addrDevType = 0x0105; // length 2
		final int addrVersion = 0x0107;
		final int addrPeiType = 0x0109;
		final int addrRunError = 0x010d;
		final int addrGroupObjTablePtr = 0x0112;
		final int addrProgramPtr = 0x0114;
		final int addrGroupAddrTable = 0x0116; // max. length 233

		readMem(addrDoA, 2, info, "DoA ", true);
		final int mfId = readMem(addrManufact, 1, info, "KNX manufacturer ID ", false);
		if (mfId > -1)
			info.append("    Manufacturer ").append(manufacturer.get(Integer.valueOf(mfId))).append("\n");

		/*final int devtype = */readMem(addrDevType, 2, info, "Device type number ", true);
		final int version = readMem(addrVersion, 1, info, "SW version ", true);
		info.append("    Main ").append(version >> 4).append(", sub ").append(version & 0xf).append("\n");
		final int peitype = readMem(addrPeiType, 1, info, "Hardware PEI type ", false);
		info.append("    " + toPeiTypeString(peitype)).append("\n");
		final int runerror = readMem(addrRunError, 1, info, "Run error 0x", true);
		info.append("    " + decodeRunError(runerror)).append("\n");
		// realization type 1
		readMem(addrGroupObjTablePtr, 1, info, "Location of group object table 0x", true);
		// realization type 1
		final int entries = readMem(addrGroupAddrTable, 1, info, "Group address table entries ", false);
		int startAddr = addrGroupAddrTable + 1;
		final KNXAddress device = new IndividualAddress(readMem(startAddr, 2) & 0x7fff);
		info.append("Device address ").append(device).append("\nGroup addresses:");
		for (int i = 1; i < entries; i++) {
			startAddr += 2;
			final int raw = readMem(startAddr, 2);
			final KNXAddress group = new GroupAddress(raw & 0x7fff);
			info.append(" ").append(group);
			// are we the group responder
			if ((raw & 0x8000) == 0x8000)
				info.append("(R)");
		}
		info.append("\n");
		final int progptr = readMem(addrProgramPtr, 1, info, "Location of user program 0x100 + 0x", true);
		final int userprog = 0x100 + progptr;
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
		if (data != null && data[0] == 3 && hasErrorCode) {
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
			out.log(LogLevel.WARN, "object index " + objectIndex + " property " + pid + " error, "
					+ e.getMessage(), null);
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

	private int readMem(final int startAddr, final int bytes, final StringBuffer buf, final String prefix,
		final boolean hex) throws InterruptedException
	{
		final int v = readMem(startAddr, bytes);
		if (v != -1)
			buf.append(prefix).append(hex ? Integer.toHexString(v) : "" + v).append("\n");
		return v;
	}

	// pre: 3 bytes max
	private int readMem(final int startAddr, final int bytes) throws InterruptedException
	{
		try {
			return toUnsigned(mc.readMemory(d, startAddr, bytes));
		}
		catch (final KNXException e) {
			return -1;
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
		if (options.containsKey("tpuart")) {
			// create TP-UART link
			final IndividualAddress device = (IndividualAddress) options.get("knx-address");
			medium.setDeviceAddress(device);
			return new KNXNetworkLinkTpuart(host, medium, Collections.emptyList());
		}
		// create local and remote socket address for network link
		final InetSocketAddress local = createLocalSocket((InetAddress) options.get("localhost"),
				(Integer) options.get("localport"));
		final InetSocketAddress remote = new InetSocketAddress(host,
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
		// default subnetwork address for TP1 and unregistered device
		options.put("knx-address", new IndividualAddress(0, 0x02, 0xff));

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
			else if (isOption(arg, "-tpuart", null))
				options.put("tpuart", null);
			else if (isOption(arg, "-medium", "-m"))
				options.put("medium", getMedium(args[++i]));
			else if (isOption(arg, "-knx-address", "-k"))
				options.put("knx-address", Main.getAddress(args[++i]));
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

		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
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
		if (id.equals("tp1"))
			return TPSettings.TP1;
		else if (id.equals("p110"))
			return new PLSettings();
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
		sb.append("Usage: ").append(tool).append(" [options] <host|port> <KNX device address>")
				.append(sep);
		sb.append("Options:").append(sep);
		sb.append(" -help -h                show this help message").append(sep);
		sb.append(" -version                show tool/library version and exit").append(sep);
		sb.append(" -verbose -v             enable verbose status output").append(sep);
		sb.append(" -localhost <id>         local IP/host name").append(sep);
		sb.append(" -localport <number>     local UDP port (default system assigned)").append(sep);
		sb.append(" -port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append(" -nat -n                 enable Network Address Translation").append(sep);
		sb.append(" -serial -s              use FT1.2 serial communication").append(sep);
		sb.append(" -tpuart                 use TP-UART communication").append(sep);
		sb.append(" -routing                use KNXnet/IP routing").append(sep);
		sb.append(" -medium -m <id>         KNX medium [tp1|p110|rf] (default tp1)")
				.append(sep);
		out.log(LogLevel.ALWAYS, sb.toString(), null);
	}

	private static void showVersion()
	{
		out.log(LogLevel.ALWAYS, Settings.getLibraryHeader(false), null);
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
		case 5:
			return "KNX IP";
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

	private static String toPeiTypeString(final int peitype)
	{
		final String[] desc = new String[] {
			"No adapter", // 0
			"Illegal adapter",
			"4 inputs, 1 output (LED)",
			"Reserved",
			"2 inputs / 2 outputs, 1 output (LED)",
			"Reserved", // 5
			"3 inputs / 1 output, 1 output (LED)",
			"Reserved",
			"5 inputs",
			"Reserved",
			"FT1.2 protocol", // (default) type 10 is defined twice
			// "Loadable serial protocol", // 10 (alternative)
			"Reserved",
			"Serial sync message protocol",
			"Reserved",
			"Serial sync data block protocol",
			"Reserved", // 15
			"Serial async message protocol",
			"Programmable I/O",
			"Reserved",
			"4 outputs, 1 output (LED)",
			"Download", // 20
		};
		return desc[peitype];
	}

	private static String decodeRunError(final int runError)
	{
		final String[] flags = new String[] {"SYS0_ERR: buffer error", "SYS1_ERR: system state parity error",
			"EEPROM corrupted", "Stack overflow", "OBJ_ERR: group object/assoc. table error",
			"SYS2_ERR: transceiver error", "SYS3_ERR: confirm error"};
		final int bits = ~runError & 0xff;
		if (bits == 0)
			return "OK";
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < flags.length; i++) {
			if ((bits & (1 << i)) != 0)
				sb.append(flags[i]);
		}
		return sb.toString();
	}

	private static String getLoadState(final byte[] data)
	{
		if (data == null || data.length < 1)
			return "n/a";
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

	private static String getRunState(final byte[] data)
	{
		if (data == null || data.length < 1)
			return "n/a";
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

	// KNX manufacturer IDs as of 2015
	private static final Map manufacturer = new HashMap();
	static {
		manufacturer.put(Integer.valueOf(1), "Siemens");
		manufacturer.put(Integer.valueOf(2), "ABB");
		manufacturer.put(Integer.valueOf(4), "Albrecht Jung");
		manufacturer.put(Integer.valueOf(5), "Bticino");
		manufacturer.put(Integer.valueOf(6), "Berker");
		manufacturer.put(Integer.valueOf(7), "Busch-Jaeger Elektro");
		manufacturer.put(Integer.valueOf(8), "GIRA Giersiepen");
		manufacturer.put(Integer.valueOf(9), "Hager Electro");
		manufacturer.put(Integer.valueOf(10), "INSTA ELEKTRO");
		manufacturer.put(Integer.valueOf(11), "LEGRAND Appareillage électrique");
		manufacturer.put(Integer.valueOf(12), "Merten");
		manufacturer.put(Integer.valueOf(14), "ABB SpA – SACE Division");
		manufacturer.put(Integer.valueOf(22), "Siedle & Söhne");
		manufacturer.put(Integer.valueOf(24), "Eberle");
		manufacturer.put(Integer.valueOf(25), "GEWISS");
		manufacturer.put(Integer.valueOf(27), "Albert Ackermann");
		manufacturer.put(Integer.valueOf(28), "Schupa GmbH");
		manufacturer.put(Integer.valueOf(29), "ABB SCHWEIZ");
		manufacturer.put(Integer.valueOf(30), "Feller");
		manufacturer.put(Integer.valueOf(32), "DEHN & SÖHNE");
		manufacturer.put(Integer.valueOf(33), "CRABTREE");
		manufacturer.put(Integer.valueOf(36), "Paul Hochköpper");
		manufacturer.put(Integer.valueOf(37), "Altenburger Electronic");
		manufacturer.put(Integer.valueOf(41), "Grässlin");
		manufacturer.put(Integer.valueOf(42), "Simon");
		manufacturer.put(Integer.valueOf(44), "VIMAR");
		manufacturer.put(Integer.valueOf(45), "Moeller Gebäudeautomation KG");
		manufacturer.put(Integer.valueOf(46), "Eltako");
		manufacturer.put(Integer.valueOf(49), "Bosch-Siemens Haushaltsgeräte");
		manufacturer.put(Integer.valueOf(52), "RITTO GmbH&Co.KG");
		manufacturer.put(Integer.valueOf(53), "Power Controls");
		manufacturer.put(Integer.valueOf(55), "ZUMTOBEL");
		manufacturer.put(Integer.valueOf(57), "Phoenix Contact");
		manufacturer.put(Integer.valueOf(61), "WAGO Kontakttechnik");
		manufacturer.put(Integer.valueOf(66), "Wieland Electric");
		manufacturer.put(Integer.valueOf(67), "Hermann Kleinhuis");
		manufacturer.put(Integer.valueOf(69), "Stiebel Eltron");
		manufacturer.put(Integer.valueOf(71), "Tehalit");
		manufacturer.put(Integer.valueOf(72), "Theben AG");
		manufacturer.put(Integer.valueOf(73), "Wilhelm Rutenbeck");
		manufacturer.put(Integer.valueOf(75), "Winkhaus");
		manufacturer.put(Integer.valueOf(76), "Robert Bosch");
		manufacturer.put(Integer.valueOf(78), "Somfy");
		manufacturer.put(Integer.valueOf(80), "Woertz");
		manufacturer.put(Integer.valueOf(81), "Viessmann Werke");
		manufacturer.put(Integer.valueOf(82), "Theodor HEIMEIER Metallwerk");
		manufacturer.put(Integer.valueOf(83), "Joh. Vaillant");
		manufacturer.put(Integer.valueOf(85), "AMP Deutschland");
		manufacturer.put(Integer.valueOf(89), "Bosch Thermotechnik GmbH");
		manufacturer.put(Integer.valueOf(90), "SEF - ECOTEC");
		manufacturer.put(Integer.valueOf(92), "DORMA GmbH + Co. KG");
		manufacturer.put(Integer.valueOf(93), "WindowMaster A/S");
		manufacturer.put(Integer.valueOf(94), "Walther Werke");
		manufacturer.put(Integer.valueOf(95), "ORAS");
		manufacturer.put(Integer.valueOf(97), "Dätwyler");
		manufacturer.put(Integer.valueOf(98), "Electrak");
		manufacturer.put(Integer.valueOf(99), "Techem");
		manufacturer.put(Integer.valueOf(100), "Schneider Electric Industries SAS");
		manufacturer.put(Integer.valueOf(101), "WHD Wilhelm Huber + Söhne");
		manufacturer.put(Integer.valueOf(102), "Bischoff Elektronik");
		manufacturer.put(Integer.valueOf(104), "JEPAZ");
		manufacturer.put(Integer.valueOf(105), "RTS Automation");
		manufacturer.put(Integer.valueOf(106), "EIBMARKT GmbH");
		manufacturer.put(Integer.valueOf(107), "WAREMA electronic GmbH");
		manufacturer.put(Integer.valueOf(108), "Eelectron");
		manufacturer.put(Integer.valueOf(109), "Belden Wire & Cable B.V.");
		manufacturer.put(Integer.valueOf(110), "Becker-Antriebe GmbH");
		manufacturer.put(Integer.valueOf(111), "J.Stehle+Söhne GmbH");
		manufacturer.put(Integer.valueOf(112), "AGFEO");
		manufacturer.put(Integer.valueOf(113), "Zennio");
		manufacturer.put(Integer.valueOf(114), "TAPKO Technologies");
		manufacturer.put(Integer.valueOf(115), "HDL");
		manufacturer.put(Integer.valueOf(116), "Uponor");
		manufacturer.put(Integer.valueOf(117), "se Lightmanagement AG");
		manufacturer.put(Integer.valueOf(118), "Arcus-eds");
		manufacturer.put(Integer.valueOf(119), "Intesis");
		manufacturer.put(Integer.valueOf(120), "Herholdt Controls srl");
		manufacturer.put(Integer.valueOf(121), "Zublin AG");
		manufacturer.put(Integer.valueOf(122), "Durable Technologies");
		manufacturer.put(Integer.valueOf(123), "Innoteam");
		manufacturer.put(Integer.valueOf(124), "ise GmbH");
		manufacturer.put(Integer.valueOf(125), "TEAM FOR TRONICS");
		manufacturer.put(Integer.valueOf(126), "CIAT");
		manufacturer.put(Integer.valueOf(127), "Remeha BV");
		manufacturer.put(Integer.valueOf(128), "ESYLUX");
		manufacturer.put(Integer.valueOf(129), "BASALTE");
		manufacturer.put(Integer.valueOf(130), "Vestamatic");
		manufacturer.put(Integer.valueOf(131), "MDT technologies");
		manufacturer.put(Integer.valueOf(132), "Warendorfer Küchen GmbH");
		manufacturer.put(Integer.valueOf(133), "Video-Star");
		manufacturer.put(Integer.valueOf(134), "Sitek");
		manufacturer.put(Integer.valueOf(135), "CONTROLtronic");
		manufacturer.put(Integer.valueOf(136), "function Technology");
		manufacturer.put(Integer.valueOf(137), "AMX");
		manufacturer.put(Integer.valueOf(138), "ELDAT");
		manufacturer.put(Integer.valueOf(139), "VIKO");
		manufacturer.put(Integer.valueOf(140), "Pulse Technologies");
		manufacturer.put(Integer.valueOf(141), "Crestron");
		manufacturer.put(Integer.valueOf(142), "STEINEL professional");
		manufacturer.put(Integer.valueOf(143), "BILTON LED Lighting");
		manufacturer.put(Integer.valueOf(144), "denro AG");
		manufacturer.put(Integer.valueOf(145), "GePro");
		manufacturer.put(Integer.valueOf(146), "preussen automation");
		manufacturer.put(Integer.valueOf(147), "Zoppas Industries");
		manufacturer.put(Integer.valueOf(148), "MACTECH");
		manufacturer.put(Integer.valueOf(149), "TECHNO-TREND");
		manufacturer.put(Integer.valueOf(150), "FS Cables");
		manufacturer.put(Integer.valueOf(151), "Delta Dore");
		manufacturer.put(Integer.valueOf(152), "Eissound");
		manufacturer.put(Integer.valueOf(153), "Cisco");
		manufacturer.put(Integer.valueOf(154), "Dinuy");
		manufacturer.put(Integer.valueOf(155), "iKNiX");
		manufacturer.put(Integer.valueOf(156), "Rademacher Geräte-Elektronik GmbH & Co. KG");
		manufacturer.put(Integer.valueOf(157), "EGi Electroacustica General Iberica");
		manufacturer.put(Integer.valueOf(158), "Ingenium");
		manufacturer.put(Integer.valueOf(159), "ElabNET");
		manufacturer.put(Integer.valueOf(160), "Blumotix");
		manufacturer.put(Integer.valueOf(161), "Hunter Douglas");
		manufacturer.put(Integer.valueOf(162), "APRICUM");
		manufacturer.put(Integer.valueOf(163), "TIANSU Automation");
		manufacturer.put(Integer.valueOf(164), "Bubendorff");
		manufacturer.put(Integer.valueOf(165), "MBS GmbH");
		manufacturer.put(Integer.valueOf(166), "Enertex Bayern GmbH");
		manufacturer.put(Integer.valueOf(167), "BMS");
		manufacturer.put(Integer.valueOf(168), "Sinapsi");
		manufacturer.put(Integer.valueOf(169), "Embedded Systems SIA");
		manufacturer.put(Integer.valueOf(170), "KNX1");
		manufacturer.put(Integer.valueOf(171), "Tokka");
		manufacturer.put(Integer.valueOf(172), "NanoSense");
		manufacturer.put(Integer.valueOf(173), "PEAR Automation GmbH");
		manufacturer.put(Integer.valueOf(174), "DGA");
		manufacturer.put(Integer.valueOf(175), "Lutron");
		manufacturer.put(Integer.valueOf(176), "AIRZONE – ALTRA");
		manufacturer.put(Integer.valueOf(177), "Lithoss Design Switches");
		manufacturer.put(Integer.valueOf(178), "3ATEL");
		manufacturer.put(Integer.valueOf(179), "Philips Controls");
		manufacturer.put(Integer.valueOf(180), "VELUX A/S");
		manufacturer.put(Integer.valueOf(181), "LOYTEC");
		manufacturer.put(Integer.valueOf(182), "SBS S.p.A.");
		manufacturer.put(Integer.valueOf(183), "SIRLAN Technologies");
		manufacturer.put(Integer.valueOf(184), "Bleu Comm' Azur");
		manufacturer.put(Integer.valueOf(185), "IT GmbH");
		manufacturer.put(Integer.valueOf(186), "RENSON");
		manufacturer.put(Integer.valueOf(187), "HEP Group");
		manufacturer.put(Integer.valueOf(188), "Balmart");
		manufacturer.put(Integer.valueOf(189), "GFS GmbH");
		manufacturer.put(Integer.valueOf(190), "Schenker Storen AG");
		manufacturer.put(Integer.valueOf(191), "Algodue Elettronica S.r.L.");
		manufacturer.put(Integer.valueOf(192), "Newron System");
		manufacturer.put(Integer.valueOf(193), "maintronic");
		manufacturer.put(Integer.valueOf(194), "Vantage");
		manufacturer.put(Integer.valueOf(195), "Foresis");
		manufacturer.put(Integer.valueOf(196), "Research & Production Association SEM");
		manufacturer.put(Integer.valueOf(197), "Weinzierl Engineering GmbH");
		manufacturer.put(Integer.valueOf(198), "Möhlenhoff Wärmetechnik GmbH");
		manufacturer.put(Integer.valueOf(199), "PKC-GROUP Oyj");
		manufacturer.put(Integer.valueOf(200), "B.E.G.");
		manufacturer.put(Integer.valueOf(201), "Elsner Elektronik GmbH");
		manufacturer.put(Integer.valueOf(202), "Siemens Building Technologies (HK/China) Ltd.");
		manufacturer.put(Integer.valueOf(204), "Eutrac");
		manufacturer.put(Integer.valueOf(205), "Gustav Hensel GmbH & Co. KG");
		manufacturer.put(Integer.valueOf(206), "GARO AB");
		manufacturer.put(Integer.valueOf(207), "Waldmann Lichttechnik");
		manufacturer.put(Integer.valueOf(208), "SCHÜCO");
		manufacturer.put(Integer.valueOf(209), "EMU");
		manufacturer.put(Integer.valueOf(210), "JNet Systems AG");
		manufacturer.put(Integer.valueOf(214), "O.Y.L. Electronics");
		manufacturer.put(Integer.valueOf(215), "Galax System");
		manufacturer.put(Integer.valueOf(216), "Disch");
		manufacturer.put(Integer.valueOf(217), "Aucoteam");
		manufacturer.put(Integer.valueOf(218), "Luxmate Controls");
		manufacturer.put(Integer.valueOf(219), "Danfoss");
		manufacturer.put(Integer.valueOf(220), "AST GmbH");
		manufacturer.put(Integer.valueOf(222), "WILA Leuchten");
		manufacturer.put(Integer.valueOf(223), "b+b Automations- und Steuerungstechnik");
		manufacturer.put(Integer.valueOf(225), "Lingg & Janke");
		manufacturer.put(Integer.valueOf(227), "Sauter");
		manufacturer.put(Integer.valueOf(228), "SIMU");
		manufacturer.put(Integer.valueOf(232), "Theben HTS AG");
		manufacturer.put(Integer.valueOf(233), "Amann GmbH");
		manufacturer.put(Integer.valueOf(234), "BERG Energiekontrollsysteme GmbH");
		manufacturer.put(Integer.valueOf(235), "Hüppe Form Sonnenschutzsysteme GmbH");
		manufacturer.put(Integer.valueOf(237), "Oventrop KG");
		manufacturer.put(Integer.valueOf(238), "Griesser AG");
		manufacturer.put(Integer.valueOf(239), "IPAS GmbH");
		manufacturer.put(Integer.valueOf(240), "elero GmbH");
		manufacturer.put(Integer.valueOf(241), "Ardan Production and Industrial Controls Ltd.");
		manufacturer.put(Integer.valueOf(242), "Metec Meßtechnik GmbH");
		manufacturer.put(Integer.valueOf(244), "ELKA-Elektronik GmbH");
		manufacturer.put(Integer.valueOf(245), "ELEKTROANLAGEN D. NAGEL");
		manufacturer.put(Integer.valueOf(246), "Tridonic Bauelemente GmbH");
		manufacturer.put(Integer.valueOf(248), "Stengler Gesellschaft");
		manufacturer.put(Integer.valueOf(249), "Schneider Electric (MG)");
		manufacturer.put(Integer.valueOf(250), "KNX Association");
		manufacturer.put(Integer.valueOf(251), "VIVO");
		manufacturer.put(Integer.valueOf(252), "Hugo Müller GmbH & Co KG");
		manufacturer.put(Integer.valueOf(253), "Siemens HVAC");
		manufacturer.put(Integer.valueOf(254), "APT");
		manufacturer.put(Integer.valueOf(256), "HighDom");
		manufacturer.put(Integer.valueOf(257), "Top Services");
		manufacturer.put(Integer.valueOf(258), "ambiHome");
		manufacturer.put(Integer.valueOf(259), "DATEC electronic AG");
		manufacturer.put(Integer.valueOf(260), "ABUS Security-Center");
		manufacturer.put(Integer.valueOf(261), "Lite-Puter");
		manufacturer.put(Integer.valueOf(262), "Tantron Electronic");
		manufacturer.put(Integer.valueOf(263), "Yönnet");
		manufacturer.put(Integer.valueOf(264), "DKX Tech");
		manufacturer.put(Integer.valueOf(265), "Viatron");
		manufacturer.put(Integer.valueOf(266), "Nautibus");
		manufacturer.put(Integer.valueOf(268), "Longchuang");
		manufacturer.put(Integer.valueOf(269), "Air-On AG");
		manufacturer.put(Integer.valueOf(270), "ib-company GmbH");
		manufacturer.put(Integer.valueOf(271), "SATION");
		manufacturer.put(Integer.valueOf(272), "Agentilo GmbH");
		manufacturer.put(Integer.valueOf(273), "Makel Elektrik");
		manufacturer.put(Integer.valueOf(274), "Helios Ventilatoren");
		manufacturer.put(Integer.valueOf(275), "Otto Solutions Pte Ltd");
		manufacturer.put(Integer.valueOf(276), "Airmaster");
		manufacturer.put(Integer.valueOf(277), "HEINEMANN GmbH");
		manufacturer.put(Integer.valueOf(278), "LDS");
		manufacturer.put(Integer.valueOf(279), "ASIN");
		manufacturer.put(Integer.valueOf(280), "Bridges");
		manufacturer.put(Integer.valueOf(281), "ARBONIA");
		manufacturer.put(Integer.valueOf(282), "KERMI");
		manufacturer.put(Integer.valueOf(283), "PROLUX");
		manufacturer.put(Integer.valueOf(284), "ClicHome");
		manufacturer.put(Integer.valueOf(285), "COMMAX");
		manufacturer.put(Integer.valueOf(286), "EAE");
		manufacturer.put(Integer.valueOf(287), "Tense");
		manufacturer.put(Integer.valueOf(288), "Seyoung Electronics");
		manufacturer.put(Integer.valueOf(289), "Lifedomus");
		manufacturer.put(Integer.valueOf(290), "EUROtronic Technology GmbH");
		manufacturer.put(Integer.valueOf(291), "tci");
		manufacturer.put(Integer.valueOf(292), "Rishun Electronic");
		manufacturer.put(Integer.valueOf(293), "Zipato");
		manufacturer.put(Integer.valueOf(294), "cm-security GmbH & Co KG");
		manufacturer.put(Integer.valueOf(295), "Qing Cables");
		manufacturer.put(Integer.valueOf(296), "LABIO");
		manufacturer.put(Integer.valueOf(297), "Coster Tecnologie Elettroniche S.p.A.");
		manufacturer.put(Integer.valueOf(298), "E.G.E");
		manufacturer.put(Integer.valueOf(299), "NETxAutomation");
		manufacturer.put(Integer.valueOf(300), "tecalor");
		manufacturer.put(Integer.valueOf(301), "Urmet Electronics (Huizhou) Ltd.");
		manufacturer.put(Integer.valueOf(302), "Peiying Building Control");
		manufacturer.put(Integer.valueOf(303), "BPT S.p.A. a Socio Unico");
		manufacturer.put(Integer.valueOf(304), "Kanontec - KanonBUS");
		manufacturer.put(Integer.valueOf(305), "ISER Tech");
		manufacturer.put(Integer.valueOf(306), "Fineline");
		manufacturer.put(Integer.valueOf(307), "CP Electronics Ltd");
		manufacturer.put(Integer.valueOf(308), "Servodan A/S");
		manufacturer.put(Integer.valueOf(309), "Simon");
		manufacturer.put(Integer.valueOf(310), "GM modular pvt. Ltd.");
		manufacturer.put(Integer.valueOf(311), "FU CHENG Intelligence");
		manufacturer.put(Integer.valueOf(312), "NexKon");
		manufacturer.put(Integer.valueOf(313), "FEEL s.r.l");
		manufacturer.put(Integer.valueOf(314), "Not Assigned");
		manufacturer.put(Integer.valueOf(315), "Shenzhen Fanhai Sanjiang Electronics Co., Ltd.");
		manufacturer.put(Integer.valueOf(316), "Jiuzhou Greeble");
		manufacturer.put(Integer.valueOf(317), "Aumüller Aumatic GmbH");
		manufacturer.put(Integer.valueOf(318), "Etman Electric");
		manufacturer.put(Integer.valueOf(319), "EMT Controls");
		manufacturer.put(Integer.valueOf(320), "ZidaTech AG");
		manufacturer.put(Integer.valueOf(321), "IDGS bvba");
		manufacturer.put(Integer.valueOf(322), "dakanimo");
		manufacturer.put(Integer.valueOf(323), "Trebor Automation AB");
		manufacturer.put(Integer.valueOf(324), "Satel sp. z o.o.");
		manufacturer.put(Integer.valueOf(325), "Russound, Inc.");
		manufacturer.put(Integer.valueOf(326), "Midea Heating & Ventilating Equipment CO LTD");
		manufacturer.put(Integer.valueOf(327), "Consorzio Terranuova");
		manufacturer.put(Integer.valueOf(328), "Wolf Heiztechnik GmbH");
		manufacturer.put(Integer.valueOf(329), "SONTEC");
		manufacturer.put(Integer.valueOf(330), "Belcom Cables Ltd.");
		manufacturer.put(Integer.valueOf(331), "Guangzhou SeaWin Electrical Technologies Co., Ltd.");
		manufacturer.put(Integer.valueOf(332), "Acrel");
		manufacturer.put(Integer.valueOf(333), "Franke Aquarotter GmbH");
		manufacturer.put(Integer.valueOf(334), "Orion Systems");
		manufacturer.put(Integer.valueOf(335), "Schrack Technik GmbH");
		manufacturer.put(Integer.valueOf(336), "INSPRID");
		manufacturer.put(Integer.valueOf(337), "Sunricher");
		manufacturer.put(Integer.valueOf(338), "Menred automation system(shanghai) Co.,Ltd.");
		manufacturer.put(Integer.valueOf(339), "Aurex");
		manufacturer.put(Integer.valueOf(340), "Josef Barthelme GmbH & Co. KG");
		manufacturer.put(Integer.valueOf(341), "Architecture Numerique");
		manufacturer.put(Integer.valueOf(342), "UP GROUP");
		manufacturer.put(Integer.valueOf(343), "Teknos-Avinno");
		manufacturer.put(Integer.valueOf(344), "Ningbo Dooya Mechanic & Electronic Technology");
		manufacturer.put(Integer.valueOf(345), "Thermokon Sensortechnik GmbH");
		manufacturer.put(Integer.valueOf(346), "BELIMO Automation AG");
		manufacturer.put(Integer.valueOf(347), "Zehnder Group International AG");
		manufacturer.put(Integer.valueOf(348), "sks Kinkel Elektronik");
		manufacturer.put(Integer.valueOf(349), "ECE Wurmitzer GmbH");
		manufacturer.put(Integer.valueOf(350), "LARS");
		manufacturer.put(Integer.valueOf(351), "URC");
		manufacturer.put(Integer.valueOf(352), "LightControl");
		manufacturer.put(Integer.valueOf(353), "ShenZhen YM");
		manufacturer.put(Integer.valueOf(354), "MEAN WELL Enterprises Co. Ltd.");
		manufacturer.put(Integer.valueOf(355), "OSix");
		manufacturer.put(Integer.valueOf(356), "AYPRO Technology");
		manufacturer.put(Integer.valueOf(357), "Hefei Ecolite Software");
		manufacturer.put(Integer.valueOf(358), "Enno");
		manufacturer.put(Integer.valueOf(359), "Ohosure");
	}
}
