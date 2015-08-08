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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.Discoverer;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.knxnetip.servicetype.DescriptionResponse;
import tuwien.auto.calimero.knxnetip.servicetype.SearchResponse;
import tuwien.auto.calimero.log.LogLevel;
import tuwien.auto.calimero.log.LogManager;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogStreamWriter;
import tuwien.auto.calimero.log.LogWriter;

/**
 * A tool for Calimero showing the KNXnet/IP discovery and self description feature.
 * <p>
 * Discover is a {@link Runnable} tool implementation allowing a user to do KNXnet/IP
 * discovery and self description of KNXnet/IP capable devices. As the protocol name
 * already implies, this is done using the IP protocol. This tool shows the necessary
 * interaction with the Calimero 2 API for discovering KNXnet/IP capable devices and
 * query descriptions. The main part of this tool implementation interacts with the type
 * {@link Discoverer} in the library, which implements the necessary discovery and self
 * description features.<br>
 * When running this tool from the console, the <code>main</code>- method of this class is
 * invoked, otherwise use it in the context appropriate to a {@link Runnable}.
 * <p>
 * To cancel a running discovery/description request on the console, use a user interrupt
 * for termination, for example, <code>^C</code>.<br>
 * In console mode, discovery and self description responses, as well as errors and
 * problems during discovery/description are written to <code>System.out</code>.
 *
 * @author B. Malinowsky
 */
public class Discover implements Runnable
{
	private static final String tool = "Discover";
	private static final String sep = System.getProperty("line.separator");

	private static LogService out = LogManager.getManager().getLogService(Discoverer.LOG_SERVICE);

	private final Discoverer d;
	private final Map options = new HashMap();

