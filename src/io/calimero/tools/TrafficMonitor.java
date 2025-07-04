/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2019, 2025 B. Malinowsky

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
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import io.calimero.DataUnitBuilder;
import io.calimero.FrameEvent;
import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.KNXAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.Priority;
import io.calimero.SerialNumber;
import io.calimero.cemi.CEMI;
import io.calimero.cemi.CEMILData;
import io.calimero.cemi.CEMILDataEx;
import io.calimero.datapoint.Datapoint;
import io.calimero.datapoint.DatapointMap;
import io.calimero.datapoint.DatapointModel;
import io.calimero.datapoint.StateDP;
import io.calimero.dptxlator.DPTXlator;
import io.calimero.dptxlator.TranslatorTypes;
import io.calimero.dptxlator.TranslatorTypes.MainType;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.knxnetip.LostMessageEvent;
import io.calimero.knxnetip.RoutingBusyEvent;
import io.calimero.knxnetip.servicetype.TunnelingFeature;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.LinkEvent;
import io.calimero.link.NetworkLinkListener;
import io.calimero.link.medium.KNXMediumSettings;
import io.calimero.link.medium.RFSettings;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;
import io.calimero.tools.Main.ShutdownHandler;
import io.calimero.xml.KNXMLException;
import io.calimero.xml.XmlInputFactory;
import io.calimero.xml.XmlReader;

/**
 * A tool for showing KNX network traffic &amp; link status information.
 * <p>
 * Traffic monitor is a {@link Runnable} tool implementation allowing to monitor network traffic in a KNX network. It
 * supports KNX network access using a KNXnet/IP, KNX IP, USB, FT1.2, or TP-UART connection.
 * <p>
 * When running this tool from the terminal, method <code>main</code> of this class is invoked; otherwise, use this
 * class in the context appropriate to a {@link Runnable}. In console mode, KNX network traffic data as well as status
 * information are written to <code>System.out</code>.
 * <p>
 * Note that communication will use default settings if not specified otherwise using command line options. Since these
 * settings might be system dependent (for example, the local host) and not always predictable, a user may want to
 * specify particular settings using the available options.
 *
 */
public class TrafficMonitor implements Runnable {
	private static final String tool = MethodHandles.lookup().lookupClass().getSimpleName();
	private static final Logger out = LogService.getLogger("io.calimero.tools." + tool);


	private final Map<String, Object> options = new HashMap<>();
	// contains the datapoints for which translation information is known
	private final DatapointModel<StateDP> datapoints = new DatapointMap<>();

	private KNXNetworkLink link;

	/**
	 * Creates a new instance using the supplied options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public TrafficMonitor(final String[] args) {
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
	 * Entry point for running the traffic monitor. The endpoint for KNX network access is either an IP host or port identifier for
	 * IP, USB, FT1.2 or TP-UART communication. Use the command line option <code>--help</code> (or <code>-h</code>) to show the
	 * usage of this tool.
	 * <p>
	 * Command line options are treated case-sensitive. Available options for communication:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--compact -c</code> show incoming process communication data in compact format</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
	 * </ul>
	 * <p>
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
	 * @param args command line options for traffic monitoring
	 */
	public static void main(final String... args) {
		try {
			final TrafficMonitor pc = new TrafficMonitor(args);
			final ShutdownHandler sh = new Main.ShutdownHandler().register();
			pc.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.log(ERROR, "tool options", t);
		}
	}

