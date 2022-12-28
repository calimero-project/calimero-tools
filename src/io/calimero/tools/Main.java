/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2022 B. Malinowsky

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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.LoggerFactory;

import io.calimero.CloseEvent;
import io.calimero.DataUnitBuilder;
import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.Settings;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.knxnetip.SecureConnection;
import io.calimero.knxnetip.TcpConnection;
import io.calimero.link.Connector;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.KNXNetworkLinkFT12;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.KNXNetworkLinkTpuart;
import io.calimero.link.KNXNetworkLinkUsb;
import io.calimero.link.LinkEvent;
import io.calimero.link.NetworkLinkListener;
import io.calimero.link.medium.KNXMediumSettings;
import io.calimero.link.medium.PLSettings;
import io.calimero.link.medium.RFSettings;
import io.calimero.mgmt.LocalDeviceManagementIp;
import io.calimero.secure.Keyring;
import io.calimero.secure.Keyring.Interface;
import io.calimero.secure.Security;
import io.calimero.serial.ConnectionStatus;

/**
 * @author B. Malinowsky
 */
final class Main
{
	private static final String[][] cmds = new String[][] {
		{ "discover", "Discover KNXnet/IP devices", "search" },
		{ "describe", "KNXnet/IP device self-description", "describe" },
		{ "scan", "Determine the existing KNX devices on a KNX subnetwork" },
		{ "ipconfig", "KNXnet/IP device address configuration" },
		{ "monitor", "Open network monitor (passive) for KNX network traffic" },
		{ "read", "Read a value using KNX process communication", "read" },
		{ "write", "Write a value using KNX process communication", "write" },
		{ "groupmon", "Open group monitor for KNX process communication", "monitor" },
		{ "trafficmon", "KNX link-layer traffic & link status monitor" },
		{ "get", "Read a KNX property", "get" },
		{ "set", "Write a KNX property", "set" },
		{ "properties", "Open KNX property client", },
		{ "info", "Send an LTE info command", "info" },
		{ "baos", "Communicate with a KNX BAOS device" },
		{ "devinfo", "Read KNX device information" },
		{ "mem", "Access KNX device memory" },
		{ "progmode", "Check/set device(s) in programming mode" },
		{ "restart", "Restart a KNX interface/device" },
		{ "import", "Import datapoints from a KNX project (.knxproj) or group addresses file (.xml|.csv)" },
	};

	private static final List<Class<? extends Runnable>> tools = Arrays.asList(Discover.class, Discover.class,
			ScanDevices.class, IPConfig.class, NetworkMonitor.class, ProcComm.class, ProcComm.class, ProcComm.class,
			TrafficMonitor.class,
			Property.class, Property.class, PropClient.class, ProcComm.class, BaosClient.class, DeviceInfo.class,
			Memory.class, ProgMode.class, Restart.class, DatapointImporter.class);

	private static final Map<InetSocketAddress, TcpConnection> tcpConnectionPool = new HashMap<>();
	private static boolean registeredTcpShutdownHook;

	static synchronized TcpConnection tcpConnection(final InetSocketAddress local, final InetSocketAddress server)
			throws KNXException {
		if (!registeredTcpShutdownHook) {
			Runtime.getRuntime().addShutdownHook(new Thread(Main::closeTcpConnections));
			registeredTcpShutdownHook = true;
		}

		var connection = tcpConnectionPool.get(server);
		if (connection == null || !connection.isConnected()) {
			if (local.getAddress().isAnyLocalAddress() && local.getPort() == 0)
				connection = TcpConnection.newTcpConnection(server);
			else
				connection = TcpConnection.newTcpConnection(local, server);
			tcpConnectionPool.put(server, connection);
		}
		return connection;
	}

	private static void closeTcpConnections() {
		final var connections = tcpConnectionPool.values().toArray(TcpConnection[]::new);
		for (final var c : connections) {
			c.close();
		}
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
		int cmdIdx = 0;
		if (args.length > 0 && args[0].startsWith("-v")) {
			final String vs = args[0];
			final String level = vs.startsWith("-vvv") ? "trace" : vs.startsWith("-vv") ? "debug" : "info";
			System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);
			cmdIdx++;
		}

