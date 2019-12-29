/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2019 B. Malinowsky

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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.knxnetip.Connection;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.knxnetip.SecureConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.KNXNetworkLinkTpuart;
import tuwien.auto.calimero.link.KNXNetworkLinkUsb;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.KnxIPSettings;
import tuwien.auto.calimero.link.medium.PLSettings;
import tuwien.auto.calimero.link.medium.RFSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.LocalDeviceManagementIp;

/**
 * @author B. Malinowsky
 */
final class Main
{
	private static final String[][] cmds = new String[][] {
		{ "discover", "Discover KNXnet/IP servers", "search" },
		{ "describe", "KNXnet/IP server self-description", "describe" },
		{ "scan", "Determine the existing KNX devices on a KNX subnetwork" },
		{ "ipconfig", "KNXnet/IP device address configuration" },
		{ "monitor", "Open network monitor (passive) for KNX network traffic" },
		{ "read", "Read a value using KNX process communication", "read" },
		{ "write", "Write a value using KNX process communication", "write" },
		{ "groupmon", "Open group monitor for KNX process communication", "monitor" },
		{ "get", "Read a KNX property", "get" },
		{ "set", "Write a KNX property", "set" },
		{ "properties", "Open KNX property client", },
		{ "devinfo", "Read KNX device information" },
		{ "progmode", "Check/set device(s) in programming mode" },
		{ "restart", "Restart a KNX interface/device" },
	};

	private static final List<Class<? extends Runnable>> tools = Arrays.asList(Discover.class, Discover.class,
			ScanDevices.class, IPConfig.class, NetworkMonitor.class, ProcComm.class, ProcComm.class, ProcComm.class,
			Property.class, Property.class, PropClient.class, DeviceInfo.class, ProgMode.class, Restart.class);

	private static final String sep = System.getProperty("line.separator");

	private static final Map<InetSocketAddress, Connection> tcpConnectionPool = new HashMap<>();

	static synchronized Connection tcpConnection(final InetSocketAddress local, final InetSocketAddress server)
		throws KNXException {
		var connection = tcpConnectionPool.get(server);
		if (connection == null || !connection.isConnected()) {
			connection = Connection.newTcpConnection(local, server);
			tcpConnectionPool.put(server, connection);
		}
		return connection;
	}

	private Main() {}

	/**
	 * Provides a common entry point for running the Calimero Tools.
	 * <p>
	 * This is useful to start the tools from within a .jar file.
	 *
	 * @param args the first argument being the tool to invoke, followed by the command line options of that tool
	 */
	public static void main(final String[] args)
	{
		final boolean help = args.length >= 1 && (args[0].equals("--help") || args[0].equals("-h"));
		if (args.length == 0 || help) {
			usage();
			return;
		}
		final String cmd = args[0];
		for (int i = 0; i < cmds.length; i++) {
			if (cmds[i][0].equals(cmd)) {
				try {
					final Method m = tools.get(i).getMethod("main", String[].class);
					final String[] toolargs;
					if (args.length > 1 && (args[1].equals("--help") || args[1].equals("-h")))
						toolargs = new String[] { "-h" };
					else {
						final int defaultArgsStart = 2;
						final int defaultArgs = cmds[i].length - defaultArgsStart;
						final int userArgs = args.length - 1;
						toolargs = Arrays.copyOfRange(cmds[i], defaultArgsStart,
								defaultArgsStart + defaultArgs + userArgs);
						System.arraycopy(args, 1, toolargs, defaultArgs, userArgs);
					}
					m.invoke(null, new Object[] { toolargs });
				}
				catch (final Exception e) {
					System.err.print("internal error initializing tool \"" + cmd + "\": ");
					if (e instanceof InvocationTargetException)
						e.getCause().printStackTrace();
					else
						e.printStackTrace();
				}
				return;
			}
		}
		System.out.println("unknown command \"" + cmd + "\"");
	}

