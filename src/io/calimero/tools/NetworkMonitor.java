/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2022 B. Malinowsky

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

import static io.calimero.tools.Main.tcpConnection;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.lang.System.Logger;
import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import io.calimero.CloseEvent;
import io.calimero.DataUnitBuilder;
import io.calimero.FrameEvent;
import io.calimero.GroupAddress;
import io.calimero.KNXAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.LteHeeTag;
import io.calimero.cemi.CEMIBusMon;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXNetworkMonitor;
import io.calimero.link.KNXNetworkMonitorFT12;
import io.calimero.link.KNXNetworkMonitorIP;
import io.calimero.link.KNXNetworkMonitorTpuart;
import io.calimero.link.KNXNetworkMonitorUsb;
import io.calimero.link.LinkListener;
import io.calimero.link.MonitorFrameEvent;
import io.calimero.link.medium.KNXMediumSettings;
import io.calimero.link.medium.RFLData;
import io.calimero.link.medium.RFLData.Tpci;
import io.calimero.link.medium.RawFrame;
import io.calimero.link.medium.RawFrameBase;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;

/**
 * A tool for Calimero allowing monitoring of KNX network messages.
 * <p>
 * NetworkMonitor is a {@link Runnable} tool implementation allowing a user to track KNX network
 * messages in a KNX network. It provides monitoring access using a KNXnet/IP, KNX IP, USB, FT1.2,
 * or TP-UART connection. NetworkMonitor shows the necessary interaction with the core library API
 * for this particular task.<br>
 * Note that by default the network monitor will run with default settings, if not specified
 * otherwise using command line options. Since these settings might be system dependent (for example
 * the local host) and not always predictable, a user may want to specify particular settings using
 * the available tool options.
 * <p>
 * The main part of this tool implementation interacts with the type {@link KNXNetworkMonitor},
 * which offers monitoring access to a KNX network. All monitoring output, as well as occurring
 * problems are written to either <code>System.out
 * </code> (console mode), or the log writer supplied by the user.
 * <p>
 * To use the network monitor, invoke {@link NetworkMonitor#main(String[])}, or create a new
 * instance with {@link NetworkMonitor#NetworkMonitor(String[])}, and invoke
 * {@link NetworkMonitor#start()} or {@link NetworkMonitor#run()} on that instance. When running
 * this tool from the console, the <code>main</code> method of this class is executed, otherwise use
 * it in the context appropriate to a {@link Runnable}.
 * <p>
 * To quit a monitor running on a console, use a user interrupt for termination, e.g.,
 * <code>^C</code>.
 *
 * @author B. Malinowsky
 */
public class NetworkMonitor implements Runnable
{
	private static final String tool = "NetworkMonitor";
	private static final String sep = System.getProperty("line.separator");

	private static Logger out = LogService.getLogger("io.calimero.tools");

	private final Map<String, Object> options = new HashMap<>();
	private KNXNetworkMonitor m;

	private final LinkListener l = new LinkListener() {
		@Override
		public void indication(final FrameEvent e)
		{
			try {
				NetworkMonitor.this.onIndication(e);
			}
			catch (final RuntimeException rte) {
				out.log(WARNING, "on indication", rte);
			}
		}
		@Override
		public void linkClosed(final CloseEvent e)
		{
			out.log(INFO, "network monitor closed (" + e.getReason() + ")");
			synchronized (NetworkMonitor.this) {
				NetworkMonitor.this.notify();
			}
		}
	};

