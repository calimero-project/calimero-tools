/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2015 B. Malinowsky

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
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXRemoteException;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.KNXNetworkLinkTpuart;
import tuwien.auto.calimero.link.KNXNetworkLinkUsb;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.mgmt.Description;
import tuwien.auto.calimero.mgmt.LocalDeviceMgmtAdapter;
import tuwien.auto.calimero.mgmt.PropertyAccess;
import tuwien.auto.calimero.mgmt.PropertyAdapter;
import tuwien.auto.calimero.mgmt.PropertyClient;
import tuwien.auto.calimero.mgmt.RemotePropertyServiceAdapter;

/**
 * A tool for Calimero to read/set the IP configuration of a KNXnet/IP server using KNX properties.
 * <p>
 * IPConfig is a {@link Runnable} tool implementation for reading and writing the IP configuration
 * in the KNXnet/IP Parameter Object. It supports network access using a KNXnet/IP, KNX IP, USB,
 * FT1.2, or TP-UART connection. IPConfig shows the necessary interaction with the core library API
 * for this particular task.<br>
 * To run IPConfig, invoke {@link IPConfig#main(String[])}, or create a new instance using
 * {@link IPConfig#IPConfig(String[])} and invoke {@link #run()} on that instance.<br>
 * All configuration output, as well as occurring problems are written to either
 * <code>System.out</code> (console mode), or the log writer supplied by the user. See the tool
 * options for a list of communication settings.
 * <p>
 * The main part of this tool interacts with the Calimero 2 {@link PropertyClient} interface, which
 * offers high level access to KNX property information. It shows creation of the
 * {@link PropertyAdapter}, necessary for a property client to work.
 *
 * @author B. Malinowsky
 */
public class IPConfig implements Runnable
{
	private static final String tool = "IPConfig";

	private static final String sep = System.getProperty("line.separator");
	private static final int IPObjType = 11;

	private static Logger out = LogService.getLogger("calimero.tools");

	private KNXNetworkLink lnk;
	private PropertyClient pc;
	private int objIndex = -1;

	private final Map<String, Object> options = new HashMap<>();

	/**
	 * Creates a new IPConfig instance using the supplied options.
	 * <p>
	 * See {@link #main(String[])} for a list of options.
	 *
	 * @param args list with options
	 */
	protected IPConfig(final String[] args)
	{
		try {
			// read in the command line options
			parseOptions(args);
		}
		catch (final KNXIllegalArgumentException e) {
			throw e;
		}
		catch (final NoSuchElementException e) {
			// create our own message since e usually comes with no error message at all
			throw new KNXIllegalArgumentException("no more options in argument list", e);
		}
		catch (final RuntimeException e) {
			throw new KNXIllegalArgumentException(e.getMessage(), e);
		}
	}

