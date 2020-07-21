/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2020 B. Malinowsky

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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;

import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.knxnetip.Discoverer;
import tuwien.auto.calimero.knxnetip.Discoverer.Result;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.knxnetip.servicetype.DescriptionResponse;
import tuwien.auto.calimero.knxnetip.servicetype.SearchResponse;
import tuwien.auto.calimero.knxnetip.util.DIB;
import tuwien.auto.calimero.knxnetip.util.DeviceDIB;
import tuwien.auto.calimero.knxnetip.util.HPAI;
import tuwien.auto.calimero.knxnetip.util.ServiceFamiliesDIB;
import tuwien.auto.calimero.knxnetip.util.Srp;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.tools.Main.ShutdownHandler;

/**
 * A tool for Calimero showing the KNXnet/IP discovery and self description feature.
 * <p>
 * Discover is a {@link Runnable} tool implementation allowing a user to do KNXnet/IP discovery and
 * self description of KNXnet/IP capable devices. As the protocol name already implies, this is done
 * using the IP protocol. This tool shows the necessary interaction with the Calimero 2 API for
 * discovering KNXnet/IP capable devices and query descriptions. The main part of this tool
 * implementation interacts with the type {@link Discoverer} in the library, which implements the
 * necessary discovery and self description features.<br>
 * When running this tool from the console, the <code>main</code>- method of this class is invoked,
 * otherwise use it in the context appropriate to a {@link Runnable}.
 * <p>
 * To cancel a running discovery/description request on the console, use a user interrupt for
 * termination, for example, <code>^C</code>.<br>
 * In console mode, discovery and self description responses, as well as errors and problems during
 * discovery/description are written to <code>System.out</code>.
 *
 * @author B. Malinowsky
 */
public class Discover implements Runnable
{
	private static final String tool = "Discover";
	private static final String sep = System.getProperty("line.separator");

	private static Logger out = LogService.getLogger(Discoverer.LOG_SERVICE);

