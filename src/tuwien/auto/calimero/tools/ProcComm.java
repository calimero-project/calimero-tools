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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DetachEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.datapoint.Datapoint;
import tuwien.auto.calimero.datapoint.DatapointMap;
import tuwien.auto.calimero.datapoint.DatapointModel;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;
import tuwien.auto.calimero.dptxlator.TranslatorTypes.MainType;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXFormatException;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.PLSettings;
import tuwien.auto.calimero.link.medium.RFSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogLevel;
import tuwien.auto.calimero.log.LogManager;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogStreamWriter;
import tuwien.auto.calimero.log.LogWriter;
import tuwien.auto.calimero.process.ProcessCommunicator;
import tuwien.auto.calimero.process.ProcessCommunicatorImpl;
import tuwien.auto.calimero.process.ProcessEvent;
import tuwien.auto.calimero.process.ProcessListener;
import tuwien.auto.calimero.process.ProcessListenerEx;
import tuwien.auto.calimero.xml.KNXMLException;
import tuwien.auto.calimero.xml.XMLFactory;
import tuwien.auto.calimero.xml.XMLReader;
import tuwien.auto.calimero.xml.XMLWriter;

/**
 * A tool for Calimero 2 providing KNX process communication.
 * <p>
 * ProcComm is a {@link Runnable} tool implementation allowing a user to read or write
 * datapoint values in a KNX network. It supports KNX network access using a KNXnet/IP
 * connection or an FT1.2 connection.
 * <p>
 * <i>Group monitor mode</i>:<br>
 * When in group monitor mode, process communication lists group write, read, and read response
 * commands issued on the KNX network. Datapoint values in monitored group commands are decoded for
 * appropriate KNX datapoint types (DPT). In addition, a user can issue read and write commands on
 * the terminal. Commands have the the following syntax: <code>cmd datapoint [DPT] [value]</code>,
 * with <code>cmd = ("r"|"read") | ("w"|"write")</code>. For example, <code>r 1/0/3</code> will read
 * the current value of datapoint <code>1/0/3</code>. The command <code>w 1/0/3 1.002 true</code>
 * will write the boolean value <code>true</code> for datapoint <code>1/0/3</code>.<br>
 * By default, this tool will show the decoded value of any matching datapoint type for received
 * datapoint data. Issuing a read or write command and specifying a datapoint type (DPT) will
 * override the default datapoint type translation behavior of this tool. For example,
 * <code>read 1/0/3 1.005</code> will decode subsequent values of that datapoint using the DPT 1.005
 * "Alarm" and its value representations "alarm" and "no alarm". A subsequent write can then simply
 * issue <code>w 1/0/3 alarm</code>, setting datapoint 1/0/3 to "alarm" state.<br>
 * When the tool exits, type information about any datapoint that was monitored is stored in a file
 * on disk in the current working directory (if file creation/modification is permitted). That file
 * is named something like ".proccomm_dplist.xml" (hidden file on Unix-based systems). It allows the
 * tool to remember user-specific settings of datapoint types between tool invocations, important
 * for the appropriate decoding of datapoint values. The file uses the layout of
 * {@link DatapointMap}, and can be edited or used at any other place Calimero expects a
 * {@link DatapointModel}. Note that any actual datapoint <i>values</i> are not stored in that file.
 * <p>
 * The tool implementation shows the necessary interaction with the Calimero-core library API for
 * the described tasks. The main part of the implementation is based on the library's
 * {@link ProcessCommunicator}, which offers high-level access for reading and writing process
 * values. It also shows creation of a {@link KNXNetworkLink}, which is supplied to the process
 * communicator, and serves as the link to the KNX network. For group monitoring, the tool uses
 * {@link Datapoint} and {@link DatapointModel} to persist datapoints between tool invocations.
 * <p>
 * When running this tool from the terminal, method <code>main</code> of this class is invoked;
 * otherwise, use this class in the context appropriate to a {@link Runnable}, or use
 * {@link #start(ProcessListener)} and {@link #quit()}.<br>
 * In console mode, datapoint values as well as occurring problems are written to
 * <code>System.out</code>.
 * <p>
 * Note that communication will use default settings if not specified otherwise using command line
 * options. Since these settings might be system dependent (for example, the local host) and not
 * always predictable, a user may want to specify particular settings using the available options.
 *
 * @author B. Malinowsky
 */
