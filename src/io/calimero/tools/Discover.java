/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2006, 2025 B. Malinowsky

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

import static io.calimero.tools.Main.isOption;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

import java.io.IOException;
import java.lang.System.Logger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.calimero.DataUnitBuilder;
import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.KNXTimeoutException;
import io.calimero.KnxRuntimeException;
import io.calimero.SerialNumber;
import io.calimero.knxnetip.Discoverer;
import io.calimero.knxnetip.Discoverer.Result;
import io.calimero.knxnetip.DiscovererTcp;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.knxnetip.StreamConnection;
import io.calimero.knxnetip.TcpConnection;
import io.calimero.knxnetip.UnixDomainSocketConnection;
import io.calimero.knxnetip.servicetype.DescriptionResponse;
import io.calimero.knxnetip.servicetype.SearchResponse;
import io.calimero.knxnetip.util.DIB;
import io.calimero.knxnetip.util.DeviceDIB;
import io.calimero.knxnetip.util.HPAI;
import io.calimero.knxnetip.util.ServiceFamiliesDIB;
import io.calimero.knxnetip.util.ServiceFamiliesDIB.ServiceFamily;
import io.calimero.knxnetip.util.Srp;
import io.calimero.log.LogService;
import io.calimero.tools.Main.ShutdownHandler;

/**
 * A tool for Calimero showing the KNXnet/IP discovery and self-description feature.
 * <p>
 * Discover is a {@link Runnable} tool implementation allowing a user to do KNXnet/IP discovery and
 * self-description of KNXnet/IP capable devices. As the protocol name already implies, this is done
 * using the IP protocol. This tool shows the necessary interaction with the Calimero API for
 * discovering KNXnet/IP capable devices and query descriptions. The main part of this tool
 * implementation interacts with the type {@link Discoverer} in the library, which implements the
 * necessary discovery and self-description features.<br>
 * When running this tool from the console, the <code>main</code>- method of this class is invoked,
 * otherwise use it in the context appropriate to a {@link Runnable}.
 * <p>
 * To cancel a running discovery/description request on the console, use a user interrupt for
 * termination, for example, <code>^C</code>.<br>
 * In console mode, discovery and self-description responses, as well as errors and problems during
 * discovery/description are written to <code>System.out</code>.
 *
 * @author B. Malinowsky
 */
public class Discover implements Runnable
{
	private static final String tool = "Discover";
	private static final String sep = System.lineSeparator();

	private static final Logger out = LogService.getLogger(Discoverer.LOG_SERVICE);

	private final Discoverer d;
	private final DiscovererTcp tcp;
	private final Map<String, Object> options = new HashMap<>();
	private final List<Srp> searchParameters = new ArrayList<>();
	private final boolean reuseForDescription;

	/**
	 * Creates a new Discover instance using the supplied options; see {@link #main(String[])} for a list of options.
	 *
	 * @param args list with options
	 */
	public Discover(final String[] args) {
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
		final boolean tcpSearch = options.containsKey("search") && options.containsKey("host") && !options.containsKey("udp");
		if (tcpSearch || options.containsKey("tcp") || options.containsKey("unix-socket")) {
			final var host = options.get("host");
			final StreamConnection conn;
			if (host instanceof final String s) {
				try {
					conn = Main.udsConnection(s);
				}
				catch (final KNXException e) {
					throw new KNXIllegalArgumentException(e.getMessage(), e);
				}
			}
			else {
				final InetAddress server = (InetAddress) host;
				final var ctrlEndpoint = new InetSocketAddress(server, (int) options.get("serverport"));
				final var localEp = new InetSocketAddress(local, lp != null ? lp : 0);
				conn = Main.tcpConnection(localEp, ctrlEndpoint);
			}

			Main.lookupKeyring(options);
			final var optUserKey = Main.userKey(options);
			if (optUserKey.isPresent()) {
				final byte[] userKey = optUserKey.get();
				final byte[] devAuth = Main.deviceAuthentication(options);
				final int user = (int) options.getOrDefault("user", 0);

				final var session = conn.newSecureSession(user, userKey, devAuth);
				tcp = Discoverer.secure(session);
			}
			else if (conn instanceof final TcpConnection tcpConn)
				tcp = Discoverer.tcp(tcpConn);
			else if (conn instanceof final UnixDomainSocketConnection uds)
				tcp = Discoverer.uds(uds);
			else
				throw new IllegalStateException();

			reuseForDescription = true;
			tcp.timeout((Duration) options.get("timeout"));

			d = null;
		}
		else {
			d = new Discoverer(local, lp != null ? lp : 0, options.containsKey("nat"), mcast);
			reuseForDescription = false;
			d.timeout((Duration) options.get("timeout"));

			tcp = null;
		}
	}