	private static void usage()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("Supported commands (always safe without further options, use -h for help):").append(sep);
		for (int i = 0; i < cmds.length; i++) {
			sb.append(cmds[i][0]).append(" - ").append(cmds[i][1]).append(sep);
		}
		System.out.println(sb);
	}

	static void showVersion() {
		System.out.println(Settings.getLibraryHeader(false));
	}

	//
	// Utility methods used by the various tools
	//

	static InetSocketAddress createLocalSocket(final InetAddress host, final Integer port)
	{
		final int p = port != null ? port.intValue() : 0;
		return host != null ? new InetSocketAddress(host, p) : new InetSocketAddress(p);
	}

	static InetAddress parseHost(final String host)
	{
		try {
			return InetAddress.getByName(host);
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to read IP host " + host, e);
		}
	}

	/**
	 * Creates a medium settings type for the supplied medium identifier.
	 *
	 * @param id a medium identifier from command line option
	 * @return medium settings object
	 * @throws KNXIllegalArgumentException on unknown medium identifier
	 */
	static KNXMediumSettings getMedium(final String id)
	{
		// for now, the local device address is always left 0 in the
		// created medium setting, since there is no user cmd line option for this
		// so KNXnet/IP server will supply address

		final int medium = KNXMediumSettings.getMedium(id);
		if (medium == KNXMediumSettings.MEDIUM_TP1)
			return TPSettings.TP1;
		if (medium == KNXMediumSettings.MEDIUM_PL110)
			return new PLSettings();
		if (medium == KNXMediumSettings.MEDIUM_RF)
			return new RFSettings(KNXMediumSettings.BackboneRouter);
		if (medium == KNXMediumSettings.MEDIUM_KNXIP)
			return new KnxIPSettings(KNXMediumSettings.BackboneRouter);
		throw new KNXIllegalArgumentException("unsupported KNX medium " + id);
	}

	static IndividualAddress getAddress(final String address)
	{
		try {
			return new IndividualAddress(address);
		}
		catch (final KNXFormatException e) {
			throw new KNXIllegalArgumentException("KNX device address", e);
		}
	}

	static boolean isOption(final String arg, final String longOpt, final String shortOpt)
	{
		final boolean lo = arg.startsWith("--") && arg.regionMatches(2, longOpt, 0, arg.length() - 2);
		final boolean so = shortOpt != null && arg.startsWith("-")
				&& arg.regionMatches(1, shortOpt, 0, arg.length() - 1);
		// notify about change of prefix for long options
		if (arg.equals("-" + longOpt))
			throw new KNXIllegalArgumentException("use --" + longOpt);
		return lo || so;
	}

	static void setDomainAddress(final Map<String, Object> options)
	{
		final Long value = (Long) options.get("domain");
		if (value == null)
			return;
		final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
		buffer.putLong(value);
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		final byte[] domain = new byte[medium.getMedium() == KNXMediumSettings.MEDIUM_PL110 ? 2 : 6];
		buffer.position(8 - domain.length);
		buffer.get(domain);
		if (medium.getMedium() == KNXMediumSettings.MEDIUM_RF)
			((RFSettings) medium).setDomainAddress(domain);
		else if (medium.getMedium() == KNXMediumSettings.MEDIUM_PL110)
			((PLSettings) medium).setDomainAddress(domain);
		else
			throw new KNXIllegalArgumentException(medium.getMediumString()
					+ " networks don't use domain addresses, use --medium to specify KNX network medium");
	}

	static boolean parseCommonOption(final String[] args, final int i, final Map<String, Object> options) {
		final String arg = args[i];
		if (Main.isOption(arg, "tcp", null))
			options.put("tcp", null);
		else if (Main.isOption(arg, "udp", null))
			options.put("udp", null);
		else if (Main.isOption(arg, "ft12-cemi", null))
			options.put("ft12-cemi", null);
		else
			return false;
		return true;
	}

	static boolean parseCommonOption(final String arg, final Iterator<String> i, final Map<String, Object> options) {
		if (Main.isOption(arg, "version", null))
			options.put("about", (Runnable) Main::showVersion);
		else if (Main.isOption(arg, "localhost", null))
			options.put("localhost", Main.parseHost(i.next()));
		else if (Main.isOption(arg, "localport", null))
			options.put("localport", Integer.decode(i.next()));
		else if (Main.isOption(arg, "port", "p"))
			options.put("port", Integer.decode(i.next()));
		else if (Main.isOption(arg, "nat", "n"))
			options.put("nat", null);
		else if (Main.isOption(arg, "ft12", "f"))
			options.put("ft12", null);
		else if (Main.isOption(arg, "usb", "u"))
			options.put("usb", null);
		else if (Main.isOption(arg, "tpuart", null))
			options.put("tpuart", null);
		else if (Main.isOption(arg, "medium", "m"))
			options.put("medium", Main.getMedium(i.next()));
		else if (Main.isOption(arg, "domain", null))
			options.put("domain", Long.decode(i.next()));
		else if (Main.isOption(arg, "tcp", null))
			options.put("tcp", null);
		else if (Main.isOption(arg, "udp", null))
			options.put("udp", null);
		else if (Main.isOption(arg, "ft12-cemi", null))
			options.put("ft12-cemi", null);
		else
			return false;
		return true;
	}

	static boolean parseSecureOption(final String[] args, final int i, final Map<String, Object> options) {
		final String arg = args[i];
		if (isOption(arg, "group-key", null))
			options.put("group-key", fromHex(args[i + 1]));
		else if (isOption(arg, "device-key", null))
			options.put("device-key", fromHex(args[i + 1]));
		else if (isOption(arg, "device-pwd", null))
			options.put("device-key", SecureConnection.hashDeviceAuthenticationPassword(args[i + 1].toCharArray()));
		else if (isOption(arg, "user", null))
			options.put("user", Integer.decode(args[i + 1]));
		else if (isOption(arg, "user-key", null))
			options.put("user-key", fromHex(args[i + 1]));
		else if (isOption(arg, "user-pwd", null))
			options.put("user-key", SecureConnection.hashUserPassword(args[i + 1].toCharArray()));
		else
			return false;
		return true;
	}

	static boolean parseSecureOption(final String arg, final Iterator<String> i, final Map<String, Object> options) {
		if (isOption(arg, "group-key", null))
			options.put("group-key", fromHex(i.next()));
		else if (isOption(arg, "device-key", null))
			options.put("device-key", fromHex(i.next()));
		else if (isOption(arg, "device-pwd", null))
			options.put("device-key", SecureConnection.hashDeviceAuthenticationPassword(i.next().toCharArray()));
		else if (isOption(arg, "user", null))
			options.put("user", Integer.decode(i.next()));
		else if (isOption(arg, "user-key", null))
			options.put("user-key", fromHex(i.next()));
		else if (isOption(arg, "user-pwd", null))
			options.put("user-key", SecureConnection.hashUserPassword(i.next().toCharArray()));
		else
			return false;
		return true;
	}

	static void printCommonOptions(final StringJoiner joiner) {
		joiner.add("Options:");
		joiner.add("  --help -h                  show this help message");
		joiner.add("  --version                  show tool/library version and exit");
		joiner.add("  --localhost <id>           local IP/host name");
		joiner.add("  --localport <number>       local UDP port (default system assigned)");
		joiner.add("  --port -p <number>         UDP port on <host> (default " + KNXnetIPConnection.DEFAULT_PORT + ")");
		joiner.add("  --nat -n                   enable Network Address Translation");
		joiner.add("  --ft12 -f                  use FT1.2 serial communication");
		joiner.add("  --usb -u                   use KNX USB communication");
		joiner.add("  --tpuart                   use TP-UART communication");
		joiner.add("  --medium -m <id>           KNX medium [tp1|p110|knxip|rf] (default tp1)");
		joiner.add("  --domain <address>         domain address on KNX PL/RF medium (defaults to broadcast domain)");
	}

	static void printSecureOptions(final StringJoiner joiner, final boolean printGroupKey) {
		joiner.add("KNX IP Secure:");
		if (printGroupKey)
			joiner.add("  --group-key <key>          multicast group key (backbone key, 32 hexadecimal digits)");
		joiner.add("  --user <id>                tunneling user identifier (1..127)");
		joiner.add("  --user-pwd <password>      tunneling user password");
		joiner.add("  --user-key <key>           tunneling user password hash (32 hexadecimal digits)");
		joiner.add("  --device-pwd <password>    device authentication password");
		joiner.add("  --device-key <key>         device authentication code (32 hexadecimal digits)");
	}

	static void printSecureOptions(final StringJoiner joiner) {
		printSecureOptions(joiner, true);
	}

	static KNXNetworkLink newLink(final Map<String, Object> options) throws KNXException, InterruptedException {
		final String host = (String) options.get("host");
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");

		// check for FT1.2 network link
		if (options.containsKey("ft12")) {
			try {
				return new KNXNetworkLinkFT12(Integer.parseInt(host), medium);
			}
			catch (final NumberFormatException e) {
				return new KNXNetworkLinkFT12(host, medium);
			}
		}
		// check for FT1.2 cEMI network link
		if (options.containsKey("ft12-cemi"))
			return KNXNetworkLinkFT12.newCemiLink(host, medium);

		// check for USB network link
		if (options.containsKey("usb"))
			return new KNXNetworkLinkUsb(host, medium);

		// check for TP-UART link
		if (options.containsKey("tpuart")) {
			final IndividualAddress device = (IndividualAddress) options.get("knx-address");
			if (device != null)
				medium.setDeviceAddress(device);
			return new KNXNetworkLinkTpuart(host, medium, Collections.emptyList());
		}

		// we have an IP link
		final InetSocketAddress local = Main.createLocalSocket((InetAddress) options.get("localhost"), (Integer) options.get("localport"));
		final InetAddress addr = Main.parseHost(host);

		// check for KNX IP routing
		if (addr.isMulticastAddress()) {
			final IndividualAddress device = (IndividualAddress) options.get("knx-address");
			if (device != null)
				medium.setDeviceAddress(device);

			if (options.containsKey("group-key")) {
				try {
					final byte[] groupKey = (byte[]) options.get("group-key");
					final NetworkInterface nif = NetworkInterface.getByInetAddress(local.getAddress());
					if (nif == null && !local.getAddress().isAnyLocalAddress())
						throw new KNXIllegalArgumentException(local.getAddress() + " is not assigned to a network interface");
					return KNXNetworkLinkIP.newSecureRoutingLink(nif, addr, groupKey, Duration.ofMillis(2000), medium);
				}
				catch (final SocketException e) {
					throw new KNXIllegalArgumentException("getting network interface of " + local.getAddress(), e);
				}
			}
			return KNXNetworkLinkIP.newRoutingLink(local.getAddress(), addr, medium);
		}

		// IP tunneling
		final InetSocketAddress remote = new InetSocketAddress(addr, ((Integer) options.get("port")).intValue());
		final boolean nat = options.containsKey("nat");
		if (options.containsKey("user")) {
			final int user = (int) options.get("user");
			final byte[] userKey = (byte[]) options.getOrDefault("user-key", new byte[0]);
			final byte[] devAuth = (byte[]) options.getOrDefault("device-key", new byte[0]);

			final var tunnelingAddress = (IndividualAddress) options.get("knx-address");
			if (tunnelingAddress != null)
				medium.setDeviceAddress(tunnelingAddress);
			if (options.containsKey("udp"))
				return KNXNetworkLinkIP.newSecureTunnelingLink(local, remote, nat, devAuth, user, userKey, medium);

			final var session = tcpConnection(local, remote).newSecureSession(user, userKey, devAuth);
			return KNXNetworkLinkIP.newSecureTunnelingLink(session, medium);
		}
		if (options.containsKey("tcp")) {
			final var c = tcpConnection(local, remote);
			return KNXNetworkLinkIP.newTunnelingLink(c, medium);
		}
		return KNXNetworkLinkIP.newTunnelingLink(local, remote, nat, medium);
	}

	static LocalDeviceManagementIp newLocalDeviceMgmtIP(final Map<String, Object> options,
		final Consumer<CloseEvent> adapterClosed) throws KNXException, InterruptedException {
		final InetSocketAddress local = Main.createLocalSocket((InetAddress) options.get("localhost"),
				(Integer) options.get("localport"));
		final InetSocketAddress host = new InetSocketAddress((String) options.get("host"),
				((Integer) options.get("port")).intValue());
		final boolean nat = options.containsKey("nat");
		if (options.containsKey("user-key")) {
			final byte[] devAuth = (byte[]) options.getOrDefault("device-key", new byte[0]);
			final byte[] userKey = (byte[]) options.getOrDefault("user-key", new byte[0]);

			if (options.containsKey("udp"))
				return LocalDeviceManagementIp.newSecureAdapter(local, host, nat, devAuth, userKey, adapterClosed);

			final var session = tcpConnection(local, host).newSecureSession(1, userKey, devAuth);
			return LocalDeviceManagementIp.newSecureAdapter(session, adapterClosed);
		}
		if (options.containsKey("tcp")) {
			final var c = tcpConnection(local, host);
			return LocalDeviceManagementIp.newAdapter(c, adapterClosed);
		}
		final boolean queryWriteEnable = options.containsKey("emulatewriteenable");
		return LocalDeviceManagementIp.newAdapter(local, host, nat, queryWriteEnable, adapterClosed);
	}

	private static byte[] fromHex(final String hex) {
		final int len = hex.length();
		if (len != 0 && len != 32)
			throw new KNXIllegalArgumentException("wrong KNX key length, requires 16 bytes (32 hex chars)");
		return DataUnitBuilder.fromHex(hex);
	}

	static final class ShutdownHandler extends Thread
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

		@Override
		public void run()
		{
			t.interrupt();
		}
	}
}