public class ProcComm implements Runnable
{
	// XXX The expected sequence for read/write commands on the terminal differs between
	// the tool command line arguments and monitor mode input --> unify.

	private static final String tool = "ProcComm";
	private static final String sep = System.getProperty("line.separator");
	private static final String toolDatapointsFile = "." + tool.toLowerCase() + "_dplist.xml";

	private static LogService out = LogManager.getManager().getLogService("tools");

	/**
	 * The used process communicator.
	 */
	protected ProcessCommunicator pc;

	// specifies parameters to use for the network link and process communication
	private final Map options = new HashMap();
	// contains the datapoints for which translation information is known in monitor mode
	private final DatapointModel datapoints = new DatapointMap();

	//private final LogWriter w;

	private volatile boolean closed;

	/**
	 * Creates a new ProcComm instance using the supplied options.
	 * <p>
	 * Mandatory arguments are an IP host or a FT1.2 port identifier, depending on the type of
	 * connection to the KNX network. See {@link #main(String[])} for the list of options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public ProcComm(final String[] args)
	{
		this(args, null);
	}

	/**
	 * Creates a new ProcComm instance using the supplied options.
	 * <p>
	 * Mandatory arguments are an IP host or a FT1.2 port identifier, depending on the
	 * type of connection to the KNX network. See {@link #main(String[])} for the list of
	 * options.
	 *
	 * @param args list with options
	 * @param w a log writer, might be <code>null</code>: this parameter is ignored for now!
	 * @throws KNXIllegalArgumentException
	 */
	protected ProcComm(final String[] args, final LogWriter w)
	{
		//this.w = w;
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
	 * Entry point for running ProcComm.
	 * <p>
	 * An IP host or port identifier has to be supplied, specifying the endpoint for the KNX network
	 * access.<br>
	 * To show the usage message of this tool on the console, supply the command line option -help
	 * (or -h).<br>
	 * Command line options are treated case sensitive. Available options for the communication
	 * connection:
	 * <ul>
	 * <li><code>-help -h</code> show help message</li>
	 * <li><code>-version</code> show tool/library version and exit</li>
	 * <li><code>-verbose -v</code> enable verbose status output</li>
	 * <li><code>-localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>-localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>-port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>-nat -n</code> enable Network Address Translation</li>
	 * <li><code>-routing</code> use KNXnet/IP routing</li>
	 * <li><code>-serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>-medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|p132|rf] (defaults to
	 * tp1)</li>
	 * </ul>
	 * Available commands for process communication:
	 * <ul>
	 * <li><code>read</code> <i>DPT &nbsp;KNX-address</i> &nbsp;read from group address, using DPT
	 * value format</li>
	 * <li><code>write</code> <i>DPT &nbsp;value &nbsp;KNX-address</i> &nbsp;write to group address,
	 * using DPT value format</li>
	 * <li><code>monitor</code> enter group monitoring</li>
	 * </ul>
	 * In monitor mode, read/write commands can be issued on the terminal using
	 * <code>cmd DP [DPT] [value]</code>, with <code>cmd = ("r"|"read") | ("w"|"write")</code>.
	 * <p>
	 * For the more common datapoint types (DPTs) the following name aliases can be used instead of
	 * the general DPT number string:
	 * <ul>
	 * <li><code>switch</code> for DPT 1.001, with values <code>off</code>, <code>on</code></li>
	 * <li><code>bool</code> for DPT 1.002, with values <code>false</code>, <code>true</code></li>
	 * <li><code>dimmer</code> for DPT 3.007, with values <code>decrease 0..7</code>,
	 * <code>increase 0..7</code></li>
	 * <li><code>blinds</code> for DPT 3.008, with values <code>up 0..7</code>,
	 * <code>down 0..7</code></li>
	 * <li><code>angle</code> for DPT 5.003, with values <code>0..360</code></li>
	 * <li><code>ucount</code> for DPT 5.010, with values <code>0..255</code></li>
	 * <li><code>temp</code> for DPT 9.001, with values <code>-273..+670760</code></li>
	 * <li><code>float</code> or <code>float2</code> for DPT 9.002</li>
	 * <li><code>float4</code> for DPT 14.005</li>
	 * <li><code>int</code> for DPT 13.001</li>
	 * <li><code>string</code> for DPT 16.001</li>
	 * </ul>
	 *
	 * @param args command line options for process communication
	 */
	public static void main(final String[] args)
	{
		try {
			final LogWriter w = LogStreamWriter.newUnformatted(LogLevel.INFO, System.out, true,
					false);
			LogManager.getManager().addWriter("", w);
			final ProcComm pc = new ProcComm(args, null);
			// adjust log level, if specified
			if (!pc.options.containsKey("verbose"))
				w.setLogLevel(LogLevel.ERROR);
			final ShutdownHandler sh = pc.new ShutdownHandler().register();
			pc.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.error("tool options", t);
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
			start(null);
			if (options.containsKey("monitor"))
				runMonitorLoop();
			else
				readWrite();
		}
		catch (final KNXException e) {
			thrown = e;
		}
		catch (final IOException e) {
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
			quit();
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Runs the process communicator.
	 * <p>
	 * This method immediately returns when the process communicator is running. Call
	 * {@link #quit()} to quit process communication.
	 *
	 * @param l a process event listener, can be <code>null</code>
	 * @throws KNXException on problems creating network link or communication
	 * @throws InterruptedException on interrupted thread
	 */
	public void start(final ProcessListener l) throws KNXException, InterruptedException
	{
		if (options.isEmpty()) {
			out.log(LogLevel.ALWAYS, "A tool for KNX process communication", null);
			showVersion();
			out.log(LogLevel.ALWAYS, "type -help for help message", null);
			return;
		}
		if (options.containsKey("help")) {
			showUsage();
			return;
		}
		if (options.containsKey("version")) {
			showVersion();
			return;
		}

		// create the network link to the KNX network
		final KNXNetworkLink lnk = createLink();
		// create process communicator with the established link
		pc = new ProcessCommunicatorImpl(lnk);
		if (l != null)
			pc.addProcessListener(l);

		// this is the listener if group monitoring is requested
		if (options.containsKey("monitor")) {
			final ProcessListener monitor = new ProcessListenerEx() {
				public void groupWrite(final ProcessEvent e) { onGroupEvent(e); }
				public void groupReadResponse(final ProcessEvent e) { onGroupEvent(e); }
				public void groupReadRequest(final ProcessEvent e) { onGroupEvent(e); }
				public void detached(final DetachEvent e) { closed = true; }
			};
			pc.addProcessListener(monitor);
		}

		// user might specify a response timeout for KNX message
		// answers from the KNX network
		if (options.containsKey("timeout"))
			pc.setResponseTimeout(((Integer) options.get("timeout")).intValue());
	}

	/**
	 * Quits process communication.
	 * <p>
	 * Detaches the network link from the process communicator and closes the link.
	 */
	public void quit()
	{
		closed = true;
		if (pc != null) {
			final KNXNetworkLink lnk = pc.detach();
			if (lnk != null)
				lnk.close();
			saveDatapoints();
		}
	}

	/**
	 * Called by this tool on receiving a process communication group event.
	 * <p>
	 *
	 * @param e the process event
	 */
	protected void onGroupEvent(final ProcessEvent e)
	{
		final byte[] asdu = e.getASDU();
		String s = e.getSourceAddr() + "->" + e.getDestination() + " "
				+ DataUnitBuilder.decodeAPCI(e.getServiceCode()) + " "
				+ DataUnitBuilder.toHex(asdu, " ");
		if (asdu.length > 0) {
			final Datapoint dp = datapoints.get(e.getDestination());
			try {
				final String decodesep = ": ";
				if (dp != null)
					s = s + decodesep + asString(asdu, 0, dp.getDPT());
				else
					s = s + decodesep + decodeAsduByLength(asdu, e.isLengthOptimizedAPDU());
			}
			catch (final KNXException ke) {}
			catch (final KNXIllegalArgumentException iae) {}
			catch (final RuntimeException rte) {}
		}
		out.log(options.containsKey("monitor") ? LogLevel.ALWAYS : LogLevel.INFO, s, null);
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
		if (canceled)
			out.info("process communicator was stopped");
		if (thrown != null)
			out.error("completed", thrown);
	}

	/**
	 * Returns a string translation of the datapoint data for the specified datapoint type, using
	 * the process event ASDU.
	 * <p>
	 *
	 * @param asdu the process event ASDU with the datapoint data
	 * @param dptMainNumber DPT main number &ge; 0, can be 0 if the <code>dptID</code> is unique
	 * @param dptID datapoint type ID to lookup the translator
	 * @return the datapoint value
	 * @throws KNXException on failed creation of translator, or translator not available
	 */
	protected String asString(final byte[] asdu, final int dptMainNumber, final String dptID)
		throws KNXException
	{
		final DPTXlator t = TranslatorTypes.createTranslator(dptMainNumber, dptID);
		t.setData(asdu);
		return t.getValue();
	}

	/**
	 * Creates the KNX network link to access the network specified in <code>options</code>.
	 * <p>
	 *
	 * @return the KNX network link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkLink createLink() throws KNXException, InterruptedException
	{
		final String host = (String) options.get("host");
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (options.containsKey("serial")) {
			// create FT1.2 network link
			try {
				return new KNXNetworkLinkFT12(Integer.parseInt(host), medium);
			}
			catch (final NumberFormatException e) {
				return new KNXNetworkLinkFT12(host, medium);
			}
		}
		// create local and remote socket address for network link
		final InetSocketAddress local = createLocalSocket((InetAddress) options.get("localhost"),
				(Integer) options.get("localport"));
		final InetSocketAddress remote = new InetSocketAddress(host,
				((Integer) options.get("port")).intValue());
		final int mode = options.containsKey("routing") ? KNXNetworkLinkIP.ROUTING
				: KNXNetworkLinkIP.TUNNELING;
		return new KNXNetworkLinkIP(mode, local, remote, options.containsKey("nat"), medium);
	}

	/**
	 * Gets the datapoint type identifier from the <code>options</code>, and maps alias names of
	 * common datapoint types to its datapoint type ID.
	 * <p>
	 * The option map must contain a "dpt" key with value.
	 *
	 * @return datapoint type identifier
	 */
	private String getDPT()
	{
		final String dpt = (String) options.get("dpt");
		return fromDptName(dpt);
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
		return id;
	}

	private void readWrite() throws KNXException, InterruptedException
	{
		final boolean write = options.containsKey("write");
		if (!write && !options.containsKey("read"))
			return;
		final GroupAddress main = (GroupAddress) options.get("dst");
		// encapsulate information into a datapoint
		// this is a convenient way to let the process communicator
		// handle the DPT stuff, so an already formatted string will be returned
		readWrite(new StateDP(main, "", 0, getDPT()), write, (String) options.get("value"));
	}

	private void readWrite(final Datapoint dp, final boolean write, final String value)
		throws KNXException, InterruptedException
	{
		if (!write)
			out.log(LogLevel.ALWAYS, "read value: " + pc.read(dp), null);
		else {
			// note, a write to a non existing datapoint might finish successfully,
			// too.. no check for existence or read back of a written value is done
			pc.write(dp, value);
			out.log(LogLevel.ALWAYS, "write successful", null);
		}
	}

	// shows one DPT of each matching main type based on the length of the supplied ASDU
	private String decodeAsduByLength(final byte[] asdu, final boolean optimized)
		throws KNXFormatException
	{
		final StringBuffer sb = new StringBuffer();
		final List typesBySize = TranslatorTypes.getMainTypesBySize(optimized ? 0 : asdu.length);
		for (final Iterator i = typesBySize.iterator(); i.hasNext();) {
			final MainType main = (MainType) i.next();
			try {
				final String dptid = (String) main.getSubTypes().keySet().iterator().next();
				final DPTXlator t = TranslatorTypes.createTranslator(main.getMainNumber(), dptid);
				t.setData(asdu);
				sb.append(t.getValue()).append(" [").append(dptid).append("]")
						.append(i.hasNext() ? ", " : "");
			}
			catch (final KNXException ignore) {}
		}
		return sb.toString();
	}

	private void runMonitorLoop() throws IOException, KNXException, InterruptedException
	{
		try {
			final XMLReader r = XMLFactory.getInstance().createXMLReader(toolDatapointsFile);
			datapoints.load(r);
			r.close();
		}
		catch (final KNXMLException e) {
			out.trace("no monitor datapoint information loaded, " + e.getMessage());
		}
		final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			while (!in.ready() && !closed)
				Thread.sleep(250);
			if (closed)
				break;
			final String line = in.readLine();
			final String[] s = line.trim().split(" +");
			if (s.length == 1 && "exit".equalsIgnoreCase(s[0]))
				return;
			if (s.length > 1) {
				// expected order: <cmd> <addr> [<dpt>] [<value>]
				// cmd = ("r"|"read") | ("w"|"write")
				final String cmd = s[0];
				final String addr = s[1];

				final boolean read = cmd.equals("read") || cmd.equals("r");
				final boolean write = cmd.equals("write") || cmd.equals("w");
				if (read || write) {
					final boolean withDpt = read && s.length == 3 || write && s.length >= 4;
					try {
						final GroupAddress ga = new GroupAddress(addr);
						StateDP dp = new StateDP(ga, "tmp", 0, withDpt ? fromDptName(s[2]) : null);
						if (withDpt && !s[2].equals("-")) {
							datapoints.remove(dp);
							datapoints.add(dp);
						}
						dp = (StateDP) (datapoints.contains(ga) ? datapoints.get(ga) : dp);
						if (write) {
							final List l = Arrays.asList(s).subList(withDpt ? 3 : 2, s.length);
							final StringBuffer sb = new StringBuffer();
							for (final Iterator i = l.iterator(); i.hasNext();) {
								sb.append((String) i.next());
								sb.append(" ");
							}
							readWrite(dp, write, sb.toString());
						}
						else
							readWrite(dp, write, null);
					}
					catch (final KNXException e) {
						out.log(LogLevel.ERROR, "[" + line + "]", e);
					}
					catch (final RuntimeException e) {
						out.log(LogLevel.ERROR, "[" + line + "]", e);
					}
				}
			}
		}
	}

	private void saveDatapoints()
	{
		if (!options.containsKey("monitor"))
			return;
		try {
			final XMLWriter w = XMLFactory.getInstance().createXMLWriter(toolDatapointsFile);
			try {
				datapoints.save(w);
			}
			finally {
				w.close();
			}
		}
		catch (final KNXMLException e) {
			out.warn("on saving monitor datapoint information to " + toolDatapointsFile, e);
		}
	}

	/**
	 * Reads all options in the specified array, and puts relevant options into the supplied options
	 * map.
	 * <p>
	 * On options not relevant for doing process communication (like <code>help</code>), this method
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
		options.put("port", new Integer(KNXnetIPConnection.DEFAULT_PORT));
		options.put("medium", TPSettings.TP1);

		int i = 0;
		for (; i < args.length; i++) {
			final String arg = args[i];
			if (isOption(arg, "-help", "-h")) {
				options.put("help", null);
				return;
			}
			else if (isOption(arg, "-version", null)) {
				options.put("version", null);
				return;
			}
			else if (isOption(arg, "-verbose", "-v"))
				options.put("verbose", null);
			else if (arg.equals("read")) {
				if (i + 2 >= args.length)
					break;
				options.put("read", null);
				options.put("dpt", args[++i]);
				try {
					options.put("dst", new GroupAddress(args[++i]));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("read DPT: " + e.getMessage(), e);
				}
			}
			else if (arg.equals("write")) {
				if (i + 3 >= args.length)
					break;
				options.put("write", null);
				options.put("dpt", args[++i]);
				options.put("value", args[++i]);
				try {
					options.put("dst", new GroupAddress(args[++i]));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("write DPT: " + e.getMessage(), e);
				}
			}
			else if (arg.equals("monitor"))
				options.put("monitor", null);
			else if (isOption(arg, "-localhost", null))
				parseHost(args[++i], true, options);
			else if (isOption(arg, "-localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (isOption(arg, "-port", "-p"))
				options.put("port", Integer.decode(args[++i]));
			else if (isOption(arg, "-nat", "-n"))
				options.put("nat", null);
			else if (isOption(arg, "-serial", "-s"))
				options.put("serial", null);
			else if (isOption(arg, "-medium", "-m"))
				options.put("medium", getMedium(args[++i]));
			else if (isOption(arg, "-timeout", "-t"))
				options.put("timeout", Integer.decode(args[++i]));
			else if (isOption(arg, "-routing", null))
				options.put("routing", null);
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (options.containsKey("host") == options.containsKey("serial"))
			throw new KNXIllegalArgumentException("no host or serial port specified");

		if ((options.containsKey("monitor") ? 1 : 0) + (options.containsKey("read") ? 1 : 0)
				+ (options.containsKey("write") ? 1 : 0) != 1)
			throw new KNXIllegalArgumentException("specify either read, write, or group monitoring");
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("usage: ").append(tool + " [options] <host|port>").append(sep);
		sb.append("options:").append(sep);
		sb.append("  -help -h                show this help message").append(sep);
		sb.append("  -version                show tool/library version and exit").append(sep);
		sb.append("  -verbose -v             enable verbose status output").append(sep);
		sb.append("  -localhost <id>         local IP/host name").append(sep);
		sb.append("  -localport <number>     local UDP port (default system assigned)").append(sep);
		sb.append("  -port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT + ")").append(sep);
		sb.append("  -nat -n                 enable Network Address Translation").append(sep);
		sb.append("  -routing                use KNX net/IP routing (always on port 3671)")
				.append(sep);
		sb.append("  -serial -s              use FT1.2 serial communication").append(sep);
		sb.append("  -medium -m <id>         KNX medium [tp1|p110|p132|rf] (default tp1)")
				.append(sep);
		sb.append("Available commands for process communication:").append(sep);
		sb.append("  read <DPT> <KNX address>           read from group address").append(sep);
		sb.append("  write <DPT> <value> <KNX address>  write to group address").append(sep);
		sb.append("  monitor                 enter group monitoring, can also be").append(sep)
				.append("                          used together with read or write ").append(sep);
		sb.append("Name aliases for common DPT numbers:").append(sep);
		sb.append("  switch (1.001) {off, on}, bool (1.002) {false, true}")
				.append(sep)
				.append("  dimmer (3.007) {decrease 0..7, increase 0..7}, "
						+ "blinds (3.008) {up 0..7, down 0..7}").append(sep)
				.append("  angle (5.003) {0..360}, ucount (5.010) {0..255}").append(sep)
				.append("  temp (9.001) {-273..+670760}, float/float2 (9.002)").append(sep)
				.append("  int (13.001), float4 (14.005), string (16.001)");
		out.log(LogLevel.ALWAYS, sb.toString(), null);
	}

	//
	// utility methods
	//

	private static void showVersion()
	{
		out.log(LogLevel.ALWAYS, Settings.getLibraryHeader(false), null);
	}

	/**
	 * Creates a medium settings type for the supplied medium identifier.
	 * <p>
	 *
	 * @param id a medium identifier from command line option
	 * @return medium settings object
	 * @throws KNXIllegalArgumentException on unknown medium identifier
	 */
	private static KNXMediumSettings getMedium(final String id)
	{
		if (id.equals("tp1"))
			return TPSettings.TP1;
		if (id.equals("p110"))
			return new PLSettings();
		if (id.equals("p132"))
			return new PLSettings(true);
		if (id.equals("rf"))
			return new RFSettings(null);
		throw new KNXIllegalArgumentException("unknown medium");
	}

	private static void parseHost(final String host, final boolean local,
		final Map options)
	{
		try {
			options.put(local ? "localhost" : "host", InetAddress.getByName(host));
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to read host " + host, e);
		}
	}

	private static InetSocketAddress createLocalSocket(final InetAddress host, final Integer port)
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

	private static boolean isOption(final String arg, final String longOpt,
		final String shortOpt)
	{
		return arg.equals(longOpt) || shortOpt != null && arg.equals(shortOpt);
	}

	private final class ShutdownHandler extends Thread
	{
		ShutdownHandler register()
		{
			Runtime.getRuntime().addShutdownHook(this);
			return this;
		}

		void unregister()
		{
			try {
				Runtime.getRuntime().removeShutdownHook(this);
			}
			catch (final IllegalStateException expected) {}
		}

		public void run()
		{
			quit();
		}
	}
}