	/**
	 * Creates a new NetworkMonitor instance using the supplied options.
	 * <p>
	 * See {@link #main(String[])} for a list of options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public NetworkMonitor(final String[] args)
	{
		try {
			// read the command line options
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
	 * Entry point for running the NetworkMonitor.
	 * <p>
	 * An IP host or port identifier has to be supplied, specifying the endpoint for the KNX network
	 * access.<br>
	 * To show the usage message of this tool on the console, supply the command line option --help
	 * (or -h).<br>
	 * Command line options are treated case sensitive. Available options for network monitoring:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--compact -c</code> show incoming busmonitor indications in compact format
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
	 *
	 * @param args command line options for network monitoring
	 */
	public static void main(final String[] args)
	{
		try {
			// if listener is null, we create our default one
			final NetworkMonitor m = new NetworkMonitor(args);
			final ShutdownHandler sh = m.new ShutdownHandler().register();
			m.run();
			sh.unregister();
		}
		catch (final KNXIllegalArgumentException e) {
			out.log(ERROR, "parsing options", e);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			start();

			// If we have a user supplied link listener, we would not wake up on
			// closed link events caused by the underlying network monitor instance or
			// the server side endpoint. Therefore, we could use our own listener to
			// receive that notification, or simply wake up from time to time in the
			// wait loop and check monitor status. We do the latter.
			/*m.addMonitorListener(new LinkListener()
			{
				public void indication(final FrameEvent e)
				{}

				public void linkClosed(final CloseEvent e)
				{
					synchronized (NetworkMonitor.this) {
						NetworkMonitor.this.notify();
					}
				}
			});*/
			// just wait for the network monitor to quit
			synchronized (this) {
				while (m != null && m.isOpen())
					wait(500);
			}
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		catch (final KNXException | RuntimeException e) {
			thrown = e;
		}
		finally {
			quit();
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Starts the network monitor.
	 * <p>
	 * This method returns after the network monitor was started.
	 *
	 * @throws KNXException on problems creating or connecting the monitor
	 * @throws InterruptedException on interrupted thread
	 */
	public void start() throws KNXException, InterruptedException
	{
		if (options.isEmpty()) {
			out(tool + " - Monitor a KNX network (passive busmonitor mode)");
			Main.showVersion();
			out("Type --help for help message");
			return;
		}
		if (options.containsKey("about")) {
			((Runnable) options.get("about")).run();
			return;
		}

		m = createMonitor();

		// on console we want to have all possible information, so enable
		// decoding of a received raw frame by the monitor link
		m.setDecodeRawFrames(true);
		// listen to monitor link events
		m.addMonitorListener(l);
	}

	/**
	 * Quits a running network monitor, otherwise returns immediately.
	 */
	public void quit()
	{
		if (m != null && m.isOpen()) {
			m.close();
			synchronized (this) {
				notifyAll();
			}
		}
	}

	/**
	 * Called by this tool on receiving a monitor indication frame.
	 * <p>
	 *
	 * @param e the frame event
	 */
	protected void onIndication(final FrameEvent e)
	{
		final StringBuilder sb = new StringBuilder();
		final CEMIBusMon frame = (CEMIBusMon) e.getFrame();
		final boolean compact = options.containsKey("compact");
		if (compact) {
			sb.append("Seq ").append(frame.getSequenceNumber());
		}
		else
			sb.append(frame);

		// since we specified decoding of raw frames during monitor initialization,
		// we can get the decoded raw frame here
		// but note, that on decoding error null is returned
		final RawFrame raw = ((MonitorFrameEvent) e).getRawFrame();
		if (raw != null) {
			sb.append(compact ? " " : " = ");
			sb.append(raw.toString());
			if (raw instanceof RawFrameBase) {
				final RawFrameBase f = (RawFrameBase) raw;
				sb.append(": ").append(DataUnitBuilder.decode(f.getTPDU(), f.getDestination()));
				sb.append(" ").append(
						DataUnitBuilder.toHex(DataUnitBuilder.extractASDU(f.getTPDU()), " "));
			}
			else if (raw instanceof RFLData) {
				final RFLData rf = (RFLData) raw;
				try {
					sb.append(": ");
					final String bibat = decodeBibat(rf);
					if (!bibat.isEmpty())
						sb.append(bibat);
					else {
						sb.append(DataUnitBuilder.decode(rf.getTpdu(), rf.getDestination()));
						sb.append(" ").append(decodeLteFrame(rf));
					}
				}
				catch (final Exception ex) {
					out.log(ERROR, "decoding RF frame", ex);
				}
			}
		}
		System.out.println(LocalTime.now() + " " + sb.toString());
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
			out.log(INFO, tool + " stopped");
		if (thrown != null)
			out.log(ERROR, thrown.getMessage() != null ? thrown.getMessage() : thrown.getClass().getName());
	}

	/**
	 * Creates the KNX network monitor link to access the network specified in <code>options</code>.
	 *
	 * @return the KNX network monitor link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkMonitor createMonitor() throws KNXException, InterruptedException
	{
		final String host = (String) options.get("host");
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		// check for FT1.2 monitor link
		if (options.containsKey("ft12"))
			return new KNXNetworkMonitorFT12(host, medium);

		if (options.containsKey("ft12-cemi"))
			return KNXNetworkMonitorFT12.newCemiMonitor(host, medium);

		if (options.containsKey("usb")) {
			// create USB network monitor
			return new KNXNetworkMonitorUsb(host, medium);
		}
		if (options.containsKey("tpuart")) {
			// create TP-UART busmonitor
			return new KNXNetworkMonitorTpuart(host, true);
		}
		// create local and remote socket address for monitor link
		final InetSocketAddress local = Main.createLocalSocket(options);
		final InetSocketAddress remote = new InetSocketAddress(Main.parseHost(host), (Integer) options.get("port"));
		// create the monitor link, based on the KNXnet/IP protocol
		// specify whether network address translation shall be used,
		// and tell the physical medium of the KNX network
		final boolean nat = options.containsKey("nat");
		if (options.containsKey("user")) {
			final byte[] devAuth = (byte[]) options.getOrDefault("device-key", new byte[0]);
			final byte[] userKey = (byte[]) options.getOrDefault("user-key", new byte[0]);
			final int user = (int) options.getOrDefault("user", 0);

			if (options.containsKey("udp"))
				return KNXNetworkMonitorIP.newSecureMonitorLink(local, remote, nat, devAuth, user, userKey, medium);

			final var session = tcpConnection(local, remote).newSecureSession(user, userKey, devAuth);
			return KNXNetworkMonitorIP.newSecureMonitorLink(session, medium);
		}
		if (options.containsKey("tcp")) {
			final var c = tcpConnection(local, remote);
			return KNXNetworkMonitorIP.newMonitorLink(c, medium);
		}
		return new KNXNetworkMonitorIP(local, remote, nat, medium);
	}

	/**
	 * Reads all options in the specified array, and puts relevant options into the supplied options
	 * map.
	 * <p>
	 * On options not relevant for doing network monitoring (like <code>help</code>), this method
	 * will take appropriate action (like showing usage information). On occurrence of such an
	 * option, other options will be ignored. On unknown options, a KNXIllegalArgumentException is
	 * thrown.
	 *
	 * @param args array with command line options
	 */
	private void parseOptions(final String[] args)
	{
		if (args.length == 0)
			return;

		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", new TPSettings());

		for (final var i = List.of(args).iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) NetworkMonitor::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "compact", "c"))
				options.put("compact", null);
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (!options.containsKey("host") || (options.containsKey("ft12") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		Main.setDomainAddress(options);
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port>");
		Main.printCommonOptions(joiner);
		joiner.add("  --compact -c               show incoming busmonitor indications in compact format");
		Main.printSecureOptions(joiner, false);
		out(joiner.toString());
	}

	private static void out(final String s)
	{
		System.out.println(s);
	}

	protected static String decodeLteFrame(final RFLData frame) throws KNXFormatException {
		final int extFormat = frame.getFrameType() & 0xf;
		final boolean lteExt = (extFormat & 0x0c) == 0x04;
		if (!lteExt)
			return "no LTE";

		final byte[] tpdu = frame.getTpdu();
		final int pci = tpdu[0] & 0xff;
		final int tpci = (pci >>> 6);
		// LTE has tpci always set 0
		if (tpci != Tpci.UnnumberedData.ordinal())
			throw new KNXFormatException("LTE extended frame requires TPCI " + Tpci.UnnumberedData);

		return decodeLteFrame(extFormat, frame.getDestination(), DataUnitBuilder.extractASDU(tpdu));
	}

	protected static String decodeLteFrame(final int extFormat, final KNXAddress dst, final byte[] asdu)
			throws KNXFormatException {
		final StringBuilder sb = new StringBuilder();
		final var tag = LteHeeTag.from(extFormat, (GroupAddress) dst);
		sb.append(tag).append(' ');

		final int iot = ((asdu[0] & 0xff) << 8) | (asdu[1] & 0xff);
		final int ioi = asdu[2] & 0xff;
		final int pid = asdu[3] & 0xff;
		if (pid == 0xff) {
			final int companyCode = ((asdu[4] & 0xff) << 8) | (asdu[5] & 0xff);
			final int privatePid = asdu[6] & 0xff;
			sb.append("IOT " + iot + " OI " + ioi + " Company " + companyCode + " PID " + privatePid + ": "
					+ DataUnitBuilder.toHex(Arrays.copyOfRange(asdu, 7, asdu.length), ""));
		}
		else
			sb.append("IOT " + iot + " OI " + ioi + " PID " + pid + ": "
					+ DataUnitBuilder.toHex(Arrays.copyOfRange(asdu, 4, asdu.length), ""));

		return sb.toString();
	}

	protected static String decodeBibat(final RFLData frame) throws KNXFormatException {
		final int frameType = frame.getFrameType() >>> 4;
		final byte[] tpdu = frame.getTpdu();

		if (frameType == 1) // Fast ACK
			return "Fast ACK";
		if (frameType == 5) // Sync
			return "RndPausePtr " + (tpdu[0] & 0xff);
		if (frameType == 6) // Help
			return "Retransmitter " + (tpdu[0] & 0xff);
		if (frameType == 7) { // Help Response
			final int ticks = ((tpdu[0] & 0xff) << 16) | ((tpdu[1] & 0xff) << 8) | (tpdu[2] & 0xff);
			final double next = (ticks * 10) / 16384 / 10d;
			return "NextBlock " + next + " ms NextBlock# " + (tpdu[3] & 0xff) + " RndPausePtr " + (tpdu[4] & 0xff);
		}
		return "";
	}

	private final class ShutdownHandler extends Thread
	{
		private ShutdownHandler register()
		{
			Runtime.getRuntime().addShutdownHook(this);
			return this;
		}

		private void unregister()
		{
			try {
				Runtime.getRuntime().removeShutdownHook(this);
			}
			catch (final IllegalStateException expected) {}
		}

		@Override
		public void run()
		{
			quit();
		}
	}
}
