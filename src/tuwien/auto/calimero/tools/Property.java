/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2015 B. Malinowsky

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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
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
import tuwien.auto.calimero.mgmt.Description;
import tuwien.auto.calimero.mgmt.LocalDeviceManagementUsb;
import tuwien.auto.calimero.mgmt.LocalDeviceMgmtAdapter;
import tuwien.auto.calimero.mgmt.PropertyAdapter;
import tuwien.auto.calimero.mgmt.PropertyAdapterListener;
import tuwien.auto.calimero.mgmt.PropertyClient;
import tuwien.auto.calimero.mgmt.PropertyClient.PropertyKey;
import tuwien.auto.calimero.mgmt.RemotePropertyServiceAdapter;
import tuwien.auto.calimero.serial.usb.UsbConnection;

/**
 * A tool for Calimero showing features of the {@link PropertyClient} used for KNX property access.
 * <p>
 * Property is a {@link Runnable} tool implementation to set or get a KNX property from an Interface
 * Object Server (IOS), get its KNX property description, or scan the KNX descriptions available. It
 * supports network access using a KNXnet/IP, USB, or FT1.2 connection.<br>
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
public class Property implements Runnable, PropertyAdapterListener
{
	private static final String tool = "Property";
	private static final String sep = System.getProperty("line.separator");

	static Logger out;

	/** Contains tool options after parsing command line. */
	protected final Map<String, Object> options = new HashMap<>();

	private PropertyClient pc;
	private KNXNetworkLink lnk;
	private Map<PropertyKey, PropertyClient.Property> definitions;

	private final Thread interruptOnClose;

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
	 * Command line options are treated case sensitive. Available options are:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--verbose -v</code> enable verbose status output</li>
	 * <li><code>--local -l</code> local device management (default)</li>
	 * <li><code>--remote -r</code> <i>KNX addr</i> &nbsp;remote property service</li>
	 * <li><code>--definitions -d</code> <i>file</i> &nbsp;use property definition file</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * </ul>
	 * For local device management these options are available:
	 * <ul>
	 * <li><code>--emulatewriteenable -e</code> check write-enable of a property</li>
	 * </ul>
	 * For remote property service these options are available:
	 * <ul>
	 * <li><code>--routing</code> use KNXnet/IP routing</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|p132|rf] (defaults to
	 * tp1)</li>
	 * <li><code>--connect -c</code> connection oriented mode</li>
	 * <li><code>--authorize -a</code> <i>key</i> &nbsp;authorize key to access the KNX device</li>
	 * </ul>
	 * Use one of the following commands for property access, with <i>object-idx</i> being the
	 * interface object index, and <i>pid</i> the KNX property identifier:
	 * <ul>
	 * <li><code>get <i>object-idx pid [start-idx elements]</i></code> get the property value(s)</li>
	 * <li><code>set <i>object-idx pid [start-idx] string-value</i></code> set the property
	 * string-formatted value</li>
	 * <li>
	 * <code>set <i>object-idx pid start-idx elements [\"0x\"|\"0\"|\"b\"]data</i></code> set
	 * the property data</li>
	 * <li><code>desc <i>object-idx pid</i></code> get the property description of the property ID</li>
	 * <li><code>desc <i>object-idx "i" prop-idx</i></code> get the property description of the
	 * property index</li>
	 * <li><code>scan <i>[object-idx]</i></code> list interface object type descriptions (of the
	 * indexed interface object)</li>
	 * <li><code>scan <i>[object-idx]</i> "all"</code> list all property descriptions (of the
	 * indexed interface object)</li>
	 * <li><code>?</code> show command help</li>
	 * </ul>
	 *
	 * @param args command line options for the property tool
	 */
	public static void main(final String[] args)
	{
		setLogVerbosity(Arrays.asList(args));
		try {
			new Property(args).run();
		}
		catch (final Throwable t) {
			out.error("parsing option", t);
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
			LogService.logAlways(out, tool + " - Access KNX properties");
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

		// load property definitions from resource, if any
		Collection<PropertyClient.Property> defs = null;
		if (options.containsKey("definitions")) {
			try {
				defs = PropertyClient.loadDefinitions((String) options.get("definitions"), null);
			}
			catch (final KNXException e) {
				out.error("loading definitions from " + options.get("definitions") + " failed", e);
			}
		}

		Exception thrown = null;
		boolean canceled = true;
		try {
			// create a property adapter and supply it to a new property client
			pc = new PropertyClient(createAdapter());
			// check if user supplied a XML resource with property definitions
			if (defs != null) {
				pc.addDefinitions(defs);
				definitions = pc.getDefinitions();
			}

			// run the user command
			runCommand((String[]) options.get("command"));
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
			if (pc != null)
				pc.close();
			if (lnk != null)
				lnk.close();
			onCompletion(thrown, canceled);
		}
	}

	/* (non-Javadoc)
	 * @see tuwien.auto.calimero.mgmt.PropertyAdapterListener#adapterClosed(
	 * tuwien.auto.calimero.CloseEvent)
	 */
	@Override
	public void adapterClosed(final CloseEvent e)
	{
		out.info("connection closed (" + e.getReason() + ")");
		if (e.getInitiator() != CloseEvent.USER_REQUEST)
			interruptOnClose.interrupt();
	}

	/**
	 * Runs a single command.
	 * <p>
	 *
	 * @param cmd the command to execute together with its parameters
	 */
	protected void runCommand(final String[] cmd)
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
			else if ("?".equals(what))
				showCommandList(cmd);
			else
				out.info("unknown command, type ? for help");
		}
		catch (final KNXException e) {
			out.error(e.getMessage());
		}
		catch (final NumberFormatException e) {
			out.error("invalid number (" + e.getMessage() + ")");
		}
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
			out.info("reading property canceled");
		if (thrown != null)
			out.error("on completion", thrown);
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
			// create local and remote socket address for use in adapter
			return createLocalDMAdapter(host);
		}
		return createRemoteAdapter(host);
	}

	/**
	 * Creates a KNXnet/IP local device management adapter.
	 *
	 * @param host remote host
	 * @return local device management adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createLocalDMAdapter(final String host) throws KNXException,
		InterruptedException
	{
		final InetSocketAddress local = Main.createLocalSocket(
				(InetAddress) options.get("localhost"), (Integer) options.get("localport"));
		return new LocalDeviceMgmtAdapter(local, new InetSocketAddress(Main.parseHost(host),
				((Integer) options.get("port")).intValue()), options.containsKey("nat"), this,
				options.containsKey("emulatewriteenable"));
	}

	/**
	 * Creates a local device management adapter for a KNX USB interface.
	 */
	private PropertyAdapter createUsbAdapter(final String device) throws KNXException,
		InterruptedException
	{
		final UsbConnection usb = new UsbConnection(device);
		return new LocalDeviceManagementUsb(usb, this, options.containsKey("emulatewriteenable"));
	}

	/**
	 * Creates the KNX network link and remote property service adapter for one device in the KNX
	 * network. The adapter uses a KNX network link for access, also is created by this method.
	 *
	 * @param host remote host
	 * @return remote property service adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createRemoteAdapter(final String host) throws KNXException,
		InterruptedException
	{
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (options.containsKey("serial")) {
			// create FT1.2 network link
			try {
				lnk = new KNXNetworkLinkFT12(Integer.parseInt(host), medium);
			}
			catch (final NumberFormatException e) {
				lnk = new KNXNetworkLinkFT12(host, medium);
			}
		}
		else if (options.containsKey("usb")) {
			// create KNX USB HID network link
			lnk = new KNXNetworkLinkUsb(host, medium);
		}
		else {
			final InetSocketAddress local = Main.createLocalSocket(
					(InetAddress) options.get("localhost"), (Integer) options.get("localport"));
			final InetSocketAddress sa = new InetSocketAddress(Main.parseHost(host),
					((Integer) options.get("port")).intValue());
			lnk = new KNXNetworkLinkIP(options.containsKey("routing") ? KNXNetworkLinkIP.ROUTING
					: KNXNetworkLinkIP.TUNNELING, local, sa, options.containsKey("nat"), medium);
		}
		final IndividualAddress remote = (IndividualAddress) options.get("remote");
		// if an authorization key was supplied, the adapter uses
		// connection oriented mode and tries to authenticate
		final byte[] authKey = (byte[]) options.get("authorize");
		if (authKey != null)
			return new RemotePropertyServiceAdapter(lnk, remote, this, authKey);
		return new RemotePropertyServiceAdapter(lnk, remote, this, options.containsKey("connect"));
	}

	private void printDescription(final Description d)
	{
		final StringBuffer buf = new StringBuffer();
		buf.append(alignRight(d.getPropIndex()));
		buf.append(" OT ").append(alignRight(d.getObjectType()));
		buf.append(", OI ").append(d.getObjectIndex());
		buf.append(", PID ").append(alignRight(d.getPID()));

		tuwien.auto.calimero.mgmt.PropertyClient.Property p = getPropertyDef(d.getObjectType(),
				d.getPID());
		if (p == null)
			p = getPropertyDef(PropertyKey.GLOBAL_OBJTYPE, d.getPID());
		if (p != null) {
			buf.append(" ");
			buf.append(p.getName());
			while (buf.length() < 55)
				buf.append(' ');
			buf.append(" (");
			buf.append(p.getPIDName());
			buf.append(")");
		}
		final String pdtDef = p != null ? Integer.toString(p.getPDT()) : "-";
		buf.append(", PDT " + (d.getPDT() == -1 ? pdtDef : Integer.toString(d.getPDT())));
		buf.append(", curr. elems " + d.getCurrentElements());
		buf.append(", max. " + d.getMaxElements());
		buf.append(", r/w access " + d.getReadLevel() + "/" + d.getWriteLevel());
		buf.append(d.isWriteEnabled() ? ", w.enabled" : ", r.only");
		System.out.println(buf.toString());
	}

	private static String alignRight(final int value)
	{
		return value < 10 ? " " + value : "" + value;
	}

	private tuwien.auto.calimero.mgmt.PropertyClient.Property getPropertyDef(final int objType,
		final int pid)
	{
		if (definitions == null)
			return null;
		return definitions.get(new PropertyClient.PropertyKey(objType, pid));
	}

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
			if (Main.isOption(arg, "local", "l"))
				options.put("local", null);
			else if (Main.isOption(arg, "remote", "r"))
				try {
					options.put("remote", new IndividualAddress(args[++i]));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException(e.getMessage(), e);
				}
			else if (Main.isOption(arg, "definitions", "d"))
				options.put("definitions", args[++i]);
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
			else if (Main.isOption(arg, "serial", "s"))
				options.put("serial", null);
			else if (Main.isOption(arg, "usb", "u"))
				options.put("usb", null);
			else if (Main.isOption(arg, "medium", "m"))
				options.put("medium", Main.getMedium(args[++i]));
			else if (Main.isOption(arg, "emulatewriteenable", "e"))
				options.put("emulatewriteenable", null);
			else if (Main.isOption(arg, "connect", "c"))
				options.put("connect", null);
			else if (Main.isOption(arg, "authorize", "a"))
				options.put("authorize", getAuthorizeKey(args[++i]));
			else if (Main.isOption(arg, "routing", null))
				options.put("routing", null);
			else if (arg.equals("get") || arg.equals("set") || arg.equals("desc")
					|| arg.equals("scan") || arg.equals("?")) {
				final String[] command = Arrays.asList(args).subList(i, args.length)
						.toArray(new String[0]);
				options.put("command", command);
				// XXX the break enforces that the actual command is last, necessary to allow the
				// variable command argument list. This won't permit invocation from
				// class Main with single get/set commands, as those are required last in sequence
				break;
			}
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (!options.containsKey("remote"))
			options.put("local", null);
		if (!options.containsKey("host"))
			throw new KNXIllegalArgumentException("no communication device/host specified");
		if (options.containsKey("serial") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("--remote option is mandatory with --serial");
	}

	//
	// utility methods
	//

	private void getProperty(final String[] args) throws KNXException
	{
		String s = "sorry, wrong number of arguments";
		if (args.length == 2 && args[1].equals("?"))
			s = "get object-idx pid [start-idx elements]";
		else if (args.length == 3 || args.length == 5) {
			final int oi = toInt(args[1]);
			final int pid = toInt(args[2]);
			try {
				if (args.length == 3)
					s = pc.getProperty(oi, pid);
				else
					s = Arrays.asList(pc.getPropertyTranslated(oi, pid, toInt(args[3]),
							toInt(args[4])).getAllValues()).toString();
			}
			catch (final KNXException e) {
				if (args.length == 3)
					s = "0x" + DataUnitBuilder.toHex(pc.getProperty(oi, pid, 1, 1), "");
				else {
					final int elems = toInt(args[4]);
					final String hex = DataUnitBuilder.toHex(
							pc.getProperty(oi, pid, toInt(args[3]), elems), "");
					final int chars = hex.length() / elems;
					for (int i = 0; i < elems; ++i)
						s += "0x" + hex.substring(i * chars, (i + 1) * chars) + " ";
				}
			}
		}
		LogService.logAlways(out, s);
	}

	private void getDescription(final String[] args) throws KNXException
	{
		if (args.length == 3)
			printDescription(pc.getDescription(toInt(args[1]), toInt(args[2])));
		else if (args.length == 4 && args[2].equals("i"))
			printDescription(pc.getDescriptionByIndex(toInt(args[1]), toInt(args[3])));
		else if (args.length == 2 && args[1].equals("?"))
			printHelp("desc object-idx pid" + sep + "desc object-idx \"i\" prop-idx");
		else
			out.info("sorry, wrong number of arguments");
	}

	private void setProperty(final String[] args) throws KNXException
	{
		if (args.length < 4 || args.length > 6) {
			out.info("sorry, wrong number of arguments");
			return;
		}
		if (args.length == 2 && args[1].equals("?"))
			printHelp("set object-idx pid [start-idx] string-value" + sep
					+ "set object-idx pid start-idx elements [\"0x\"|\"0\"|\"b\"]data" + sep
					+ "(use hexadecimal format for more than 8 byte data or leading zeros)");
		final int cnt = args.length;
		final int oi = toInt(args[1]);
		final int pid = toInt(args[2]);
		if (cnt == 4)
			pc.setProperty(oi, pid, 1, args[3]);
		else if (cnt == 5)
			pc.setProperty(oi, pid, toInt(args[3]), args[4]);
		else if (cnt == 6)
			pc.setProperty(oi, pid, toInt(args[3]), toInt(args[4]), toByteArray(args[5]));
	}

	private void scanProperties(final String[] args) throws KNXException
	{
		final int cnt = args.length;
		List<Description> l = Collections.emptyList();
		if (cnt == 1)
			l = pc.scanProperties(false);
		else if (cnt == 2) {
			if (args[1].equals("all"))
				l = pc.scanProperties(true);
			else if (args[1].equals("?"))
				printHelp("scan [object-idx] [\"all\" for all object properties]");
			else
				l = pc.scanProperties(toInt(args[1]), false);
		}
		else if (cnt == 3 && args[2].equals("all"))
			l = pc.scanProperties(toInt(args[1]), true);
		else
			out.info("sorry, wrong number of arguments");

		System.out.println("Object Type (OT), Object Index (OI), Property ID (PID)");
		for (final Iterator<Description> i = l.iterator(); i.hasNext();) {
			final Description d = i.next();
			printDescription(d);
		}
	}

	private void showCommandList(final String[] args)
	{
		final StringBuffer buf = new StringBuffer();
		buf.append("commands: get | set | desc | scan (append ? for help)" + sep);
		buf.append("get  - read property value(s)" + sep);
		buf.append("set  - write property value(s)" + sep);
		buf.append("desc - read one property description" + sep);
		buf.append("scan - read property descriptions" + sep);
		out.info(buf.toString());
	}

	private void printHelp(final String help)
	{
		out.info(help);
	}

	// a helper in case slf4j simple logger is used
	private static void setLogVerbosity(final List<String> args)
	{
		// TODO problem: this overrules the log level from a simplelogger.properties file!!
		final String simpleLoggerLogLevel = "org.slf4j.simpleLogger.defaultLogLevel";
		if (!System.getProperties().containsKey(simpleLoggerLogLevel)) {
			final String lvl = args.contains("-v") || args.contains("--verbose") ? "debug" : "warn";
			System.setProperty(simpleLoggerLogLevel, lvl);
		}
		out = LogService.getLogger("calimero.tools");
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("Usage: ").append(tool).append(" [options] <host|port> <command>").append(sep);
		sb.append("Options:").append(sep);
		sb.append("  --help -h                show this help message").append(sep);
		sb.append("  --version                show tool/library version and exit").append(sep);
		sb.append("  --verbose -v             enable verbose status output").append(sep);
		sb.append("  --local -l               local device management").append(sep);
		sb.append("  --remote -r <KNX addr>   remote property service").append(sep);
		sb.append("  --definitions -d <file>  use property definition file").append(sep);
		sb.append("  --localhost <id>         local IP/host name").append(sep);
		sb.append("  --localport <number>     local UDP port (default system assigned)")
				.append(sep);
		sb.append("  --port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append("  --nat -n                 enable Network Address Translation").append(sep);
		sb.append("  --serial -s              use FT1.2 serial communication").append(sep);
		sb.append("  --usb -u                 use KNX USB communication").append(sep);
		sb.append("Options for local device management mode only:").append(sep);
		sb.append("  --emulatewriteenable -e  check write-enable of a property").append(sep);
		sb.append("Options for remote property service mode only:").append(sep);
		sb.append("  --routing                use KNXnet/IP routing").append(sep);
		sb.append("  --medium -m <id>         KNX medium [tp1|p110|p132|rf] (default tp1)")
				.append(sep);
		sb.append("  --connect -c             connection oriented mode").append(sep);
		sb.append("  --authorize -a <key>     authorize key to access KNX device").append(sep);
		sb.append("Available commands:").append(sep);
		sb.append("  get <object-idx> <pid> [<start-idx> <elements>]  get the property value(s)")
				.append(sep);
		sb.append("  set <i>object-idx pid [start-idx] string-value   "
				+ "set the property string-formatted value").append(sep);
		sb.append("  set <object-idx> <pid> <start-idx> <elements> [\"0x\"|\"0\"|\"b\"]<data>    "
				+ "set the property data").append(sep);
		sb.append("  desc <object-idx> <pid>                "
				+ "get the property description of the property ID").append(sep);
		sb.append("  desc <object-idx> \"i\" <prop-idx>       "
				+ "get the property description of the property index").append(sep);
		sb.append("  scan [<object-idx>]"
				+ "                    list interface object type descriptions").append(sep);
		sb.append("  scan [<object-idx>] \"all\"              list all property descriptions")
				.append(sep);
		sb.append("  ?                                      show command help").append(sep);

		LogService.logAlways(out, sb.toString());
	}

	//
	// utility methods
	//

	private static void showVersion()
	{
		LogService.logAlways(out, Settings.getLibraryHeader(false));
	}

	private static byte[] getAuthorizeKey(final String key)
	{
		final long value = Long.decode(key).longValue();
		if (value < 0 || value > 0xFFFFFFFFL)
			throw new KNXIllegalArgumentException("invalid authorize key");
		return new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
			(byte) value };
	}

	private static int toInt(final String number)
	{
		return Integer.decode(number).intValue();
	}

	private static byte[] toByteArray(final String s)
	{
		// use of BigXXX equivalent is a bit awkward, for now this is sufficient ...
		long l = 0;
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
}
