/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2024 B. Malinowsky

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

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.util.stream.Collectors.joining;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;

import io.calimero.CloseEvent;
import io.calimero.DeviceDescriptor;
import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.KnxRuntimeException;
import io.calimero.Priority;
import io.calimero.Settings;
import io.calimero.dptxlator.DPTXlator;
import io.calimero.dptxlator.PropertyTypes;
import io.calimero.dptxlator.TranslatorTypes;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;
import io.calimero.mgmt.Description;
import io.calimero.mgmt.LocalDeviceManagementIp;
import io.calimero.mgmt.LocalDeviceManagementUsb;
import io.calimero.mgmt.PropertyAccess.PID;
import io.calimero.mgmt.PropertyAdapter;
import io.calimero.mgmt.PropertyClient;
import io.calimero.mgmt.PropertyClient.PropertyKey;
import io.calimero.mgmt.PropertyClient.XmlPropertyDefinitions;
import io.calimero.mgmt.RemotePropertyServiceAdapter;
import io.calimero.serial.usb.UsbConnection;
import io.calimero.serial.usb.UsbConnectionFactory;
import io.calimero.tools.Main.PeekingIterator;
import io.calimero.xml.KNXMLException;
import io.calimero.xml.XmlInputFactory;
import io.calimero.xml.XmlReader;

/**
 * A tool for Calimero showing features of the {@link PropertyClient} used for KNX property access.
 * <p>
 * Property is a {@link Runnable} tool implementation to set or get a KNX property from an Interface
 * Object Server (IOS), get its KNX property description, or scan the KNX descriptions available. It
 * supports network access using a KNXnet/IP, KNX IP, USB, FT1.2, or TP-UART connection.<br>
 * The tool implementation mainly interacts with {@link PropertyClient}, which offers high-level
 * access to KNX property information. It also shows creation of the {@link PropertyAdapter},
 * necessary for a property client to work. All queried property values, as well as occurring
 * problems are written to <code>System.out</code>.
 * <p>
 * When starting this tool from the console, the <code>main</code>-method of this class is invoked,
 * otherwise use it in the context appropriate to {@link Runnable}. Take a look at the command line
 * options to configure the tool with the appropriate communication settings.
 *
 * @author B. Malinowsky
 */
public class Property implements Runnable
{
	private static final String tool = "Property";
	private static final String sep = System.lineSeparator();

	static Logger out = LogService.getLogger("io.calimero.tools");

	/** Contains tool options after parsing command line. */
	protected final Map<String, Object> options = new HashMap<>();

	/** The used property client. */
	protected PropertyClient pc;
	private KNXNetworkLink link;
	private Map<PropertyKey, PropertyClient.Property> definitions;

	// object index -> object type
	private final Map<Integer, Integer> objIndexToType = new HashMap<>();

	private final Thread interruptOnClose;

	private boolean associationTableFormat1;
	private int groupDescriptorSize;

	private static final int pidGroupObjectTable = 51;


