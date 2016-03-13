/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2016 B. Malinowsky

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
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.PLSettings;
import tuwien.auto.calimero.link.medium.RFSettings;
import tuwien.auto.calimero.link.medium.TPSettings;

/**
 * @author B. Malinowsky
 */
final class Main
{
	private static final String DISCOVER = "discover";
	private static final String DESCRIPTION = "describe";
	private static final String SCAN = "scan";
	private static final String IPCONFIG = "ipconfig";
	private static final String MONITOR = "monitor";
	private static final String COM_READ = "read";
	private static final String COM_WRITE = "write";
	private static final String COM_GROUPMON = "groupmon";
	private static final String GET_PROPERTY = "get";
	private static final String SET_PROPERTY = "set";
	private static final String PROPERTIES = "properties";
	private static final String DEV_INFO = "devinfo";

	private static final String[][] cmds = new String[][] {
		{ DISCOVER, "Discover KNXnet/IP servers", "--search" },
		{ DESCRIPTION, "KNXnet/IP server self-description", "--description" },
		{ SCAN, "Determine the existing KNX devices on a KNX subnetwork", "" },
		{ IPCONFIG, "KNXnet/IP device address configuration", "" },
		{ MONITOR, "Open bus-monitor for KNX network", "" },
		{ COM_READ, "Read a value using KNX process communication", "read" },
		{ COM_WRITE, "Write a value using KNX process communication", "write" },
		{ COM_GROUPMON, "Open group-monitor for KNX process communication", "monitor" },
		{ GET_PROPERTY, "Read a KNX property", "get" },
		{ SET_PROPERTY, "Write a KNX property", "set" },
		{ PROPERTIES, "Open KNX property client", "" },
		{ DEV_INFO, "Read KNX device information", "" },
	};

	private static final List<Class<? extends Runnable>> tools = Arrays.asList(Discover.class,
			Discover.class, ScanDevices.class, IPConfig.class, NetworkMonitor.class,
			ProcComm.class, ProcComm.class, ProcComm.class, Property.class, Property.class, PropClient.class,
			DeviceInfo.class);

	private static final String sep = System.getProperty("line.separator");

	private Main() {}

	/**
	 * Provides a common entry point for running the Calimero Tools.
	 * <p>
	 * This is useful to start the tools from within a .jar file.
	 *
	 * @param args the first argument being the tool to invoke, followed by the command line options
	 *        of that tool
	 */
	public static void main(final String[] args)
	{
		final boolean help = args.length == 1 && (args[0].equals("-help") || args[0].equals("-h"));
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
					if (args.length > 1 && (args[1].equals("-help") || args[1].equals("-h")))
						toolargs = new String[] { "-h" };
					else {
						args[0] = cmds[i][2];
						toolargs = args[0].isEmpty() ? Arrays.copyOfRange(args, 1, args.length) : args;
					}
					m.invoke(null, new Object[] { toolargs });
				}
				catch (final InvocationTargetException e) {
					e.getCause().printStackTrace();
				}
				catch (final Exception e) {
					// NoSuchMethodEx, IllegalAccessEx, // SecurityEx, IllegalArgumentEx, ...
					e.printStackTrace();
				}
				return;
			}
		}
		System.out.println("unknown command \"" + cmd + "\"");
	}

	private static void usage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("Supported commands (always safe without further options, use -h for help):")
				.append(sep);
		for (int i = 0; i < cmds.length; i++) {
			sb.append(cmds[i][0]).append(" - ").append(cmds[i][1]).append(sep);
		}
		System.out.println(sb);
	}

	//
	// Utility methods used by the various tools
	//

	static InetSocketAddress createLocalSocket(final InetAddress host, final Integer port)
	{
		final int p = port != null ? port.intValue() : 0;
		try {
			return host != null ? new InetSocketAddress(host, p) : p != 0 ? new InetSocketAddress(
					InetAddress.getLocalHost(), p) : null;
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed getting local host " + e.getMessage(), e);
		}
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
	 * <p>
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
		if (id.equals("tp1"))
			return TPSettings.TP1;
		else if (id.equals("p110"))
			return new PLSettings();
		else if (id.equals("rf"))
			return new RFSettings(null);
		else
			throw new KNXIllegalArgumentException("unknown medium");
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
		final boolean lo = arg.startsWith("--")
				&& arg.regionMatches(2, longOpt, 0, arg.length() - 2);
		final boolean so = shortOpt != null && arg.startsWith("-")
				&& arg.regionMatches(1, shortOpt, 0, arg.length() - 1);
		// notify about change of prefix for long options
		if (arg.equals("-" + longOpt))
			throw new KNXIllegalArgumentException("use --" + longOpt);
		return lo || so;
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

		public void run()
		{
			t.interrupt();
		}
	}
}
