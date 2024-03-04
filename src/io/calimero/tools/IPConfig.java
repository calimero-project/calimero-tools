/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2024 B. Malinowsky

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

import static io.calimero.tools.Main.setDomainAddress;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

import java.lang.System.Logger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.KNXRemoteException;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;
import io.calimero.mgmt.PropertyAccess;
import io.calimero.mgmt.PropertyAdapter;
import io.calimero.mgmt.PropertyClient;
import io.calimero.mgmt.RemotePropertyServiceAdapter;

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

	private static final String sep = System.lineSeparator();
	private static final int IPObjType = 11;

	private static final Logger out = LogService.getLogger("io.calimero.tools");

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
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * </ul>
	 * For remote property service these options are available:
	 * <ul>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
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
	public static void main(final String... args)
	{
		try {
			new IPConfig(args).run();
		}
		catch (final Throwable t) {
			out.log(ERROR, "IP config", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			if (options.isEmpty()) {
				out(tool + " - KNXnet/IP address configuration");
				Main.showVersion();
				out("Type --help for help message");
				return;
			}
			if (options.containsKey("about")) {
				((Runnable) options.get("about")).run();
				return;
			}

			// create the property adapter based on user options
			final PropertyAdapter adapter = createAdapter();
			// hand our adapter to a new property client
			pc = new PropertyClient(adapter);

			// get object type with KNXnet/IP parameters
			pc.scanProperties(false, d -> {
				if (d.objectType() == IPObjType)
					objIndex = d.objectIndex();
			});
			if (objIndex == -1) {
				out.log(ERROR, PropertyClient.getObjectTypeName(IPObjType) + " not found");
				return;
			}
			setIPAssignment();
			setIP(PropertyAccess.PID.IP_ADDRESS, "ip");
			setIP(PropertyAccess.PID.SUBNET_MASK, "subnet");
			setIP(PropertyAccess.PID.DEFAULT_GATEWAY, "gateway");
			setIP(PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS, "multicast");
			final List<String[]> config = new ArrayList<>();
			try {
				readConfig(config);
			}
			finally {
				onConfigurationReceived(config);
			}
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
		if (options.containsKey("json")) {
			System.out.println(toJson(config));
			return;
		}
		final StringBuilder sb = new StringBuilder();
		sb.append("KNX IP device ").append(config.get(0)[2]).append(" ")
				.append(config.get(1)[2]).append(sep);
		final String padding = "                                   ";
		for (int i = 2; i < config.size(); ++i) {
			final String[] s = config.get(i);
			final String value = s[2].isEmpty() ? "n/a" : s[2];
			sb.append(s[1]).append(padding.substring(s[1].length())).append(value).append(sep);
		}
		System.out.println(sb);
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to an raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.log(INFO, "configuration canceled");
		if (thrown != null)
			out.log(ERROR, "completed with error", thrown);
	}

	private String toJson(final List<String[]> config) {
		final var device = options.containsKey("remote") ? options.get("remote") : options.get("host");
		// convert String[2] entries into key/value pairs and put them in a map
		final var map = config.stream().collect(Collectors.toMap(sa -> sa[1], sa -> sa[2]));

		record JsonIpConfig(String device, Map<String, String> ipConfig) implements Json {}
		final var jsonIpConfig = new JsonIpConfig(device.toString(), map);
		return jsonIpConfig.toJson();
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
					(byte) assignment);
	}

	private void setIP(final int pid, final String key) throws InterruptedException
	{
		if (options.containsKey(key))
			try {
				pc.setProperty(objIndex, pid, 1, 1, ((InetAddress) options.get(key)).getAddress());
			}
			catch (final KNXException e) {
				out.log(ERROR, "setting " + key + " failed, " + e.getMessage());
			}
	}

	private void readConfig(final List<String[]> config) throws KNXException, InterruptedException
	{
		int pid = PropertyAccess.PID.KNX_INDIVIDUAL_ADDRESS;
		byte[] data = query(pid);
		if (data != null)
			add(config, pid, "KNXnet/IP server", new IndividualAddress(data).toString());
		add(config, PropertyAccess.PID.FRIENDLY_NAME, "Name", queryFriendlyName());

		pid = PropertyAccess.PID.IP_CAPABILITIES;
		data = query(pid);
		if (data != null)
			add(config, pid, "Supported IP assignment methods",
					getIPAssignment(new byte[] { (byte) (data[0] << 1 | 0x01) }));

		pid = PropertyAccess.PID.IP_ASSIGNMENT_METHOD;
		data = query(pid);
		if (data != null)
			add(config, pid, "Enabled IP assignment methods", getIPAssignment(data));

		pid = PropertyAccess.PID.CURRENT_IP_ASSIGNMENT_METHOD;
		data = query(pid);
		if (data != null)
			add(config, pid, "Current IP assignment method", getIPAssignment(data));

		pid = PropertyAccess.PID.KNXNETIP_ROUTING_CAPABILITIES;
		data = query(pid);
		if (data != null)
			add(config, pid, "Routing capabilities", getRoutingCaps(data));

		addIP(config, PropertyAccess.PID.IP_ADDRESS, "Configured IP address");
		addIP(config, PropertyAccess.PID.SUBNET_MASK, "Configured subnet mask");
		addIP(config, PropertyAccess.PID.CURRENT_IP_ADDRESS, "Current IP address");
		addIP(config, PropertyAccess.PID.CURRENT_SUBNET_MASK, "Current subnet mask");
		addIP(config, PropertyAccess.PID.DEFAULT_GATEWAY, "Configured default gateway");
		addIP(config, PropertyAccess.PID.CURRENT_DEFAULT_GATEWAY, "Current default gateway");
		addIP(config, PropertyAccess.PID.DHCP_BOOTP_SERVER, "DHCP/BootP server");
		addIP(config, PropertyAccess.PID.ROUTING_MULTICAST_ADDRESS, "Routing multicast");
	}

	private void addIP(final List<String[]> config, final int pid, final String name)
		throws InterruptedException
	{
		add(config, pid, name, queryIP(pid));
	}

	private static void add(final List<String[]> config, final int pid, final String name,
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
			out.log(ERROR, "getting property with ID " + pid + " failed: {0}", e.getMessage());
			return null;
		}
	}

	private String queryIP(final int pid) throws InterruptedException
	{
		try {
			final byte[] data = query(pid);
			return data == null ? "" : InetAddress.getByAddress(data).getHostAddress();
		}
		catch (final UnknownHostException | KNXException e) {}
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
			s += (s.isEmpty() ? "" : div) + "Bootstrap Protocol";
		if ((bitset & 0x04) != 0)
			s += (s.isEmpty() ? "" : div) + "DHCP";
		if ((bitset & 0x08) != 0)
			s += (s.isEmpty() ? "" : div) + "Auto IP";
		return s;
	}

	private static String getRoutingCaps(final byte[] value)
	{
		final int bitset = value[0] & 0xff;
		final var caps = new StringJoiner(", ");
		if ((bitset & 0x01) != 0)
			caps.add("queue overflow statistics");
		if ((bitset & 0x02) != 0)
			caps.add("transmitted telegrams statistics");
		if ((bitset & 0x04) != 0)
			caps.add("priority/FIFO");
		if ((bitset & 0x08) != 0)
			caps.add("multiple KNX installations");
		if ((bitset & 0x10) != 0)
			caps.add("group address mapping");
		return caps.toString();
	}

	/**
	 * Creates the property adapter to be used, depending on the supplied user <code>options</code>.
	 * <p>
	 * There are two types of property adapters. One uses KNXnet/IP local device management to access KNX properties in
	 * an interface object, the other type uses remote property services. The remote adapter needs a KNX network link to
	 * access the KNX network, the link is also created by this method if this adapter type is requested.
	 *
	 * @return the created adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createAdapter() throws KNXException, InterruptedException {
		// decide what type of adapter to create
		if (options.containsKey("localDM"))
			return createLocalDMAdapter();
		return createRemoteAdapter();
	}

	/**
	 * Creates a local device management adapter.
	 *
	 * @return local DM adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createLocalDMAdapter() throws KNXException, InterruptedException {
		return Main.newLocalDeviceMgmtIP(options, closed -> {});
	}

	/**
	 * Creates a remote property service adapter for one device in the KNX network.
	 * The adapter uses a KNX network link for access, which is also created by this method.
	 *
	 * @return remote property service adapter
	 * @throws KNXException on adapter creation problem
	 * @throws InterruptedException on interrupted thread
	 */
	private PropertyAdapter createRemoteAdapter() throws KNXException, InterruptedException {
		lnk = Main.newLink(options);
		final IndividualAddress remote = (IndividualAddress) options.get("remote");
		// if an authorization key was supplied, the adapter uses
		// connection oriented mode and tries to authenticate
		final byte[] authKey = (byte[]) options.get("authorize");
		if (authKey != null)
			return new RemotePropertyServiceAdapter(lnk, remote, close -> {}, authKey);
		return new RemotePropertyServiceAdapter(lnk, remote, close -> {}, options.containsKey("connect"));
	}

	private void parseOptions(final String[] args)
	{
		// remove empty arguments
		final List<String> l = new ArrayList<>(Arrays.asList(args));
		l.removeAll(List.of(""));
		if (l.isEmpty())
			return;

		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", new TPSettings());

		for (final var i = l.iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) IPConfig::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "local", "l"))
				options.put("localDM", null);
			else if (Main.isOption(arg, "remote", "r"))
				options.put("remote", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "connect", "c"))
				options.put("connect", null);
			else if (Main.isOption(arg, "authorize", "a"))
				options.put("authorize", getAuthorizeKey(i.next()));
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
		// we allow a default usb config where the first found knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

		if (!options.containsKey("localDM") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("no connection category specified");
		if (!options.containsKey("host") && !options.containsKey("ft12"))
			throw new KNXIllegalArgumentException("no host or serial port specified");
		if (options.containsKey("ft12") && !options.containsKey("remote"))
			throw new KNXIllegalArgumentException("--remote option is mandatory with --ft12");
		setDomainAddress(options);
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port>");
		joiner.add(Main.printCommonOptions());
		joiner.add("  --local -l                 local device management (default)");
		joiner.add("  --remote -r <KNX addr>     remote property service");
		joiner.add("Options for remote property services only:");
		joiner.add("  --connect -c               connection oriented mode");
		joiner.add("  --authorize -a <key>       authorize key to access KNX device");
		joiner.add(Main.printSecureOptions());
		joiner.add("Commands to change the IP configuration:");
		joiner.add("  ip <address>               set the configured fixed IP address");
		joiner.add("  subnet <address>           set the configured IP subnet mask");
		joiner.add("  gateway <address>          set the configured IP address of the default gateway");
		joiner.add("  multicast <address>        set the routing multicast address");
		joiner.add("  manual                     enable manual IP assignment for current IP address");
		joiner.add("  bootp                      enable Bootstrap Protocol IP assignment for current IP address");
		joiner.add("  dhcp                       enable DHCP IP assignment for current IP address");
		joiner.add("  auto                       enable automatic IP (AutoIP) assignment for current IP address")
			  .add("                                             (address range 169.254.1.0 to 169.254.254.255)");
		out(joiner.toString());
	}

	private static void out(final String s)
	{
		System.out.println(s);
	}

	private static byte[] getAuthorizeKey(final String key)
	{
		final long value = Long.decode(key);
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