	/**
	 * Creates a new Discover instance using the supplied options.
	 * <p>
	 * See {@link #main(String[])} for a list of options.
	 *
	 * @param args list with options
	 * @throws KNXException on instantiation problems
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public Discover(final String[] args) throws KNXException
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

		// create a new discoverer with a (default) local port and specify
		// whether network address translation (NAT) should be used
		final Integer lp = ((Integer) options.get("localport"));
		// if a network interface was specified, use an assigned IP for local host
		final NetworkInterface nif = (NetworkInterface) options.get("if");
		final InetAddress local = (InetAddress) (nif != null ? nif.getInetAddresses().nextElement()
				: null);
		d = new Discoverer(local, lp != null ? lp.intValue() : 0, options.containsKey("nat"), true);
	}

	/**
	 * Entry point for running Discover.
	 * <p>
	 * To show usage message of the tool on the console, supply the command line option
	 * -help (or -h).<br>
	 * Command line options are treated case sensitive. Available options for
	 * discovery/self description:
	 * <ul>
	 * <li>no arguments: only show short description and version info</li>
	 * <li><code>-help -h</code> show help message</li>
	 * <li><code>-version</code> show tool/library version and exit</li>
	 * <li><code>-localport</code> <i>number</i> &nbsp;local UDP port (default system
	 * assigned)</li>
	 * <li><code>-nat -n</code> enable Network Address Translation</li>
	 * <li><code>-timeout -t</code> discovery/self description response timeout in seconds</li>
	 * <li><code>-search -s</code> start a discovery search</li>
	 * <li><code>-interface -i</code> <i>if-name</i> | <i>ip-address</i> &nbsp;local
	 * multicast network interface for discovery or local host for self description
	 * (default system assigned)</li>
	 * <li><code>-description -d <i>host</i></code> &nbsp;query description from host</li>
	 * <li><code>-serverport -p</code> <i>number</i> &nbsp;server UDP port for description
	 * (defaults to port 3671)</li>
	 * </ul>
	 *
	 * @param args command line options for discovery or self description
	 */
	public static void main(final String[] args)
	{
		// use a log writer to System.out
		final LogWriter w = LogStreamWriter.newUnformatted(LogLevel.INFO, System.out, true, false);
		out.addWriter(w);
		try {
			final Discover d = new Discover(args);
			final ShutdownHandler sh = new ShutdownHandler().register();
			d.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.error("parsing options", t);
		}
		LogManager.getManager().shutdown(true);
	}

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			if (options.isEmpty()) {
				out.log(LogLevel.ALWAYS, tool + " - KNXnet/IP server discovery "
						+ "& self description", null);
				showVersion();
				out.log(LogLevel.ALWAYS, "type -help for help message", null);
			}
			else if (options.containsKey("help"))
				showUsage();
			else if (options.containsKey("version"))
				showVersion();
			else if (options.containsKey("search"))
				search();
			else
				description();
		}
		catch (final KNXException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		catch (final RuntimeException e) {
			thrown = e;
		}
		finally {
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Invoked by this tool immediately after receiving a search response.
	 * <p>
	 * This default implementation writes the endpoint information to standard output.
	 *
	 * @param response the received search response containing information about a
	 *        KNXnet/IP endpoint
	 */
	protected void onEndpointReceived(final SearchResponse response)
	{
		final StringBuffer buf = new StringBuffer();
		buf.append(sep).append("control endpoint ");
		buf.append(response.getControlEndpoint().toString()).append(sep);
		buf.append(response.getDevice().toString());
		buf.append(sep).append(sep).append("supported service families:").append(sep);
		buf.append(response.getServiceFamilies().toString());
		for (int i = buf.indexOf(", "); i != -1; i = buf.indexOf(", "))
			buf.replace(i, i + 2, sep);
		System.out.println(buf);
	}

	/**
	 * Invoked by this tool immediately after receiving a description response.
	 * <p>
	 * This default implementation extracts the information and writes it to the standard
	 * output.
	 * <p>
	 *
	 * @param r the received description response
	 */
	protected void onDescriptionReceived(final DescriptionResponse r)
	{
		final StringBuffer buf = new StringBuffer();
		buf.append(r.getDevice().toString());
		buf.append(sep).append(sep).append("supported service families:").append(sep);
		buf.append(r.getServiceFamilies().toString());
		if (r.getManufacturerData() != null)
			buf.append(sep).append(sep).append(r.getManufacturerData().toString());
		for (int i = buf.indexOf(", "); i != -1; i = buf.indexOf(", "))
			buf.replace(i, i + 2, sep);
		System.out.println(buf);
	}

	/**
	 * Called by this tool on completion.
	 * <p>
	 *
	 * @param thrown the thrown exception if operation completed due to a raised
	 *        exception, <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled) {
			final String msg = options.containsKey("search") ? "stopped discovery"
					: "self description canceled";
			out.info(msg);
		}
		if (thrown != null)
			out.error("completed", thrown);
	}

	/**
	 * Starts a discovery search using the supplied options.
	 * <p>
	 *
	 * @throws KNXException on problem during discovery
	 * @throws InterruptedException
	 */
	private void search() throws KNXException, InterruptedException
	{
		final int timeout = ((Integer) options.get("timeout")).intValue();
		// start the search, using a particular network interface if supplied
		if (options.containsKey("if"))
			d.startSearch(0, (NetworkInterface) options.get("if"), timeout, false);
		else
			d.startSearch(timeout, false);
		int displayed = 0;
		// wait until search finished, and update console 4 times/second with
		// received search responses
		try {
			while (d.isSearching()) {
				Thread.sleep(250);
				final SearchResponse[] res = d.getSearchResponses();
				for (; displayed < res.length; ++displayed)
					onEndpointReceived(res[displayed]);
			}
			final SearchResponse[] res = d.getSearchResponses();
			for (; displayed < res.length; ++displayed)
				onEndpointReceived(res[displayed]);
		}
		finally {
			out.info("search stopped after " + timeout + " seconds with " + displayed
					+ " responses");
		}
	}

	/**
	 * Requests a self description using the supplied options.
	 * <p>
	 *
	 * @throws KNXException on problem requesting the description
	 */
	private void description() throws KNXException
	{
		// create socket address of server to request self description from
		final InetSocketAddress host = new InetSocketAddress(
			(InetAddress) options.get("host"),
			((Integer) options.get("serverport")).intValue());
		final int timeout = ((Integer) options.get("timeout")).intValue();
		// request description
		final DescriptionResponse res = d.getDescription(host, timeout);
		onDescriptionReceived(res);
	}

	/**
	 * Reads all command line options, and puts relevant options into the supplied options
	 * map.
	 * <p>
	 * On options not relevant for doing discovery/description (like <code>help</code>),
	 * this method will take appropriate action (like showing usage information). On
	 * occurrence of such an option, other options will be ignored. On unknown options, a
	 * KNXIllegalArgumentException is thrown.
	 *
	 * @param args array with command line options
	 */
	private void parseOptions(final String[] args)
	{
		if (args.length == 0)
			return;

		// add defaults
		options.put("localport", new Integer(0));
		options.put("serverport", new Integer(KNXnetIPConnection.DEFAULT_PORT));
		options.put("timeout", new Integer(3));

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

			if (isOption(arg, "-localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (isOption(arg, "-nat", "-n"))
				options.put("nat", null);
			else if (isOption(arg, "-interface", "-i"))
				options.put("if", getNetworkIF(args[++i]));
			else if (isOption(arg, "-timeout", "-t")) {
				final Integer timeout = Integer.valueOf(args[++i]);
				// a value of 0 means infinite timeout
				if (timeout.intValue() > 0)
					options.put("timeout", timeout);
			}
			else if (isOption(arg, "-search", "-s"))
				options.put("search", null);
			else if (isOption(arg, "-description", "-d"))
				parseHost(args[++i], false, options);
			else if (isOption(arg, "-serverport", "-p"))
				options.put("serverport", Integer.decode(args[++i]));
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
	}

	/**
	 * Gets the local network interface using the supplied identifier.
	 * <p>
	 *
	 * @param id identifier associated with the network interface, either an network
	 *        interface name, a host name, or an IP address bound to that interface
	 * @return the network interface
	 * @throws KNXIllegalArgumentException if no network interface found
	 */
	private static NetworkInterface getNetworkIF(final String id)
	{
		try {
			NetworkInterface nif = NetworkInterface.getByName(id);
			if (nif != null)
				return nif;
			nif = NetworkInterface.getByInetAddress(InetAddress.getByName(id));
			if (nif != null)
				return nif;
			throw new KNXIllegalArgumentException("no network interface associated with " + id);
		}
		catch (final IOException e) {
			throw new KNXIllegalArgumentException("error getting network interface, "
					+ e.getMessage(), e);
		}
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("Usage: ").append(tool).append(" [options]").append(sep);
		sb.append("Options:").append(sep);
		sb.append(" -help -h                show this help message").append(sep);
		sb.append(" -version                show tool/library version and exit").append(sep);
		sb.append(" -localport <number>     local UDP port (default system assigned)").append(sep);
		sb.append(" -nat -n                 enable Network Address Translation").append(sep);
		sb.append(" -timeout -t             discovery/description response timeout").append(sep);
		sb.append(" -search -s              start a discovery search").append(sep);
		sb.append(" -interface -i <IF name | host name | IP address>").append(sep);
		sb.append("      local multicast network interface for discovery or").append(sep);
		sb.append("      local host for self description (default system assigned)").append(sep);
		sb.append(" -description -d <host>  query description from host").append(sep);
		sb.append(" -serverport -p <number> server UDP port for description (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		out.log(LogLevel.ALWAYS, sb.toString(), null);
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

	private static boolean isOption(final String arg, final String longOpt, final String shortOpt)
	{
		return arg.equals(longOpt) || shortOpt != null && arg.equals(shortOpt);
	}

	private static void showVersion()
	{
		out.log(LogLevel.ALWAYS, Settings.getLibraryHeader(false), null);
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
}