	/**
	 * Entry point for running IPConfig.
	 * <p>
	 * An IP host or port identifier has to be supplied to specify the endpoint for the KNX network
	 * access.<br>
	 * To show the usage message of this tool on the console, supply the command line option --help
	 * (or -h).<br>
	 * Command line options are treated case sensitive. Available options:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--local -l</code> local device management (default)</li>
	 * <li><code>--remote -r</code> <i>KNX addr</i> &nbsp;remote property service</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)
	 * </li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * </ul>
	 * For remote property service these options are available:
	 * <ul>
	 * <li><code>--routing</code> use KNXnet/IP routing</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|p132|rf] (defaults to tp1)
	 * </li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local
	 * endpoint</li>
	 * <li><code>--connect -c</code> connection oriented mode</li>
	 * <li><code>--authorize -a</code> <i>key</i> &nbsp;authorize key to access KNX device</li>
	 * </ul>
	 * <br>
	 * In any case, the tool reads out the IP configuration of the connected endpoint and writes it
	 * to standard output.<br>
	 * Supply one or more of the following commands to change the IP configuration:
	 * <ul>
	 * <li><code>ip</code> <i>address</i> &nbsp;set the configured fixed IP address</li>
	 * <li><code>subnet</code> <i>address</i> &nbsp;set the configured IP subnet mask</li>
	 * <li><code>gateway</code> <i>address</i> &nbsp;set the configured IP address of the default
	 * gateway</li>
	 * <li><code>multicast</code> <i>address</i> &nbsp;set the routing multicast address</li>
	 * <li><code>manual</code> set manual IP assignment for the current IP address to enabled</li>
	 * <li><code>bootp</code> set Bootstrap Protocol IP assignment for the current IP address to
	 * enabled</li>
	 * <li><code>dhcp</code> set DHCP IP assignment for the current IP address to enabled</li>
	 * <li><code>auto</code> set automatic IP assignment for the current IP address to enabled</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected
	 * that directly communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX
	 * individual address shall be unique in a network, and the subnetwork address (area and line)
	 * should be set to match the network configuration.
	 *
	 * @param args command line options to run the tool
	 */
	public static void main(final String[] args)
	{
		try {
			new IPConfig(args).run();
		}
		catch (final Throwable t) {
			out.error("IP config", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			if (options.isEmpty()) {
				LogService.logAlways(out, tool + " - KNXnet/IP address configuration");
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

			// create the property adapter based on user options
			final PropertyAdapter adapter = createAdapter();
			// hand our adapter to a new property client
			pc = new PropertyClient(adapter);
			// ??? we should add our log writer to property client logger
			// LogManager.getManager().addWriter("PC " + adapter.getName(), w);

			// get object type with KNXnet/IP parameters
			final List<Description> l = pc.scanProperties(false);

			for (final Iterator<Description> i = l.iterator(); i.hasNext();) {
				final Description d = i.next();
				if (d.getObjectType() == IPObjType) {
					objIndex = d.getObjectIndex();
					break;
				}
			}
			if (objIndex == -1) {
				out.error(PropertyClient.getObjectTypeName(IPObjType) + " not found");
				return;
			}
			setIPAssignment();
			setIP(PropertyAccess.PID.IP_ADDRESS, "IP");
			setIP(PropertyAccess.PID.SUBNET_MASK, "subnet");
			setIP(PropertyAccess.PID.DEFAULT_GATEWAY, "gateway");
			setIP(PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS, "multicast");
			readConfig();
		}
		catch (final KNXException e) {
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

	/**
	 * Supplies information about a received IP configuration.
	 * <p>
	 * This default implementation writes information to standard output.
	 *
	 * @param config a list with configuration entries, an entry is of type String[3], with [0]
	 *        being the property identifier (PID), [1] being a short descriptive name of the
	 *        configuration property, [2] being the value
	 */
	protected void onConfigurationReceived(final List<String[]> config)
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("KNXnet/IP server ").append(config.get(0)[2]).append(" ")
				.append(config.get(1)[2]).append(sep);
		final String padding = "                                   ";
		for (int i = 2; i < config.size(); ++i) {
			final String[] s = config.get(i);
			sb.append(s[1]).append(padding.substring(s[1].length()) + s[2]).append(sep);
		}
		System.out.println(sb.toString());
	}

	/**
	 * Called by this tool on completion.
	 * <p>
	 *
	 * @param thrown the thrown exception if operation completed due to an raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.info("configuration canceled");
		if (thrown != null)
			out.error("completed", thrown);
	}

	private void setIPAssignment() throws KNXException, InterruptedException
	{
		int assignment = 0;
		if (options.containsKey("manual"))
			assignment |= 0x01;
		if (options.containsKey("bootp"))
			assignment |= 0x02;
		if (options.containsKey("dhcp"))
			assignment |= 0x04;
		if (options.containsKey("auto"))
			assignment |= 0x08;
		if (assignment != 0)
			pc.setProperty(objIndex, PropertyAccess.PID.IP_ASSIGNMENT_METHOD, 1, 1,
					new byte[] { (byte) assignment });
	}

	private void setIP(final int pid, final String key) throws InterruptedException
	{
		if (options.containsKey(key))
			try {
				pc.setProperty(objIndex, pid, 1, 1, ((InetAddress) options.get(key)).getAddress());
			}
			catch (final KNXException e) {
				out.error("setting " + key + " failed, " + e.getMessage());
			}
	}

	private void readConfig() throws KNXException, InterruptedException
	{
		final List<String[]> config = new ArrayList<>();
		int pid = PropertyAccess.PID.KNX_INDIVIDUAL_ADDRESS;
		byte[] data = query(pid);
		if (data != null)
			add(config, pid, "KNXnet/IP server", new IndividualAddress(data).toString());
		add(config, PropertyAccess.PID.FRIENDLY_NAME, "name", queryFriendlyName());

		pid = PropertyAccess.PID.IP_CAPABILITIES;
		data = query(pid);
		if (data != null)
			add(config, pid, "supported IP assignment methods",
					getIPAssignment(new byte[] { (byte) (data[0] << 1 | 0x01) }));

		pid = PropertyAccess.PID.IP_ASSIGNMENT_METHOD;
		data = query(pid);
		if (data != null)
			add(config, pid, "enabled IP assignment methods", getIPAssignment(data));

		pid = PropertyAccess.PID.CURRENT_IP_ASSIGNMENT_METHOD;
		data = query(pid);
		if (data != null)
			add(config, pid, "current IP assignment method", getIPAssignment(data));

		pid = PropertyAccess.PID.KNXNETIP_ROUTING_CAPABILITIES;
		data = query(pid);
		if (data != null)
			add(config, pid, "routing capabilities", getRoutingCaps(data));

		addIP(config, PropertyAccess.PID.IP_ADDRESS, "IP address configured");
		addIP(config, PropertyAccess.PID.CURRENT_IP_ADDRESS, "IP address current");
		addIP(config, PropertyAccess.PID.SUBNET_MASK, "subnet mask configured");
		addIP(config, PropertyAccess.PID.CURRENT_SUBNET_MASK, "subnet mask  current");
		addIP(config, PropertyAccess.PID.DEFAULT_GATEWAY, "default gateway configured");
		addIP(config, PropertyAccess.PID.CURRENT_DEFAULT_GATEWAY, "default gateway current");
		addIP(config, PropertyAccess.PID.DHCP_BOOTP_SERVER, "DHCP/BootP server");
		addIP(config, PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS, "routing multicast");

		onConfigurationReceived(config);
	}

	private void addIP(final List<String[]> config, final int pid, final String name)
		throws InterruptedException
	{
		add(config, pid, name, queryIP(pid));
	}

	private void add(final List<String[]> config, final int pid, final String name,
		final String value)
	{
		config.add(new String[] { Integer.toString(pid), name, value });
	}

	private String queryFriendlyName() throws KNXException, InterruptedException
	{
		final char[] name = new char[30];
		int start = 0;
		while (true) {
			final byte[] data = pc.getProperty(objIndex, PropertyAccess.PID.FRIENDLY_NAME,
					start + 1, 10);
			for (int i = 0; i < 10 && data[i] != 0; ++i, ++start)
				name[start] = (char) (data[i] & 0xff);
			if (start >= 30 || data[9] == 0)
				return new String(name, 0, start);
		}
	}

	private byte[] query(final int pid) throws KNXException, InterruptedException
	{
		try {
			return pc.getProperty(objIndex, pid, 1, 1);
		}
		catch (final KNXRemoteException e) {
			out.error("getting property with ID " + pid + " failed: {}", e.getMessage());
			return null;
		}
	}

	private String queryIP(final int pid) throws InterruptedException
	{
		try {
			final byte[] data = query(pid);
			return data == null ? "PID not found" : InetAddress.getByAddress(data).getHostAddress();
		}
		catch (final UnknownHostException e) {}
		catch (final KNXException e) {}
		return "-";
	}

	private static String getIPAssignment(final byte[] value)
	{
		final int bitset = value[0] & 0xff;
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

	private static String getRoutingCaps(final byte[] value)
	{
		final int bitset = value[0] & 0xff;
		String caps = "";
		if ((bitset & 0x01) != 0)
			caps += "queue overflow statistics, ";
		if ((bitset & 0x02) != 0)
			caps += "transmitted telegrams statistics, ";
		if ((bitset & 0x04) != 0)
			caps += "priority/FIFO, ";
		if ((bitset & 0x08) != 0)
			caps += "multiple KNX installations, ";
		if ((bitset & 0x10) != 0)
			caps += "group address mapping";
		return caps;
	}

	/**
	 * Creates the property adapter to be used, depending on the supplied user <code>options</code>.
	 * <p>
	 * There are two types of property adapters. One uses KNXnet/IP local device management to
	 * access KNX properties in an interface object, the other type uses remote property services.
	 * The remote adapter needs a KNX network link to access the KNX network, the link is also
	 * created by this method if this adapter type is requested.
	 *
	 * @return the created adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException
	 */
	private PropertyAdapter createAdapter() throws KNXException, InterruptedException
	{
		// create local and remote socket address for use in adapter
		final InetSocketAddress local = Main.createLocalSocket(
				(InetAddress) options.get("localhost"), (Integer) options.get("localport"));
		// decide what type of adapter to create
		if (options.containsKey("localDM")) {
			final InetSocketAddress host = new InetSocketAddress((InetAddress) options.get("host"),
					((Integer) options.get("port")).intValue());
			return createLocalDMAdapter(local, host);
		}
		return createRemoteAdapter(local);
	}

	/**
	 * Creates a local device management adapter.
	 * <p>
	 *
	 * @param local local socket address
	 * @param host remote socket address of host
	 * @return local DM adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createLocalDMAdapter(final InetSocketAddress local,
		final InetSocketAddress host) throws KNXException, InterruptedException
	{
		return new LocalDeviceMgmtAdapter(local, host, options.containsKey("nat"), null, false);
	}

	/**
	 * Creates a remote property service adapter for one device in the KNX network.
	 * The adapter uses a KNX network link for access, which is also created by this method.
	 *
	 * @param local local socket address
	 * @return remote property service adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createRemoteAdapter(final InetSocketAddress local)
		throws KNXException, InterruptedException
	{
		final String host = (String) options.get("host");
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
		else if (options.containsKey("usb"))
			lnk = new KNXNetworkLinkUsb(host, medium);
		else if (options.containsKey("tpuart")) {
			// create TP-UART link
			final IndividualAddress device = (IndividualAddress) options.get("knx-address");
			medium.setDeviceAddress(device);
			lnk = new KNXNetworkLinkTpuart(host, medium, Collections.emptyList());
		}
		else {
			final int mode = options.containsKey("routing") ? KNXNetworkLinkIP.ROUTING
					: KNXNetworkLinkIP.TUNNELING;
			final InetSocketAddress remote = new InetSocketAddress(Main.parseHost(host),
					((Integer) options.get("port")).intValue());
			lnk = new KNXNetworkLinkIP(mode, local, remote, options.containsKey("nat"), medium);
		}
		final IndividualAddress remote = (IndividualAddress) options.get("remote");
		// if an authorization key was supplied, the adapter uses
		// connection oriented mode and tries to authenticate
		final byte[] authKey = (byte[]) options.get("authorize");
		if (authKey != null)
			return new RemotePropertyServiceAdapter(lnk, remote, null, authKey);
		return new RemotePropertyServiceAdapter(lnk, remote, null, options.containsKey("connect"));
	}

	private void parseOptions(final String[] args)
	{
		// remove empty arguments
		final List<String> l = new ArrayList<>(Arrays.asList(args));
		l.removeAll(Arrays.asList(new String[] { "" }));
		if (l.size() == 0)
			return;

		// add defaults
		options.put("port", new Integer(KNXnetIPConnection.DEFAULT_PORT));
		options.put("medium", TPSettings.TP1);
		// default subnetwork address for TP1 and unregistered device
		options.put("knx-address", new IndividualAddress(0, 0x02, 0xff));

		for (final Iterator<String> i = l.iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("help", null);
				return;
			}
			if (Main.isOption(arg, "version", null)) {
				options.put("version", null);
				return;
			}
			if (Main.isOption(arg, "local", "l"))
				options.put("localDM", null);
			else if (Main.isOption(arg, "remote", "r"))
				options.put("remote", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "localhost", null))
				parseIP(i.next(), "localhost", options);
			else if (Main.isOption(arg, "localport", null))
				options.put("localport", Integer.decode(i.next()));
			else if (Main.isOption(arg, "port", "p"))
				options.put("port", Integer.decode(i.next()));
			else if (Main.isOption(arg, "nat", "n"))
				options.put("nat", null);
			else if (Main.isOption(arg, "serial", "s"))
				options.put("serial", null);
			else if (Main.isOption(arg, "usb", "u"))
				options.put("usb", null);
			else if (Main.isOption(arg, "tpuart", null))
				options.put("tpuart", null);
			else if (Main.isOption(arg, "medium", "m"))
				options.put("medium", Main.getMedium(i.next()));
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "connect", "c"))
				options.put("connect", null);
			else if (Main.isOption(arg, "authorize", "a"))
				options.put("authorize", getAuthorizeKey(i.next()));
			else if (Main.isOption(arg, "routing", null))
				options.put("routing", null);
			// IP configuration options
			else if (arg.equalsIgnoreCase("manual"))
				options.put("manual", null);
			else if (arg.equalsIgnoreCase("bootp"))
				options.put("bootp", null);
			else if (arg.equalsIgnoreCase("dhcp"))
				options.put("dhcp", null);
			else if (arg.equalsIgnoreCase("auto"))
				options.put("auto", null);
			else if (arg.equalsIgnoreCase("ip"))
				parseIP(i.next(), "ip", options);
			else if (arg.equalsIgnoreCase("subnet"))
				parseIP(i.next(), "subnet", options);
			else if (arg.equalsIgnoreCase("gateway"))
				parseIP(i.next(), "gateway", options);
			else if (arg.equalsIgnoreCase("multicast"))
				parseIP(i.next(), "multicast", options);
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option \"" + arg + "\"");
		}
		if (!options.containsKey("remote"))
			options.put("localDM", null);

		if (!options.containsKey("localDM") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("no connection category specified");
		if (!options.containsKey("host") && !options.containsKey("serial"))
			throw new KNXIllegalArgumentException("no host or serial port specified");
		if (options.containsKey("serial") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("-remote option is mandatory with -serial");
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("Usage: ").append(tool).append(" [options] <host|port>").append(sep);
		sb.append("Options:").append(sep);
		sb.append("  --help -h                show this help message and exit").append(sep);
		sb.append("  --version                show tool/library version and exit").append(sep);
		sb.append("  --local -l               local device management (default)").append(sep);
		sb.append("  --remote -r <KNX addr>   remote property service").append(sep);
		sb.append("  --localhost <id>         local IP/host name").append(sep);
		sb.append("  --localport <number>     local UDP port (default system assigned)")
				.append(sep);
		sb.append("  --port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append("  --nat -n                 enable Network Address Translation").append(sep);
		sb.append("Options for remote property service only:").append(sep);
		sb.append("  --serial -s              use FT1.2 serial communication").append(sep);
		sb.append("  --usb -u                 use KNX USB communication").append(sep);
		sb.append("  --tpuart                 use TP-UART communication").append(sep);
		sb.append("  --routing                use KNXnet/IP routing (always on port 3671)").append(
				sep);
		sb.append("  --medium -m <id>         KNX medium [tp1|p110|p132|rf] (default tp1)")
				.append(sep);
		sb.append("  --connect -c             connection oriented mode").append(sep);
		sb.append("  --authorize -a <key>     authorize key to access KNX device").append(sep);
		sb.append("To change the IP configuration, supply one or more commands:").append(sep);
		sb.append("  ip <address>            set the configured fixed IP address").append(sep);
		sb.append("  subnet <address>        set the configured IP subnet mask").append(sep);
		sb.append(
				"  gateway <address>       set the configured IP address of the "
						+ "default gateway").append(sep);
		sb.append("  multicast <address>     set the routing multicast address").append(sep);
		sb.append("  manual         enable manual IP assignment for current IP address")
				.append(sep);
		sb.append(
				"  bootp          enable Bootstrap Protocol IP assignment for current "
						+ "IP address").append(sep);
		sb.append("  dhcp           enable DHCP IP assignment for current IP address").append(sep);
		sb.append("  auto           enable automatic IP assignment for current IP address").append(
				sep);
		LogService.logAlways(out, sb.toString());
	}

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

	private static void parseIP(final String address, final String key,
		final Map<String, Object> options)
	{
		try {
			options.put(key, InetAddress.getByName(address));
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to read IP " + address, e);
		}
	}
}
