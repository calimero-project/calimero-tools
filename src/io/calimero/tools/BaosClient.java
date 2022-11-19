/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2019, 2022 B. Malinowsky

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.calimero.CloseEvent;
import io.calimero.DataUnitBuilder;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.KNXTimeoutException;
import io.calimero.KnxRuntimeException;
import io.calimero.baos.BaosLink;
import io.calimero.baos.BaosLinkAdapter;
import io.calimero.baos.BaosService;
import io.calimero.baos.BaosService.DatapointCommand;
import io.calimero.baos.BaosService.ErrorCode;
import io.calimero.baos.BaosService.HistoryCommand;
import io.calimero.baos.BaosService.Item;
import io.calimero.baos.BaosService.Property;
import io.calimero.baos.BaosService.Timer;
import io.calimero.baos.BaosService.ValueFilter;
import io.calimero.baos.ip.BaosLinkIp;
import io.calimero.dptxlator.DPT;
import io.calimero.dptxlator.DPTXlator;
import io.calimero.dptxlator.TranslatorTypes;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.LinkEvent;
import io.calimero.link.NetworkLinkListener;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;
import io.calimero.tools.Main.ShutdownHandler;

/**
 * A tool for Calimero providing KNX BAOS (Bus Access and Object Server) communication.
 * It supports access to a BAOS capable device using a KNXnet/IP, USB, or FT1.2 connection.
 */
public class BaosClient implements Runnable
{
	private static final String tool = MethodHandles.lookup().lookupClass().getSimpleName();
	private static final Logger out = LogService.getLogger(MethodHandles.lookup().lookupClass());

	private static final Duration defaultTimeout = Duration.ofSeconds(2);

	// specifies parameters to use for the network link
	private final Map<String, Object> options = new HashMap<>();

	private BaosLink link;
	private final BlockingQueue<BaosService> rcvQueue = new LinkedBlockingQueue<>();

	private final Map<Integer, DPT> dpIdToDpt = new ConcurrentHashMap<>();

	private volatile boolean closed;