		final String cmd = args[cmdIdx];
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
						final int startArgs = cmdIdx + 1;
						final int userArgs = args.length - startArgs;
						toolargs = Arrays.copyOfRange(cmds[i], defaultArgsStart,
								defaultArgsStart + defaultArgs + userArgs);
						System.arraycopy(args, startArgs, toolargs, defaultArgs, userArgs);
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
		final String sep = System.lineSeparator();
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

	static InetSocketAddress createLocalSocket(final Map<String, Object> options) {
		return createLocalSocket((InetAddress) options.get("localhost"), (Integer) options.get("localport"));
	}

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
	static KNXMediumSettings getMedium(final String id) {
		final int medium = KNXMediumSettings.getMedium(id);
		return KNXMediumSettings.create(medium, KNXMediumSettings.BackboneRouter);
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

	static boolean parseCommonOption(final String arg, final Iterator<String> i, final Map<String, Object> options) {
		if (isOption(arg, "version", null))
			options.put("about", (Runnable) Main::showVersion);
		else if (isOption(arg, "localhost", null))
			options.put("localhost", parseHost(i.next()));
		else if (isOption(arg, "localport", null))
			options.put("localport", Integer.decode(i.next()));
		else if (isOption(arg, "port", "p"))
			options.put("port", Integer.decode(i.next()));
		else if (isOption(arg, "nat", "n"))
			options.put("nat", null);
		else if (isOption(arg, "ft12", "f"))
			options.put("ft12", null);
		else if (isOption(arg, "usb", "u"))
			options.put("usb", null);
		else if (isOption(arg, "tpuart", null))
			options.put("tpuart", null);
		else if (isOption(arg, "medium", "m"))
			options.put("medium", getMedium(i.next()));
		else if (isOption(arg, "domain", null))
			options.put("domain", Long.decode(i.next()));
		else if (isOption(arg, "tcp", null))
			options.put("tcp", null);
		else if (isOption(arg, "udp", null))
			options.put("udp", null);
		else if (isOption(arg, "ft12-cemi", null))
			options.put("ft12-cemi", null);
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
		else if (isOption(arg, "keyring", null))
			options.put("keyring", Keyring.load(i.next()));
		else if (isOption(arg, "keyring-pwd", null))
			options.put("keyring-pwd", i.next().toCharArray());
		else if (isOption(arg, "interface", null))
			options.put("interface", getAddress(i.next()));
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
		joiner.add("  --port -p <number>         UDP/TCP port on <host> (default " + KNXnetIPConnection.DEFAULT_PORT + ")");
		joiner.add("  --udp                      use UDP (default for unsecure communication)");
		joiner.add("  --tcp                      use TCP (default for KNX IP secure)");
		joiner.add("  --nat -n                   enable Network Address Translation");
		joiner.add("  --ft12 -f                  use FT1.2 serial communication");
		joiner.add("  --usb -u                   use KNX USB communication");
		joiner.add("  --tpuart                   use TP-UART communication");
		joiner.add("  --medium -m <id>           KNX medium [tp1|p110|knxip|rf] (default tp1)");
		joiner.add("  --domain <address>         domain address on KNX PL/RF medium (defaults to broadcast domain)");
		joiner.add("  --knx-address -k <addr>    KNX device address of local endpoint");
	}

	static void printSecureOptions(final StringJoiner joiner, final boolean printGroupKey) {
		joiner.add("KNX Secure:");
		joiner.add("  --keyring <path>           *.knxkeys file for secure communication (defaults to keyring in current working directory)");
		joiner.add("  --keyring-pwd <password>   keyring password");

		joiner.add("KNX IP Secure specific:");
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
		final var link = new Connector().reconnectOn(false, true, true).reconnectDelay(Duration.ofSeconds(4))
				.newLink(() -> createNewLink(options));
		link.addLinkListener(new NetworkLinkListener() {
			@LinkEvent
			void connectionStatus(final ConnectionStatus status) {
				System.out.println(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " connection status KNX " + status);
			}
		});
		return link;
	}

	private static KNXNetworkLink createNewLink(final Map<String, Object> options) throws KNXException, InterruptedException {
		lookupKeyring(options);

		final String host = (String) options.get("host");
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");

		// check for FT1.2 network link
		if (options.containsKey("ft12"))
			return new KNXNetworkLinkFT12(host, medium);

		// check for FT1.2 cEMI network link
		if (options.containsKey("ft12-cemi"))
			return KNXNetworkLinkFT12.newCemiLink(host, medium);

		// check for USB network link
		if (options.containsKey("usb"))
			return new KNXNetworkLinkUsb(host, medium);

		// rest of connections can have a specific local knx address
		final IndividualAddress device = (IndividualAddress) options.get("knx-address");
		if (device != null)
			medium.setDeviceAddress(device);

		// check for TP-UART link
		if (options.containsKey("tpuart")) {
			final var link = new KNXNetworkLinkTpuart(host, medium, Collections.emptyList());
			if (device == null)
				LoggerFactory.getLogger("io.calimero.tools").info("TP-UART sends without assigned KNX address (--knx-address)");
			return link;
		}

		// we have an IP link
		final InetSocketAddress local = createLocalSocket(options);
		final InetAddress addr = parseHost(host);

		// check for KNX IP routing
		if (addr.isMulticastAddress()) {
			if (medium.getDeviceAddress().equals(KNXMediumSettings.BackboneRouter)) {
				// default subnetwork and device address for unregistered device
				medium.setDeviceAddress(new IndividualAddress(0x0f, 0x0f, 0xff));
			}
			final var optGroupKey = groupKey(addr, options);
			if (optGroupKey.isPresent()) {
				final byte[] groupKey = optGroupKey.get();
				try {
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

		// supported combination of options for secure connection:
		// 1.) user and user key
		// 2.) interface address and user
		// 3.) single keyring interface and (tunneling address or user)
		final var optUserKey = userKey(options);
		if (optUserKey.isPresent()) {
			final byte[] userKey = optUserKey.get();
			final byte[] devAuth = deviceAuthentication(options);
			final int user = (int) options.getOrDefault("user", 0);

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
		lookupKeyring(options);

		final InetSocketAddress local = createLocalSocket(options);
		final InetSocketAddress host = new InetSocketAddress((String) options.get("host"),
				((Integer) options.get("port")).intValue());
		final boolean nat = options.containsKey("nat");
		final Optional<byte[]> optUserKey = deviceMgmtKey(options);
		if (optUserKey.isPresent()) {
			final byte[] userKey = optUserKey.get();
			final byte[] devAuth = deviceAuthentication(options);

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

	private static Keyring toolKeyring;

	static void lookupKeyring(final Map<String, Object> options) {
		// check for keyring password, and user-supplied keyring or any keyring in current working directory
		final boolean gotPwd = options.containsKey("keyring-pwd");
		final Optional<Keyring> optKeyring = Optional.ofNullable((Keyring) options.get("keyring"));
		if (gotPwd) {
			optKeyring.or(Main::cwdKeyring).ifPresent(keyring -> {
					Security.defaultInstallation().useKeyring(keyring, (char[]) options.get("keyring-pwd"));
					toolKeyring = keyring;
			});
		}
		else if (gotPwd ^ optKeyring.isPresent()) // should maybe make this an exception, too
			System.out.println("both keyring and keyring password are required, secure communication won't be available!");
	}

	private static Optional<Keyring> cwdKeyring() {
		final String knxkeys = ".knxkeys";
		try (var list = Files.list(Path.of(""))) {
			return list.map(Path::toString).filter(path -> path.endsWith(knxkeys)).findAny().map(Keyring::load);
		}
		catch (final IOException ignore) {
			return Optional.empty();
		}
	}

	static Optional<byte[]> userKey(final Map<String, Object> options) {
		return Optional.ofNullable((byte[]) options.get("user-key")).or(() -> keyringUserKey(options));
	}

	private static Optional<byte[]> deviceMgmtKey(final Map<String, Object> options) {
		return Optional.ofNullable((byte[]) options.get("user-key")).or(() -> keyringDeviceMgmtKey(options));
	}

	private static Optional<byte[]> groupKey(final InetAddress multicastGroup, final Map<String, Object> options) {
		return Optional.ofNullable((byte[]) options.get("group-key")).or(() -> keyringGroupKey(multicastGroup, options));
	}

	static byte[] deviceAuthentication(final Map<String, Object> options) {
		return Optional.ofNullable((byte[]) options.get("device-key"))
				.or(() -> keyringDeviceAuthentication(options))
				.orElse(new byte[0]);
	}

	private static Optional<byte[]> keyringUserKey(final Map<String, Object> options) {
		if (toolKeyring == null)
			return Optional.empty();
		final var ifAddr = (IndividualAddress) options.get("interface");
		final int user = (int) options.getOrDefault("user", 0);
		final var connectConfig = interfaceFor(ifAddr, user);
		if (connectConfig.isPresent()) {
			options.put("user", connectConfig.get().user());
			return connectConfig.get().password().map(decryptAndHashUserPwd(options));
		}
		return Optional.empty();
	}

	private static Optional<byte[]> keyringDeviceMgmtKey(final Map<String, Object> options) {
		final var ifAddr = (IndividualAddress) options.get("interface");
		return keyringDeviceForInterface(ifAddr).flatMap(Keyring.Device::password).map(decryptAndHashUserPwd(options));
	}

	private static Optional<byte[]> keyringGroupKey(final InetAddress multicastGroup,
			final Map<String, Object> options) {
		if (toolKeyring == null)
			return Optional.empty();
		return toolKeyring.backbone().filter(bb -> bb.multicastGroup().equals(multicastGroup))
				.flatMap(Keyring.Backbone::groupKey)
				.map(key -> toolKeyring.decryptKey(key, (char[]) options.get("keyring-pwd")));
	}

	private static Optional<byte[]> keyringDeviceAuthentication(final Map<String, Object> options) {
		final var ifAddr = (IndividualAddress) options.get("interface");
		return keyringDeviceForInterface(ifAddr).flatMap(Keyring.Device::authentication).map(pwd -> SecureConnection
				.hashDeviceAuthenticationPassword(toolKeyring.decryptPassword(pwd, (char[]) options.get("keyring-pwd"))));
	}

	private static Optional<Keyring.Device> keyringDeviceForInterface(final IndividualAddress ifAddr) {
		if (toolKeyring == null)
			return Optional.empty();
		final var devices = toolKeyring.devices();
		final var interfaces = toolKeyring.interfaces();

		if (ifAddr != null) {
			if (devices.containsKey(ifAddr))
				return Optional.of(devices.get(ifAddr));
			// XXX keyring interface does not provide host address, so we have to iterate entry set
			for (final var entry : interfaces.entrySet()) {
				for (final var iface : entry.getValue()) {
					if (iface.address().equals(ifAddr)) {
						final var host = entry.getKey();
						return Optional.ofNullable(devices.get(host));
					}
				}
			}
		}

		if (interfaces.size() != 1)
			return Optional.empty();
		final var deviceAddr = interfaces.keySet().iterator().next();
		return Optional.of(devices.get(deviceAddr));
	}

	// user = 0 for unspecified user, or ifAddr null for unspecified interface address
	private static Optional<Interface> interfaceFor(final IndividualAddress ifAddr, final int user) {
		if (toolKeyring == null)
			return Optional.empty();
		final var interfaces = toolKeyring.interfaces();

		List<Interface> list = null;
		if (ifAddr == null) {
			// lookup default host
			if (interfaces.size() != 1)
				return Optional.empty();
			list = interfaces.values().iterator().next();
		}
		if (user != 0) {
			// lookup by host address and user
			if (list == null) {
				list = interfaces.get(ifAddr);
				if (list == null)
					return Optional.empty();
			}
			for (final var ifConfig : list)
				if (ifConfig.user() == user)
					return Optional.of(ifConfig);
		}
		if (list == null) {
			// ifAddr != null and user == 0, lookup specific interface given by ifAddr
			for (final var configs : interfaces.values()) {
				for (final var ifConfig : configs)
					if (ifConfig.address().equals(ifAddr))
						return Optional.of(ifConfig);
			}
		}
		return Optional.empty();
	}

	private static Function<byte[], byte[]> decryptAndHashUserPwd(final Map<String, Object> options) {
		return pwd -> SecureConnection
				.hashUserPassword(toolKeyring.decryptPassword(pwd, (char[]) options.get("keyring-pwd")));
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

	static final class PeekingIterator<E> implements Iterator<E> {
		private final Iterator<E> i;
		private E next;

		PeekingIterator(final Iterator<E> i) { this.i = i; }

		public E peek() { return next != null ? next : (next = next()); }

		@Override
		public boolean hasNext() { return next != null || i.hasNext(); }

		@Override
		public E next() {
			final E e = next != null ? next : i.next();
			next = null;
			return e;
		}
	}
}
