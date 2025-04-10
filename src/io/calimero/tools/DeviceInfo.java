/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2011, 2025 B. Malinowsky

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

import static io.calimero.tools.Main.manufacturer;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;

import io.calimero.DeviceDescriptor;
import io.calimero.DeviceDescriptor.DD0;
import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.KNXAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.KNXRemoteException;
import io.calimero.Settings;
import io.calimero.dptxlator.DPTXlatorBoolean;
import io.calimero.dptxlator.DptXlator16BitSet;
import io.calimero.dptxlator.TranslatorTypes;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXLinkClosedException;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;
import io.calimero.mgmt.Destination;
import io.calimero.mgmt.LocalDeviceManagementUsb;
import io.calimero.mgmt.ManagementClient;
import io.calimero.mgmt.PropertyAccess;
import io.calimero.mgmt.PropertyAccess.PID;
import io.calimero.mgmt.PropertyAdapter;
import io.calimero.mgmt.PropertyClient;
import io.calimero.mgmt.PropertyClient.PropertyKey;
import io.calimero.mgmt.RemotePropertyServiceAdapter;
import io.calimero.serial.usb.UsbConnection;
import io.calimero.serial.usb.UsbConnectionFactory;
import io.calimero.tools.Main.ShutdownHandler;
import io.calimero.xml.KNXMLException;
import io.calimero.xml.XmlInputFactory;

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
		 * @return parameter name
		 */
		String name();

		// create human-readable name from parameter by inserting some spaces
		default String friendlyName() { return name().replaceAll("([A-Z])", " $1").replace("I P", "IP").trim(); }
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

	public enum SecurityParameter implements Parameter {
		SecurityMode,
		SecurityFailure,
		SecurityFailureCounters,
		LastSecurityFailure
	}

	// not in a category yet
	public enum InternalParameter implements Parameter {
		IndividualAddressWriteEnabled, ServiceControl, AdditionalProfile, ErrorFlags
	}

	public static final class Item {
		private final String category;
		private final Parameter parameter;
		private final String value;
		private final byte[] raw;

		Item(final String category, final Parameter parameter, final String value, final byte[] raw) {
			this.category = category;
			this.parameter = parameter;
			this.value = value;
			this.raw = raw;
		}

		public String category() { return category; }
		public Parameter parameter() { return parameter; }
		public String value() { return value; }
		public byte[] raw() { return raw; }
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
	// Interface Object "Security" in interface object server
	private static final int securityObject = 17;
	// Interface Object "RF Medium Object" in interface object server
	private static final int rfMediumObject = 19;

	// property id to distinguish hardware types which are using the same
	// device descriptor mask version
	private static final int pidHardwareType = 78;

	// maps object type to object indices in device
	private final Map<Integer, List<Integer>> ifObjects = new HashMap<>();

	private static final Logger out = LogService.getLogger("io.calimero.tools");


	private ManagementClient mc;
	private Destination d;
	private PropertyClient pc;

	private final Map<String, Object> options = new HashMap<>();

	private DeviceDescriptor dd;
	// System B has mask version 0x07B0 or 0x17B0 and provides error code property
	private boolean isSystemB;

	private boolean groupAddressesDone;

	private final Set<String> categories = new HashSet<>();
	private String category = "General";

	private record JsonResult(String device, Collection<JsonItem> info) implements Json {}
	private record JsonItem(String category, Parameter parameter, String value, byte[] data) implements Json {}

	private final JsonResult jsonResult;

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
			if (options.containsKey("json")) {
				final var dev = options.containsKey("device") ? options.get("device") : options.get("host");
				jsonResult = new JsonResult(dev.toString(), new ArrayList<>());
			}
			else
				jsonResult = null;
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
	 * options are treated case-sensitive. Available options for connecting to the KNX device in question:
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
	public static void main(final String... args)
	{
		try {
			final DeviceInfo d = new DeviceInfo(args);
			final ShutdownHandler sh = new ShutdownHandler().register();
			d.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.log(ERROR, "parsing options", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		final IndividualAddress device = (IndividualAddress) options.get("device");

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
						var adapter = new RemotePropertyServiceAdapter(link, device, e -> {}, true)) {
					mc = adapter.managementClient();
					d = adapter.destination();
					readDeviceInfo(adapter, "device " + device);
				}
			}
			else if (options.containsKey("usb")) {
				// setup for reading device info of usb interface
				try (UsbConnection conn = UsbConnectionFactory.open((String) options.get("host"));
						PropertyAdapter adapter = new LocalDeviceManagementUsb(conn, e -> {}, false)) {
					dd = conn.deviceDescriptor();
					readDeviceInfo(adapter, "KNX USB adapter " + dd);
				}
			}
			else {
				try (PropertyAdapter adapter = Main.newLocalDeviceMgmt(options, closed -> {})) {
					readDeviceInfo(adapter, "KNXnet/IP " + adapter.getName());
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
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Invoked after successfully reading a KNX device parameter. If a device parameter is not available or accessible
	 * in the KNX device, this method won't be called.
	 *
	 * @param parameter the parameter read from the device
	 * @param value formatted value of that parameter
	 * @param raw raw value of that parameter
	 */
	protected void onDeviceInformation(final Parameter parameter, final String value, final byte[] raw) {}

	/**
	 * Invoked on each successfully read device parameter of a KNX device.
	 *
	 * @param item device parameter and value
	 */
	protected void onDeviceInformation(final Item item) {
		if (options.containsKey("json"))
			jsonResult.info().add(new JsonItem(item.category, item.parameter, item.value, item.raw));
		else
			out(item);
		onDeviceInformation(item.parameter(), item.value(), item.raw());
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
		if (options.containsKey("json"))
			System.out.println(jsonResult.toJson());
		if (canceled)
			out.log(INFO, "reading device info canceled");
		if (thrown != null)
			out.log(ERROR, "completed with error", thrown);
	}

	private void out(final Item item) {
		final boolean printUnformatted = false; // TODO create option 'raw/unformatted'

		final boolean printCategory = categories.add(item.category());
		if (printCategory && !"General".equals(item.category()))
			out(System.lineSeparator() + item.category());

		final String s = item.parameter().friendlyName() + " = " + item.value();
		final String hex = item.raw().length > 0 ? "0x" + HexFormat.of().formatHex(item.raw()) : "n/a";
		// left-pad unformatted output
		final int n = Math.max(1, 60 - s.length());
		final String detail = printUnformatted ? String.format(" %" + n + "s%s]", "[", hex) : "";
		out(s + detail);
	}

	private String interfaceObjectName(final int objectIndex) {
		return interfaceObjectType(objectIndex).map(PropertyClient::getObjectTypeName).orElse("");
	}

	private Optional<Integer> interfaceObjectType(final int objectIndex) {
		for (final var entry : ifObjects.entrySet()) {
			if (entry.getValue().contains(objectIndex))
				return Optional.of(entry.getKey());
		}
		return Optional.empty();
	}

	private void findInterfaceObjects() throws InterruptedException
	{
		// check if there are any interface object at all, i.e., the Device Object
		if (readElements(0, PID.OBJECT_TYPE) <= 0)
			return;

		final int deviceObjectIdx = 0;
		final int objects = readElements(deviceObjectIdx, PropertyAccess.PID.IO_LIST);
		if (objects > 0) {
			final byte[] data = read(deviceObjectIdx, PropertyAccess.PID.IO_LIST, 1, objects);
			if (data == null)
				return;
			for (int i = 0; i < data.length / 2; ++i) {
				final int type = (data[2 * i] & 0xff) << 8 | (data[2 * i + 1] & 0xff);
				ifObjects.compute(type, (__, v) -> v == null ? new ArrayList<>() : v).add(i);
			}
		}
		else {
			// device only has at least device- and cEMI server-object
			ifObjects.put(0, List.of(deviceObjectIdx));
			for (int i = 1; i < 100; ++i) {
				final int type = (int) toUnsigned(read(i, PID.OBJECT_TYPE));
				if (type < 0)
					break;
				ifObjects.compute(type, (__, v) -> v == null ? new ArrayList<>() : v).add(i);
			}

			if (ifObjects.size() == 1) {
				ifObjects.put(cemiServerObject, List.of(1));
				out.log(INFO, "Device implements only Device Object and cEMI Object");
			}
		}
	}

	private void createPropertyClient(final PropertyAdapter adapter) throws KNXFormatException {
		pc = new PropertyClient(adapter);

		final String resource = "/properties.xml";
		try (var is = Settings.class.getResourceAsStream(resource);
		     var r = XmlInputFactory.newInstance().createXMLStreamReader(is)) {
			pc.addDefinitions(new PropertyClient.XmlPropertyDefinitions().load(r));
		} catch (IOException | RuntimeException e) {
			out.log(DEBUG, "failed to load property definitions: {0}", e.toString());
		}
	}

	private void readDeviceInfo(final PropertyAdapter adapter, final String device) throws KNXException, InterruptedException {
		createPropertyClient(adapter);
		out.log(INFO, "Reading info of {0}, might take some seconds ...", device);
		readDeviceInfo();
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
			else if (dd == DD0.TYPE_0700 || dd == DD0.TYPE_0701)
				readTP1Bcu1();
			else {
				findInterfaceObjects();
			}
		}
		else {
			findInterfaceObjects();
		}

		// System B has mask version x7B0 and provides error code property
		isSystemB = dd == DD0.TYPE_07B0 || dd == DD0.TYPE_17B0 || dd == DD0.TYPE_27B0 || dd == DD0.TYPE_57B0;

		if (ifObjects.containsKey(0))
			readDeviceObject(0);

		readActualPeiType();
		programmingMode();

		// Application Program (Application Program Object)
		iterate(appProgramObject, idx -> {
			readUnsigned(idx, PID.PEI_TYPE, false, CommonParameter.RequiredPeiType);
			readProgram(idx);
		});

		// PEI Program (Interface Program Object)
		iterate(interfaceProgramObject, this::readProgram);

		// Group Communication
		iterate(addresstableObject, this::readLoadState);
		iterate(assoctableObject, this::readLoadState);
		if (mc != null && !groupAddressesDone)
			readGroupAddresses();

		iterate(cemiServerObject, this::readCemiServerObject);
		iterate(rfMediumObject, this::readRFMediumObject);
		try {
			iterate(knxnetipObject, this::readKnxipInfo);
		}
		catch (InterruptedException | KNXException e) { throw e; }
		catch (final Exception e) { out.log(WARNING, e); }

		iterate(securityObject, this::readSecurityObject);
	}

	@FunctionalInterface
	private interface ThrowingConsumer<T, E extends Exception> {
		void accept(T t) throws E;
	}

	private <E extends Exception> void iterate(final int objectType, final ThrowingConsumer<Integer, E> consumer)
			throws E {
		int i = 0;
		for (final var idx : objectIndices(objectType)) {
			category = interfaceObjectName(idx) + (++i > 1 ? " " + i : "");
			consumer.accept(idx);
		}
	}

	private List<Integer> objectIndices(final int objectType) { return ifObjects.getOrDefault(objectType, List.of()); }

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
		catch (final KNXException ignore) {}

		// fall back and read memory location (remote device info only)
		try {
			if (mc != null) {
				x.setData(mc.readMemory(d, 0x60, 1));
				putResult(CommonParameter.ProgrammingMode, x.getValue(), x.getData());
			}
		}
		catch (final KNXException e) {
			out.log(ERROR, "reading memory location 0x60", e);
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

	private void readDeviceObject(final int objectIdx) throws InterruptedException
	{
		read(CommonParameter.Manufacturer, objectIdx, PID.MANUFACTURER_ID, data -> manufacturer((int) toUnsigned(data)));
		readUnsigned(objectIdx, PID.ORDER_INFO, true, CommonParameter.OrderInfo);
		read(CommonParameter.SerialNumber, objectIdx, PropertyAccess.PID.SERIAL_NUMBER, DeviceInfo::knxSerialNumber);

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
		final byte[] data = read(CommonParameter.DeviceDescriptor, objectIdx, PID.DEVICE_DESCRIPTOR);
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
			final byte[] profileSna = read(CommonParameter.DeviceAddress, objectIdx, PID.SUBNET_ADDRESS);
			final byte[] profileDev = read(objectIdx, PID.DEVICE_ADDRESS);
			final byte[] profileAddr = new byte[] { profileSna[0], profileDev[0] };
			final IndividualAddress ia = new IndividualAddress(profileAddr);
			putResult(CommonParameter.DeviceAddress, "Additional profile address " + ia, ia.toByteArray());
		}
		catch (final Exception ignore) {}

		// read device service control
		try {
			final byte[] svcCtrl = read(InternalParameter.IndividualAddressWriteEnabled, objectIdx, PID.SERVICE_CONTROL);
			final boolean indAddrWriteEnabled = (svcCtrl[1] & 0x04) == 0x04;
			putResult(InternalParameter.IndividualAddressWriteEnabled, indAddrWriteEnabled ? "yes" : "no", svcCtrl[1] & 0x04);
			final int services = svcCtrl[0] & 0xff;
			final String formatted = String.format("%8s", Integer.toBinaryString(services)).replace(' ', '0');
			putResult(InternalParameter.ServiceControl,
					"Disabled services on EMI [Mgmt App TL-conn Switch TL-group Network Link User]: " + formatted,
					services);
		}
		catch (final Exception ignore) {}

		// RF domain address
		// Device object RF domain address is mandatory if the cEMI server supports RF.
		// With mask 0x2311, the RF domain address is mandatory in the RF medium object (PID 56) and
		// optional in the device object (PID 82). At least the Weinzierl USB stores it only in the device object.
		try {
			read(CommonParameter.DomainAddress, objectIdx, PID.RF_DOMAIN_ADDRESS,
					bytes -> HexFormat.of().formatHex(bytes));
		}
		catch (final Exception ignore) {}

		read(CommonParameter.SoftwareVersion, objectIdx, PID.VERSION, DeviceInfo::version);

		readUnsigned(objectIdx, PID.MAX_APDULENGTH, false, CommonParameter.MaxApduLength);

		final int pidErrorFlags = 53;
		read(InternalParameter.ErrorFlags, objectIdx, pidErrorFlags, DeviceInfo::errorFlags);
	}

	private static final int legacyPidFilteringModeSelect = 62;
	private static final int legacyPidFilteringModeSupport = 63;

	private void readCemiServerObject(final int objectIndex) throws InterruptedException {
		read(CemiParameter.MediumType, objectIndex, PropertyAccess.PID.MEDIUM_TYPE, DeviceInfo::mediumTypes);

		// Get supported cEMI communication modes, DLL is mandatory for any cEMI server
		// communication mode can then be set using PID_COMM_MODE
		read(CemiParameter.SupportedCommModes, objectIndex, PID.COMM_MODES_SUPPORTED, DeviceInfo::supportedCommModes);
		read(CemiParameter.SelectedCommMode, objectIndex, PID.COMM_MODE, DeviceInfo::commMode);

		// if we deal with a USB stand-alone device, the Device Object stores the IA of the USB interface
		// if we deal with a USB device that is embedded with another end device profile, the Device Object stores
		// the IA of the end device. In that case, the cEMI Server Object holds the IA of the USB interface
		try {
			final byte[] dev = read(CemiParameter.ClientAddress, objectIndex, PID.CLIENT_DEVICE_ADDRESS);
			final byte[] sna = read(objectIndex, PID.CLIENT_SNA);

			final byte[] addr = new byte[] { sna[0], dev[0] };
			final IndividualAddress ia = new IndividualAddress(addr);
			putResult(CemiParameter.ClientAddress, "USB cEMI client address " + ia, ia.toByteArray());
		}
		catch (final Exception ignore) {}

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
		catch (final Exception ignore) {}

		try {
			final byte[] data = read(objectIndex, PID.RF_MODE_SELECT);
			final int selected = data[0] & 0xff;
			final boolean slave = (data[0] & 0x04) == 0x04;
			final boolean master = (data[0] & 0x02) == 0x02;
			final boolean async = (data[0] & 0x01) == 0x01;
			final String formatted = "BiBat slave " + slave + ", BiBat master " + master + ", async " + async;
			putResult(CemiParameter.SelectedRfMode, formatted, selected);
		}
		catch (final Exception ignore) {}
	}

	//         		Supports/Disable filtering on:
	//      ------------------------------------------------------
	// Bit  |  15 ... 4  |     3     |  2  |     1    |  0       |
	// Name |  Reserved  | Ext. Grp  | DoA | Repeated | Own Ind. |
	//      |            | Addresses |     |  Frames  | Address  |
	//      ------------------------------------------------------
	private void readSupportedFilteringModes(final int objectIndex, final int pid) {
		try {
			read(CemiParameter.SupportedFilteringModes, objectIndex, pid, filters -> {
				final int filter = filters[1] & 0xff;
				final boolean grp = (filter & 0x08) == 0x08;
				final boolean doa = (filter & 0x04) == 0x04;
				final boolean rep = (filter & 0x02) == 0x02;
				final boolean ownIa = (filter & 0x01) == 0x01;
				return "ext. group addresses " + grp + ", domain address " + doa + ", repeated frames " + rep
						+ ", own individual address " + ownIa;
			});
		}
		catch (final Exception ignore) {}
	}

	// Check disabled frame filters in the device
	// A set bit (1) indicates a disabled filter, by default all filters are active
	private void readSelectedFilteringMode(final int objectIndex, final int pid) {
		try {
			read(CemiParameter.SelectedFilteringModes, objectIndex, pid, filters -> {
				final int selected = filters[1] & 0xff;
				final boolean grp = (selected & 0x08) == 0x08;
				final boolean doa = (selected & 0x04) == 0x04;
				final boolean rep = (selected & 0x02) == 0x02;
				final boolean ownIa = (selected & 0x01) == 0x01;
				if (selected == 0)
					return "all supported filters active";
				return "disabled frame filters: ext. group addresses " + grp + ", domain address " + doa
						+ ", repeated frames " + rep + ", own individual address " + ownIa;
			});
		}
		catch (final Exception ignore) {}
	}

	private void cEmiExtensionRfBiBat(final int objectIndex) throws InterruptedException {
		read(CemiParameter.SupportedRfModes, objectIndex, PID.RF_MODE_SUPPORT, support -> {
			final boolean slave = (support[0] & 0x04) == 0x04;
			final boolean master = (support[0] & 0x02) == 0x02;
			final boolean async = (support[0] & 0x01) == 0x01;

			return "BiBat slave " + slave + ", BiBat master " + master + ", Async " + async;
		});
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
	private static String supportedCommModes(final byte[] commModes) {
		final int modes = commModes[1] & 0xff;
		final boolean tll = (modes & 0x08) == 0x08;
		final boolean raw = (modes & 0x04) == 0x04;
		final boolean bm = (modes & 0x02) == 0x02;
		final boolean dll = (modes & 0x01) == 0x01;
		return "Transport layer local " + tll + ", Data link layer modes: normal " + dll + ", busmonitor "
				+ bm + ", raw mode " + raw;
	}

	private static String commMode(final byte[] data) {
		final int commMode = data[0] & 0xff;
		return switch (commMode) {
			case 0 -> "Data link layer";
			case 1 -> "Data link layer busmonitor";
			case 2 -> "Data link layer raw frames";
			case 6 -> "cEMI transport layer";
			case 0xff -> "no layer";
			default -> "unknown/unspecified (" + commMode + ")";
		};
	}

	private void readRFMediumObject(final int objectIndex) {
		try {
			// different PID as in Device Object !!!
			final int pidRfDomainAddress = 56;
			read(RfParameter.DomainAddress, objectIndex, pidRfDomainAddress, doa -> "0x" + HexFormat.of().formatHex(doa));
		}
		catch (final Exception ignore) {}
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
			out.log(ERROR, MessageFormat.format("reading actual PEI type (A/D converter channel {0}, repeat {1})", channel, repeat), e);
		}
	}

	private void readSecurityObject(final int objectIndex) throws InterruptedException {
		final int pidSecurityMode = 51;
		final byte[] empty = {};
		readFunctionPropertyState(SecurityParameter.SecurityMode, securityObject, pidSecurityMode, 0, empty,
				DeviceInfo::toOnOff);

		final int pidSecurityReport = 57;
		read(SecurityParameter.SecurityFailure, objectIndex, pidSecurityReport, DeviceInfo::toYesNo);

		final int pidSecurityFailuresLog = 55;
		final byte[] readFailureCounters = { 0 };
		readFunctionPropertyState(SecurityParameter.SecurityFailureCounters, securityObject, pidSecurityFailuresLog, 0,
				readFailureCounters, DeviceInfo::securityFailureCounters);

		for (int i = 0; i < 5; i++) {
			final byte[] failure = { (byte) i };
			final var result = readFunctionPropertyState(SecurityParameter.LastSecurityFailure, securityObject,
					pidSecurityFailuresLog, 1, failure, DeviceInfo::latestSecurityFailure);
			if (result.isEmpty())
				break;
		}
	}

	// for function property response only
	private static String toOnOff(final byte[] data) {
		return (data[2] & 0x01) != 0 ? "on" : "off";
	}

	private static String toYesNo(final byte[] data) {
		return (data[0] & 0x01) != 0 ? "yes" : "no";
	}

	private static String securityFailureCounters(final byte[] data) {
		final var counters = ByteBuffer.wrap(data, 3, data.length - 3);
		final int scfErrors = counters.getShort() & 0xffff;
		final int seqNoErrors = counters.getShort() & 0xffff;
		final int cryptoErrors = counters.getShort() & 0xffff;
		final int accessRoleErrors = counters.getShort() & 0xffff;
		return "control field " + scfErrors + ", sequence " + seqNoErrors + ", cryptographic " + cryptoErrors
				+ ", access " + accessRoleErrors;
	}

	private static String latestSecurityFailure(final byte[] data) {
		final var msgInfo = ByteBuffer.wrap(data, 3, data.length - 3);

		final var src = new IndividualAddress(msgInfo.getShort() & 0xffff);
		final var dstRaw = msgInfo.getShort() & 0xffff;
		final int ctrl2 = msgInfo.get() & 0xff;
		final boolean group = (ctrl2 & 0x80) != 0;
		final var dst = group ? new GroupAddress(dstRaw) : new IndividualAddress(dstRaw);

		final var seqData = new byte[6];
		msgInfo.get(seqData);
		final long seqNo = toUnsigned(seqData);

		final String[] errorTypes = { "reserved", "invalid SCF", "sequence error", "cryptographic error",
			"error against access & roles" };
		final var error = errorTypes[msgInfo.get() & 0xff];

		return String.format("%s->%s seq %d: %s", src, dst, seqNo, error);
	}

	// same as BCU error flags located at 0x10d
	private static String errorFlags(final byte[] data) {
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
		return String.join(", ", errors);
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
		final var item = new Item(category, p, formatted, raw);
		onDeviceInformation(item);
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

		readMem(addrDoA, 2, true, CommonParameter.DomainAddress);
		readBcuInfo(true);
	}

	private void readTP1Bcu1() throws InterruptedException
	{
		// Option Reg: bit 1: mem 0x120-0x1ff protected/writable
//		final int addrOptionReg = 0x100;
		final int addrManufactData = 0x0101; // length 3
//		final int addrMxRstCnt = 0x010f; // bits (msb): 3 INAK / 2 (unused) / 3 BUSY (lsb)
//		final int addrConfigDesc = 0x0110;
//		final int addrAssocTablePtr = 0x0111;

		readMem(addrManufactData, 3, true, CommonParameter.ManufacturerData);

		readBcuInfo(true);
	}

	private void readTP1Bcu2() throws InterruptedException {
		// Option Reg: bit 0: watchdog disabled/enabled, bit 1: mem 0x300-0x4df protected/writable
//		final int addrOptionReg = 0x100;

		final int addrManufacturer = 0x0101; // length 2
		// App Id, length 5: App manufacturer (2), SW dev type (2), SW version (1)
		// the App manufacturer can differ from product manufacturer (0x101), if a compatible program was downloaded
		final int addrAppId = 0x0103;

		// address table realization type 2
//		final int addrUsrEeprom = 0x0116; // max. length 858: Addr Table, Assoc Table, EEData, Code

		// Page 0 RAM
//		final int addrPeiInterface = 0x00c4; // if used
//		final int addrPeiInfo = 0x00c5; // if used

		readMem(addrManufacturer, 2, Main::manufacturer, CommonParameter.Manufacturer);
		final long appId = readMemLong(addrAppId, 5);
		final String appMf = manufacturer((int) appId >> (3 * 8));
		final long swDev = (appId >> 8) & 0xff;
		final long swVersion = appId & 0xff;

		out.log(INFO, "appId 0x{0} - app manufacturer: {1}, SW dev type {2}, SW version {3}", Long.toHexString(appId),
				appMf, swDev, swVersion);

		readBcuInfo(false);

		// interface objects: Device Object, Address table object, Assoc table object, App program object
		findInterfaceObjects();
	}

	private void readBcuInfo(final boolean bcu1) throws InterruptedException
	{
		if (bcu1) {
			readMem(addrManufact, 1, Main::manufacturer, CommonParameter.Manufacturer);
			readMem(addrDevType, 2, true, CommonParameter.DeviceTypeNumber);
		}
		readMem(addrVersion, 1, i -> version(new byte[] { (byte) (int) i }), CommonParameter.SoftwareVersion);
		// mechanical PEI type required by the application SW
		readMem(addrPeiType, 1, DeviceInfo::toPeiTypeString, CommonParameter.RequiredPeiType);
		readMem(addrRunError, 1, DeviceInfo::decodeRunError, CommonParameter.RunError);

		readSystemState();

		readMem(addrRoutingCnt, 1, v -> Integer.toString((v >> 4) & 0x7),
				CommonParameter.RoutingCount);
		// realization type 1
		// Location of group object table
		readMem(addrGroupObjTablePtr, 1, true, CommonParameter.GroupObjTableLocation);
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
			final int addresstableObjectIdx = ifObjects.get(addresstableObject).get(0);
			final int tableSize = readElements(addresstableObjectIdx, PID.TABLE);
			if (tableSize > 0) {
				final StringJoiner joiner = new StringJoiner(", ");
				for (int i = 0; i < tableSize; i++) {
					final GroupAddress group = new GroupAddress(read(addresstableObjectIdx, PID.TABLE, i + 1, 1));
					joiner.add(group.toString());
				}
				groupAddressesDone = true;
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
		final int lengthSize = isSystemB ? 2 : 1;
		final int entries = readMem(memLocation, lengthSize, false,
				CommonParameter.GroupAddressTableEntries);

		int startAddr = memLocation + lengthSize;
		if (!isSystemB && entries > 0) {
			// address of device address
			readMem(startAddr, 2, v -> new IndividualAddress(v & 0x7fff).toString(), CommonParameter.DeviceAddress);
			startAddr += 2;
		}

		final StringBuilder sb = new StringBuilder();
		for (int i = isSystemB ? 0 : 1; i < entries; i++) {
			final int raw = readMem(startAddr, 2);
			final KNXAddress group = new GroupAddress(raw & 0x7fff);
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(group);
			// are we the group responder
			if ((raw & 0x8000) == 0x8000)
				sb.append("(R)");
			startAddr += 2;
		}
		groupAddressesDone = true;
		putResult(CommonParameter.GroupAddresses, sb.toString(), new byte[0]);
	}

	private void readKnxipInfo(final int objectIndex) throws KNXException, InterruptedException
	{
		read(KnxipParameter.DeviceName, () -> readFriendlyName(objectIndex));

		// Device Capabilities Device State
		byte[] data = read(KnxipParameter.Capabilities, objectIndex, PropertyAccess.PID.KNXNETIP_DEVICE_CAPABILITIES,
				DeviceInfo::toCapabilitiesString).orElse(new byte[2]);
		final boolean supportsTunneling = (data[1] & 0x01) == 0x01;

		// MAC Address
		read(KnxipParameter.MacAddress, objectIndex, PropertyAccess.PID.MAC_ADDRESS, HexFormat.ofDelimiter(":")::formatHex);
		// Current IP Assignment
		data = read(KnxipParameter.CurrentIPAssignment, objectIndex, PropertyAccess.PID.CURRENT_IP_ASSIGNMENT_METHOD,
				DeviceInfo::toIPAssignmentString).orElse(new byte[1]);
		final int currentIPAssignment = data[0] & 0x0f;
		// Bits (from LSB): Manual=0, BootP=1, DHCP=2, AutoIP=3
		final boolean dhcpOrBoot = (data[0] & 0x06) != 0;

		// Read currently set IP parameters
		final var currentIP = readIp(KnxipParameter.CurrentIPAddress, objectIndex, PropertyAccess.PID.CURRENT_IP_ADDRESS);
		final var currentMask = readIp(KnxipParameter.CurrentSubnetMask, objectIndex, PropertyAccess.PID.CURRENT_SUBNET_MASK);
		final var currentGw = readIp(KnxipParameter.CurrentDefaultGateway, objectIndex, PropertyAccess.PID.CURRENT_DEFAULT_GATEWAY);
		// DHCP Server (show only if current assignment method is DHCP or BootP)
		if (dhcpOrBoot) {
			readIp(KnxipParameter.DhcpServer, objectIndex, PropertyAccess.PID.DHCP_BOOTP_SERVER);
		}

		// IP Assignment Method (shown only if different from current IP assign. method)
		data = read(KnxipParameter.ConfiguredIPAssignment, objectIndex, PropertyAccess.PID.IP_ASSIGNMENT_METHOD,
				config -> {
					final int ipAssignment = config[0] & 0x0f;
					return ipAssignment != currentIPAssignment ? toIPAssignmentString(config) : "";
				}).orElse(new byte[1]);
		// Read IP parameters for manual assignment
		// the following info is only shown if manual assignment method is enabled, and parameter
		// is different from current one
		final boolean manual = (data[0] & 0x01) == 0x01;
		if (manual) {
			// Manual IP Address
			read(KnxipParameter.IPAddress, objectIndex, PropertyAccess.PID.IP_ADDRESS,
					ip -> Arrays.equals(currentIP, ip) ? "" : toIP(ip));
			// Manual Subnet Mask
			read(KnxipParameter.SubnetMask, objectIndex, PropertyAccess.PID.SUBNET_MASK,
					mask -> Arrays.equals(currentMask, mask) ? "" : toIP(mask));
			// Manual Default Gateway
			read(KnxipParameter.DefaultGateway, objectIndex, PropertyAccess.PID.DEFAULT_GATEWAY,
					gw -> Arrays.equals(currentGw, gw) ? "" : toIP(gw));
		}

		readIp(KnxipParameter.RoutingMulticast, objectIndex, PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS);
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
				data = read(objectIndex, pid, i + 1, 1, false);
				sb.append(new IndividualAddress(data)).append(" ");
			}
			putResult(KnxipParameter.AdditionalIndividualAddresses, sb.toString(), new byte[0]);
		}
	}

	private void readProgram(final int objectIdx) throws InterruptedException
	{
		read(CommonParameter.ProgramVersion, objectIdx, PID.PROGRAM_VERSION, DeviceInfo::programVersion);
		readLoadState(objectIdx);
		read(CommonParameter.RunStateControl, objectIdx, PropertyAccess.PID.RUN_STATE_CONTROL, DeviceInfo::getRunState);
	}

	private static String programVersion(final byte[] data) {
		if (data.length != 5)
			return HexFormat.of().formatHex(data);
		final int mfr = (data[0] & 0xff) << 8 | (data[1] & 0xff);
		return String.format("%s %02x%02x v%d.%d", manufacturer(mfr), data[2], data[3], (data[4] & 0xff) >> 4, data[4] & 0xf);
	}

	private void readLoadState(final int objectIdx) throws InterruptedException
	{
		final var data = read(CommonParameter.LoadStateControl, objectIdx, PropertyAccess.PID.LOAD_STATE_CONTROL,
				DeviceInfo::getLoadState);
		// System B contains error code for load state "Error" (optional, but usually yes)
		final boolean hasErrorCode = isSystemB;
		if (hasErrorCode && data.isPresent() && data.get()[0] == 3) {
			read(CommonParameter.LoadStateError, objectIdx, PropertyAccess.PID.ERROR_CODE, error -> {
				try {
					// enum ErrorClassSystem
					return TranslatorTypes.createTranslator("20.011", error).getValue();
				}
				catch (final KNXException e) {
					return "";
				}
			});
		}
	}

	private Optional<byte[]> readFunctionPropertyState(final Parameter p, final int objectType, final int propertyId,
			final int service, final byte[] info, final Function<byte[], String> representation) throws InterruptedException {
		final var data = Optional.ofNullable(readFunctionPropertyState(p, objectType, propertyId, service, info));
		data.map(representation).filter(Predicate.not(String::isEmpty))
				.ifPresent(formatted -> putResult(p, formatted, data.get()));
		return data;
	}

	private byte[] readFunctionPropertyState(final Parameter p, final int objectType, final int propertyId,
			final int service, final byte... info) throws InterruptedException {
		if (mc == null)
			return null;

		final int oinstance = 1;
		out.log(DEBUG, "read {0} function property state {1}({2})|{3} service {4}", p.friendlyName(), objectType,
				oinstance, propertyId, service);
		try {
			return mc.readFunctionPropertyState(d, objectType, oinstance, propertyId, service, info);
		}
		catch (final KNXException e) {
			out.log(DEBUG, e.getMessage());
		}
		return null;
	}

	private Optional<byte[]> read(final Parameter p, final int objectIndex, final int pid,
		final Function<byte[], String> representation) throws InterruptedException {
		final var data = Optional.ofNullable(read(p, objectIndex, pid));
		data.map(representation).filter(Predicate.not(String::isEmpty))
				.ifPresent(formatted -> putResult(p, formatted, data.get()));
		return data;
	}

	private void read(final Parameter p, final Callable<String> c) throws KNXLinkClosedException, InterruptedException {
		try {
			out.log(DEBUG, "read {0} ...", p.friendlyName());
			final String s = c.call();
			putResult(p, s, s.getBytes(StandardCharsets.ISO_8859_1));
		}
		catch (InterruptedException | KNXLinkClosedException e) {
			throw e;
		}
		catch (final KNXRemoteException e) {
			out.log(WARNING, "reading {0}: {1}", p, e.getMessage());
		}
		catch (final Exception e) {
			out.log(ERROR, "error reading " + p, e);
		}
	}

	private byte[] readIp(final Parameter p, final int objectIndex, final int pid) throws InterruptedException {
		return read(p, objectIndex, pid, DeviceInfo::toIP).orElse(new byte[4]);
	}

	private String readFriendlyName(final int objectIndex) throws KNXException, InterruptedException
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
		return read(objectIndex, pid, 1, 1, true);
	}

	private byte[] read(final Parameter p, final int objectIndex, final int pid) throws InterruptedException
	{
		out.log(DEBUG, "read {0}|{1} {2}", objectIndex, pid, p.friendlyName());
		return read(objectIndex, pid, 1, 1, false);
	}

	private byte[] read(final int objectIndex, final int pid, final int start, final int elements)
			throws InterruptedException {
		return read(objectIndex, pid, start, elements, true);
	}

	private byte[] read(final int objectIndex, final int pid, final int start, final int elements, final boolean log)
		throws InterruptedException
	{
		if (log) {
			final PropertyKey key;
			if (pid <= 50)
				key = new PropertyKey(pid);
			else if (objectIndex == 0)
				key = new PropertyKey(0, pid);
			else
				key = interfaceObjectType(objectIndex).map(ot -> new PropertyKey(ot, pid)).orElse(null);

			final var property = pc.getDefinitions().get(key);
			String friendlyName = "";
			if (property != null && !property.propertyName().isEmpty())
				friendlyName = " " + property.propertyName();

			out.log(DEBUG, "read {0}|{1}{2}", objectIndex, pid, friendlyName);
		}
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
			out.log(DEBUG, "reading KNX property " + objectIndex + "|" + pid + ": " + e.getMessage());
		}
		return null;
	}

	private void readUnsigned(final int objectIndex, final int pid, final boolean hex, final Parameter p)
		throws InterruptedException
	{
		final byte[] data = read(p, objectIndex, pid);
		if (data != null) {
			final String formatted = hex ? HexFormat.of().formatHex(data) : Long.toString(toUnsigned(data));
			putResult(p, formatted, data);
		}
	}

	private int readMem(final int startAddr, final int bytes, final boolean hex, final Parameter p)
			throws InterruptedException
	{
		out.log(DEBUG, "read 0x{0}..0x{1} {2}", Long.toHexString(startAddr), Long.toHexString(startAddr + bytes), p.friendlyName());
		final long v = readMemLong(startAddr, bytes);
		if (v != -1)
			putResult(p, hex ? Long.toHexString(v) : Long.toString(v), v);
		return (int) v;
	}

	private void readMem(final int startAddr, final int bytes, final Function<Integer, String> representation,
			final Parameter p) throws InterruptedException
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
			out.log(DEBUG, "error reading 0x{0}..0x{1}: {2}", Long.toHexString(startAddr),
					Long.toHexString(startAddr + bytes), e.toString());
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

		for (final var i = List.of(args).iterator(); i.hasNext();) {
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
					throw new KNXIllegalArgumentException("KNX device " + e, e);
				}
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		// we allow a default usb config where the first found knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

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
			options.put("medium", new TPSettings());
		Main.setDomainAddress(options);
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(System.lineSeparator());
		joiner.add("Usage: " + tool + " [options] <host|port> [KNX device address]");
		joiner.add(Main.printCommonOptions());
		joiner.add(Main.printSecureOptions());
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
		catch (final UnknownHostException ignore) {}
		return "n/a";
	}

	private static String toMediumTypeString(final int type)
	{
		return switch (type) {
			case 0 -> "Twisted Pair 1";
			case 1 -> "Power-line 110";
			case 2 -> "Radio Frequency";
			case 5 -> "KNX IP";
			default -> "Type " + type;
		};
	}

	private static String mediumTypes(final byte[] data) {
		try {
			return TranslatorTypes.createTranslator(DptXlator16BitSet.DptMedia, data).getValue();
		}
		catch (final Exception e) {
			return "";
		}
	}

	private static String toFirmwareTypeString(final int type)
	{
		return switch (type) {
			case 0 -> "BCU 1, BCU 2, BIM M113";
			case 1 -> "Unidirectional devices";
			case 3 -> "Property based device management";
			case 7 -> "BIM M112";
			case 8 -> "IR Decoder, TP1 legacy";
			case 9 -> "Repeater, Coupler";
			default -> "Type " + type;
		};
	}

	private static String toPeiTypeString(final int peitype)
	{
		if (peitype == -1 || peitype == 0xff)
			return "n/a";
		return switch (peitype) {
			case 0 -> "No adapter";
			case 1 -> "Illegal adapter";
			case 2 -> "4 inputs, 1 output (LED)";
			case 4 -> "2 inputs / 2 outputs, 1 output (LED)";
			case 6 -> "3 inputs / 1 output, 1 output (LED)";
			case 8 -> "5 inputs";
			case 10 -> "FT1.2 protocol"; // (default) type 10 is defined twice
//			case 10 -> "Loadable serial protocol", // 10 (alternative)
			case 12 -> "Serial sync message protocol";
			case 14 -> "Serial sync data block protocol";
			case 16 -> "Serial async message protocol";
			case 17 -> "Programmable I/O";
			case 19 -> "4 outputs, 1 output (LED)";
			case 20 -> "Download";
			default -> "Reserved";
		};
	}

	private static String decodeRunError(final int runError)
	{
		final String[] flags = new String[] {"SYS0_ERR: buffer error", "SYS1_ERR: system state parity error",
			"EEPROM corrupted", "Stack overflow", "OBJ_ERR: group object/assoc. table error",
			"SYS2_ERR: transceiver error", "SYS3_ERR: confirm error"};
		final int bits = ~runError & 0xff;
		if (bits == 0)
			return "OK";
		final var sb = new StringJoiner(", ");
		for (int i = 0; i < flags.length; i++) {
			if ((bits & (1 << i)) != 0)
				sb.add(flags[i]);
		}
		return sb.toString();
	}

	private static String getLoadState(final byte[] data)
	{
		if (data == null || data.length < 1)
			return "n/a";
		final int state = data[0] & 0xff;
		return switch (state) {
			case 0 -> "Unloaded";
			case 1 -> "Loaded";
			case 2 -> "Loading";
			case 3 -> "Error (during load process)";
			case 4 -> "Unloading";
			case 5 -> "Load Completing (Intermediate)";
			default -> "Invalid load status " + state;
		};
	}

	private static String getRunState(final byte[] data)
	{
		if (data == null || data.length < 1)
			return "n/a";
		final int state = data[0] & 0xff;
		return switch (state) {
			case 0 -> "Halted";
			case 1 -> "Running";
			case 2 -> "Ready";
			case 3 -> "Terminated";
			case 4 -> "Starting";
			case 5 -> "Shutting down";
			default -> "Invalid run state " + state;
		};
	}

	private static String toIPAssignmentString(final byte[] data)
	{
		final int bitset = data[0] & 0xff;
		final var joiner = new StringJoiner(", ");
		if ((bitset & 0x01) != 0)
			joiner.add("manual");
		if ((bitset & 0x02) != 0)
			joiner.add("Bootstrap Protocol");
		if ((bitset & 0x04) != 0)
			joiner.add("DHCP");
		if ((bitset & 0x08) != 0)
			joiner.add("Auto IP");
		return joiner.toString();
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
		final var hex = HexFormat.of().formatHex(data);
		return hex.substring(0, 4) + ":" + hex.substring(4);
	}

	private static String version(final byte[] data) {
		if (data.length == 1) { // BCU1
			return ((data[0] & 0xff) >> 4) + "." + (data[0] & 0x0f);
		}

		final int magic = (data[0] & 0xff) >> 3;
		final int version = ((data[0] & 0x07) << 2) | ((data[1] & 0x0c0) >> 6);
		final int rev = data[1] & 0x3f;
		return "[" + magic + "] " + version + "." + rev;
	}
}