	/**
	 * Creates a new BaosClient instance using the supplied options.
	 * Mandatory arguments are either an IP host, USB, or FT1.2 port identifier, depending on the type of
	 * connection to the KNX network. See {@link #main(String[])} for the list of options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public BaosClient(final String[] args)
	{
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
	 * Entry point for running BaosClient. The endpoint for KNX network access is either an IP host or port identifier
	 * for IP, USB, or FT1.2. Use the command line option <code>--help</code> (or <code>-h</code>) to show the
	 * usage of this tool.
	 * <p>
	 * Command line options are treated case sensitive. Available options for communication:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tcp</code> use TCP/IP communication (default)</li>
	 * <li><code>--udp</code> use UDP/IP communication</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 12004)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * </ul>
	 * Available commands for BAOS communication:
	 * <ul>
	 * <li>get {property|value|timer|history|description}: get a property, value, timer, history, or description</li>
	 * <li>set {property|value|timer|history}: set a property, value, timer, or history"</li>
	 * </ul>
	 * For common datapoint types (DPTs) the following name aliases can be used instead of the general DPT number
	 * string:
	 * <ul>
	 * <li><code>switch</code> for DPT 1.001, with values <code>off</code>, <code>on</code></li>
	 * <li><code>bool</code> for DPT 1.002, with values <code>false</code>, <code>true</code></li>
	 * <li><code>dimmer</code> for DPT 3.007, with values <code>decrease 0..7</code>, <code>increase 0..7</code></li>
	 * <li><code>blinds</code> for DPT 3.008, with values <code>up 0..7</code>, <code>down 0..7</code></li>
	 * <li><code>percent</code> for DPT 5.001, with values <code>0..100</code></li>
	 * <li><code>%</code> for DPT 5.001, with values <code>0..100</code></li>
	 * <li><code>angle</code> for DPT 5.003, with values <code>0..360</code></li>
	 * <li><code>ucount</code> for DPT 5.010, with values <code>0..255</code></li>
	 * <li><code>temp</code> for DPT 9.001, with values <code>-273..+670760</code></li>
	 * <li><code>float</code> or <code>float2</code> for DPT 9.002</li>
	 * <li><code>float4</code> for DPT 14.005</li>
	 * <li><code>int</code> for DPT 13.001</li>
	 * <li><code>string</code> for DPT 16.001</li>
	 * </ul>
	 *
	 * @param args command line options for BAOS communication
	 */
	public static void main(final String[] args)
	{
		try {
			final BaosClient baos = new BaosClient(args);
			final var sh = new ShutdownHandler().register();
			baos.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.log(ERROR, "tool options", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			start();
			if (options.containsKey("about"))
				return;
			if (options.containsKey("repl"))
				runRepl();
			else
				issueBaosService();
		}
		catch (final KNXException | IOException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		finally {
			quit();
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Starts communication with the BAOS device. This method immediately returns when the communication link is
	 * established. Call {@link #quit()} to quit communication.
	 *
	 * @throws KNXException on problems creating network link
	 * @throws InterruptedException on interrupted thread
	 */
	public void start() throws KNXException, InterruptedException
	{
		if (options.containsKey("about")) {
			((Runnable) options.get("about")).run();
			return;
		}
		link = newBaosLink();
		link.addLinkListener(new NetworkLinkListener() {
			@LinkEvent
			void baosService(final BaosService svc) { rcvQueue.offer(svc); onBaosEvent(svc); }

			@Override
			public void linkClosed(final CloseEvent e) {
				quit();
				onCompletion(null, e.getInitiator() != CloseEvent.USER_REQUEST);
			}
		});
	}

	/**
	 * Quits BAOS communication and closes the network link.
	 */
	public void quit() {
		closed = true;
		final KNXNetworkLink lnk = link;
		if (lnk != null)
			lnk.close();
	}

	public static String manufacturer(final int mf) {
		return DeviceInfo.manufacturer(mf);
	}

	protected void executeBaosCommand(final String cmd) throws KNXException, InterruptedException {
		issueBaosService(cmd.split(" +"));
	}

	/**
	 * Called by this tool on receiving a BAOS communication event.
	 *
	 * @param svc the baos service
	 */
	protected void onBaosEvent(final BaosService svc) {
		out(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " " + svc);
		if (svc.error() != ErrorCode.NoError)
			return;

		final int subService = svc.subService();
		if (subService == BaosService.GetDatapointValue || subService == BaosService.DatapointValueIndication
				|| subService == BaosService.GetDatapointHistory || subService == BaosService.GetDatapointHistoryState) {
			try {
				for (final var item : svc.items()) {
					if (subService == BaosService.GetDatapointHistory) {
						final var instant = item.info();
						final var value = translate(item.id(), item.data());
						out(instant + " DP #" + item.id() + " = " + value);
					}
					else if (subService == BaosService.GetDatapointHistoryState) {
						final String state = datapointHistoryState((int) item.info());
						final int entries = ByteBuffer.wrap(item.data()).getInt() & 0xffff_ffff;
						out("DP #" + item.id() + " history " + state + ", " + entries + " entries");
					}
					else {
						final var value = translate(item.id(), item.data());
						out("DP #" + item.id() + " (" + item.info() + ") = " + value);
					}
				}
			}
			catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception, <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.log(INFO, "BAOS communication was stopped");
		if (thrown != null)
			out.log(ERROR, "completed with error", thrown);
	}

	private BaosLink newBaosLink() throws KNXException, InterruptedException {
		final boolean ft12 = options.containsKey("ft12");
		final boolean usb = options.containsKey("usb");
		if (ft12 || usb)
			return BaosLinkAdapter.asBaosLink(Main.newLink(options));

		final var local = Main.createLocalSocket(options);
		final var addr = Main.parseHost((String) options.get("host"));
		final int port = (Integer) options.get("port");
		final var remote = new InetSocketAddress(addr, port);

		if (options.containsKey("udp"))
			return BaosLinkIp.newUdpLink(local, remote);

		final var connection = Main.tcpConnection(local, remote);
		return BaosLinkIp.newTcpLink(connection);
	}

	private String translate(final int dpId, final byte[] data) throws InterruptedException {
		try {
			final var xlator = translatorFor(dpId);
			xlator.setData(data);
			return xlator.getValue();
		}
		catch (final KNXException e) {
			return DataUnitBuilder.toHex(data, " ");
		}
	}

	private DPTXlator translatorFor(final int dpId) throws KNXException, InterruptedException {
		final var dpt = dpIdToDpt.get(dpId);
		if (dpt != null)
			return TranslatorTypes.createTranslator(dpt);

		final var desc = datapointDescription(dpId);
		if (desc.items().isEmpty())
			throw new KNXFormatException("no datapoint description for DP #" + dpId);
		final byte[] data = desc.items().get(0).data();
		final int mainNumber = data[2] & 0xff;
		final var xlator = TranslatorTypes.createTranslator(mainNumber, 0);
		dpIdToDpt.put(dpId, xlator.getType());
		return xlator;
	}

	private BaosService datapointDescription(final int dpId) throws KNXException, InterruptedException {
		final var desc = BaosService.getDatapointDescription(dpId, 1);
		link.send(desc);
		return waitForResponse(desc.subService());
	}

	private BaosService waitForResponse(final int subService) throws InterruptedException {
		final long start = System.nanoTime();
		final long end = start + ((Duration) options.get("timeout")).toNanos();

		long remaining = end - start;
		while (remaining > 0) {
			final var svc = rcvQueue.poll(remaining, TimeUnit.NANOSECONDS);
			if (svc != null && svc.subService() == subService)
				return svc;
			remaining = end - System.nanoTime();
		}
		return null;
	}

	private BaosService parse(final String[] args) throws KNXException, InterruptedException {
		if (args.length < 2)
			throw new KNXIllegalArgumentException("command too short: " + List.of(args));
		switch (args[0]) {
		case "get": return get(args);
		case "set": return set(args);
		default: throw new KNXIllegalArgumentException("unknown command " + args[0]);
		}
	}

	private static BaosService get(final String[] args) {
		final String svc = args[1];
		final int id = unsigned(args[2]);
		final int items = args.length > 3 ? unsigned(args[3]) : 1;

		switch (svc) {
			case "property": return BaosService.getServerItem(Property.of(id), items);
			case "value":
				final var filter = args.length > 4 ? valueFilter(args[4]) : ValueFilter.All;
				return BaosService.getDatapointValue(id, items, filter);
			case "timer": return BaosService.getTimer(id, items);
			case "desc":
			case "description": return BaosService.getDatapointDescription(id, items);
			case "history":
				// NYI what format to expect
				final var start = Instant.EPOCH; //Instant.parse(args[4]);
				final var end = Instant.now(); //Instant.parse(args[5]);
				return BaosService.getDatapointHistory(id, items, start, end);
			case "hs":
				return BaosService.getDatapointHistoryState(id, items);
			default: throw new KNXIllegalArgumentException("unsupported BAOS service '" + svc + "'");
		}
	}

	private BaosService set(final String[] args) throws KNXException, InterruptedException {
		final String svc = args[1];
		final int id = unsigned(args[2]);

		switch (svc) {
			case "property": return BaosService.setServerItem(Item.property(Property.of(id), DataUnitBuilder.fromHex(args[3])));
			case "value": return BaosService.setDatapointValue(
					parseDatapointValue(id, Arrays.copyOfRange(args, 3, args.length)));
			case "timer": return setTimer(id, args);
			case "history":
				final String cmd = args[4] + (args.length > 5 ? args[5] : "");
				return BaosService.setDatapointHistoryCommand(id, unsigned(args[3]), HistoryCommand.of(cmd));
			default: throw new KNXIllegalArgumentException("unsupported BAOS service '" + svc + "'");
		}
	}

	private static ValueFilter valueFilter(final String arg) {
		switch (arg) {
		case "all": return ValueFilter.All;
		case "valid": return ValueFilter.ValidOnly;
		case "updated": return ValueFilter.UpdatedOnly;
		default: throw new KNXIllegalArgumentException("unknown value filter " + arg);
		}
	}

	// format: <datapoint id> <datapoint cmd> [value]
	private Item<DatapointCommand> parseDatapointValue(final int dpId, final String[] args)
			throws KNXException, InterruptedException {
		final var cmd = DatapointCommand.of(unsigned(args[0]));
		switch (cmd) {
		case NoCommand:
		case SendValueOnBus:
		case ReadValueViaBus:
		case ClearTransmissionState:
			return Item.datapoint(dpId, cmd, new byte[0]);

		case SetValue:
		case SetValueAndSendOnBus:
			final DPTXlator xlator;
			if (isDpt(args[1])) {
				final var dptId = fromDptName(args[1]);
				xlator = TranslatorTypes.createTranslator(dptId);
				dpIdToDpt.put(dpId, xlator.getType());
				xlator.setValue(args[2]);
			}
			else {
				xlator = translatorFor(dpId);
				xlator.setValue(args[1]);
			}
			return Item.datapoint(dpId, cmd, xlator.getData());
		default: throw new IllegalStateException(cmd.toString());
		}
	}

	private BaosService setTimer(final int id, final String[] args) throws KNXException, InterruptedException {
		final String action = args[3];
		switch (action) {
			case "delete": return BaosService.setTimer(Timer.delete(id));
			case "oneshot": {
				final var dateTime = parseDateTime(args[4]);
				final int dpId = unsigned(args[5]);
				final var valueItem = parseDatapointValue(dpId, Arrays.copyOfRange(args, 6, args.length));
				final var job = timerJobSetValue(valueItem);
				final var description = ""; // NYI
				return BaosService.setTimer(Timer.oneShot(id, dateTime, job, description));
			}
			case "interval": {
				final var start = parseDateTime(args[4]);
				final var end = parseDateTime(args[5]);
				final var interval = Duration.parse(args[6]);
				final int dpId = unsigned(args[7]);
				final var valueItem = parseDatapointValue(dpId, Arrays.copyOfRange(args, 8, args.length));
				final var job = timerJobSetValue(valueItem);
				final var description = ""; // NYI
				return BaosService.setTimer(Timer.interval(id, start, end, interval, job, description));
			}
			default: throw new KNXIllegalArgumentException("unsupported timer action '" + action + "'");
		}
	}

	private static byte[] timerJobSetValue(final Item<DatapointCommand> value) {
		final byte[] data = value.data();
		return ByteBuffer.allocate(2 + 2 + data.length).putShort((short) value.id()).put((byte) value.info().ordinal())
				.put((byte) data.length).put(data).array();
	}

	private static String datapointHistoryState(final int state) {
		switch (state) {
		case 0: return "inactive";
		case 1: return "available";
		case 2: return "active";
		case 3: return "active available";
		default: return state + " (unknown)";
		}
	}

	private static String fromDptName(final String id)
	{
		if ("switch".equals(id))
			return "1.001";
		if ("bool".equals(id))
			return "1.002";
		if ("dimmer".equals(id))
			return "3.007";
		if ("blinds".equals(id))
			return "3.008";
		if ("string".equals(id))
			return "16.001";
		if ("temp".equals(id))
			return "9.001";
		if ("float".equals(id))
			return "9.002";
		if ("float2".equals(id))
			return "9.002";
		if ("float4".equals(id))
			return "14.005";
		if ("ucount".equals(id))
			return "5.010";
		if ("int".equals(id))
			return "13.001";
		if ("angle".equals(id))
			return "5.003";
		if ("percent".equals(id))
			return "5.001";
		if ("%".equals(id))
			return "5.001";
		if (!"-".equals(id) && !Character.isDigit(id.charAt(0)))
			throw new KnxRuntimeException("unrecognized DPT '" + id + "'");
		return id;
	}

	private void issueBaosService() throws KNXException, InterruptedException
	{
		if (!options.containsKey("cmd"))
			return;
		issueBaosService((String[]) options.get("cmd"));
	}

	private void issueBaosService(final String[] args) throws KNXException, InterruptedException {
		final var svc = parse(args);
		out(svc);
		link.send(svc);
		waitForResponse(svc.subService());
	}

	private void runRepl() throws IOException, KNXException, InterruptedException {
		final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
		while (true) {
			while (!in.ready() && !closed)
				Thread.sleep(250);
			if (closed)
				break;
			final String line = in.readLine();
			if (line == null)
				continue;
			final String[] s = line.trim().split(" +");
			if (s.length == 1 && "exit".equalsIgnoreCase(s[0]))
				return;
			if (s.length == 1 && ("?".equals(s[0]) || "help".equals(s[0])))
				out(listCommandsAndDptAliases(new StringJoiner(System.lineSeparator()), false));
			if (s.length == 1 && ("properties".equals(s[0])))
				out(listSupportedProperties());
			if (s.length == 1 && ("commands".equals(s[0])))
				out(listSupportedDpCommands());

			if (s.length > 1) {
				final String cmd = s[0];
				final boolean get = cmd.equals("get");
				final boolean set = cmd.equals("set");
				try {
					if (get || set)
						issueBaosService(s);
					else
						out("unknown command '" + cmd + "'");
				}
				catch (final KNXTimeoutException e) {
					out(e.getMessage());
				}
				catch (KNXException | RuntimeException e) {
					out.log(ERROR, "[{0}]", line, e);
				}
			}
		}
	}

	private void parseOptions(final String[] args)
	{
		if (args.length == 0) {
			options.put("about", (Runnable) BaosClient::showToolInfo);
			return;
		}

		// add defaults
		options.put("port", 12004);
		options.put("medium", new TPSettings());
		options.put("timeout", defaultTimeout);

		for (final var i = new Main.PeekingIterator<>(List.of(args).iterator()); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) BaosClient::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (arg.equals("get")) {
				if (!i.hasNext())
					break;
				options.put("cmd", remainingOptions(arg, i));
			}
			else if (arg.equals("set")) {
				if (!i.hasNext())
					break;
				options.put("cmd", remainingOptions(arg, i));
			}
			else if (Main.isOption(arg, "timeout", "t"))
				options.put("timeout", Duration.ofSeconds(Integer.decode(i.next())));
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		// we allow a default usb config where the first knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

		if (!options.containsKey("host") || (options.containsKey("ft12") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		if (!options.containsKey("cmd"))
			options.put("repl", null);
	}

	private String[] remainingOptions(final String arg, final Iterator<String> i) {
		final var list = new ArrayList<String>();
		list.add(arg);
		i.forEachRemaining(list::add);
		return list.toArray(String[]::new);
	}

	private static final DateTimeFormatter isoVariants = DateTimeFormatter
			.ofPattern("[yyyyMMdd][yyyy-MM-dd]['T'[HHmmss][HH:mm:ss][HHmm][HH:mm]][z][XXXXX][XXXX]['['VV']']");

	static ZonedDateTime parseDateTime(final CharSequence dateTime) {
	    final var temporalAccessor = isoVariants.parseBest(dateTime, ZonedDateTime::from, LocalDateTime::from, LocalDate::from);
	    if (temporalAccessor instanceof ZonedDateTime)
			return ((ZonedDateTime) temporalAccessor);
	    if (temporalAccessor instanceof LocalDateTime)
			return ((LocalDateTime) temporalAccessor).atZone(ZoneId.systemDefault());
	    return ((LocalDate) temporalAccessor).atStartOfDay(ZoneId.systemDefault());
	}

	private static boolean isDpt(final String s) {
		if (s.startsWith("-"))
			return false;
		final var id = fromDptName(s);
		final var regex = "[0-9][0-9]*\\.[0-9][0-9][0-9]";
		return Pattern.matches(regex, id);
	}

	private static void showToolInfo() {
		out(tool + " - KNX BAOS communication");
		Main.showVersion();
		out("Type --help for help message");
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(System.lineSeparator());
		joiner.add("Usage: " + tool + " [options] <host|port> <command>");
		Main.printCommonOptions(joiner);
		listCommandsAndDptAliases(joiner, true);
		out(joiner);
	}

	private static StringJoiner listCommandsAndDptAliases(final StringJoiner joiner, final boolean showMonitor) {
		joiner.add("Supported BAOS commands:");
		joiner.add("  get {property|value|timer|history|description}  get a property, value, timer, history, or description");
		joiner.add("  set {property|value|timer|history}              set a property, value, timer, or history");
		joiner.add("");
		joiner.add("get property <id> [<items>]");
		joiner.add("set property <id> <hex value>");
		joiner.add("get description <id> [<items>]");
		joiner.add("get value <id> [<items> [all|valid|updated]]");
		joiner.add("set value <id> <cmd> [value]");
		joiner.add("get history <id> [items]");
		joiner.add("set history <id> <items> <clear [start] | start | stop [clear]>");
		joiner.add("get timer <id> [<items>]");
		joiner.add("set timer <id> delete");
		joiner.add("set timer <id> oneshot <date/time> <dpId> <cmd> [<value>]");
		joiner.add("set timer <id> interval <start date/time> <end date/time> ... <dpId> [<value>]");
		return joiner;
	}

	private static StringJoiner listSupportedProperties() {
		final var joiner = new StringJoiner(System.lineSeparator());
		for (final var property : BaosService.Property.values())
			if (property.id() != 0)
				joiner.add(String.format("%2d = %s", property.id(), property));
		return joiner;
	}

	private static StringJoiner listSupportedDpCommands() {
		final var joiner = new StringJoiner(System.lineSeparator());
		for (final var cmd : BaosService.DatapointCommand.values())
			joiner.add(String.format("%2d = %s", cmd.ordinal(), cmd));
		return joiner;
	}

	private static void out(final Object s) {
		System.out.println(s);
	}

	private static int unsigned(final String s) {
		return Integer.parseUnsignedInt(s);
	}
}
