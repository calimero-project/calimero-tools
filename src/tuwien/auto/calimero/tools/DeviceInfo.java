/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2011, 2020 B. Malinowsky

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
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DeviceDescriptor;
import tuwien.auto.calimero.DeviceDescriptor.DD0;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXRemoteException;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.DPTXlatorBoolean;
import tuwien.auto.calimero.dptxlator.DptXlator16BitSet;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.LocalDeviceManagementUsb;
import tuwien.auto.calimero.mgmt.ManagementClient;
import tuwien.auto.calimero.mgmt.PropertyAccess;
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;
import tuwien.auto.calimero.mgmt.PropertyAdapter;
import tuwien.auto.calimero.mgmt.PropertyClient;
import tuwien.auto.calimero.mgmt.RemotePropertyServiceAdapter;
import tuwien.auto.calimero.serial.usb.UsbConnection;
import tuwien.auto.calimero.tools.DeviceInfo.CommonParameter;
import tuwien.auto.calimero.tools.DeviceInfo.KnxipParameter;
import tuwien.auto.calimero.tools.Main.ShutdownHandler;

/**
 * A tool for Calimero showing device information of a device in a KNX network.
 * <p>
 * DeviceInfo is a {@link Runnable} tool implementation allowing a user to read information about a
 * KNX device.<br>
 * <br>
 * This tool supports KNX network access using a KNXnet/IP, KNX IP, USB, FT1.2, or TP-UART
 * connection. It uses the {@link ManagementClient} functionality of the library to read KNX device
 * description, properties, and memory locations. It collects and shows device information similar
 * to the ETS.
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
	/** Device parameter that can be queried by a client. */
	public interface Parameter {

		/**
		 * Name of parameter.
		 *
		 * @return unique parameter name
		 */
		String name();
	}

	/**
	 * Common device info parameters.
	 */
	@SuppressWarnings("checkstyle:javadocvariable")
	public enum CommonParameter implements Parameter {
		DeviceDescriptor,
		KnxMedium,
		FirmwareType,
		FirmwareVersion,
		HardwareType,
		SerialNumber,
		DomainAddress,
		MaxApduLength,
		Manufacturer,
		ManufacturerData,
		DeviceTypeNumber,
		SoftwareVersion,
		/** Actual PEI type is determined by reading the ADC. */
		ActualPeiType,
		/**
		 * Required PEI type for user and application software, either taken from Application Program interface object
		 * or read from BCU PEI type address location.
		 */
		RequiredPeiType,
		FirmwareRevision,
		RunError,
		ProgrammingMode,
		SystemState,
		RoutingCount,
		GroupObjTableLocation,
		GroupAddressTableEntries,
		DeviceAddress,
		GroupAddresses,
		ProgramVersion,
		LoadStateControl,
		LoadStateError,
		RunStateControl,
		OrderInfo,
	}

	/**
	 * cEMI server parameters.
	 */
	public enum CemiParameter implements Parameter {
		MediumType, SupportedCommModes, SelectedCommMode, ClientAddress, SupportedRfModes, SelectedRfMode,
		SupportedFilteringModes, SelectedFilteringModes
	}

	/**
	 * KNX IP device parameters.
	 */
	@SuppressWarnings("checkstyle:javadocvariable")
	public enum KnxipParameter implements Parameter {
		DeviceName,
		Capabilities,
		MacAddress,
		IPAddress,
		SubnetMask,
		DefaultGateway,
		CurrentIPAddress,
		CurrentSubnetMask,
		CurrentDefaultGateway,
		IPAssignment,
		ConfiguredIPAssignment,
		DhcpServer,
		CurrentIPAssignment,
		RoutingMulticast,
		TimeToLive,
		TransmitToIP,
		AdditionalIndividualAddresses
	}

	/**
	 * RF medium parameters.
	 */
	public enum RfParameter implements Parameter {
		DomainAddress
	}

	// not in a category yet
	public enum InternalParameter implements Parameter {
		IndividualAddressWriteEnabled, ServiceControl, AdditionalProfile, ErrorFlags
	}

	/**
	 * Result container of reading the device information.
	 */
	public static final class Result
	{
		private final Map<Parameter, String> formatted = new HashMap<>();
		private final Map<Parameter, byte[]> raw = new HashMap<>();

		/**
		 * Returns the formatted value of the requested device information parameter.
		 *
		 * @param p parameter indicating the requested device information
		 * @return value formatted as String
		 */
		public String formatted(final Parameter p)
		{
			return formatted.get(p);
		}

		/**
		 * Returns the raw data of the requested device information parameter, if available.
		 *
		 * @param p parameter specifying the requested device information
		 * @return raw data for parameter, or empty array
		 */
		public byte[] raw(final Parameter p)
		{
			return raw.get(p);
		}
	}

	private static final String tool = "DeviceInfo";

	// Interface Object "Addresstable Object" in interface object server
	private static final int addresstableObject = 1;
	// Interface Object "Associationtable Object" in interface object server
	private static final int assoctableObject = 2;
	// Interface Object "Application Program Object" in interface object server
	private static final int appProgramObject = 3;
	// Interface Object "Interface Program Object" in interface object server
	private static final int interfaceProgramObject = 4;
	// Interface Object "cEMI Server Object" in interface object server
	private static final int cemiServerObject = 8;
	// Interface Object "KNXnet/IP Parameter Object" in interface object server
	private static final int knxnetipObject = 11;
	// Interface Object "RF Medium Object" in interface object server
	private static final int rfMediumObject = 19;

	// property id to distinguish hardware types which are using the same
	// device descriptor mask version
	private static final int pidHardwareType = 78;

	// maps object type to object indices in device
	private final Map<Integer, List<Integer>> ifObjects = new HashMap<>();

	private static Logger out = LogService.getLogger("calimero.tools");


	private ManagementClient mc;
	private Destination d;
	private PropertyClient pc;

	private final Map<String, Object> options = new HashMap<>();
	private Result result;

	private DeviceDescriptor dd;
	// System B has mask version 0x07B0 or 0x17B0 and provides error code property
	private boolean isSystemB;

	/**
	 * Creates a new DeviceInfo instance using the supplied options.
	 * <p>
	 * Mandatory arguments are the connection options depending on the type
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
	 * Syntax: DeviceInfo [options] &lt;host|port&gt; [&lt;KNX device address&gt;]
	 * <p>
	 * Running the tool without a KNX device address will read the device info of the local KNX interface (KNXnet/IP and
	 * USB only).<br>
	 * To show usage message of the tool on the console, supply the command line option --help (or -h). Command line
	 * options are treated case sensitive. Available options for connecting to the KNX device in question:
	 * <ul>
	 * <li>no arguments: only show short description and version info</li>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
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
			final DeviceInfo d = new DeviceInfo(args);
			final ShutdownHandler sh = new ShutdownHandler().register();
			d.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.error("parsing options", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		final IndividualAddress device = (IndividualAddress) options.get("device");
		result = new Result();

		try {
			if (options.isEmpty()) {
				out(tool + " - Read KNX device information");
				Main.showVersion();
				out("Type --help for help message");
				return;
			}
			if (options.containsKey("about")) {
				((Runnable) options.get("about")).run();
				return;
			}

			if (device != null) {
				// setup for reading device info of remote device
				try (KNXNetworkLink link = createLink();
						RemotePropertyServiceAdapter adapter = new RemotePropertyServiceAdapter(link, device, e -> {},
								true)) {
					mc = adapter.managementClient();
					d = adapter.destination();
					pc = new PropertyClient(adapter);

					out.info("Reading data from device {}, might take some seconds ...", device);
					readDeviceInfo();
				}
			}
			else if (options.containsKey("usb")) {
				// setup for reading device info of usb interface
				try (UsbConnection conn = new UsbConnection((String) options.get("host"));
						PropertyAdapter adapter = new LocalDeviceManagementUsb(conn, e -> {}, false)) {
					dd = conn.deviceDescriptor();
					pc = new PropertyClient(adapter);

					out.info("Reading info of KNX USB adapter {}, might take some seconds ...", dd);
					readDeviceInfo();
				}
			}
			else {
				try (PropertyAdapter adapter = Main.newLocalDeviceMgmtIP(options, closed -> {})) {
					pc = new PropertyClient(adapter);

					out.info("Reading info of KNXnet/IP {}, might take some seconds ...", adapter.getName());
					readDeviceInfo();
				}
			}
		}
		catch (KNXException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		finally {
			if (!result.formatted.isEmpty())
				onDeviceInformation(device == null ? KNXMediumSettings.BackboneRouter : device, result);
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Invoked on successfully finishing reading the device information of a KNX device.
	 *
	 * @param device KNX device address
	 * @param info holds the result of reading KNX device information; depending on the device, not all available
	 *        parameters might be set
	 */
	protected void onDeviceInformation(final IndividualAddress device, final Result info) {}

	/**
	 * Invoked after successfully reading a KNX device parameter. If a device parameter is not available or accessible
	 * in the KNX device, this method won't be called.
	 *
	 * @param parameter the parameter read from the device
	 * @param value formatted value of that parameter
	 * @param raw raw value of that parameter
	 */
	protected void onDeviceInformation(final Parameter parameter, final String value, final byte[] raw) {
		out(parameter, value, raw);
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

	private void out(final Parameter p, final String value, final byte[] raw) {
		final boolean verbose = false; // TODO create option 'raw/unformatted'
		// create human readable name from parameter by inserting some spaces
		final String name = p.name().replaceAll("([A-Z])", " $1").replace("I P", "IP").trim();
		final String s = name + " = " + value;
		final String hex = raw.length > 0 ? "0x" + DataUnitBuilder.toHex(raw, "") : "n/a";
		// left-pad verbose output
		final int n = Math.max(1, 60 - s.length());
		final String detail = verbose ? String.format("%" + n + "s%s]", "[raw=", hex) : "";
		System.out.println(s + detail);
	}

	private void findInterfaceObjects() throws KNXException, InterruptedException
	{
		// check if there are any interface object at all, i.e., the Device Object
		if (readElements(0, PID.OBJECT_TYPE) <= 0)
			return;

		final int deviceObjectIdx = 0;
		final int objects = readElements(deviceObjectIdx, PropertyAccess.PID.IO_LIST);
		if (objects > 0) {
			final byte[] data = read(deviceObjectIdx, PropertyAccess.PID.IO_LIST, 1, objects);
			for (int i = 0; i < objects; ++i) {
				final int type = (data[2 * i] & 0xff) << 8 | (data[2 * i + 1] & 0xff);
				ifObjects.compute(type, (__, v) -> v == null ? new ArrayList<Integer>() : v).add(i);
			}
		}
		else {
			// device only has at least device- and cEMI server-object
			ifObjects.put(0, List.of(deviceObjectIdx));
			for (int i = 1; i < 100; ++i) {
				int type = (int) toUnsigned(read(i, PID.OBJECT_TYPE));
				if (type < 0)
					break;
				ifObjects.compute(type, (__, v) -> v == null ? new ArrayList<Integer>() : v).add(i);
			}

			if (ifObjects.size() == 1) {
				ifObjects.put(cemiServerObject, List.of(1));
				out.info("Device implements only Device Object and cEMI Object");
			}
		}
	}

	private void readDeviceInfo() throws KNXException, InterruptedException
	{
		// find device descriptor
		if (dd != null)
			dd = deviceDescriptor(dd.toByteArray());
		else if (mc != null)
			dd = deviceDescriptor(mc.readDeviceDesc(d, 0));

		// check for BCU1/BCU2 first, which don't have interface objects
		if (dd != null) {
			if (dd == DD0.TYPE_1013)
				readPL110Bcu1();
			else if (dd == DD0.TYPE_0010 || dd == DD0.TYPE_0011 || dd == DD0.TYPE_0012)
				readTP1Bcu1();
			else if (dd == DD0.TYPE_0020 || dd == DD0.TYPE_0021 || dd == DD0.TYPE_0025)
				readTP1Bcu2();
			else {
				findInterfaceObjects();
			}
		}
		else {
			findInterfaceObjects();
		}

		// System B has mask version 0x07B0 or 0x17B0 and provides error code property
		isSystemB = dd == DD0.TYPE_07B0 || dd == DD0.TYPE_17B0;

		if (ifObjects.containsKey(0))
			readDeviceObject(0);

		readActualPeiType();
		// Required PEI Type (Application Program Object)
		for (var idx : objectIndices(appProgramObject))
			readUnsigned(idx, PID.PEI_TYPE, false, CommonParameter.RequiredPeiType);

		programmingMode();

		// Application Program (Application Program Object)
		for (var idx : objectIndices(appProgramObject))
			readProgram(idx);

		// PEI Program (Interface Program Object)
		for (var idx : objectIndices(interfaceProgramObject))
			readProgram(idx);

		// Group Communication
		for (var idx : objectIndices(addresstableObject))
			readLoadState(idx);
		for (var idx : objectIndices(assoctableObject))
			readLoadState(idx);
		if (mc != null && !result.formatted.containsKey(CommonParameter.GroupAddresses))
			readGroupAddresses();

		for (var idx : objectIndices(cemiServerObject))
			readCemiServerObject(idx);
		for (var idx : objectIndices(rfMediumObject))
			readRFMediumObject(idx);
		for (var idx : objectIndices(knxnetipObject))
			readKnxipInfo(idx);
	}

	private List<Integer> objectIndices(int objectType) { return ifObjects.getOrDefault(objectType, List.of()); }

	// is device in programming mode
	private void programmingMode() throws KNXFormatException, InterruptedException {
		final DPTXlatorBoolean x = new DPTXlatorBoolean(DPTXlatorBoolean.DPT_SWITCH);
		try {
			if (ifObjects.containsKey(0)) {
				x.setData(pc.getProperty(0, PID.PROGMODE, 1, 1));
				putResult(CommonParameter.ProgrammingMode, x.getValue(), x.getData());
				return;
			}
		}
		catch (final KNXException e) {}

		// fall back and read memory location (remote device info only)
		try {
			if (mc != null) {
				x.setData(mc.readMemory(d, 0x60, 1));
				putResult(CommonParameter.ProgrammingMode, x.getValue(), x.getData());
			}
		}
		catch (final KNXException e) {
			out.error("reading memory location 0x60", e);
		}
	}

	private DD0 deviceDescriptor(final byte[] data)
	{
		final DD0 dd = DeviceDescriptor.DD0.from(data);
		putResult(CommonParameter.DeviceDescriptor, dd.toString(), dd.maskVersion());
		putResult(CommonParameter.KnxMedium, toMediumTypeString(dd.mediumType()), dd.mediumType());
		putResult(CommonParameter.FirmwareType, toFirmwareTypeString(dd.firmwareType()), dd.firmwareType());
		putResult(CommonParameter.FirmwareVersion, "" + dd.firmwareVersion(), dd.firmwareVersion());
		return dd;
	}

	private void readDeviceObject(int objectIdx) throws InterruptedException
	{
		// Manufacturer ID (Device Object)
		byte[] data = read(objectIdx, PID.MANUFACTURER_ID);
		if (data != null) {
			final int mfId = (int) toUnsigned(data);
			putResult(CommonParameter.Manufacturer, manufacturer(mfId), data);
		}
		// Order Info
		readUnsigned(objectIdx, PID.ORDER_INFO, true, CommonParameter.OrderInfo);

		// Serial Number
		data = read(objectIdx, PropertyAccess.PID.SERIAL_NUMBER);
		if (data != null)
			putResult(CommonParameter.SerialNumber, knxSerialNumber(data), data);

		// Physical PEI type, i.e., the currently connected PEI type
		readUnsigned(objectIdx, PID.PEI_TYPE, false, CommonParameter.ActualPeiType);

		// Hardware Type, 6 bytes with most significant byte always 0
		readUnsigned(objectIdx, pidHardwareType, true, CommonParameter.HardwareType);

		// Firmware Revision
		readUnsigned(objectIdx, PID.FIRMWARE_REVISION, false, CommonParameter.FirmwareRevision);

		// Get information about an optional additional KNX profile implemented in the device.
		// This property is optional, and only required in KNXnet/IP devices or if the device
		// is implemented in combination with another profile. If a DD is returned we should compare
		// it to our other DD. If they match, we can assume a stand-alone device (no other profile).
		// Also, if there is another profile, the KNX individual address has to be different to
		// the cEMI server one (as provided by the cEMI server object)
		data = read(objectIdx, PID.DEVICE_DESCRIPTOR, 1, 1);
		if (data != null) {
			final DD0 profile = DeviceDescriptor.DD0.from(data);
			if (dd == null)
				dd = deviceDescriptor(data);
			// device with additional profile?
			else if (!profile.equals(dd))
				putResult(InternalParameter.AdditionalProfile, profile.toString(), data);
		}

		// Info about possible additional profile in device
		try {
			final byte[] profileSna = read(objectIdx, PID.SUBNET_ADDRESS, 1, 1);
			final byte[] profileDev = read(objectIdx, PID.DEVICE_ADDRESS, 1, 1);
			final byte[] profileAddr = new byte[] { profileSna[0], profileDev[0] };
			final IndividualAddress ia = new IndividualAddress(profileAddr);
			putResult(CommonParameter.DeviceAddress, "Additional profile address " + ia, ia.toByteArray());
		}
		catch (final Exception e) {}

		// read device service control
		try {
			final byte[] svcCtrl = read(objectIdx, PID.SERVICE_CONTROL, 1, 1);
			final boolean indAddrWriteEnabled = (svcCtrl[1] & 0x04) == 0x04;
			putResult(InternalParameter.IndividualAddressWriteEnabled, indAddrWriteEnabled ? "yes" : "no", svcCtrl[1] & 0x04);
			final int services = svcCtrl[0] & 0xff;
			final String formatted = String.format("%8s", Integer.toBinaryString(services)).replace(' ', '0');
			putResult(InternalParameter.ServiceControl,
					"Disabled services on EMI [Mgmt App TL-conn Switch TL-group Network Link User]: " + formatted,
					services);
		}
		catch (final Exception e) {}

		// RF domain address
		// Device object RF domain address is mandatory if the cEMI server supports RF.
		// With mask 0x2311, the RF domain address is mandatory in the RF medium object (PID 56) and
		// optional in the device object (PID 82). At least the Weinzierl USB stores it only in the device object.
		try {
			final byte[] doaAddr = read(objectIdx, PID.RF_DOMAIN_ADDRESS, 1, 1);
			if (doaAddr != null)
				putResult(CommonParameter.DomainAddress, DataUnitBuilder.toHex(doaAddr, ""), toUnsigned(doaAddr));
		}
		catch (final Exception e) {}

		readUnsigned(objectIdx, PID.MAX_APDULENGTH, false, CommonParameter.MaxApduLength);

		final int pidErrorFlags = 53;
		final byte[] flags = read(objectIdx, pidErrorFlags);
		if (flags != null)
			putResult(InternalParameter.ErrorFlags, errorFlags(flags), toUnsigned(data));
	}

	private static final int legacyPidFilteringModeSelect = 62;
	private static final int legacyPidFilteringModeSupport = 63;

	private void readCemiServerObject(int objectIndex) throws InterruptedException {
		try {
			final byte[] d = read(objectIndex, PropertyAccess.PID.MEDIUM_TYPE, 1, 1);
			if (d != null)
				putResult(CemiParameter.MediumType, mediumTypes(d), toUnsigned(d));
		}
		catch (final Exception e) {}

		// Get supported cEMI communication modes, DLL is mandatory for any cEMI server
		// communication mode can then be set using PID_COMM_MODE
		try {
			final byte[] commModes = read(objectIndex, PID.COMM_MODES_SUPPORTED, 1, 1);
			putResult(CemiParameter.SupportedCommModes, supportedCommModes(commModes), toUnsigned(commModes));
		}
		catch (final Exception e) {}

		try {
			final byte[] d = read(objectIndex, PID.COMM_MODE, 1, 1);
			if (d != null)
				putResult(CemiParameter.SelectedCommMode, commMode(d), toUnsigned(d));
		}
		catch (final Exception e) {}

		// if we deal with a USB stand-alone device, the Device Object stores the IA of the USB interface
		// if we deal with a USB device that is embedded with another end device profile, the Device Object stores
		// the IA of the end device. In that case, the cEMI Server Object holds the IA of the USB interface
		try {
			final byte[] dev = read(objectIndex, PID.CLIENT_DEVICE_ADDRESS, 1, 1);
			final byte[] sna = read(objectIndex, PID.CLIENT_SNA, 1, 1);

			final byte[] addr = new byte[] { sna[0], dev[0] };
			final IndividualAddress ia = new IndividualAddress(addr);
			putResult(CemiParameter.ClientAddress, "USB cEMI client address " + ia, ia.toByteArray());
		}
		catch (final Exception e) {}

		// filtering modes
		readSupportedFilteringModes(objectIndex, PID.FILTERING_MODE_SUPPORT);
		readSelectedFilteringMode(objectIndex, PID.FILTERING_MODE_SELECT);

		// do the same stuff again using the legacy PIDs for filtering mode
		readSupportedFilteringModes(objectIndex, legacyPidFilteringModeSupport);
		readSelectedFilteringMode(objectIndex, legacyPidFilteringModeSelect);

		// read supported and selected RF communication mode
		try {
			cEmiExtensionRfBiBat(objectIndex);
		}
		catch (final Exception e) {}

		try {
			final byte[] data = read(objectIndex, PID.RF_MODE_SELECT, 1, 1);
			final int selected = data[0] & 0xff;
			final boolean slave = (data[0] & 0x04) == 0x04;
			final boolean master = (data[0] & 0x02) == 0x02;
			final boolean async = (data[0] & 0x01) == 0x01;
			final String formatted = "BiBat slave " + slave + ", BiBat master " + master + ", async " + async;
			putResult(CemiParameter.SelectedRfMode, formatted, selected);
		}
		catch (final Exception e) {}
	}

	//         		Supports/Disable filtering on:
	//      ------------------------------------------------------
	// Bit  |  15 ... 4  |     3     |  2  |     1    |  0       |
	// Name |  Reserved  | Ext. Grp  | DoA | Repeated | Own Ind. |
	//      |            | Addresses |     |  Frames  | Address  |
	//      ------------------------------------------------------
	private void readSupportedFilteringModes(int objectIndex, final int pid) {
		try {
			final byte[] filters = read(objectIndex, pid, 1, 1);
			final int filter = filters[1] & 0xff;
			final boolean grp = (filter & 0x08) == 0x08;
			final boolean doa = (filter & 0x04) == 0x04;
			final boolean rep = (filter & 0x02) == 0x02;
			final boolean ownIa = (filter & 0x01) == 0x01;
			putResult(CemiParameter.SupportedFilteringModes, "ext. group addresses " + grp
					+ ", domain address " + doa + ", repeated frames " + rep + ", own individual address " + ownIa,
					filters);
		}
		catch (final Exception e) {}
	}

	// Check disabled frame filters in the device
	// A set bit (1) indicates a disabled filter, by default all filters are active
	private void readSelectedFilteringMode(int objectIndex, final int pid) {
		try {
			final byte[] filters = read(objectIndex, pid, 1, 1);
			final int selected = filters[1] & 0xff;
			final boolean grp = (selected & 0x08) == 0x08;
			final boolean doa = (selected & 0x04) == 0x04;
			final boolean rep = (selected & 0x02) == 0x02;
			final boolean ownIa = (selected & 0x01) == 0x01;
			if (selected == 0)
				putResult(CemiParameter.SelectedFilteringModes, "all supported filters active", selected);
			else
				putResult(CemiParameter.SelectedFilteringModes, "disabled frame filters: ext. group addresses " + grp
						+ ", domain address " + doa + ", repeated frames " + rep + ", own individual address " + ownIa,
						selected);
		}
		catch (final Exception e) {}
	}

	private void cEmiExtensionRfBiBat(int objectIndex) throws KNXException, InterruptedException {
		final byte[] support = read(objectIndex, PID.RF_MODE_SUPPORT, 1, 1);
		final boolean slave = (support[0] & 0x04) == 0x04;
		final boolean master = (support[0] & 0x02) == 0x02;
		final boolean async = (support[0] & 0x01) == 0x01;

		final String formatted = "BiBat slave " + slave + ", BiBat master " + master + ", Async " + async;
		putResult(CemiParameter.SupportedRfModes, formatted, support);
	}

	//      --------------------------------------
	// Bit  |  15 ... 4  |  3  |  2  |  1  |  0  |
	// Name |  Reserved  | TLL | RAW | BM  | DLL |
	//      --------------------------------------
	//
	// TLL: Transport layer local
	// RAW: Data link layer, RAW mode (receive L-Raw.req, L-Raw.ind from the bus)
	// BM: Data link layer, busmonitor mode
	// DLL: Data link layer, normal mode
	private String supportedCommModes(final byte[] commModes) {
		final int modes = commModes[1] & 0xff;
		final boolean tll = (modes & 0x08) == 0x08;
		final boolean raw = (modes & 0x04) == 0x04;
		final boolean bm = (modes & 0x02) == 0x02;
		final boolean dll = (modes & 0x01) == 0x01;
		final String s = "Transport layer local " + tll + ", Data link layer modes: normal " + dll + ", busmonitor "
				+ bm + ", raw mode " + raw;
		return s;
	}

	private String commMode(final byte[] data) {
		final int commMode = data[0] & 0xff;
		switch (commMode) {
		case 0:
			return "Data link layer";
		case 1:
			return "Data link layer busmonitor";
		case 2:
			return "Data link layer raw frames";
		case 6:
			return "cEMI transport layer";
		case 0xff:
			return "no layer";
		}
		return "unknown/unspecified (" + commMode + ")";
	}

	private void readRFMediumObject(int objectIndex) {
		try {
			// different PID as in Device Object !!!
			final int pidRfDomainAddress = 56;
			final byte[] doaAddr = read(objectIndex, pidRfDomainAddress, 1, 1);
			if (doaAddr != null)
				putResult(RfParameter.DomainAddress, "0x" + DataUnitBuilder.toHex(doaAddr, ""), doaAddr);
		}
		catch (final Exception e) {}
	}

	// verbose info what the BCU is currently doing
	private void readSystemState() throws InterruptedException
	{
		int state = readMem(0x60, 1);
		// Bit 7 is parity (even parity)
		state &= 0x7f;

		final String[] mode = new String[] { "Programming mode", "Normal operation", // else busmonitor mode
			"Transport layer", "Application layer", "Serial PEI interface (msg protocol)", "User program",
			"Programming mode (ind. address)" // else normal operation
		};

		final StringBuilder sb = new StringBuilder();
		for (int bit = 0; bit < 7; bit++)
			if ((state & (1 << bit)) != 0)
				sb.append(mode[bit]).append(", ");
		putResult(CommonParameter.SystemState, sb.toString(), state);

		// reading back legal values, although there is no 1:1 mapping
//		String layer = null;
//		if (state == 0x90)
//			layer = "Busmonitor";
//		else if (state == 0x12)
//			layer = "Link layer";
//		else if (state == 0x96)
//			layer = "Transport layer";
//		else if (state == 0x1E)
//			layer = "Application layer";
//		else if (state == 0xC0)
//			layer = "Reset";
//		if (layer != null)
//			putResult(Parameter.SystemState, layer, state);
	}

	private void readActualPeiType() throws InterruptedException
	{
		if (mc == null)
			return;
		final int channel = 4;
		final int repeat = 1;
		try {
			final int v = mc.readADC(d, channel, repeat);
			final int peitype = (10 * v + 60) / 128;
			putResult(CommonParameter.ActualPeiType, toPeiTypeString(peitype), peitype);
		}
		catch (final KNXException e) {
			out.error("reading actual PEI type (A/D converter channel {}, repeat {})", channel, repeat, e);
		}
	}

	// same as BCU error flags located at 0x10d
	private String errorFlags(final byte[] data) {
		if ((data[0] & 0xff) == 0xff)
			return "everything OK";
		final String[] description = { "System 1 internal system error", "Illegal system state",
			"Checksum / CRC error in internal non-volatile memory", "Stack overflow error",
			"Inconsistent system tables", "Physical transceiver error", "System 2 internal system error",
			"System 3 internal system error" };
		final List<String> errors = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			if ((data[0] & (1 << i)) == 0)
				errors.add(description[i]);
		return errors.stream().collect(Collectors.joining(", "));
	}

	private void putResult(final Parameter p, final long raw)
	{
		putResult(p, "" + raw, raw);
	}

	private void putResult(final Parameter p, final String formatted, final long raw)
	{
		putResult(p, formatted, ByteBuffer.allocate(Long.BYTES).putLong(raw).array());
	}

	private void putResult(final Parameter p, final String formatted, final int raw)
	{
		putResult(p, formatted, ByteBuffer.allocate(Integer.BYTES).putInt(raw).array());
	}

	private void putResult(final Parameter p, final String formatted, final byte[] raw)
	{
		result.formatted.put(p, formatted);
		result.raw.put(p, raw);
		onDeviceInformation(p, formatted, raw);
	}

	private static final int addrManufact = 0x0104;
	private static final int addrDevType = 0x0105; // length 2
	private static final int addrVersion = 0x0107;
	private static final int addrPeiType = 0x0109; // _required_ PEI type
	private static final int addrRunError = 0x010d;
	private static final int addrRoutingCnt = 0x010e;
	private static final int addrGroupObjTablePtr = 0x0112;
//	private static final int addrProgramPtr = 0x0114;
	private static final int addrGroupAddrTable = 0x0116; // max. length 233
	private static final int addrGroupAddrTableMask5705 = 0x4000; // max. length impl. dependent

	private void readPL110Bcu1() throws InterruptedException
	{
		final int addrDoA = 0x0102; // length 2

		readMem(addrDoA, 2, "DoA ", true, CommonParameter.DomainAddress);
		readBcuInfo();
	}

	private void readTP1Bcu1() throws InterruptedException
	{
		// Option Reg: bit 1: mem 0x120-0x1ff protected/writable
//		final int addrOptionReg = 0x100;
		final int addrManufactData = 0x0101; // length 3
//		final int addrMxRstCnt = 0x010f; // bits (msb): 3 INAK / 2 (unused) / 3 BUSY (lsb)
//		final int addrConfigDesc = 0x0110;
//		final int addrAssocTablePtr = 0x0111;

		readMem(addrManufactData, 3, "KNX manufacturer data ", true, CommonParameter.ManufacturerData);

		readBcuInfo();
	}

	private void readTP1Bcu2() throws InterruptedException, KNXException
	{
		// Option Reg: bit 0: watchdog disabled/enabled, bit 1: mem 0x300-0x4df protected/writable
//		final int addrOptionReg = 0x100;

		final int addrManufactData = 0x0101; // length 2
		// App Id, length 5: App manufacturer (2), SW dev type (2), SW version (1)
		// the App manufacturer can differ from product manufacturer (0x101), if a compatible program was downloaded
		final int addrAppId = 0x0103;

		// address table realization type 2
//		final int addrUsrEeprom = 0x0116; // max. length 858: Addr Table, Assoc Table, EEData, Code

		// Page 0 RAM
//		final int addrPeiInterface = 0x00c4; // if used
//		final int addrPeiInfo = 0x00c5; // if used

		readMem(addrManufactData, 3, "KNX manufacturer data ", true, CommonParameter.ManufacturerData);
		final long appId = readMemLong(addrAppId, 5);
		final String appMf = manufacturer.get((int) appId >> (3 * 8));
		final long swDev = (appId >> 8) & 0xff;
		final long swVersion = appId & 0xff;

		out.info("appId 0x{} - app manufacturer: {}, SW dev type {}, SW version {}", Long.toHexString(appId),
				appMf, swDev, swVersion);

		readBcuInfo();

		// interface objects: Device Object, Address table object, Assoc table object, App program object
		findInterfaceObjects();
	}

	private void readBcuInfo() throws InterruptedException
	{
		readMem(addrManufact, 1, "KNX manufacturer ID ", DeviceInfo::manufacturer, CommonParameter.Manufacturer);
		readMem(addrDevType, 2, "Device type number ", true, CommonParameter.DeviceTypeNumber);
		readMem(addrVersion, 1, "SW version ", v -> (v >> 4) + "." + (v & 0xf), CommonParameter.SoftwareVersion);
		// mechanical PEI type required by the application SW
		readMem(addrPeiType, 1, "Hardware PEI type ", DeviceInfo::toPeiTypeString, CommonParameter.RequiredPeiType);
		readMem(addrRunError, 1, "Run error 0x", DeviceInfo::decodeRunError, CommonParameter.RunError);

		readSystemState();

		readMem(addrRoutingCnt, 1, "Routing count ", v -> Integer.toString((v >> 4) & 0x7),
				CommonParameter.RoutingCount);
		// realization type 1
		// Location of group object table
		readMem(addrGroupObjTablePtr, 1, "Group object table location ", true, CommonParameter.GroupObjTableLocation);
		readGroupAddresses();

		// Location of user program 0x100 + progptr
//		final int progptr = readMem(addrProgramPtr, 1);
//		final int userprog = 0x100 + progptr;
	}

	private void readGroupAddresses() throws InterruptedException
	{
		final int memLocation;
		// realization type 8
		if (dd.equals(DD0.TYPE_5705))
			memLocation = addrGroupAddrTableMask5705;
		else if (ifObjects.containsKey(addresstableObject)) {
			int addresstableObjectIdx = ifObjects.get(addresstableObject).get(0);
			int tableSize = readElements(addresstableObjectIdx, PID.TABLE);
			if (tableSize > 0) {
				StringJoiner joiner = new StringJoiner(", ");
				for (int i = 0; i < tableSize; i++) {
					GroupAddress group = new GroupAddress(read(addresstableObjectIdx, PID.TABLE, i + 1, 1));
					joiner.add(group.toString());
				}
				putResult(CommonParameter.GroupAddresses, joiner.toString(), new byte[0]);
				return;
			}
			memLocation = (int) toUnsigned(read(addresstableObjectIdx, PID.TABLE_REFERENCE));
			if (memLocation <= 0)
				return;
		}
		else
			// realization type 1
			memLocation = addrGroupAddrTable;

		// realization type 1
		final int entries = readMem(memLocation, 1, "Group address table entries ", false,
				CommonParameter.GroupAddressTableEntries);

		// address of device address
		int startAddr = memLocation + 1;
		readMem(startAddr, 2, "", v -> new IndividualAddress(v & 0x7fff).toString(), CommonParameter.DeviceAddress);

		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < entries; i++) {
			startAddr += 2;
			final int raw = readMem(startAddr, 2);
			final KNXAddress group = new GroupAddress(raw & 0x7fff);
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(group);
			// are we the group responder
			if ((raw & 0x8000) == 0x8000)
				sb.append("(R)");
		}
		putResult(CommonParameter.GroupAddresses, sb.toString(), new byte[0]);
	}

	private void readKnxipInfo(int objectIndex) throws KNXException, InterruptedException
	{
		// Device Name (friendly)
		read(KnxipParameter.DeviceName, () -> readFriendlyName(objectIndex));

		// Device Capabilities Device State
		byte[] data = read(objectIndex, PropertyAccess.PID.KNXNETIP_DEVICE_CAPABILITIES);
		if (data == null)
			return;
		putResult(KnxipParameter.Capabilities, toCapabilitiesString(data), data);
		final boolean supportsTunneling = (data[1] & 0x01) == 0x01;

		// MAC Address
		data = read(objectIndex, PropertyAccess.PID.MAC_ADDRESS);
		putResult(KnxipParameter.MacAddress, DataUnitBuilder.toHex(data, ":"), toUnsigned(data));
		// Current IP Assignment
		data = read(objectIndex, PropertyAccess.PID.CURRENT_IP_ASSIGNMENT_METHOD);
		putResult(KnxipParameter.CurrentIPAssignment, toIPAssignmentString(data), toUnsigned(data));
		final int currentIPAssignment = data[0] & 0x0f;
		// Bits (from LSB): Manual=0, BootP=1, DHCP=2, AutoIP=3
		final boolean dhcpOrBoot = (data[0] & 0x06) != 0;

		// Read currently set IP parameters
		// IP Address
		final byte[] currentIP = read(objectIndex, PropertyAccess.PID.CURRENT_IP_ADDRESS);
		putResult(KnxipParameter.CurrentIPAddress, toIP(currentIP), toUnsigned(currentIP));
		// Subnet Mask
		final byte[] currentMask = read(objectIndex, PropertyAccess.PID.CURRENT_SUBNET_MASK);
		putResult(KnxipParameter.CurrentSubnetMask, toIP(currentMask), toUnsigned(currentMask));
		// Default Gateway
		final byte[] currentGw = read(objectIndex, PropertyAccess.PID.CURRENT_DEFAULT_GATEWAY);
		putResult(KnxipParameter.CurrentDefaultGateway, toIP(currentGw), toUnsigned(currentGw));
		// DHCP Server (show only if current assignment method is DHCP or BootP)
		if (dhcpOrBoot) {
			data = read(objectIndex, PropertyAccess.PID.DHCP_BOOTP_SERVER);
			putResult(KnxipParameter.DhcpServer, toIP(data), toUnsigned(data));
		}

		// IP Assignment Method (shown only if different from current IP assign. method)
		data = read(objectIndex, PropertyAccess.PID.IP_ASSIGNMENT_METHOD);
		final int ipAssignment = data[0] & 0x0f;
		if (ipAssignment != currentIPAssignment) {
			putResult(KnxipParameter.ConfiguredIPAssignment, ipAssignment);
		}
		// Read IP parameters for manual assignment
		// the following info is only shown if manual assignment method is enabled, and parameter
		// is different from current one
		final boolean manual = (ipAssignment & 0x01) == 0x01;
		if (manual) {
//			info.append("Differing manual configuration:\n");
			// Manual IP Address
			final byte[] ip = read(objectIndex, PropertyAccess.PID.IP_ADDRESS);
			if (!Arrays.equals(currentIP, ip))
				putResult(KnxipParameter.IPAddress, toIP(ip), toUnsigned(ip));
			// Manual Subnet Mask
			final byte[] mask = read(objectIndex, PropertyAccess.PID.SUBNET_MASK);
			if (!Arrays.equals(currentMask, mask))
				putResult(KnxipParameter.SubnetMask, toIP(mask), toUnsigned(mask));
			// Manual Default Gateway
			final byte[] gw = read(objectIndex, PropertyAccess.PID.DEFAULT_GATEWAY);
			if (!Arrays.equals(currentGw, gw))
				putResult(KnxipParameter.DefaultGateway, toIP(gw), toUnsigned(gw));
		}

		// Routing Multicast Address
		data = read(objectIndex, PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS);
		putResult(KnxipParameter.RoutingMulticast, toIP(data), toUnsigned(data));
		// Multicast TTL
		readUnsigned(objectIndex, PropertyAccess.PID.TTL, false, KnxipParameter.TimeToLive);
		// Messages to Multicast Address
		readUnsigned(objectIndex, PID.MSG_TRANSMIT_TO_IP, false, KnxipParameter.TransmitToIP);

		// Additional Ind. Addresses (shown only if tunneling is implemented)
		if (supportsTunneling) {
			final int pid = PID.ADDITIONAL_INDIVIDUAL_ADDRESSES;
			final int elements = readElements(objectIndex, pid);
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < elements; i++) {
				data = read(objectIndex, pid);
				sb.append(new IndividualAddress(data)).append(" ");
			}
			putResult(KnxipParameter.AdditionalIndividualAddresses, sb.toString(), new byte[0]);
		}
	}

	private void readProgram(final int objectIdx) throws InterruptedException
	{
		byte[] data = read(objectIdx, PID.PROGRAM_VERSION);
		if (data != null)
			putResult(CommonParameter.ProgramVersion, programVersion(data), data);
		readLoadState(objectIdx);

		data = read(objectIdx, PropertyAccess.PID.RUN_STATE_CONTROL);
		if (data != null)
			putResult(CommonParameter.RunStateControl, getRunState(data), data);
	}

	private static String programVersion(final byte[] data) {
		if (data.length != 5)
			return DataUnitBuilder.toHex(data, "");
		final int mfr = (data[0] & 0xff) << 8 | (data[1] & 0xff);
		return String.format("%s %02x%02x v%d.%d", manufacturer(mfr), data[2], data[3], (data[4] & 0xff) >> 4, data[4] & 0xf);
	}

	private void readLoadState(final int objectIdx) throws InterruptedException
	{
		final boolean hasErrorCode = isSystemB;

		byte[] data = read(objectIdx, PropertyAccess.PID.LOAD_STATE_CONTROL);
		final String ls = getLoadState(data);
		putResult(CommonParameter.LoadStateControl, ls, data == null ? new byte[0] : data);
		// System B contains error code for load state "Error" (optional, but usually yes)
		if (data != null && data[0] == 3 && hasErrorCode) {
			data = read(objectIdx, PropertyAccess.PID.ERROR_CODE);
			if (data != null) {
				try {
					// enum ErrorClassSystem
					final DPTXlator t = TranslatorTypes.createTranslator(0, "20.011");
					t.setData(data);
					putResult(CommonParameter.LoadStateError, t.getValue(), data);
				}
				catch (final KNXException e) {
					// no translator
				}
			}
		}
	}

	private void read(final Parameter p, final Callable<String> c) throws KNXLinkClosedException, InterruptedException {
		try {
			out.debug("read {} ...", p);
			final String s = c.call();
			putResult(p, s, s.getBytes(StandardCharsets.ISO_8859_1));
		}
		catch (InterruptedException | KNXLinkClosedException e) {
			throw e;
		}
		catch (final KNXRemoteException e) {
			out.warn("reading {}: {}", p, e.getMessage());
		}
		catch (final Exception e) {
			out.error("error reading {}", p, e);
		}
	}

	private String readFriendlyName(int objectIndex) throws KNXException, InterruptedException
	{
		final char[] name = new char[30];
		int start = 0;
		while (true) {
			final byte[] data = pc.getProperty(objectIndex, PID.FRIENDLY_NAME, start + 1, 10);
			for (int i = 0; i < 10 && data[i] != 0; ++i, ++start)
				name[start] = (char) (data[i] & 0xff);
			if (start >= 30 || data[9] == 0)
				return new String(name, 0, start);
		}
	}

	private int readElements(final int objectIndex, final int pid) throws InterruptedException
	{
		final byte[] elems = read(objectIndex, pid, 0, 1);
		return elems == null ? -1 : (int) toUnsigned(elems);
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
				final byte[] data = pc.getProperty(objectIndex, pid, i, 1);
				res.write(data, 0, data.length);
			}
			return res.toByteArray();
		}
		catch (final KNXException e) {
			out.debug("reading KNX property " + objectIndex + "|" + pid + ": " + e.getMessage());
		}
		return null;
	}

	private void readUnsigned(final int objectIndex, final int pid, final boolean hex, final Parameter p)
		throws InterruptedException
	{
		final byte[] data = read(objectIndex, pid);
		if (data == null) {
			result.formatted.put(p, "-");
			result.raw.put(p, new byte[0]);
		}
		else {
			final String formatted = hex ? DataUnitBuilder.toHex(data, "") : Long.toString(toUnsigned(data));
			putResult(p, formatted, data);
		}
	}

	private int readMem(final int startAddr, final int bytes, final String prefix, final boolean hex, final Parameter p)
		throws InterruptedException
	{
		final long v = readMemLong(startAddr, bytes);
		putResult(p, hex ? Long.toHexString(v) : Long.toString(v), v);
		return (int) v;
	}

	private void readMem(final int startAddr, final int bytes, final String prefix,
		final Function<Integer, String> representation, final Parameter p) throws InterruptedException
	{
		final int v = readMem(startAddr, bytes);
		putResult(p, representation.apply(v), v);
	}

	// pre: 3 bytes max
	private int readMem(final int startAddr, final int bytes) throws InterruptedException
	{
		return (int) readMemLong(startAddr, bytes);
	}

	// pre: 7 bytes max
	private long readMemLong(final int startAddr, final int bytes) throws InterruptedException
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
	 *
	 * @return the KNX network link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkLink createLink() throws KNXException, InterruptedException
	{
		return Main.newLink(options);
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
		// default subnetwork address for TP1 and unregistered device
		options.put("knx-address", new IndividualAddress(0, 0x02, 0xff));

		for (final var i = List.of(args).iterator(); i.hasNext(); ) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) DeviceInfo::showUsage);
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

		if (!options.containsKey("host") || (options.containsKey("ft12") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		if (!options.containsKey("device")) {
			// we will read device info using cEMI local device management, check some invalid options
			// supported is knxnet/ip and usb
			final String adapter = options.containsKey("ft12") ? "FT1.2"
					: options.containsKey("tpuart") ? "TP-UART" : "";
			if (!adapter.isEmpty())
				throw new KNXIllegalArgumentException("reading device info of local " + adapter
						+ " interface is not supported, specify remote KNX device address");

			if (options.containsKey("medium") || options.containsKey("domain"))
				throw new KNXIllegalArgumentException("missing remote KNX device address");
		}
		if (!options.containsKey("medium"))
			options.put("medium", TPSettings.TP1);
		Main.setDomainAddress(options);
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(System.lineSeparator());
		joiner.add("Usage: " + tool + " [options] <host|port> [KNX device address]");
		Main.printCommonOptions(joiner);
		Main.printSecureOptions(joiner);
		out(joiner.toString());
	}

	private static void out(final String s)
	{
		System.out.println(s);
	}

	private static long toUnsigned(final byte[] data)
	{
		// XXX remove again
		if (data == null || data.length > 8)
			return -1;
		long value = 0;
		for (final byte b : data) {
			value = value << 8 | (b & 0xff);
		}
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

	private static String mediumTypes(final byte[] data) throws KNXException {
		return TranslatorTypes.createTranslator(DptXlator16BitSet.DptMedia, data).getValue();
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
		if (peitype == -1 || peitype == 0xff)
			return "n/a";
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
		final var joiner = new StringJoiner(", ");
		if ((data[1] & 0x01) == 0x01)
			joiner.add("Device Management");
		if ((data[1] & 0x02) == 0x02)
			joiner.add("Tunneling");
		if ((data[1] & 0x04) == 0x04)
			joiner.add("Routing");
		if ((data[1] & 0x08) == 0x08)
			joiner.add("Remote Logging");
		if ((data[1] & 0x10) == 0x10)
			joiner.add("Remote Configuration and Diagnosis");
		if ((data[1] & 0x20) == 0x20)
			joiner.add("Object Server");
		return joiner.toString();
	}

	private static String knxSerialNumber(final byte[] data) {
		final var hex = DataUnitBuilder.toHex(data, "");
		return hex.substring(0, 4) + ":" + hex.substring(4);
	}

	static String manufacturer(final int mf) {
		return manufacturer.getOrDefault(mf, "Unknown");
	}

	// KNX manufacturer IDs as of 2015
	private static final Map<Integer, String> manufacturer = new HashMap<>();
	static {
		manufacturer.put(1, "Siemens");
		manufacturer.put(2, "ABB");
		manufacturer.put(4, "Albrecht Jung");
		manufacturer.put(5, "Bticino");
		manufacturer.put(6, "Berker");
		manufacturer.put(7, "Busch-Jaeger Elektro");
		manufacturer.put(8, "GIRA Giersiepen");
		manufacturer.put(9, "Hager Electro");
		manufacturer.put(10, "INSTA ELEKTRO");
		manufacturer.put(11, "LEGRAND Appareillage électrique");
		manufacturer.put(12, "Merten");
		manufacturer.put(14, "ABB SpA – SACE Division");
		manufacturer.put(22, "Siedle & Söhne");
		manufacturer.put(24, "Eberle");
		manufacturer.put(25, "GEWISS");
		manufacturer.put(27, "Albert Ackermann");
		manufacturer.put(28, "Schupa GmbH");
		manufacturer.put(29, "ABB SCHWEIZ");
		manufacturer.put(30, "Feller");
		manufacturer.put(31, "Glamox AS");
		manufacturer.put(32, "DEHN & SÖHNE");
		manufacturer.put(33, "CRABTREE");
		manufacturer.put(36, "Paul Hochköpper");
		manufacturer.put(37, "Altenburger Electronic");
		manufacturer.put(41, "Grässlin");
		manufacturer.put(42, "Simon");
		manufacturer.put(44, "VIMAR");
		manufacturer.put(45, "Moeller Gebäudeautomation KG");
		manufacturer.put(46, "Eltako");
		manufacturer.put(49, "Bosch-Siemens Haushaltsgeräte");
		manufacturer.put(52, "RITTO GmbH&Co.KG");
		manufacturer.put(53, "Power Controls");
		manufacturer.put(55, "ZUMTOBEL");
		manufacturer.put(57, "Phoenix Contact");
		manufacturer.put(61, "WAGO Kontakttechnik");
		manufacturer.put(66, "Wieland Electric");
		manufacturer.put(67, "Hermann Kleinhuis");
		manufacturer.put(69, "Stiebel Eltron");
		manufacturer.put(71, "Tehalit");
		manufacturer.put(72, "Theben AG");
		manufacturer.put(73, "Wilhelm Rutenbeck");
		manufacturer.put(75, "Winkhaus");
		manufacturer.put(76, "Robert Bosch");
		manufacturer.put(78, "Somfy");
		manufacturer.put(80, "Woertz");
		manufacturer.put(81, "Viessmann Werke");
		manufacturer.put(82, "Theodor HEIMEIER Metallwerk");
		manufacturer.put(83, "Joh. Vaillant");
		manufacturer.put(85, "AMP Deutschland");
		manufacturer.put(89, "Bosch Thermotechnik GmbH");
		manufacturer.put(90, "SEF - ECOTEC");
		manufacturer.put(92, "DORMA GmbH + Co. KG");
		manufacturer.put(93, "WindowMaster A/S");
		manufacturer.put(94, "Walther Werke");
		manufacturer.put(95, "ORAS");
		manufacturer.put(97, "Dätwyler");
		manufacturer.put(98, "Electrak");
		manufacturer.put(99, "Techem");
		manufacturer.put(100, "Schneider Electric Industries SAS");
		manufacturer.put(101, "WHD Wilhelm Huber + Söhne");
		manufacturer.put(102, "Bischoff Elektronik");
		manufacturer.put(104, "JEPAZ");
		manufacturer.put(105, "RTS Automation");
		manufacturer.put(106, "EIBMARKT GmbH");
		manufacturer.put(107, "WAREMA electronic GmbH");
		manufacturer.put(108, "Eelectron");
		manufacturer.put(109, "Belden Wire & Cable B.V.");
		manufacturer.put(110, "Becker-Antriebe GmbH");
		manufacturer.put(111, "J.Stehle+Söhne GmbH");
		manufacturer.put(112, "AGFEO");
		manufacturer.put(113, "Zennio");
		manufacturer.put(114, "TAPKO Technologies");
		manufacturer.put(115, "HDL");
		manufacturer.put(116, "Uponor");
		manufacturer.put(117, "se Lightmanagement AG");
		manufacturer.put(118, "Arcus-eds");
		manufacturer.put(119, "Intesis");
		manufacturer.put(120, "Herholdt Controls srl");
		manufacturer.put(121, "Zublin AG");
		manufacturer.put(122, "Durable Technologies");
		manufacturer.put(123, "Innoteam");
		manufacturer.put(124, "ise GmbH");
		manufacturer.put(125, "TEAM FOR TRONICS");
		manufacturer.put(126, "CIAT");
		manufacturer.put(127, "Remeha BV");
		manufacturer.put(128, "ESYLUX");
		manufacturer.put(129, "BASALTE");
		manufacturer.put(130, "Vestamatic");
		manufacturer.put(131, "MDT technologies");
		manufacturer.put(132, "Warendorfer Küchen GmbH");
		manufacturer.put(133, "Video-Star");
		manufacturer.put(134, "Sitek");
		manufacturer.put(135, "CONTROLtronic");
		manufacturer.put(136, "function Technology");
		manufacturer.put(137, "AMX");
		manufacturer.put(138, "ELDAT");
		manufacturer.put(139, "Panasonic");
		manufacturer.put(140, "Pulse Technologies");
		manufacturer.put(141, "Crestron");
		manufacturer.put(142, "STEINEL professional");
		manufacturer.put(143, "BILTON LED Lighting");
		manufacturer.put(144, "denro AG");
		manufacturer.put(145, "GePro");
		manufacturer.put(146, "preussen automation");
		manufacturer.put(147, "Zoppas Industries");
		manufacturer.put(148, "MACTECH");
		manufacturer.put(149, "TECHNO-TREND");
		manufacturer.put(150, "FS Cables");
		manufacturer.put(151, "Delta Dore");
		manufacturer.put(152, "Eissound");
		manufacturer.put(153, "Cisco");
		manufacturer.put(154, "Dinuy");
		manufacturer.put(155, "iKNiX");
		manufacturer.put(156, "Rademacher Geräte-Elektronik GmbH & Co. KG");
		manufacturer.put(157, "EGi Electroacustica General Iberica");
		manufacturer.put(158, "Ingenium");
		manufacturer.put(159, "ElabNET");
		manufacturer.put(160, "Blumotix");
		manufacturer.put(161, "Hunter Douglas");
		manufacturer.put(162, "APRICUM");
		manufacturer.put(163, "TIANSU Automation");
		manufacturer.put(164, "Bubendorff");
		manufacturer.put(165, "MBS GmbH");
		manufacturer.put(166, "Enertex Bayern GmbH");
		manufacturer.put(167, "BMS");
		manufacturer.put(168, "Sinapsi");
		manufacturer.put(169, "Embedded Systems SIA");
		manufacturer.put(170, "KNX1");
		manufacturer.put(171, "Tokka");
		manufacturer.put(172, "NanoSense");
		manufacturer.put(173, "PEAR Automation GmbH");
		manufacturer.put(174, "DGA");
		manufacturer.put(175, "Lutron");
		manufacturer.put(176, "AIRZONE – ALTRA");
		manufacturer.put(177, "Lithoss Design Switches");
		manufacturer.put(178, "3ATEL");
		manufacturer.put(179, "Philips Controls");
		manufacturer.put(180, "VELUX A/S");
		manufacturer.put(181, "LOYTEC");
		manufacturer.put(182, "SBS S.p.A.");
		manufacturer.put(183, "SIRLAN Technologies");
		manufacturer.put(184, "Bleu Comm' Azur");
		manufacturer.put(185, "IT GmbH");
		manufacturer.put(186, "RENSON");
		manufacturer.put(187, "HEP Group");
		manufacturer.put(188, "Balmart");
		manufacturer.put(189, "GFS GmbH");
		manufacturer.put(190, "Schenker Storen AG");
		manufacturer.put(191, "Algodue Elettronica S.r.L.");
		manufacturer.put(192, "Newron System");
		manufacturer.put(193, "maintronic");
		manufacturer.put(194, "Vantage");
		manufacturer.put(195, "Foresis");
		manufacturer.put(196, "Research & Production Association SEM");
		manufacturer.put(197, "Weinzierl Engineering GmbH");
		manufacturer.put(198, "Möhlenhoff Wärmetechnik GmbH");
		manufacturer.put(199, "PKC-GROUP Oyj");
		manufacturer.put(200, "B.E.G.");
		manufacturer.put(201, "Elsner Elektronik GmbH");
		manufacturer.put(202, "Siemens Building Technologies (HK/China) Ltd.");
		manufacturer.put(204, "Eutrac");
		manufacturer.put(205, "Gustav Hensel GmbH & Co. KG");
		manufacturer.put(206, "GARO AB");
		manufacturer.put(207, "Waldmann Lichttechnik");
		manufacturer.put(208, "SCHÜCO");
		manufacturer.put(209, "EMU");
		manufacturer.put(210, "JNet Systems AG");
		manufacturer.put(214, "O.Y.L. Electronics");
		manufacturer.put(215, "Galax System");
		manufacturer.put(216, "Disch");
		manufacturer.put(217, "Aucoteam");
		manufacturer.put(218, "Luxmate Controls");
		manufacturer.put(219, "Danfoss");
		manufacturer.put(220, "AST GmbH");
		manufacturer.put(222, "WILA Leuchten");
		manufacturer.put(223, "b+b Automations- und Steuerungstechnik");
		manufacturer.put(225, "Lingg & Janke");
		manufacturer.put(227, "Sauter");
		manufacturer.put(228, "SIMU");
		manufacturer.put(232, "Theben HTS AG");
		manufacturer.put(233, "Amann GmbH");
		manufacturer.put(234, "BERG Energiekontrollsysteme GmbH");
		manufacturer.put(235, "Hüppe Form Sonnenschutzsysteme GmbH");
		manufacturer.put(237, "Oventrop KG");
		manufacturer.put(238, "Griesser AG");
		manufacturer.put(239, "IPAS GmbH");
		manufacturer.put(240, "elero GmbH");
		manufacturer.put(241, "Ardan Production and Industrial Controls Ltd.");
		manufacturer.put(242, "Metec Meßtechnik GmbH");
		manufacturer.put(244, "ELKA-Elektronik GmbH");
		manufacturer.put(245, "ELEKTROANLAGEN D. NAGEL");
		manufacturer.put(246, "Tridonic Bauelemente GmbH");
		manufacturer.put(248, "Stengler Gesellschaft");
		manufacturer.put(249, "Schneider Electric (MG)");
		manufacturer.put(250, "KNX Association");
		manufacturer.put(251, "VIVO");
		manufacturer.put(252, "Hugo Müller GmbH & Co KG");
		manufacturer.put(253, "Siemens HVAC");
		manufacturer.put(254, "APT");
		manufacturer.put(256, "HighDom");
		manufacturer.put(257, "Top Services");
		manufacturer.put(258, "ambiHome");
		manufacturer.put(259, "DATEC electronic AG");
		manufacturer.put(260, "ABUS Security-Center");
		manufacturer.put(261, "Lite-Puter");
		manufacturer.put(262, "Tantron Electronic");
		manufacturer.put(263, "Yönnet");
		manufacturer.put(264, "DKX Tech");
		manufacturer.put(265, "Viatron");
		manufacturer.put(266, "Nautibus");
		manufacturer.put(267, "ON Semiconductor");
		manufacturer.put(268, "Longchuang");
		manufacturer.put(269, "Air-On AG");
		manufacturer.put(270, "ib-company GmbH");
		manufacturer.put(271, "SATION");
		manufacturer.put(272, "Agentilo GmbH");
		manufacturer.put(273, "Makel Elektrik");
		manufacturer.put(274, "Helios Ventilatoren");
		manufacturer.put(275, "Otto Solutions Pte Ltd");
		manufacturer.put(276, "Airmaster");
		manufacturer.put(277, "Vallox GmbH");
		manufacturer.put(278, "Dalitek");
		manufacturer.put(279, "ASIN");
		manufacturer.put(280, "Bridges Intelligence Technology Inc.");
		manufacturer.put(281, "ARBONIA");
		manufacturer.put(282, "KERMI");
		manufacturer.put(283, "PROLUX");
		manufacturer.put(284, "ClicHome");
		manufacturer.put(285, "COMMAX");
		manufacturer.put(286, "EAE");
		manufacturer.put(287, "Tense");
		manufacturer.put(288, "Seyoung Electronics");
		manufacturer.put(289, "Lifedomus");
		manufacturer.put(290, "EUROtronic Technology GmbH");
		manufacturer.put(291, "tci");
		manufacturer.put(292, "Rishun Electronic");
		manufacturer.put(293, "Zipato");
		manufacturer.put(294, "cm-security GmbH & Co KG");
		manufacturer.put(295, "Qing Cables");
		manufacturer.put(296, "LABIO");
		manufacturer.put(297, "Coster Tecnologie Elettroniche S.p.A.");
		manufacturer.put(298, "E.G.E");
		manufacturer.put(299, "NETxAutomation");
		manufacturer.put(300, "tecalor");
		manufacturer.put(301, "Urmet Electronics (Huizhou) Ltd.");
		manufacturer.put(302, "Peiying Building Control");
		manufacturer.put(303, "BPT S.p.A. a Socio Unico");
		manufacturer.put(304, "Kanontec - KanonBUS");
		manufacturer.put(305, "ISER Tech");
		manufacturer.put(306, "Fineline");
		manufacturer.put(307, "CP Electronics Ltd");
		manufacturer.put(308, "Servodan A/S");
		manufacturer.put(309, "Simon");
		manufacturer.put(310, "GM modular pvt. Ltd.");
		manufacturer.put(311, "FU CHENG Intelligence");
		manufacturer.put(312, "NexKon");
		manufacturer.put(313, "FEEL s.r.l");
		manufacturer.put(314, "Not Assigned");
		manufacturer.put(315, "Shenzhen Fanhai Sanjiang Electronics Co., Ltd.");
		manufacturer.put(316, "Jiuzhou Greeble");
		manufacturer.put(317, "Aumüller Aumatic GmbH");
		manufacturer.put(318, "Etman Electric");
		manufacturer.put(319, "EMT Controls");
		manufacturer.put(320, "ZidaTech AG");
		manufacturer.put(321, "IDGS bvba");
		manufacturer.put(322, "dakanimo");
		manufacturer.put(323, "Trebor Automation AB");
		manufacturer.put(324, "Satel sp. z o.o.");
		manufacturer.put(325, "Russound, Inc.");
		manufacturer.put(326, "Midea Heating & Ventilating Equipment CO LTD");
		manufacturer.put(327, "Consorzio Terranuova");
		manufacturer.put(328, "Wolf Heiztechnik GmbH");
		manufacturer.put(329, "SONTEC");
		manufacturer.put(330, "Belcom Cables Ltd.");
		manufacturer.put(331, "Guangzhou SeaWin Electrical Technologies Co., Ltd.");
		manufacturer.put(332, "Acrel");
		manufacturer.put(333, "Franke Aquarotter GmbH");
		manufacturer.put(334, "Orion Systems");
		manufacturer.put(335, "Schrack Technik GmbH");
		manufacturer.put(336, "INSPRID");
		manufacturer.put(337, "Sunricher");
		manufacturer.put(338, "Menred automation system(shanghai) Co.,Ltd.");
		manufacturer.put(339, "Aurex");
		manufacturer.put(340, "Josef Barthelme GmbH & Co. KG");
		manufacturer.put(341, "Architecture Numerique");
		manufacturer.put(342, "UP GROUP");
		manufacturer.put(343, "Teknos-Avinno");
		manufacturer.put(344, "Ningbo Dooya Mechanic & Electronic Technology");
		manufacturer.put(345, "Thermokon Sensortechnik GmbH");
		manufacturer.put(346, "BELIMO Automation AG");
		manufacturer.put(347, "Zehnder Group International AG");
		manufacturer.put(348, "sks Kinkel Elektronik");
		manufacturer.put(349, "ECE Wurmitzer GmbH");
		manufacturer.put(350, "LARS");
		manufacturer.put(351, "URC");
		manufacturer.put(352, "LightControl");
		manufacturer.put(353, "ShenZhen YM");
		manufacturer.put(354, "MEAN WELL Enterprises Co. Ltd.");
		manufacturer.put(355, "OSix");
		manufacturer.put(356, "AYPRO Technology");
		manufacturer.put(357, "Hefei Ecolite Software");
		manufacturer.put(358, "Enno");
		manufacturer.put(359, "OHOSURE");
		manufacturer.put(360, "Garefowl");
		manufacturer.put(361, "GEZE");
		manufacturer.put(362, "LG Electronics Inc.");
		manufacturer.put(363, "SMC interiors");
		manufacturer.put(365, "SCS Cable");
		manufacturer.put(366, "Hoval");
		manufacturer.put(367, "CANST");
		manufacturer.put(368, "HangZhou Berlin");
		manufacturer.put(369, "EVN-Lichttechnik");
		manufacturer.put(370, "rutec");
		manufacturer.put(371, "Finder");
		manufacturer.put(372, "Fujitsu General Limited");
		manufacturer.put(373, "ZF Friedrichshafen AG");
		manufacturer.put(374, "Crealed");
		manufacturer.put(375, "Miles Magic Automation Private Limited");
		manufacturer.put(376, "E+");
		manufacturer.put(377, "Italcond");
		manufacturer.put(378, "SATION");
		manufacturer.put(379, "NewBest");
		manufacturer.put(380, "GDS DIGITAL SYSTEMS");
		manufacturer.put(381, "Iddero");
		manufacturer.put(382, "MBNLED");
		manufacturer.put(383, "VITRUM");
		manufacturer.put(384, "ekey biometric systems GmbH");
		manufacturer.put(385, "AMC");
		manufacturer.put(386, "TRILUX GmbH & Co. KG");
		manufacturer.put(387, "WExcedo");
		manufacturer.put(388, "VEMER SPA");
		manufacturer.put(389, "Alexander Bürkle GmbH & Co KG");
		manufacturer.put(390, "Seetroll");
		manufacturer.put(391, "Shenzhen HeGuang");
		manufacturer.put(392, "Not Assigned");
		manufacturer.put(393, "TRANE B.V.B.A");
		manufacturer.put(394, "CAREL");
		manufacturer.put(395, "Prolite Controls");
		manufacturer.put(396, "BOSMER");
		manufacturer.put(397, "EUCHIPS");
		manufacturer.put(398, "connect (Thinka connect)");
		manufacturer.put(399, "PEAKnx a DOGAWIST company ");
		manufacturer.put(400, "ACEMATIC");
		manufacturer.put(401, "ELAUSYS");
		manufacturer.put(402, "ITK Engineering AG");
		manufacturer.put(403, "INTEGRA METERING AG");
		manufacturer.put(404, "FMS Hospitality Pte Ltd");
		manufacturer.put(405, "Nuvo");
		manufacturer.put(406, "u::Lux GmbH");
		manufacturer.put(407, "Brumberg Leuchten");
		manufacturer.put(408, "Lime");
		manufacturer.put(409, "Great Empire International Group Co., Ltd.");
		manufacturer.put(410, "Kavoshpishro Asia");
		manufacturer.put(411, "V2 SpA");
		manufacturer.put(412, "Johnson Controls");
		manufacturer.put(413, "Arkud");
		manufacturer.put(414, "Iridium Ltd.");
		manufacturer.put(415, "bsmart");
		manufacturer.put(416, "BAB TECHNOLOGIE GmbH");
		manufacturer.put(417, "NICE Spa");
		manufacturer.put(418, "Redfish Group Pty Ltd");
		manufacturer.put(419, "SABIANA spa");
		manufacturer.put(420, "Ubee Interactive Europe");
		manufacturer.put(421, "Rexel");
		manufacturer.put(422, "Ges Teknik A.S.");
		manufacturer.put(423, "Ave S.p.A. ");
		manufacturer.put(424, "Zhuhai Ltech Technology Co., Ltd. ");
		manufacturer.put(425, "ARCOM");
		manufacturer.put(426, "VIA Technologies, Inc.");
		manufacturer.put(427, "FEELSMART.");
		manufacturer.put(428, "SUPCON");
		manufacturer.put(429, "MANIC");
		manufacturer.put(430, "Träum deutsche Elektronik GmbH");
		manufacturer.put(431, "Nanjing Shufan Information technology Co.,Ltd.");
		manufacturer.put(432, "EWTech");
		manufacturer.put(433, "Kluger Automation GmbH");
		manufacturer.put(434, "JoongAng Control");
		manufacturer.put(435, "GreenControls Technology Sdn. Bhd.");
		manufacturer.put(436, "IME S.p.a.");
		manufacturer.put(437, "SiChuan HaoDing");
		manufacturer.put(438, "Mindjaga Ltd.");
		manufacturer.put(439, "RuiLi Smart Control");
		manufacturer.put(440, "3S-Smart Software Solutions GmbH");
		manufacturer.put(441, "Moorgen Deutschland GmbH");
		manufacturer.put(442, "CULLMANN TECH");
		manufacturer.put(443, "Merck Window Technologies B.V. ");
		manufacturer.put(444, "ABEGO");
		manufacturer.put(445, "myGEKKO");
		manufacturer.put(446, "Ergo3 Sarl");
		manufacturer.put(447, "STmicroelectronics International N.V.");
		manufacturer.put(448, "cjc systems");
		manufacturer.put(449, "Sudoku");
		manufacturer.put(451, "AZ e-lite Pte Ltd");
		manufacturer.put(452, "Arlight");
		manufacturer.put(453, "Grünbeck Wasseraufbereitung GmbH");
		manufacturer.put(454, "Module Electronic");
		manufacturer.put(455, "KOPLAT");
		manufacturer.put(456, "Guangzhou Letour Life Technology Co., Ltd");
		manufacturer.put(457, "ILEVIA");
		manufacturer.put(458, "LN SYSTEMTEQ");
		manufacturer.put(459, "Hisense SmartHome");
		manufacturer.put(460, "Flink Automation System");
		manufacturer.put(461, "xxter bv");
		manufacturer.put(462, "lynxus technology");
		manufacturer.put(463, "ROBOT S.A.");
		manufacturer.put(464, "Shenzhen Atte Smart Life Co.,Ltd.");
		manufacturer.put(465, "Noblesse");
		manufacturer.put(466, "Advanced Devices");
		manufacturer.put(467, "Atrina Building Automation Co. Ltd");
		manufacturer.put(468, "Guangdong Daming Laffey electric Co., Ltd.");
		manufacturer.put(469, "Westerstrand Urfabrik AB");
		manufacturer.put(470, "Control4 Corporate");
		manufacturer.put(471, "Ontrol");
		manufacturer.put(472, "Starnet");
		manufacturer.put(473, "BETA CAVI");
		manufacturer.put(474, "EaseMore");
		manufacturer.put(475, "Vivaldi srl");
	}
}