	/**
	 * Constructs a new Property object.
	 * <p>
	 *
	 * @param args options for the property tool, see {@link #main(String[])}
	 * @throws KNXIllegalArgumentException on missing or wrong formatted option value
	 */
	public Property(final String[] args)
	{
		interruptOnClose = Thread.currentThread();
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
	 * Entry point for running the Property tool from the console.
	 * <p>
	 * A communication device, host, or port identifier has to be supplied to specify the endpoint
	 * for KNX network access.<br>
	 * To show the usage message of this tool on the console, supply the command line option --help
	 * (or -h).<br>
	 * Command line options are treated case-sensitive. Available options are:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--local -l</code> local device management (default)</li>
	 * <li><code>--remote -r</code> <i>KNX addr</i> &nbsp;remote property service</li>
	 * <li><code>--definitions -d</code> <i>file</i> &nbsp;use property definition file</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * </ul>
	 * For local device management these options are available:
	 * <ul>
	 * <li><code>--emulatewriteenable -e</code> check write-enable of a property</li>
	 * </ul>
	 * For remote property service these options are available:
	 * <ul>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local endpoint</li>
	 * <li><code>--connect -c</code> connection oriented mode</li>
	 * <li><code>--authorize -a</code> <i>key</i> &nbsp;authorize key to access the KNX device</li>
	 * </ul>
	 * Use one of the following commands for property access, with <i>object-idx</i> being the
	 * interface object index, and <i>pid</i> the KNX property identifier:
	 * <ul>
	 * <li><code>get <i>object-idx pid [start-idx elements]</i></code> get the property value(s)</li>
	 * <li><code>set <i>object-idx pid [start-idx] string-value</i></code> set the property string-formatted value</li>
	 * <li><code>set <i>object-idx pid start-idx elements [\"0x\"|\"0\"|\"b\"]data</i></code> set the property data</li>
	 * <li><code>desc <i>object-idx pid</i></code> get the property description of the property ID</li>
	 * <li><code>desc <i>object-idx "i" prop-idx</i></code> get the property description of the property index</li>
	 * <li><code>scan <i>[object-idx]</i></code> list interface object type descriptions (of the
	 * indexed interface object)</li>
	 * <li><code>scan <i>[object-idx]</i> "all"</code> list all property descriptions (of the
	 * indexed interface object)</li>
	 * <li><code>?</code> show command help</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected
	 * that directly communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX
	 * individual address shall be unique in a network, and the subnetwork address (area and line)
	 * should be set to match the network configuration.
	 *
	 * @param args command line options for the property tool
	 */
	public static void main(final String... args)
	{
		try {
			new Property(args).run();
		}
		catch (final Throwable t) {
			out.log(ERROR, "parsing option", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			if (options.isEmpty()) {
				out(tool + " - Access KNX properties");
				Main.showVersion();
				out("Type --help for help message");
				return;
			}
			if (options.containsKey("about")) {
				((Runnable) options.get("about")).run();
				return;
			}

			final PropertyAdapter adapter = createAdapter();

			if (options.containsKey("reset") && adapter instanceof final LocalDeviceManagementIp ldm) {
				out("send local device management reset request to " + options.get("host") + ":" + options.get("port"));
				ldm.reset();
				while (ldm.isOpen())
					Thread.sleep(1000);
			}

			pc = new PropertyClient(adapter);
			String resource = "";
			try {
				// check if user supplied an XML resource with property definitions
				if (options.containsKey("definitions")) {
					resource = (String) options.get("definitions");
					pc.addDefinitions(new XmlPropertyDefinitions().load(resource));
				}
				else {
					resource = "/properties.xml";
					try (InputStream is = Settings.class.getResourceAsStream(resource);
							XmlReader r = XmlInputFactory.newInstance().createXMLStreamReader(is)) {
						pc.addDefinitions(new XmlPropertyDefinitions().load(r));
					}
				}
				definitions = pc.getDefinitions();
			}
			catch (IOException | KNXMLException e) {
				out.log(ERROR, "loading definitions from " + resource + " failed", e);
			}

			// run the user command
			runCommand((String[]) options.get("command"));
		}
		catch (KNXException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		finally {
			if (pc != null)
				pc.close();
			if (link != null)
				link.close();
			onCompletion(thrown, canceled);
		}
	}

	private void adapterClosed(final CloseEvent e)
	{
		out("connection closed (" + e.getReason() + ")");
		if (e.getInitiator() != CloseEvent.USER_REQUEST)
			interruptOnClose.interrupt();
	}

	/**
	 * Runs a single command.
	 *
	 * @param cmd the command to execute together with its parameters
	 * @throws InterruptedException on thread interrupt
	 */
	protected void runCommand(final String... cmd) throws InterruptedException
	{
		if (cmd == null)
			return;
		try {
			final String what = cmd[0];
			if ("get".equals(what))
				getProperty(cmd);
			else if ("set".equals(what))
				setProperty(cmd);
			else if ("scan".equals(what))
				scanProperties(cmd);
			else if ("desc".equals(what))
				getDescription(cmd);
			else if ("?".equals(what) || "help".equals(what))
				showCommandList();
			else
				out("unknown command ('?' or 'help' shows help)");
		}
		catch (final NumberFormatException e) {
			out.log(ERROR, "invalid number (" + e.getMessage() + ")");
		}
		catch (final KNXException | RuntimeException e) {
			out.log(ERROR, e.getMessage());
		}
	}

	private void notifyDescription(final Description d) {
		objIndexToType.put(d.objectIndex(), d.objectType());
		onDescription(d);
	}

	private record JsonDescription(int objIndex, int objType, int objInstance, int pid, int propIndex, String name,
	                               String pidName, int maxElems, int currElems, int pdt, String dpt, int readLevel,
	                               int writeLevel, boolean writeEnabled) implements Json {}

	/**
	 * Invoked on receiving a property description.
	 *
	 * @param d the KNX property description
	 */
	protected void onDescription(final Description d)
	{
		PropertyClient.Property p = getPropertyDef(d.objectType(), d.pid());
		if (p == null)
			p = getPropertyDef(PropertyKey.GLOBAL_OBJTYPE, d.pid());

		final int pdtDefault = p != null ? p.pdt() : -1;
		final int pdt = d.pdt() == -1 ? pdtDefault : d.pdt();

		if (options.containsKey("json")) {
			final var name = p != null ? p.propertyName() : null;
			final var pidName = p != null ? p.pidName() : null;
			final var json = new JsonDescription(d.objectIndex(), d.objectType(), d.objectInstance(), d.pid(),
					d.propIndex(), name, pidName, d.maxElements(), d.currentElements(), pdt,
					d.dpt().orElse(null), d.readLevel(), d.writeLevel(), d.writeEnabled());
			System.out.println(json.toJson());
			return;
		}

		final StringBuilder buf = new StringBuilder();
		buf.append("OI ").append(alignRight(d.objectIndex(), 2));
		buf.append(", PI ").append(alignRight(d.propIndex(), 2)).append(" |");
		buf.append(" OT ").append(alignRight(d.objectType(), 3));
		buf.append(", PID ").append(alignRight(d.pid(), 3));
		buf.append(" | ");
		if (p != null) {
			buf.append(p.propertyName());
			while (buf.length() < 65)
				buf.append(' ');
			buf.append(" (");
			buf.append(p.pidName());
			buf.append(")");
		}
		else
			buf.append(new String(new char[33]).replace('\0', ' ')).append("(n/a)");
		buf.append(", PDT ").append(pdt == -1 ? "-" : pdt);
		buf.append(", curr. elems ").append(d.currentElements());
		buf.append(", max. ").append(d.maxElements());
		buf.append(", r/w access ").append(d.readLevel()).append("/").append(d.writeLevel());
		buf.append(d.writeEnabled() ? ", w.enabled" : ", r.only");
		System.out.println(buf);
	}

	/**
	 * Invoked on receiving a property value.
	 *
	 * @param idx the object index
	 * @param pid the property ID
	 * @param value the property values
	 * @param raw list with the raw property data, <code>list size == property elements</code>
	 */
	protected void onPropertyValue(final int idx, final int pid, final String value, final List<byte[]> raw)
	{
		if (options.containsKey("json"))
			System.out.println(toJson(idx, pid, value, raw));
		else {
			final String rawValue = raw.stream().map(HexFormat.of()::formatHex).collect(joining(delimiter, " (", ")"));
			System.out.println(value + rawValue);
		}
	}

	private static String toJson(final int idx, final int pid, final String value, final List<byte[]> raw) {
		record JsonProperty(int index, int pid, String value, List<byte[]> data) implements Json {}

		final var json = new JsonProperty(idx, pid, value, raw);
		return json.toJson();
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
			out("reading property canceled");
		if (thrown != null)
			out.log(ERROR, "on completion", thrown);
	}

	/** @return the network link used by this tool */
	protected KNXNetworkLink link()
	{
		return link;
	}

	/**
	 * Creates the property adapter to be used with the property client depending on the supplied
	 * user <code>options</code>.
	 * <p>
	 * There are two types of property adapters. One is for local device management to access KNX
	 * properties of the connected interface, specifically, KNXnet/IP and KNX USB devices. The other
	 * type uses remote property services to access KNX properties of a KNX device over the KNX bus.
	 * If a remote property service adapter is requested, the required KNX network link to access
	 * the KNX network is automatically created.
	 *
	 * @return the created adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createAdapter() throws KNXException, InterruptedException
	{
		final String host = (String) options.get("host");
		// decide what type of adapter to create
		if (options.containsKey("local")) {
			if (options.containsKey("usb"))
				return createUsbAdapter(host);
			if (!options.getOrDefault("user", 1).equals(1))
				throw new KnxRuntimeException("secure local device management requires user 1 (management user)");
			return Main.newLocalDeviceMgmt(options, this::adapterClosed);
		}
		return createRemoteAdapter();
	}

	/**
	 * Creates a local device management adapter for a KNX USB interface.
	 */
	private PropertyAdapter createUsbAdapter(final String device) throws KNXException,
		InterruptedException
	{
		final UsbConnection usb = UsbConnectionFactory.open(device);
		return new LocalDeviceManagementUsb(usb, this::adapterClosed, options.containsKey("emulatewriteenable"));
	}

	/**
	 * Creates the KNX network link and remote property service adapter for one device in the KNX
	 * network. The adapter uses a KNX network link for access, also is created by this method.
	 *
	 * @return remote property service adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createRemoteAdapter() throws KNXException, InterruptedException
	{
		link = Main.newLink(options);
		final IndividualAddress remote = (IndividualAddress) options.get("remote");
		// if an authorization key was supplied, the adapter uses
		// connection oriented mode and tries to authenticate
		final byte[] authKey = (byte[]) options.get("authorize");
		if (authKey != null) {
			final RemotePropertyServiceAdapter adapter = new RemotePropertyServiceAdapter(link, remote, this::adapterClosed, authKey);
			out.log(INFO, "{0} granted access level {1}", remote, adapter.accessLevel());
			return adapter;
		}
		return new RemotePropertyServiceAdapter(link, remote, this::adapterClosed, options.containsKey("connect"));
	}

	private static String alignRight(final int value, final int width)
	{
		return String.format("%1$" + width + "s", value);
	}

	private PropertyClient.Property getPropertyDef(final int objType, final int pid)
	{
		if (definitions == null)
			return null;
		return definitions.get(new PropertyKey(objType, pid));
	}

	private void parseOptions(final String[] args)
	{
		if (args.length == 0)
			return;
		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", new TPSettings());

		for (final var i = new Main.PeekingIterator<>(List.of(args).iterator()); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) Property::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "local", "l"))
				options.put("local", null);
			else if (Main.isOption(arg, "remote", "r"))
				options.put("remote", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "definitions", "d"))
				options.put("definitions", i.next());
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "emulatewriteenable", "e"))
				options.put("emulatewriteenable", null);
			else if (Main.isOption(arg, "connect", "c"))
				options.put("connect", null);
			else if (Main.isOption(arg, "authorize", "a"))
				options.put("authorize", getAuthorizeKey(i.next()));
			else if (arg.equals("reset"))
				options.put("reset", null);
			else if (parseCommand(i, arg))
				;
			else if (arg.equals("?"))
				options.put("command", new String[] { "?" });
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		// we allow a default usb config where the first found knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

		if (!options.containsKey("remote"))
			options.put("local", null);
		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("no communication device/host specified");
		if (options.containsKey("ft12") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("--remote option is mandatory with --ft12");
		Main.setDomainAddress(options);
	}

	private boolean parseCommand(final PeekingIterator<String> i, final String arg) {
		if (!arg.equals("get") && !arg.equals("set") && !arg.equals("desc") && !arg.equals("scan"))
			return false;
		final List<String> cmd = new ArrayList<>();
		cmd.add(arg);
		try {
			while (i.hasNext()) {
				Integer.decode(i.peek());
				cmd.add(i.next());
			}
		}
		catch (final NumberFormatException expected) {}

		if (arg.equals("desc") && i.hasNext() && "i".equals(i.peek())) {
			cmd.add(i.next());
			cmd.add(i.next());
		}
		else if (arg.equals("scan") && "all".equals(i.peek()))
			cmd.add(i.next());

		options.put("command", cmd.toArray(new String[0]));
		return true;
	}

	//
	// utility methods
	//

	private void getProperty(final String[] args) throws KNXException, InterruptedException
	{
		if (args.length == 2 && args[1].equals("?"))
			out("get object-idx pid [start-idx elements]");
		else if (args.length == 3 || args.length == 5) {
			final int oi = toInt(args[1]);
			final int pid = toInt(args[2]);

			final int maxElements = 15;
			String s = "";
			final List<byte[]> raw = new ArrayList<>();

			int objType = PropertyKey.GLOBAL_OBJTYPE;
			try {
				objType = objIndexToType.getOrDefault(oi, PropertyKey.GLOBAL_OBJTYPE);
				if (customFormatter.containsKey(key(objType, pid)) || customFormatter.containsKey(key(pid)))
					throw new KNXException();

				if (args.length == 3) {
					final DPTXlator x = pc.getPropertyTranslated(oi, pid, 1, 1);
					s = x.getValue();
					raw.add(x.getData());
				}
				else {
					final int start = toInt(args[3]);
					final int elements = toInt(args[4]);
					if (start == 0 && elements != 1) {
						out("reading number of property elements (start-idx 0) requires elements = 1");
						return;
					}

					for (int i = 0; i < elements; i += maxElements) {
						final int min = Math.min(maxElements, elements - i);
						final DPTXlator translator = pc.getPropertyTranslated(oi, pid, start + i, min);
						final byte[] data = translator.getData();
						final int size = data.length / min;
						final String[] allValues = translator.getAllValues();
						if (!s.isEmpty())
							s += ", ";
						s += String.join(delimiter, allValues);
						for (int from = 0; from < data.length; from += size)
							raw.add(Arrays.copyOfRange(data, from, from + size));
					}
				}
			}
			catch (final KNXException | RuntimeException e) {
				// if we're reading association table content, figure out table format size before
				if (objType == 2 && pid == PID.TABLE) {
					final var desc = pc.getDescription(oi, PID.TABLE);
					final int pdt = desc.pdt();
					associationTableFormat1 = pdt == PropertyTypes.PDT_GENERIC_04;
				}
				// if we're reading group object table content, figure out GO descriptor size before
				if (objType == 9 && pid == PID.TABLE) {
					final var desc = pc.getDescription(oi, PID.TABLE);
					final int pdt = desc.pdt();
					switch (pdt) {
					case PropertyTypes.PDT_GENERIC_02:
						groupDescriptorSize = 2;
						break;
					case PropertyTypes.PDT_GENERIC_03:
						groupDescriptorSize = 3;
						break;
					case PropertyTypes.PDT_GENERIC_04:
						groupDescriptorSize = 4;
						break;
					case PropertyTypes.PDT_GENERIC_06:
						groupDescriptorSize = 6;
						break;
					default:
						groupDescriptorSize = 0;
						break;
					}
				}
				if (objType == 9 && pid == pidGroupObjectTable) {
					groupDescriptorSize = 6;
				}

				if (args.length == 3) {
					final byte[] data = pc.getProperty(oi, pid, 1, 1);

					s = customFormatter(objType, pid).map(f -> f.apply(data)).orElseGet(() -> "0x" + HexFormat.of().formatHex(data));
					raw.add(data);
				}
				else {
					final int start = toInt(args[3]);
					final int elements = toInt(args[4]);
					if (start == 0 && elements != 1) {
						out("reading number of property elements (start-idx 0) requires elements = 1");
						return;
					}

					final var collect = new ByteArrayOutputStream();
					for (int i = 0; i < elements; i += maxElements) {
						final int min = Math.min(maxElements, elements - i);
						final byte[] part = pc.getProperty(oi, pid, start + i, min);
						collect.writeBytes(part);
					}

					final var data = collect.toByteArray();
					if (data.length > 0) {
						s = customFormatter(objType, pid).map(f -> f.apply(data)).orElseGet(() -> {
							final StringBuilder tmp = new StringBuilder();
							final String hex = HexFormat.of().formatHex(data);
							final int chars = hex.length() / elements;
							for (int k = 0; k < elements; ++k)
								tmp.append("0x").append(hex, k * chars, (k + 1) * chars).append(" ");
							return tmp.toString();
						});

						final int size = data.length / elements;
						for (int from = 0; from < data.length; from += size)
							raw.add(Arrays.copyOfRange(data, from, from + size));

						s = s.trim();
					}
				}
			}
			onPropertyValue(oi, pid, s, raw);
		}
		else
			out("sorry, wrong number of arguments");
	}

	private void getDescription(final String[] args) throws KNXException, InterruptedException
	{
		if (args.length == 3)
			notifyDescription(pc.getDescription(toInt(args[1]), toInt(args[2])));
		else if (args.length == 4 && args[2].equals("i"))
			notifyDescription(pc.getDescriptionByIndex(toInt(args[1]), toInt(args[3])));
		else if (args.length == 2 && args[1].equals("?"))
			printHelp("desc object-idx pid" + sep + "desc object-idx \"i\" prop-idx");
		else
			out("sorry, wrong number of arguments");
	}

	private void setProperty(final String[] args) throws KNXException, InterruptedException
	{
		if (args.length == 2 && args[1].equals("?")) {
			printHelp("set object-idx pid [start-idx] string-value" + sep
					+ "set object-idx pid start-idx elements [\"0x\"|\"0\"|\"b\"]data" + sep
					+ "(use hexadecimal format for more than 8 byte data or leading zeros)");
			return;
		}
		if (args.length < 4 || args.length > 6) {
			out("sorry, wrong number of arguments");
			return;
		}
		final int cnt = args.length;
		final int oi = toInt(args[1]);
		final int pid = toInt(args[2]);
		if (cnt == 4)
			pc.setProperty(oi, pid, 1, args[3]);
		else if (cnt == 5)
			pc.setProperty(oi, pid, toInt(args[3]), args[4]);
		else {
			final int start = toInt(args[3]);
			final int elements = toInt(args[4]);
			final byte[] data = toByteArray(args[5]);
			final int typeSize = data.length / elements;
			if (typeSize == 0)
				throw new KNXIllegalArgumentException(String.format(
						"property data %s cannot be split into %d elements (type size 0)", args[5], elements));
			final int usableAsdu = 14 - 4; // std. frame
			final int maxLength = usableAsdu / typeSize * typeSize;
			for (int i = 0; i < data.length; i += maxLength) {
				final int len = Math.min(maxLength, (data.length - i));
				pc.setProperty(oi, pid, start + i / typeSize, len / typeSize, Arrays.copyOfRange(data, i, i + len));
			}
		}
	}

	private void scanProperties(final String[] args) throws KNXException, InterruptedException
	{
		final int cnt = args.length;
		if (cnt == 2 && args[1].equals("?")) {
			printHelp("scan [object-idx] [\"all\" for all object properties]");
			return;
		}

		System.out.println("Object Index (OI), Property Index (PI), Object Type (OT), Property ID (PID)");
		if (cnt == 1)
			pc.scanProperties(false, this::notifyDescription);
		else if (cnt == 2) {
			if (args[1].equals("all"))
				pc.scanProperties(true, this::notifyDescription);
			else
				pc.scanProperties(toInt(args[1]), false, this::notifyDescription);
		}
		else if (cnt == 3 && args[2].equals("all"))
			pc.scanProperties(toInt(args[1]), true, this::notifyDescription);
		else {
			out("sorry, wrong number of arguments");
			return;
		}
		System.out.println("scan complete");
	}

	private static void showCommandList()
	{
		out("""
				commands: get | set | desc | scan (append ? for help)
				  get  - read property value(s)
				  set  - write property value(s)
				  desc - read one property description
				  scan - read property descriptions""");
	}

	private static void printHelp(final String help)
	{
		out(help);
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port> <command>");
		joiner.add(Main.printCommonOptions());
		final var options = """
				  --local -l                 local device management
				  --remote -r <KNX addr>     remote property service
				  --definitions -d <file>    use property definition file
				Options for local device management only:
				  --emulatewriteenable -e    check write-enable of a property
				Options for remote property services only:
				  --connect -c               connection oriented mode
				  --authorize -a <key>       authorize key to access KNX device""";
		joiner.add(options);
		joiner.add(Main.printSecureOptions());
		final var commands = """
				Available commands:
				  get <object-idx> <pid> [<start-idx> <elements>]     get the property value(s)
				  set <object-idx> <pid> [start-idx] <string-value>   set the formatted property value (according to PDT)
				  set <object-idx> <pid> <start-idx> <elements> ["0x"|"0"|"b"]<data>    set the property data
				  desc <object-idx> <pid>                get the property description of the property ID
				  desc <object-idx> "i" <prop-idx>       get the property description of the property index
				  scan [<object-idx>]                    list interface object type description
				  scan [<object-idx>] "all"              list all property descriptions
				  ?                                      show command help""";
		joiner.add(commands);
		out(joiner.toString());
	}

	//
	// utility methods
	//

	private static byte[] getAuthorizeKey(final String key)
	{
		final long value = Long.decode(key);
		if (value < 0 || value > 0xFFFFFFFFL)
			throw new KNXIllegalArgumentException("invalid authorize key");
		return new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value };
	}

	private static int toInt(final String number)
	{
		return Integer.decode(number);
	}

	private static byte[] toByteArray(final String s)
	{
		// use of BigXXX equivalent is a bit awkward, for now this is sufficient ...
		long l;
		if (s.startsWith("0x") || s.startsWith("0X")) {
			final byte[] d = new byte[(s.length() - 1) / 2];
			int k = (s.length() & 0x01) != 0 ? 3 : 4;
			for (int i = 2; i < s.length(); i = k, k += 2)
				d[(i - 1) / 2] = (byte) Integer.parseInt(s.substring(i, k), 16);
			return d;
		}
		else if (s.length() > 1 && s.startsWith("0"))
			l = Long.parseLong(s, 8);
		else if (s.startsWith("b"))
			l = Long.parseLong(s.substring(1), 2);
		else
			l = Long.parseLong(s);
		int i = 0;
		for (long test = l; test != 0; test /= 0x100)
			++i;
		final byte[] d = new byte[i == 0 ? 1 : i];
		for (; i-- > 0; l /= 0x100)
			d[i] = (byte) (l & 0xff);
		return d;
	}

	static void out(final String s)
	{
		System.out.println(s);
	}

	// custom formatter stuff

	private Optional<Function<byte[], String>> customFormatter(final int objectType, final int pid) {
		final var formatter = customFormatter.get(key(objectType, pid));
		return Optional.ofNullable(formatter != null ? formatter : customFormatter.get(key(pid)));
	}

	private static PropertyKey key(final int pid) { return new PropertyKey(PropertyKey.GLOBAL_OBJTYPE, pid); }
	private static PropertyKey key(final int objectType, final int pid) { return new PropertyKey(objectType, pid); }

	private final Map<PropertyKey, Function<byte[], String>> customFormatter = new HashMap<>();

	{
		customFormatter.put(key(PID.DESCRIPTION), Property::string);
		customFormatter.put(key(PID.PROGRAM_VERSION), Property::programVersion);
		customFormatter.put(key(PID.OBJECT_NAME), Property::string);
		customFormatter.put(key(PID.MANUFACTURER_ID),
				data -> Main.manufacturer((data[0] & 0xff) << 8 | data[1] & 0xff));
		customFormatter.put(key(PID.LOAD_STATE_CONTROL), Property::loadState);
		customFormatter.put(key(PID.VERSION), Property::version);
		customFormatter.put(key(1, PID.TABLE), Property::groupAddresses);

		customFormatter.put(key(0, PID.SERIAL_NUMBER), Property::knxSerialNumber);
		customFormatter.put(key(0, 52), Property::maxRetryCount);
		customFormatter.put(key(0, PID.DEVICE_DESCRIPTOR), Property::deviceDescriptor);
		final int pidErrorFlags = 53;
		customFormatter.put(key(0, pidErrorFlags), Property::errorFlags);
		customFormatter.put(key(0, PID.SUBNET_ADDRESS), Property::subnetAddress);

		customFormatter.put(key(2, PID.TABLE), this::associationTable);

		customFormatter.put(key(6, PID.MEDIUM_STATUS),
				data -> "communication " + (bitSet(data[0], 0) ? "impossible" : "possible"));
		customFormatter.put(key(6, PID.MAIN_LCCONFIG), Property::lineCouplerConfig);
		customFormatter.put(key(6, PID.SUB_LCCONFIG), Property::lineCouplerConfig);
		customFormatter.put(key(6, PID.SUB_LCGROUPCONFIG), Property::lineCouplerGroupConfig);
		customFormatter.put(key(6, PID.MAIN_LCGROUPCONFIG), Property::lineCouplerGroupConfig);
		final int pidCouplerServiceControl = 57;
		customFormatter.put(key(6, pidCouplerServiceControl), Property::couplerServiceControl);

		customFormatter.put(key(9, PID.TABLE), this::groupObjectDescriptors);
		final int extGroupObjectReference = 52;
		customFormatter.put(key(9, pidGroupObjectTable), this::groupObjectDescriptors);
		customFormatter.put(key(9, extGroupObjectReference), this::extGroupObjectReferences);

		// at least jung devices have DD0 also in cEMI server and KNXnet/IP object
		customFormatter.put(key(8, PID.DEVICE_DESCRIPTOR), Property::deviceDescriptor);
		customFormatter.put(key(11, PID.DEVICE_DESCRIPTOR), Property::deviceDescriptor);

		customFormatter.put(key(11, PID.MAC_ADDRESS), HexFormat.ofDelimiter(":")::formatHex);
		customFormatter.put(key(11, PID.KNXNETIP_DEVICE_CAPABILITIES), Property::deviceCapabilities);
		customFormatter.put(key(11, PID.KNXNETIP_ROUTING_CAPABILITIES), Property::routingCapabilities);
		customFormatter.put(key(11, PID.CURRENT_IP_ADDRESS), Property::ipAddress);
		customFormatter.put(key(11, PID.CURRENT_SUBNET_MASK), Property::ipAddress);
		customFormatter.put(key(11, PID.CURRENT_DEFAULT_GATEWAY), Property::ipAddress);
		customFormatter.put(key(11, PID.DHCP_BOOTP_SERVER), Property::ipAddress);
		customFormatter.put(key(11, PID.IP_ADDRESS), Property::ipAddress);
		customFormatter.put(key(11, PID.SUBNET_MASK), Property::ipAddress);
		customFormatter.put(key(11, PID.DEFAULT_GATEWAY), Property::ipAddress);
		customFormatter.put(key(11, PID.ROUTING_MULTICAST_ADDRESS), Property::ipAddress);
		customFormatter.put(key(11, PID.SYSTEM_SETUP_MULTICAST_ADDRESS), Property::ipAddress);
		customFormatter.put(key(11, PID.FRIENDLY_NAME), Property::string);
		customFormatter.put(key(11, PID.CURRENT_IP_ASSIGNMENT_METHOD), Property::ipAssignmentMethod);
		customFormatter.put(key(11, PID.IP_ASSIGNMENT_METHOD), Property::ipAssignmentMethod); // ??? correct
		customFormatter.put(key(11, PID.KNX_INDIVIDUAL_ADDRESS), Property::individualAddresses);
		customFormatter.put(key(11, PID.ADDITIONAL_INDIVIDUAL_ADDRESSES), Property::individualAddresses);
		customFormatter.put(key(11, PID.IP_CAPABILITIES),
				data -> ipAssignmentMethod(new byte[] { (byte) ((data[0] << 1) | 0x01) }));
	}

	private static final String delimiter = ", ";

	private static String knxSerialNumber(final byte[] data) {
		final var hex = HexFormat.of().formatHex(data);
		return hex.substring(0, 4) + ":" + hex.substring(4);
	}

	private static String maxRetryCount(final byte[] data) {
		return "Busy: " + (data[0] >> 4) + ", NAK: " + (data[0] & 0x7);
	}

	private static String couplerServiceControl(final byte[] data) {
		final var v = data[0];
		final var joiner = new StringJoiner(delimiter);
		joiner.add("SNA inconsistency check: " + enabled(v, 0));
		joiner.add("SNA heartbeat: " + enabled(v, 1));
		joiner.add("Update SNA: " + enabled(v, 2));
		joiner.add("SNA read: " + enabled(v, 3));
		joiner.add("Distribute subline status: " + enabled(v, 4));
		return joiner.toString();
	}

	private static String enabled(final byte v, final int bit) {
		return bitSet(v, bit) ? "enabled" : "disabled";
	}

	private static String version(final byte[] data) {
		final var magic = (data[0] & 0xff) >> 3;
		final var version = ((data[0] & 0x07) << 2) | ((data[1] & 0x0c0) >> 6);
		final var rev = data[1] & 0x3f;
		return "[" + magic + "] " + version + "." + rev;
	}

	private static String individualAddresses(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		for (int i = 0; i < data.length; i += 2) {
			final var address = ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
			joiner.add(new IndividualAddress(address).toString());
		}
		return joiner.toString();
	}

	private static String groupAddresses(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		for (int i = 0; i < data.length; i += 2) {
			final var address = ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
			joiner.add(new GroupAddress(address).toString());
		}
		return joiner.toString();
	}

	// Group Object Association Table: connection number (TSAP) -> group object number (ASAP)
	private String associationTable(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		final var buffer = ByteBuffer.wrap(data);
		while (buffer.hasRemaining()) {
			final int first = associationTableFormat1 ? buffer.getShort() & 0xffff : buffer.get() & 0xff;
			final int second = associationTableFormat1 ? buffer.getShort() & 0xffff : buffer.get() & 0xff;
			final var assoc = first + "=" + second;
			joiner.add(assoc);
		}
		return joiner.toString();
	}

	private String groupObjectDescriptors(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		final var buffer = ByteBuffer.wrap(data);
		int groupObject = 1;

		String dptId = null;
		while (buffer.hasRemaining()) {
			final byte[] descriptor = new byte[groupDescriptorSize];
			buffer.get(descriptor);

			final StringBuilder sb = new StringBuilder();

			final int config;
			final int bitsize;

			switch (descriptor.length) {
			case 2:
				// System B
				config = descriptor[0] & 0xff;
				bitsize = valueFieldTypeToBits(descriptor[1] & 0xff);
				break;
			case 3:
				// realization type 1 & 2, most devices
				config = descriptor[1] & 0xff;
				bitsize = valueFieldTypeToBits(descriptor[2] & 0xff);
				break;
			case 4:
				// system 7
				config = descriptor[2] & 0xff;
				bitsize = valueFieldTypeToBits(descriptor[3] & 0xff);
				break;
			case 6:
				// System 300
				// config is 2 bytes, but high byte is always 0
				config = descriptor[1] & 0xff;

				final int mainType = (descriptor[2] & 0xff) << 8 | descriptor[3] & 0xff;
				final int subType = (descriptor[4] & 0xff) << 8 | descriptor[5] & 0xff;
				dptId = String.format("%d.%03d", mainType, subType);
				bitsize = translatorBitSize(dptId);
				break;
			default:
				return HexFormat.ofDelimiter(" ").formatHex(data);
			}

			final var priority = Priority.get(config & 0x03);
			final boolean enable = (config & 0x04) != 0;
			final boolean responder = (config & 0x08) != 0;
			final boolean write = (config & 0x10) != 0;
			final boolean transmit = (config & 0x40) != 0;
			final boolean updateOnResponse = (config & 0x80) != 0;

			sb.append("GO#").append(groupObject).append(" ");
			sb.append(bitsize).append(bitsize == 1 ? " bit " : " bits ");
			if (dptId != null)
				sb.append(dptId).append(" ");

			final var commFlags = new StringJoiner("/");
			if (enable)
				commFlags.add("C");
			if (responder)
				commFlags.add("R");
			if (write)
				commFlags.add("W");
			if (transmit)
				commFlags.add("T");
			if (updateOnResponse)
				commFlags.add("U");

			sb.append("(").append(commFlags).append(", ").append(priority).append(")");
			joiner.add(sb);

			groupObject++;
		}
		return joiner.toString();
	}

	private String extGroupObjectReferences(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		final var buffer = ByteBuffer.wrap(data);

		String s = "OT(Oinst)|PID ";
		while (buffer.hasRemaining()) {
			final int ot = buffer.getShort() & 0xffff;
			final int oi = buffer.get() & 0xff;
			final int pid = buffer.get() & 0xff;
			final int startIdx = buffer.getShort() & 0xffff;
			final int bitOffset = buffer.get() & 0xff;
			final int conv = buffer.get() & 0xff;

			s += String.format("%d(%d)|%d startidx %d bitoffset %d conv %d", ot, oi, pid, startIdx,
					bitOffset, conv);
			joiner.add(s);
			s = "";
		}
		return joiner.toString();
	}

	// decodes group object descriptor value field type into PDT bit size
	private static int valueFieldTypeToBits(final int code) {
		final int[] lowerFieldTypes = { 1, 2, 3, 4, 5, 6, 7, 8,
			2 * 8, 3 * 8, 4 * 8, 6 * 8, 8 * 8, 10 * 8, 14 * 8,
			5 * 8, 7 * 8, 9 * 8, 11 * 8, 12 * 8, 13 * 8
		};

		if (code < lowerFieldTypes.length)
			return lowerFieldTypes[code];
		if (code == 255)
			return 252 * 8;
		return (code - 6) * 8;
	}

	private static int translatorBitSize(final String dptId) {
		try {
			return TranslatorTypes.createTranslator(dptId).bitSize();
		}
		catch (final KNXException e) {
			return 0;
		}
	}

	// same as BCU error flags located at 0x10d
	private static String errorFlags(final byte[] data) {
		if ((data[0] & 0xff) == 0xff)
			return "everything OK";
		final String[] description = { "System 1 internal system error", "illegal system state",
			"checksum / CRC error in internal non-volatile memory", "stack overflow error",
			"inconsistent system tables", "physical transceiver error", "System 2 internal system error",
			"System 3 internal system error" };
		final var joiner = new StringJoiner(delimiter);
		for (int i = 0; i < 8; i++)
			if ((data[0] & (1 << i)) == 0)
				joiner.add(description[i]);
		return joiner.toString();
	}

	private static String subnetAddress(final byte[] data) {
		final int i = data[0] & 0xff;
		return (i >> 4) + "." + (i & 0x0f);
	}

	private static String programVersion(final byte[] data) {
		if (data.length != 5)
			return HexFormat.of().formatHex(data);
		final int mfr = (data[0] & 0xff) << 8 | data[1] & 0xff;
		return String.format("%s %02x%02x v%d.%d", Main.manufacturer(mfr), data[2], data[3],
				(data[4] & 0xff) >> 4, data[4] & 0xf);
	}

	private static String string(final byte[] data) {
		return new String(data, StandardCharsets.ISO_8859_1);
	}

	private static String deviceCapabilities(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		if ((data[1] & 0x01) == 0x01)
			joiner.add("Device Management");
		if ((data[1] & 0x02) == 0x02)
			joiner.add("Tunneling");
		if ((data[1] & 0x04) == 0x04)
			joiner.add("Routing");
		if ((data[1] & 0x08) == 0x08)
			joiner.add("Remote Logging");
		if ((data[1] & 0x10) == 0x10)
			joiner.add("Remote Configuration & Diagnosis");
		if ((data[1] & 0x20) == 0x20)
			joiner.add("Object Server");
		return joiner.toString();
	}

	private static String routingCapabilities(final byte[] data) {
		final var joiner = new StringJoiner(delimiter);
		final byte b = data[0];
		if ((b & 0x01) == 0x01)
			joiner.add("Queue overflow error count");
		if ((b & 0x02) == 0x02)
			joiner.add("Transmitted telegram count");
		if ((b & 0x04) == 0x04)
			joiner.add("Priority/FIFO");
		if ((b & 0x08) == 0x08)
			joiner.add("Multiple KNX installations");
		if ((b & 0x10) == 0x10)
			joiner.add("Group address mapping between installations");
		return joiner.toString();
	}

	private static String ipAddress(final byte[] data) {
		try {
			return InetAddress.getByAddress(data).getHostAddress();
		}
		catch (final UnknownHostException e) {}
		return "n/a";
	}

	private static String ipAssignmentMethod(final byte[] data) {
		final int bitset = data[0] & 0xff;
		final var joiner = new StringJoiner(delimiter);
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

	private static String deviceDescriptor(final byte[] data) {
		try {
			return DeviceDescriptor.from(data).toString();
		}
		catch (final KNXFormatException e) {
			return HexFormat.of().formatHex(data);
		}
	}

	private static String lineCouplerConfig(final byte[] data) {
		final var v = data[0];
		final var joiner = new StringJoiner(delimiter);

		// handling of received frames in p2p connectionless or CO communication mode
		final var physFrame = v & 0x03;
		joiner.add("P2P frames: " + (physFrame == 1 ? "route all"
				: physFrame == 2 ? "don't route" : physFrame == 3 ? "normal mode" : "-"));
		// repetition of frames in p2p connectionless or CO communication mode in case of tx errors
		joiner.add((bitSet(v, 2) ? "repeat" : "don't repeat") + " on TX error");
		// routing of frames in broadcast communication mode
		joiner.add("BC frames: " + (bitSet(v, 3) ? "block" : "route"));
		// repetition of frames in broadcast communication mode in case of tx errors
		joiner.add((bitSet(v, 4) ? "repeat" : "don't repeat") + " on TX error");
		// Layer-2 acknowledge of received frames in multicast communication mode
		joiner.add("MC ACK: " + (bitSet(v, 5) ? "routed only" : "all"));
		// Layer-2 acknowledge of received frames in p2p connectionless or CO communication mode
		final var physIack = (v >> 6) & 0x03;
		joiner.add("L2 P2P ACK: "
				+ (physIack == 1 ? "normal mode" : physIack == 2 ? "all" : physIack == 3 ? "NACK all" : "-"));

		return joiner.toString();
	}

	private static String lineCouplerGroupConfig(final byte[] data) {
		final var v = data[0];
		final var joiner = new StringJoiner(delimiter);

		// handling of standard group addressed frames with address ≤ 0x6FFF
		final var group6fff = v & 0x03;
		joiner.add("group address ≤ 0x6fff (13/7/255): " + (group6fff == 1 ? "route all"
				: group6fff == 2 ? "don't route" : group6fff == 3 ? "routing table" : "-"));
		// handling of group addressed frames with address ≥ 0x7000
		final var group7000 = (v >> 2) & 0x03;
		joiner.add("group address ≥ 0x7000 (14/0/0): " + (group7000 == 1 ? "route all"
				: group7000 == 2 ? "don't route" : group7000 == 3 ? "routing table" : "-"));
		// repetition of group addressed frames in case of tx errors
		joiner.add("TX error: " + (bitSet(v, 4) ? "repeat" : "don't repeat"));

		return joiner.toString();
	}

	private static String loadState(final byte[] data) {
		final int state = data[0] & 0xff;
		return switch (state) {
			case 0 -> "unloaded";
			case 1 -> "loaded";
			case 2 -> "loading";
			case 3 -> "error (during load process)";
			case 4 -> "unloading";
			case 5 -> "load completing";
			default -> "invalid load status " + state;
		};
	}

	private static boolean bitSet(final byte value, final int bit) {
		return (value & (1 << bit)) == (1 << bit);
	}
}