	private final Discoverer d;
	private final Map<String, Object> options = new HashMap<>();
	private final List<Srp> searchParameters = new ArrayList<>();

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
		final InetAddress local = nif != null ? inetAddress(nif) : null;
		final boolean mcast = (boolean) options.get("mcastResponse");
		d = new Discoverer(local, lp != null ? lp.intValue() : 0, options.containsKey("nat"), mcast);
	}

	/**
	 * Entry point for running Discover.
	 * <p>
	 * To show usage message of the tool on the console, supply the command line option --help (or -h).<br>
	 * Command line arguments are treated case sensitive; if no command is given, the tool only shows a short
	 * description and version info. Available commands and options for discovery/self description:
	 * <ul>
	 * <li><code>search</code> start a discovery search</li>
	 * <li>
	 * <ul>
	 * <li><code>--withDescription</code> query self description for each search result</li>
	 * <li><code>--interface -i</code> <i>interface name</i> | <i>IP address</i> &nbsp;local multicast network interface</li>
	 * <li><code>--unicast -u</code> request unicast responses</li>
	 * </ul>
	 * </li>
	 * <li><code>describe <i>host</i></code> &nbsp;query self description from host</li>
	 * <li>
	 * <ul>
	 * <li><code>--interface -i</code> <i>interface name</i> | <i>IP address</i> &nbsp;local network interface for
	 * sending description request</li>
	 * <li><code>--serverport -p</code> <i>number</i> &nbsp;server UDP port (defaults to port 3671)</li>
	 * </ul>
	 * </li>
	 * <li><code>sd</code> &nbsp;shortcut for {@code search --withDescription}</li>
	 * </ul>
	 * Other options:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--timeout -t</code> discovery/self description response timeout in seconds</li>
	 * </ul>
	 *
	 * @param args command line options for discovery or self description
	 */
	public static void main(final String[] args)
	{
		try {
			final Discover d = new Discover(args);
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
		try {
			if (options.containsKey("help"))
				showUsage();
			else if (options.containsKey("version"))
				Main.showVersion();
			else if (options.containsKey("search")) {
				if (options.containsKey("withDescription"))
					searchWithDescription();
				else
					search();
			}
			else if (options.containsKey("host"))
				description();
			else {
				out(tool + " - KNXnet/IP server discovery & self description");
				Main.showVersion();
				out("Type --help for help message");
			}
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
	 * @param result the search result containing the received search response with information
	 *        about a KNXnet/IP endpoint
	 */
	protected void onEndpointReceived(final Result<SearchResponse> result)
	{
		final SearchResponse sr = result.getResponse();
		System.out.println(formatResponse(result, sr.getControlEndpoint(), sr.getDevice(), sr.getServiceFamilies(),
				sr.description()));
	}

	/**
	 * Invoked by this tool immediately after receiving a description response.
	 * <p>
	 * This default implementation extracts the information and writes it to the standard output.
	 * <p>
	 *
	 * @param result the description result containing the received description response
	 */
	protected void onDescriptionReceived(final Result<DescriptionResponse> result)
	{
		onDescriptionReceived(result, null);
	}

	private static void onDescriptionReceived(final Result<DescriptionResponse> result, final HPAI hpai)
	{
		final DescriptionResponse dr = result.getResponse();
		System.out.println(formatResponse(result, hpai, dr.getDevice(), dr.getServiceFamilies(), dr.getDescription()));
	}

	private static String formatResponse(final Result<?> r, final HPAI controlEp, final DeviceDIB device,
		final ServiceFamiliesDIB serviceFamilies, final Collection<DIB> description)
	{
		final StringBuilder sb = new StringBuilder(sep);
		sb.append("Using ").append(r.getAddress().getHostAddress()).append(" at ")
				.append(nameOf(r.getNetworkInterface())).append(sep);
		sb.append("-".repeat(sb.length() - 2)).append(sep);
		sb.append("\"").append(device.getName()).append("\"");
		if (controlEp != null) {
			var endpoint = controlEp.toString();
			final var tcp = serviceFamilies.getVersion(ServiceFamiliesDIB.CORE) > 1;
			if (tcp)
				endpoint = endpoint.replace("UDP", "UDP & TCP");
			sb.append(" endpoint ").append(endpoint);
		}
		final String info = device.toString();
		// device name is already there
		final String withoutName = info.substring(info.indexOf(","));
		// skip SN in search responses
		final boolean search = r.getResponse() instanceof SearchResponse;
		final String formatted = search ? withoutName.substring(0, withoutName.lastIndexOf(",")) : withoutName;
		sb.append(formatted).append(sep);
		sb.append("Supported services: ");
		final String basic = sb.toString().replaceAll(", ", sep) + serviceFamilies.toString();

		final var joiner = new StringJoiner(sep);
		joiner.add(basic);

		final var desc = new ArrayList<>(description);
		desc.remove(device);
		desc.remove(serviceFamilies);

		extractDib(DIB.SecureServiceFamilies, desc).map(dib -> " ".repeat(20) + dib).ifPresent(joiner::add);
		extractDib(DIB.AdditionalDeviceInfo, desc).map(dib -> dib.toString().replace(", ", sep)).ifPresent(joiner::add);
		extractDib(DIB.TunnelingInfo, desc).map(dib -> dib.toString().replaceFirst(", ", sep)).ifPresent(joiner::add);
		desc.forEach(dib -> joiner.add(dib.toString()));
		return joiner.toString();
	}

	private static Optional<DIB> extractDib(final int typeCode, final Collection<DIB> description) {
		for (final DIB dib : description) {
			if (dib.getDescTypeCode() == typeCode) {
				description.remove(dib);
				return Optional.of(dib);
			}
		}
		return Optional.empty();
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
		if (canceled) {
			final String msg = options.containsKey("search") ? "stopped discovery" : "self description canceled";
			out.info(msg);
		}
		if (thrown != null)
			out.error("completed", thrown);
	}

	/**
	 * Starts a discovery search using the supplied options.
	 *
	 * @throws KNXException on problem during discovery
	 * @throws InterruptedException on interrupted thread
	 */
	private void search() throws KNXException, InterruptedException
	{
		final int timeout = ((Integer) options.get("timeout")).intValue();

		// see if we have an extended unicast search to a control endpoint
		if (options.containsKey("host")) {
			final InetAddress server = (InetAddress) options.get("host");
			final var ctrlEndpoint = new InetSocketAddress(server, (int) options.get("serverport"));
			try {
				d.search(ctrlEndpoint, searchParameters.toArray(new Srp[0])).thenAccept(this::onEndpointReceived).join();
			}
			catch (final CompletionException e) {
				if (TimeoutException.class.isAssignableFrom(e.getCause().getClass()))
					throw new KNXTimeoutException("timeout waiting for response from " + ctrlEndpoint);
			}
			return;
		}
		// start the search, using a particular network interface if supplied
		else if (options.containsKey("if"))
			d.startSearch(0, (NetworkInterface) options.get("if"), timeout, false);
		else
			d.startSearch(timeout, false);
		int displayed = 0;
		// wait until search finished, and update console 4 times/second with
		// received search responses
		try {
			while (d.isSearching()) {
				Thread.sleep(250);
				final List<Result<SearchResponse>> res = d.getSearchResponses();
				for (; displayed < res.size(); ++displayed)
					onEndpointReceived(res.get(displayed));
			}
		}
		finally {
			out.info("search stopped after " + timeout + " seconds with " + displayed + " responses");
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
		final InetSocketAddress host = new InetSocketAddress((InetAddress) options.get("host"),
				((Integer) options.get("serverport")).intValue());
		final int timeout = ((Integer) options.get("timeout")).intValue();
		// request description
		final Result<DescriptionResponse> res = d.getDescription(host, timeout);
		onDescriptionReceived(res);
	}

	// implements search combined with description as done by ETS
	private void searchWithDescription() throws KNXException, InterruptedException
	{
		final int timeout = ((Integer) options.get("timeout"));
		// start the search, using a particular network interface if supplied
		if (options.containsKey("if"))
			d.startSearch(0, (NetworkInterface) options.get("if"), timeout, true);
		else
			d.startSearch(timeout, true);
		final List<Result<SearchResponse>> res = d.getSearchResponses();
		new HashSet<>(res).parallelStream().forEach(this::description);
	}

	private void description(final Result<SearchResponse> r)
	{
		final SearchResponse sr = r.getResponse();
		final HPAI hpai = sr.getControlEndpoint();

		final InetSocketAddress server;
		// check NAT with unicast response
		if (hpai.getAddress().isAnyLocalAddress() || hpai.getPort() == 0)
			server = r.remoteEndpoint();
		else
			server = new InetSocketAddress(hpai.getAddress(), hpai.getPort());

		final int timeout = 2;
		try {
			final Result<DescriptionResponse> dr = new Discoverer(r.getAddress(), 0,
					options.containsKey("nat"), false).getDescription(server, timeout);
			onDescriptionReceived(dr, new HPAI(hpai.getHostProtocol(), server));
		}
		catch (final KNXException e) {
			System.out.println("description failed for server " + server + " using " + r.getAddress()
					+ " at " + r.getNetworkInterface().getName() + ": " + e.getMessage());
		}
	}

	/**
	 * Reads all command line options, and puts relevant options into the supplied options map.
	 * <p>
	 * On options not relevant for doing discovery/description (like <code>help</code>), this method
	 * will take appropriate action (like showing usage information). On occurrence of such an
	 * option, other options will be ignored. On unknown options, a KNXIllegalArgumentException is
	 * thrown.
	 *
	 * @param args array with command line options
	 */
	private void parseOptions(final String[] args)
	{
		// add defaults
		options.put("localport", Integer.valueOf(0));
		options.put("serverport", Integer.valueOf(KNXnetIPConnection.DEFAULT_PORT));
		options.put("timeout", Integer.valueOf(3));
		options.put("mcastResponse", Boolean.TRUE);

		if (args.length == 0)
			return;

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

			if (Main.isOption(arg, "localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "nat", "n"))
				options.put("nat", null);
			else if (Main.isOption(arg, "interface", "i"))
				options.put("if", getNetworkIF(args[++i]));
			else if (Main.isOption(arg, "timeout", "t")) {
				final Integer timeout = Integer.valueOf(args[++i]);
				// a value of 0 means infinite timeout
				if (timeout.intValue() > 0)
					options.put("timeout", timeout);
			}
			else if ("search".equals(arg) || Main.isOption(arg, "search", "s"))
				options.put("search", null);
			else if (Main.isOption(arg, "unicast", "u"))
				options.put("mcastResponse", Boolean.FALSE);
			else if (Main.isOption(arg, "withDescription", null))
				options.put("withDescription", Boolean.FALSE);
			else if (arg.equals("sd")) {
				options.put("search", null);
				options.put("withDescription", null);
			}
			else if (Main.isOption(arg, "progmode", null))
				searchParameters.add(Srp.withProgrammingMode());
			else if (Main.isOption(arg, "mac", null))
				searchParameters.add(Srp.withMacAddress(fromHex(args[++i])));
			else if ("describe".equals(arg) || Main.isOption(arg, "description", "d")) {
				if (args.length <= i + 1)
					throw new KNXIllegalArgumentException("specify remote host");
				options.put("host", Main.parseHost(args[++i]));
			}
			else if (Main.isOption(arg, "serverport", "p"))
				options.put("serverport", Integer.decode(args[++i]));
			else if (options.containsKey("search"))
				options.put("host", Main.parseHost(arg)); // update to unicast search with server control endpoint
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
	}

	private static String nameOf(final NetworkInterface nif)
	{
		final String name = nif.getName();
		final String friendly = nif.getDisplayName();
		if (friendly != null && !name.equals(friendly))
			return name + " (" + friendly + ")";
		return name;
	}

	private InetAddress inetAddress(final NetworkInterface nif) {
		if (options.containsKey("nat"))
			return nif.getInetAddresses().nextElement();
		return nif.inetAddresses().filter(Inet4Address.class::isInstance).findAny().orElseThrow(
				() -> new KNXIllegalArgumentException("no IPv4 address bound to interface " + nif.getName()));
	}

	/**
	 * Gets the local network interface using the supplied identifier.
	 * <p>
	 *
	 * @param id identifier associated with the network interface, either an network interface name,
	 *        a host name, or an IP address bound to that interface
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
		final StringBuilder sb = new StringBuilder();
		sb.append("Usage: ").append(tool).append(" {search | describe} [options]").append(sep);
		sb.append("Supported commands:").append(sep);
		sb.append("  search                     start a discovery search").append(sep);
		sb.append("    --withDescription        query self description for each search result").append(sep);
		sb.append("    --unicast -u             request unicast response (default is multicast)").append(sep);
		sb.append("    --interface -i <interface/host name | IP address>"
				+ "    local multicast network interface").append(sep);
		sb.append("  describe <host>            query self description from host").append(sep);
		sb.append("    --interface -i <interface/host name | IP address>"
				+ "    local outgoing network interface").append(sep);
		sb.append("    --serverport -p <number> server UDP port (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append("  sd                         shortcut for 'search --withDescription'").append(sep);
		sb.append("Other options:").append(sep);
		sb.append("  --localport <number>       local UDP port (default system assigned)").append(sep);
		sb.append("  --nat -n                   enable Network Address Translation").append(sep);
		sb.append("  --timeout -t               discovery/description response timeout in seconds").append(sep);
		sb.append("  --version                  show tool/library version and exit").append(sep);
		sb.append("  --help -h                  show this help message").append(sep);
		out(sb.toString());
	}

	private static void out(final String s)
	{
		System.out.println(s);
	}

	private static byte[] fromHex(final String hex) {
		final String s = hex.replaceAll(" |:", "");
		final int len = s.length();

		final byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2)
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
		return data;
	}
}