	@Override
	public void run() {
		Exception thrown = null;
		boolean canceled = false;
		try {
			start();
			if (options.containsKey("about"))
				return;
			loadDatapoints();
			runMonitorLoop();
		}
		catch (KNXException | IOException | RuntimeException e) {
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

	private void start() throws KNXException, InterruptedException {
		if (options.containsKey("about")) {
			((Runnable) options.get("about")).run();
			return;
		}

		link = Main.newLink(options);
		link.addLinkListener(new NetworkLinkListener(){
			@Override
			public void indication(final FrameEvent e) { onFrameEvent(e); }
			@Override
			public void confirmation(final FrameEvent e) { onFrameEvent(e); }

			@LinkEvent
			void routingBusy(final RoutingBusyEvent e) {
				outTimestamped(e.sender().getAddress().getHostAddress() + " sent " + e.get());
			}
			@LinkEvent
			void routingLostMessage(final LostMessageEvent e) {
				outTimestamped(e.getSender() + " lost " + e.getLostMessages() + " routing messages"
						+ (e.isKNXFault() ? ", KNX network fault" : ""));
			}
			@LinkEvent
			void tunnelingFeature(final TunnelingFeature feature) { outTimestamped(feature.toString()); }
		});
	}

	/**
	 * Quits traffic monitoring and closes the network link.
	 */
	public void quit() {
		if (link != null)
			link.close();
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception, <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled) {
		if (canceled)
			out.log(INFO, "traffic monitor was stopped");
		if (thrown != null)
			out.log(ERROR, "completed with error", thrown);
	}

	private String asString(final byte[] asdu, final int dptMainNumber, final String dptID) throws KNXException {
		final DPTXlator t = TranslatorTypes.createTranslator(dptMainNumber, dptID);
		t.setData(asdu);
		return t.getValue();
	}

	private void onFrameEvent(final FrameEvent e) {
		final var frame = e.getFrame();

		if (options.containsKey("json")) {
			System.out.println(toJson(frame));
			return;
		}

		final var joiner = new StringJoiner(" ");
		if (frame instanceof final CEMILData ldata) {
			final var dst = ldata.getDestination();
			final var payload = frame.getPayload();

			final boolean compact = options.containsKey("compact");
			if (compact)
				joiner.add(ldata.getSource() + "->" + dst);
			else
				joiner.add(frame.toString()).add("--");

			joiner.add(DataUnitBuilder.decode(payload, dst));
			if (payload.length > 1) {
				final byte[] asdu = DataUnitBuilder.extractASDU(payload);
				if (asdu.length > 0)
					joiner.add(HexFormat.ofDelimiter(" ").formatHex(asdu));
				final int apduSvc = DataUnitBuilder.getAPDUService(payload);

				try {
					if ((apduSvc & 0b1111111100) == 0b1111101000) {
						// group property service
						final CEMILDataEx f = (CEMILDataEx) ldata;
						final byte[] data = f.toByteArray();
						final int ctrl2 = data[3 + data[1]] & 0xff;
						if ((ctrl2 & 0x04) != 0) {
							final int eff = ctrl2 & 0x0f;
							joiner.add(compact ? "" : ":");
							joiner.add(decodeLteFrame(eff, dst, asdu));
						}
					}
					else {
						if (asdu.length > 0 && dst instanceof GroupAddress && dst.getRawAddress() != 0) {
							final Datapoint dp = datapoints.get((GroupAddress) dst);
							joiner.add(compact ? "" : ":");
							if (dp != null)
								joiner.add(asString(asdu, 0, dp.getDPT()));
							else
								joiner.add(decodeAsduByLength(asdu, payload.length == 2));
						}
					}
				}
				catch (KNXException | RuntimeException ex) {
					out.log(INFO, "error parsing group event {0} {1}", joiner, ex.toString());
				}
			}
		}
		else {
			joiner.add(frame.toString());
		}
		outTimestamped(joiner.toString());
	}

	private static String toJson(final CEMI frame) {
		if (frame instanceof final CEMILData ldata) {
			final var payload = frame.getPayload();
			String tpci = "";
			String apci = "";
			byte[] asdu = null;
			if (payload.length > 1) {
				tpci = DataUnitBuilder.decodeTPCI(DataUnitBuilder.getTPDUService(payload), ldata.getDestination());
				apci = DataUnitBuilder.decodeAPCI(DataUnitBuilder.getAPDUService(payload));
				asdu = DataUnitBuilder.extractASDU(payload);
			}
			final boolean extended = ldata instanceof CEMILDataEx;

			// ??? add boolean lengthOptimizedApdu, String decodedAsdu
			record JsonRawFrame(String svc, boolean extended, IndividualAddress src, KNXAddress dst,
					boolean repetition, int hopCount, Priority priority, boolean ack, boolean sysBcast, boolean con,
					String tpci, String apci, byte[] asdu) implements Json {}
			record JsonTrafficEvent(Instant time, JsonRawFrame frame) implements Json {}

			final var json = new JsonRawFrame(svcPrimitive(ldata.getMessageCode()), extended,
					ldata.getSource(), ldata.getDestination(), ldata.isRepetition(), ldata.getHopCount(), ldata.getPriority(),
					ldata.isAckRequested(), ldata.isSystemBroadcast(), ldata.isPositiveConfirmation(), tpci, apci,
					asdu);
			final var jsonTraffic = new JsonTrafficEvent(Instant.now(), json);
			return jsonTraffic.toJson();
		}
		else { // we shouldn't receive CEMIBusMon, CEMIDevMgmt, or CemiTData here
			out.log(DEBUG, "unsupported cEMI frame format " + frame);
			return null;
		}
	}

	static String svcPrimitive(final int msgCode) {
		return switch (msgCode) {
			case 0x2B -> "L_Busmon.ind";
			case 0x11 -> "L_Data.req";
			case 0x2E -> "L_Data.con";
			case 0x29 -> "L_Data.ind";
			case 0x10 -> "L_Raw.req";
			case 0x2D -> "L_Raw.ind";
			case 0x2F -> "L_Raw.con";
			case 0x13 -> "L_Poll_Data.req";
			case 0x25 -> "L_Poll_Data.con";
			case 0x41 -> "T_Data_Connected.req";
			case 0x89 -> "T_Data_Connected.ind";
			case 0x4A -> "T_Data_Individual.req";
			case 0x94 -> "T_Data_Individual.ind";
			default -> "0x" + Integer.toHexString(msgCode);
		};
	}

	private static String decodeLteFrame(final int extFormat, final KNXAddress dst, final byte[] asdu)
			throws KNXFormatException {
		return NetworkMonitor.decodeLteFrame(extFormat, dst, asdu);
	}

	// shows one DPT of each matching main type based on the length of the supplied ASDU
	private static String decodeAsduByLength(final byte[] asdu, final boolean optimized) {
		final var joiner = new StringJoiner(", ");
		final List<MainType> typesBySize = TranslatorTypes.getMainTypesBySize(optimized ? 0 : asdu.length);
		for (final var mainType : typesBySize) {
			try {
				final String dptid = mainType.getSubTypes().keySet().iterator().next();
				final DPTXlator t = TranslatorTypes.createTranslator(mainType.mainNumber(), dptid);
				t.setData(asdu);
				joiner.add(t.getValue() + " [" + dptid + "]");
			}
			catch (final KNXException | KNXIllegalArgumentException ignore) {}
		}
		return joiner.toString();
	}

	private void runMonitorLoop() throws IOException, InterruptedException {
		final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
		while (true) {
			while (!in.ready() && link.isOpen())
				Thread.sleep(250);
			if (!link.isOpen())
				break;
			final String line = in.readLine();
			if (line == null)
				continue;
			final String[] s = line.trim().split(" +");
			if (s.length == 1 && "exit".equalsIgnoreCase(s[0]))
				return;
			if (s.length == 1 && ("?".equals(s[0]) || "help".equals(s[0])))
				out(listCommands());
			if (s.length > 1) {
				final String cmd = s[0];
				try {
					try {
						final var ga = new GroupAddress(cmd);
						final var dpt = Main.fromDptName(s[1]);
						final var dp = new StateDP(ga, "tmp", 0, dpt);
						datapoints.remove(dp);
						datapoints.add(dp);
					}
					catch (final KNXFormatException e) {
						out("unknown command '" + cmd + "'");
					}
				}
				catch (final RuntimeException e) {
					out.log(INFO, "[{0}] {1}", line, e.toString());
				}
			}
		}
	}

	private void loadDatapoints() {
		final var datapointsFile = (String) options.get("datapoints");
		if (datapointsFile != null && Files.isRegularFile(Path.of(datapointsFile))) {
			try (XmlReader r = XmlInputFactory.newInstance().createXMLReader(datapointsFile)) {
				datapoints.load(r);
			}
			catch (final KNXMLException e) {
				out.log(INFO, "failed to load datapoint information from {0}: {1}", datapointsFile, e.getMessage());
			}
		}
	}

	private void parseOptions(final String[] args) {
		if (args.length == 0) {
			options.put("about", (Runnable) TrafficMonitor::showToolInfo);
			return;
		}

		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", new TPSettings());

		for (final var i = new Main.PeekingIterator<>(List.of(args).iterator()); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) TrafficMonitor::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "datapoints", null))
				options.put("datapoints", i.next());
			else if (Main.isOption(arg, "compact", "c"))
				options.put("compact", null);
			else if (Main.isOption(arg, "timeout", "t"))
				options.put("timeout", Duration.ofSeconds(Integer.decode(i.next())));
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		// we allow a default usb config where the first found knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

		if (!options.containsKey("host") || (options.containsKey("ft12") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");

		setDomainAddress(options);
		setRfDeviceSettings();
	}

	private void setRfDeviceSettings() {
		final var sn = (SerialNumber) options.get("sn");
		if (sn == null)
			return;
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (medium.getMedium() != KNXMediumSettings.MEDIUM_RF)
			throw new KNXIllegalArgumentException(
					medium.getMediumString() + " networks don't use serial number, use --medium to specify KNX RF");
		final RFSettings rf = ((RFSettings) medium);
		final IndividualAddress device = (IndividualAddress) options.getOrDefault("knx-address", rf.getDeviceAddress());

		options.put("medium", new RFSettings(device, rf.getDomainAddress(), sn, rf.isUnidirectional()));
	}

	private static void showToolInfo() {
		out(tool + " - KNX traffic monitor");
		Main.showVersion();
		out("Type --help for help message");
	}

	private static void showUsage() {
		final var joiner = new StringJoiner(System.lineSeparator());
		joiner.add("Usage: " + tool + " [options] <host|port> <command>");
		joiner.add(Main.printCommonOptions());
		joiner.add("  --compact -c               show incoming indications in compact format");
		joiner.add(Main.printSecureOptions());
		joiner.add(listCommands());
		out(joiner);
	}

	private static String listCommands() {
		return "Available commands for filtering traffic: none";
	}

	private static void outTimestamped(final String s) {
		out(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " " + String.join("", s));
	}

	private static void out(final Object s) {
		System.out.println(s);
	}
}