	/**
	 * Entry point for running Discover.
	 * <p>
	 * To show usage message of the tool on the console, supply the command line option --help (or -h).<br>
	 * Command line arguments are treated case-sensitive; if no command is given, the tool only shows a short
	 * description and version info. Available commands and options for discovery/self-description:
	 * <ul>
	 * <li><code>search [<i>host</i>]</code> start a discovery search
	 * <ul>
	 * <li><code>--withDescription</code> query self-description for each search result</li>
	 * <li><code>--netif -i</code> <i>interface name</i> | <i>IP address</i> &nbsp;local multicast network interface</li>
	 * <li><code>--unicast -u</code> request unicast responses</li>
	 * <li><code>--mac</code> <i>address</i> &nbsp;extended search requesting the specified MAC address</li>
	 * <li><code>--progmode</code> &nbsp;extended search requesting devices in programming mode</li>
	 * </ul>
	 * <li><code>describe <i>host</i></code> &nbsp;query self description from host
	 * <ul>
	 * <li><code>--netif -i</code> <i>interface name</i> | <i>IP address</i> &nbsp;local network interface for
	 * sending description request</li>
	 * <li><code>--serverport -p</code> <i>number</i> &nbsp;server UDP/TCP port (defaults to port 3671)</li>
	 * </ul>
	 * <li><code>sd</code> &nbsp;shortcut for {@code search --withDescription}</li>
	 * </ul>
	 * Other options:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP/TCP port (default system assigned)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--timeout -t</code> discovery/self-description response timeout in seconds</li>
	 * <li><code>--tcp</code> request TCP communication</li>
	 * <li><code>--udp</code> request UDP communication</li>
	 * </ul>
	 *
	 * @param args command line options for discovery or self-description
	 */
	public static void main(final String... args)
	{
		try {
			final Discover d = new Discover(args);
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
		catch (final KNXException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		} finally {
			onCompletion(thrown, canceled);
		}
	}

	private record JsonResult(String netif, InetSocketAddress localEndpoint, InetSocketAddress remoteEndpoint,
			Json response) implements Json {}

	private record JsonDeviceInfo(String name, IndividualAddress address, String macAddress, String multicast,
			SerialNumber sn, String knxMedium, boolean programmingMode, int installationId, int projectId) implements Json {}

	private record JsonDescriptionResponse(JsonDeviceInfo device, Map<ServiceFamily, Integer> svcFamilies, Collection<DIB> dibs)
			implements Json {
		JsonDescriptionResponse {
			dibs = dibs.stream().filter(
					dib -> dib.getDescTypeCode() != DIB.DEVICE_INFO && dib.getDescTypeCode() != DIB.SUPP_SVC_FAMILIES)
					.toList();
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
		if (options.containsKey("json"))
			System.out.println(endpointToJson(result));
		else {
			final SearchResponse sr = result.response();
			System.out.println(formatResponse(result, sr.getControlEndpoint(), sr.getDevice(), sr.getServiceFamilies(),
					sr.description()));
		}
	}

	/**
	 * Invoked by this tool immediately after receiving a description response.
	 * <p>
	 * This default implementation extracts the information and writes it to the standard output.
	 *
	 * @param result the description result containing the received description response
	 */
	protected void onDescriptionReceived(final Result<DescriptionResponse> result)
	{
		if (options.containsKey("json"))
			descriptionToJson(result);
		else
			onDescriptionReceived(result, null);
	}

	private static String endpointToJson(final Result<SearchResponse> result) {
		final SearchResponse sr = result.response();
		final var jsonDesc = new JsonDescriptionResponse(toJson(sr.getDevice()), sr.getServiceFamilies().families(),
				sr.description());

		record JsonSearchResponse(boolean v2, InetSocketAddress ctrlEndpoint, JsonDescriptionResponse description)
				implements Json {}
		final var jsonResponse = new JsonSearchResponse(sr.v2(), sr.getControlEndpoint().endpoint(), jsonDesc);
		return toJson(result, jsonResponse);
	}

	private static void descriptionToJson(final Result<DescriptionResponse> result) {
		final var dr = result.response();
		final var jsonDesc = new JsonDescriptionResponse(toJson(dr.getDevice()), dr.getServiceFamilies().families(),
				dr.getDescription());
		System.out.println(toJson(result, jsonDesc));
	}

	private static String toJson(final Result<?> result, final Json response) {
		final var jsonResult = new JsonResult(result.networkInterface().getName(), result.localEndpoint(),
				result.remoteEndpoint(), response);
		return jsonResult.toJson();
	}

	private static JsonDeviceInfo toJson(final DeviceDIB device) {
		String mcast = "";
		try {
			mcast = InetAddress.getByAddress(device.getMulticastAddress()).getHostAddress();
		}
		catch (final UnknownHostException ignore) {}
		final boolean progMode = (device.getDeviceStatus() & 0x01) != 0;
		return new JsonDeviceInfo(device.getName(), device.getAddress(), device.getMACAddressString(),
				mcast, device.serialNumber(), device.getKNXMediumString(), progMode, device.getInstallation(),
				device.getProject());
	}

	private static void onDescriptionReceived(final Result<DescriptionResponse> result, final HPAI hpai)
	{
		final DescriptionResponse dr = result.response();
		System.out.println(formatResponse(result, hpai, dr.getDevice(), dr.getServiceFamilies(), dr.getDescription()));
	}

	private static String formatResponse(final Result<?> r, final HPAI controlEp, final DeviceDIB device,
			final ServiceFamiliesDIB serviceFamilies, final Collection<DIB> description) {
		final StringBuilder sb = new StringBuilder();

		final var addr = r.localEndpoint().getAddress();
		final var localEndpoint = addr instanceof Inet6Address ? addr.toString()
				: addr.getHostAddress() + " (" + nameOf(r.networkInterface()) + ")";

		sb.append("Using ").append(localEndpoint).append(sep);
		sb.append("-".repeat(sb.length() - 1)).append(sep);
		if (device != null)
			sb.append("\"").append(device.getName()).append("\" ");
		if (controlEp != null) {
			var endpoint = controlEp.toString();
			if (serviceFamilies != null) {
				final var tcp = serviceFamilies.families().getOrDefault(ServiceFamily.Core, 0) > 1;
				if (tcp)
					endpoint = endpoint.replace("UDP", "UDP & TCP");
			}
			sb.append("endpoint ").append(endpoint);
		}

		if (device != null) {
			final String info = device.toString();
			// device name is already there
			final String withoutName = info.substring(info.indexOf(","));
			// skip SN in search responses
			final boolean search = r.response() instanceof SearchResponse;
			final String formatted = search ? withoutName.substring(0, withoutName.lastIndexOf(",")) : withoutName;
			sb.append(formatted).append(sep);
		}

		String basic = sb.toString().replaceAll(", ", sep);
		if (serviceFamilies != null)
			basic += "Supported services: " + serviceFamilies;

		final var joiner = new StringJoiner(sep);
		joiner.add(basic);

		final var desc = new ArrayList<>(description);
		desc.remove(device);
		desc.remove(serviceFamilies);

		extractDib(DIB.SecureServiceFamilies, desc).map(dib -> " ".repeat(20) + dib).ifPresent(joiner::add);
		extractDib(DIB.AdditionalDeviceInfo, desc).map(dib -> dib.toString().replace(", ", sep)).ifPresent(joiner::add);
		extractDib(DIB.TunnelingInfo, desc).map(dib -> dib.toString().replaceFirst(", ", sep)).ifPresent(joiner::add);
		desc.forEach(dib -> joiner.add(dib.toString()));
		// add empty line for visual gap with subsequent responses
		return joiner.add("").toString();
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
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled) {
			final String msg = options.containsKey("search") ? "stopped discovery" : "self description canceled";
			out.log(INFO, msg);
		}
		if (thrown != null)
			out.log(ERROR, "completed with error", thrown);
	}

	/**
	 * Starts a discovery search using the supplied options.
	 *
	 * @throws KNXException on problem during discovery
	 * @throws InterruptedException on interrupted thread
	 */
	private void search() throws KNXException, InterruptedException
	{
		final Srp[] srps = searchParameters.toArray(new Srp[0]);

		if (tcp != null) {
			final var result = tcp.search(srps).thenApply(list -> list.get(0)).thenAccept(this::onEndpointReceived);
			joinOnResult(result);
			return;
		}

		// see if we have an extended unicast search to a control endpoint
		if (options.containsKey("host")) {
			final InetAddress server = (InetAddress) options.get("host");
			final var ctrlEndpoint = new InetSocketAddress(server, (int) options.get("serverport"));
			final var result = d.search(ctrlEndpoint, srps).thenAccept(this::onEndpointReceived);
			joinOnResult(result);
			return;
		}

		final var timeout = ((Duration) options.get("timeout"));
		// start the search, using a particular network interface if supplied
		if (options.containsKey("if"))
			d.startSearch(0, (NetworkInterface) options.get("if"), (int) timeout.toSeconds(), false);
		else if (srps.length > 0) {
			try {
				final var results = d.search(srps).get();
				results.forEach(this::onEndpointReceived);
			}
			catch (final ExecutionException e) {
				if (e.getCause() instanceof KNXException)
					throw (KNXException) e.getCause();
				throw new KnxRuntimeException("extended search", e.getCause());
			}
			return;
		}
		else
			d.startSearch((int) timeout.toSeconds(), false);


		class TimestampedResponse {
			final Instant received;
			final Result<SearchResponse> result;
			boolean shown;

			TimestampedResponse(final Result<SearchResponse> response) {
				this.received = Instant.now();
				this.result = response;
			}
		}
		final var responses = new HashMap<InetSocketAddress, TimestampedResponse>();

		// wait until search finished, polling the search responses
		int processed = 0;
		final long start = System.nanoTime();
		try {
			while (d.isSearching()) {
				final var res = d.getSearchResponses();
				for (; processed < res.size(); ++processed) {
					final var result = res.get(processed);
					final var timestampedResponse = new TimestampedResponse(result);
					// always use v2 response if supported by the server, otherwise store v1
					if (result.response().v2()) {
						responses.put(result.remoteEndpoint(), timestampedResponse);
						onEndpointReceived(timestampedResponse.result);
						timestampedResponse.shown = true;
					}
					else
						responses.putIfAbsent(result.remoteEndpoint(), timestampedResponse);
				}

				final var waitForV2Response = Duration.ofMillis(200);
				final Instant notificationThreshold = Instant.now().minus(waitForV2Response);
				for (final var timestampedResponse : responses.values()) {
					final var result = timestampedResponse.result;
					if (!timestampedResponse.shown && !result.response().v2()
							&& !Duration.between(timestampedResponse.received, notificationThreshold).isNegative()) {
						onEndpointReceived(result);
						timestampedResponse.shown = true;
					}
				}

				Thread.sleep(50);
			}
		}
		finally {
			if (processed == 0) {
				final double sec = (System.nanoTime() - start) / 1_000_000_000d;
				out.log(INFO, "search stopped after {0} seconds with 0 responses",
						new DecimalFormat("0.#").format(sec));
			}
		}
	}

	private void joinOnResult(final CompletableFuture<Void> result) throws KNXException {
		try {
			result.join();
		}
		catch (final CompletionException e) {
			final InetAddress server = (InetAddress) options.get("host");
			final var ctrlEndpoint = new InetSocketAddress(server, (int) options.get("serverport"));
			if (TimeoutException.class.isAssignableFrom(e.getCause().getClass()))
				throw new KNXTimeoutException("timeout waiting for response from " + ctrlEndpoint);
			if (e.getCause() instanceof KNXException)
				throw (KNXException) e.getCause();
		}
	}

	/**
	 * Requests a self-description using the supplied options.
	 *
	 * @throws KNXException on problem requesting the description
	 * @throws InterruptedException
	 */
	private void description() throws KNXException, InterruptedException
	{
		if (tcp != null) {
			final var res = tcp.description();
			onDescriptionReceived(res);
			return;
		}
		// create socket address of server to request self-description from
		final InetSocketAddress host = new InetSocketAddress((InetAddress) options.get("host"),
				(Integer) options.get("serverport"));
		final var timeout = ((Duration) options.get("timeout"));
		// request description
		final Result<DescriptionResponse> res = d.getDescription(host, (int) timeout.toSeconds());
		onDescriptionReceived(res);
	}

	// implements search combined with description as done by ETS
	private void searchWithDescription() throws KNXException, InterruptedException
	{
		final var timeout = ((Duration) options.get("timeout"));
		final List<Result<SearchResponse>> res;
		// start the search, using a particular network interface if supplied
		if (options.containsKey("if")) {
			if (tcp != null) {
				try {
					res = tcp.search(searchParameters.toArray(Srp[]::new)).get();
				}
				catch (final ExecutionException e) {
					if (e.getCause() instanceof KNXException)
						throw (KNXException) e.getCause();
					throw new KnxRuntimeException("waiting for search response", e);
				}
			}
			else {
				d.startSearch(0, (NetworkInterface) options.get("if"), (int) timeout.toSeconds(), true);
				res = d.getSearchResponses();
			}
		}
		else {
			try {
				res = d.search(searchParameters.toArray(Srp[]::new)).get();
			}
			catch (final ExecutionException e) {
				if (e.getCause() instanceof KNXException)
					throw (KNXException) e.getCause();
				throw new KnxRuntimeException("waiting for search response", e);
			}
		}
		new HashSet<>(res).parallelStream().forEach(this::description);
	}

	private void description(final Result<SearchResponse> r)
	{
		final SearchResponse sr = r.response();
		final HPAI hpai = sr.getControlEndpoint();
		final InetSocketAddress server = hpai.nat() ? r.remoteEndpoint() : hpai.endpoint();

		try {
			if (tcp != null) {
				try {
					final var description = tcp.description();
					onDescriptionReceived(description, new HPAI(hpai.hostProtocol(), server));
				}
				catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return;
			}

			final var discoverer = reuseForDescription ? d
					: new Discoverer(r.localEndpoint().getAddress(), 0, options.containsKey("nat"), false);
			final int timeout = 2;
			final Result<DescriptionResponse> dr = discoverer.getDescription(server, timeout);
			onDescriptionReceived(dr, new HPAI(hpai.hostProtocol(), server));
		}
		catch (final KNXException e) {
			System.out.println("description failed for server " + server + " using " + r.localEndpoint().getAddress()
					+ " at " + r.networkInterface().getName() + ": " + e.getMessage());
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
		options.put("localport", 0);
		options.put("serverport", KNXnetIPConnection.DEFAULT_PORT);
		options.put("timeout", Duration.ofSeconds(3));
		options.put("mcastResponse", true);

		if (args.length == 0)
			return;

		for (final var i = List.of(args).iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("help", null);
				return;
			}
			if (Main.isOption(arg, "version", null)) {
				options.put("version", null);
				return;
			}

			if (Main.parseSecureOption(arg, i, options)) {
				if (options.containsKey("group-key"))
					throw new KNXIllegalArgumentException("secure multicast is not specified for search & description");
			}
			else if (Main.isOption(arg, "localport", null))
				options.put("localport", Integer.decode(i.next()));
			else if (Main.isOption(arg, "nat", "n"))
				options.put("nat", null);
			else if (Main.isOption(arg, "netif", "i"))
				options.put("if", getNetworkIF(i.next()));
			else if (Main.isOption(arg, "timeout", "t")) {
				final var timeout = Duration.ofSeconds(Long.parseUnsignedLong(i.next()));
				// a value of 0 means infinite timeout
				if (timeout.toMillis() > 0)
					options.put("timeout", timeout);
			}
			else if (Main.isOption(arg, "tcp", null))
				options.put("tcp", null);
			else if (Main.isOption(arg, "udp", null))
				options.put("udp", null);
			else if (isOption(arg, "unix-socket", null))
				options.put("unix-socket", null);
			else if ("search".equals(arg))
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
				searchParameters.add(Srp.withMacAddress(DataUnitBuilder.fromHex(i.next().replaceAll(":", ""))));
			else if ("describe".equals(arg)) {
				if (!i.hasNext())
					throw new KNXIllegalArgumentException("specify remote host");
				options.put("describe", null);
			}
			else if (Main.isOption(arg, "serverport", "p"))
				options.put("serverport", Integer.decode(i.next()));
			else if (isOption(arg, "json", null))
				options.put("json", null);
			else if (options.containsKey("search") || options.containsKey("describe")) {
				if (options.containsKey("unix-socket"))
					options.put("host", arg);
				else
					options.put("host", Main.parseHost(arg));
			}
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}

		if (options.containsKey("describe") && !options.containsKey("host"))
			throw new KNXIllegalArgumentException("specify remote host");
	}

	private static String nameOf(final NetworkInterface nif)
	{
		if (nif == null)
			return "default";
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
	 *
	 * @param id identifier associated with the network interface, either a network interface name,
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

	private static void showUsage() {
		final var usage = """
				Usage: %s {search | describe} [options]
				Supported commands:
				  search [<host>]            start a discovery search
				    --withDescription        query self description for each search result
				    --unicast -u             request unicast response (where multicast would be used)
				    --netif -i <interface/host name | IP address>    local multicast network interface
				    --mac <address>          extended search requesting the specified MAC address
				    --progmode               extended search requesting devices in programming mode
				  describe <host>            query self description from host
				    --netif -i <interface/host name | IP address>    local outgoing network interface
				    --serverport -p <number> server UDP/TCP port (default %d)
				  sd                         shortcut for 'search --withDescription'
				Other options:
				  --localport <number>       local UDP/TCP port (default system assigned)
				  --nat -n                   enable Network Address Translation
				  --timeout -t               discovery/description response timeout in seconds
				  --tcp                      request TCP communication
				  --udp                      request UDP communication
				  --version                  show tool/library version and exit
				  --help -h                  show this help message"""
				.formatted(tool, KNXnetIPConnection.DEFAULT_PORT);

		out(usage + "\n" + Main.printSecureOptions(false));
	}

	private static void out(final String s)
	{
		System.out.println(s);
	}
}
