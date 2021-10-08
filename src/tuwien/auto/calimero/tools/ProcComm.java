/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2021 B. Malinowsky

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

import static java.util.Map.entry;
import static tuwien.auto.calimero.tools.Main.setDomainAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DetachEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.KnxRuntimeException;
import tuwien.auto.calimero.LteHeeTag;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.SerialNumber;
import tuwien.auto.calimero.cemi.CEMI;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.cemi.CEMILDataEx;
import tuwien.auto.calimero.datapoint.Datapoint;
import tuwien.auto.calimero.datapoint.DatapointMap;
import tuwien.auto.calimero.datapoint.DatapointModel;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.DptXlator8BitSet;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;
import tuwien.auto.calimero.dptxlator.TranslatorTypes.MainType;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.RFSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.process.LteProcessEvent;
import tuwien.auto.calimero.process.ProcessCommunicator;
import tuwien.auto.calimero.process.ProcessCommunicatorImpl;
import tuwien.auto.calimero.process.ProcessEvent;
import tuwien.auto.calimero.process.ProcessListener;
import tuwien.auto.calimero.xml.KNXMLException;
import tuwien.auto.calimero.xml.XmlInputFactory;
import tuwien.auto.calimero.xml.XmlOutputFactory;
import tuwien.auto.calimero.xml.XmlReader;
import tuwien.auto.calimero.xml.XmlWriter;

/**
 * A tool for Calimero providing KNX process communication.
 * <p>
 * ProcComm is a {@link Runnable} tool implementation allowing a user to read or write datapoint
 * values in a KNX network. It supports KNX network access using a KNXnet/IP, KNX IP, USB, FT1.2, or
 * TP-UART connection.
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
 * When the tool exits and no user-supplied datapoints file was used, type information about any datapoint
 * that was monitored is stored in a file
 * on disk in the current working directory (if file creation/modification is permitted). That file
 * is named something like ".proccomm_dplist.xml" (hidden file on Unix-based systems). It allows the
 * tool to remember user-specific settings of datapoint types between tool invocations, important
 * for the appropriate decoding of datapoint values. The file uses the layout of
 * {@link DatapointMap}, and can be edited or used at any other place Calimero expects a
 * {@link DatapointModel}. Note that any actual datapoint <i>values</i> are not stored in that file.
 * Any user-supplied datapoints resource is not modified when the tool exits.
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
	private static final String tool = "ProcComm";
	private static final String sep = System.getProperty("line.separator");
	private static final String toolDatapointsFile = "." + tool.toLowerCase() + "_dplist.xml";

	private static final Logger out = LogService.getLogger("calimero.tools." + tool);

	private KNXNetworkLink link;

	/**
	 * The used process communicator.
	 */
	protected ProcessCommunicator pc;

	// specifies parameters to use for the network link and process communication
	private final Map<String, Object> options = new HashMap<>();
	// contains the datapoints for which translation information is known in monitor mode
	private final DatapointModel<StateDP> datapoints = new DatapointMap<>();

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
	 * Entry point for running ProcComm. The endpoint for KNX network access is either an IP host or port identifier for
	 * IP, USB, FT1.2 or TP-UART communication. Use the command line option <code>--help</code> (or <code>-h</code>) to show the
	 * usage of this tool.
	 * <p>
	 * Command line options are treated case sensitive. Available options for communication:
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
	 * <li><code>--sn</code> <i>number</i> &nbsp;device serial number to use in RF multicasts &amp; broadcasts</li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local endpoint</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected that directly
	 * communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX individual address shall be unique
	 * in a network, and the subnetwork address (area and line) should be set to match the network configuration.
	 * <p>
	 * Available commands for process communication:
	 * <ul>
	 * <li><code>read</code> <i>group-address &nbsp;[DPT]</i> &nbsp;&nbsp;&nbsp;read from datapoint with the specified
	 * group address, using DPT value format (optional)</li>
	 * <li><code>write</code> <i>group-address &nbsp;[DPT] &nbsp;value</i> &nbsp;&nbsp;&nbsp;write to datapoint with the
	 * specified group address, using DPT value format</li>
	 * <li><code>monitor</code> enter group monitoring</li>
	 * </ul>
	 * In monitor mode, read/write commands can be issued on the terminal using <code>cmd DP [DPT] [value]</code>, with
	 * <code>cmd = ("r"|"read") | ("w"|"write")</code>.<br>
	 * Additionally, the tool will either create or load an existing datapoint list and maintain it with all datapoints
	 * being read or written. Hence, once the datapoint type of a datapoint is known, the DPT part can be omitted from
	 * the command. The datapoint list is saved to the current working directory.<br>
	 * Examples: <code>write 1/0/1 switch off</code>, <code>w 1/0/1 off</code>, <code>r 1/0/1</code>.
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
	 * @param args command line options for process communication
	 */
	public static void main(final String[] args)
	{
		try {
			final ProcComm pc = new ProcComm(args);
			final ShutdownHandler sh = pc.new ShutdownHandler().register();
			pc.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.error("tool options", t);
		}
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			start(null);
			if (options.containsKey("about"))
				return;
			loadDatapoints();
			if (options.containsKey("monitor"))
				runMonitorLoop();
			else
				readWrite();
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
		if (options.containsKey("about")) {
			((Runnable) options.get("about")).run();
			return;
		}

		// create the network link to the KNX network
		final KNXNetworkLink lnk = createLink();
		link = lnk;
		// create process communicator with the established link
		pc = new ProcessCommunicatorImpl(lnk);
		if (l != null)
			pc.addProcessListener(l);

		// this is the listener if group monitoring is requested
		if (options.containsKey("monitor")) {
			final ProcessListener monitor = new ProcessListener() {
				@Override
				public void groupWrite(final ProcessEvent e) { onGroupEvent(e); }
				@Override
				public void groupReadResponse(final ProcessEvent e) { onGroupEvent(e); }
				@Override
				public void groupReadRequest(final ProcessEvent e) { onGroupEvent(e); }
				@Override
				public void detached(final DetachEvent e) { closed = true; }
			};
			pc.addProcessListener(monitor);
		}
		if (options.containsKey("lte")) {
			link.addLinkListener(new NetworkLinkListener(){
				@Override
				public void indication(final FrameEvent e) { checkForLteFrame(e); }
			});
		}

		// user might specify a response timeout for KNX message
		// answers from the KNX network
		if (options.containsKey("timeout"))
			pc.responseTimeout(((Duration) options.get("timeout")));
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
	 *
	 * @param e the process event
	 */
	protected void onGroupEvent(final ProcessEvent e)
	{
		final byte[] asdu = e.getASDU();
		final StringBuilder sb = new StringBuilder();
		sb.append(e.getSourceAddr()).append("->");
		if (!options.containsKey("lte"))
			sb.append(e.getDestination());
		if (!options.containsKey("compact"))
			sb.append(" ").append(DataUnitBuilder.decodeAPCI(e.getServiceCode())).append(" ")
					.append(DataUnitBuilder.toHex(asdu, " "));
		if (asdu.length > 0) {
			try {
				sb.append(options.containsKey("compact") ? " " : ": ");
				if ((e.getServiceCode() & 0b1111111100) == 0b1111101000) {
					// group property service
					sb.append(decodeLteFrame((LteProcessEvent) e));
				}
				else {
					final Datapoint dp = datapoints.get(e.getDestination());

					if (dp != null)
						sb.append(asString(asdu, 0, dp.getDPT()));
					else
						sb.append(decodeAsduByLength(asdu, e.isLengthOptimizedAPDU()));
				}
			}
			catch (KNXException | RuntimeException ex) {
				out.info("error parsing group event {}", sb, ex);
			}
		}
		System.out.println(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " " + sb);
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
			out.info("process communication was stopped");
		if (thrown != null)
			out.error("completed with error", thrown);
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
	 *
	 * @return the KNX network link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkLink createLink() throws KNXException, InterruptedException {
		return Main.newLink(options);
	}

	/**
	 * Gets the datapoint type identifier from the <code>options</code>, and maps alias names of
	 * common datapoint types to its datapoint type ID.
	 * <p>
	 * The option map must contain a "dpt" key with value.
	 *
	 * @return datapoint type identifier
	 */
	private String getDPT(final GroupAddress group)
	{
		final String dpt = (String) options.get("dpt");
		final var datapoint = datapoints.get(group);
		if (dpt == null)
			return datapoint != null ? datapoint.getDPT() : null;
		final var id = fromDptName(dpt);
		if (datapoint != null)
			datapoint.setDPT(0, id);
		else
			datapoints.add(new StateDP(group, "", 0, id));
		return id;
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

	private void readWrite() throws KNXException, InterruptedException
	{
		if (options.containsKey("lte")) {
			issueLteCommand((String) options.get("tag"), (String[]) options.get("lte-cmd"));
			if (options.containsKey("read"))
				Thread.sleep(pc.responseTimeout().toMillis());
			return;
		}
		final boolean write = options.containsKey("write");
		if (!write && !options.containsKey("read"))
			return;
		final GroupAddress main = (GroupAddress) options.get("dst");
		// encapsulate information into a datapoint
		// this is a convenient way to let the process communicator
		// handle the DPT stuff, so an already formatted string will be returned
		readWrite(new StateDP(main, "", 0, getDPT(main)), write, (String) options.get("value"));
	}

	private void readWrite(final Datapoint dp, final boolean write, final String value)
		throws KNXException, InterruptedException
	{
		if (!write)
			System.out.println("read " + dp.getMainAddress() + " value: " + pc.read(dp));
		else {
			if (dp.getDPT() == null) {
				System.out.println("cannot write to " + dp.getMainAddress()
						+ " because DPT is not known yet, retry and specify DPT once");
				return;
			}
			// note, a write to a non existing datapoint might finish successfully,
			// too.. no check for existence or read back of a written value is done
			pc.write(dp, value);
			System.out.println("write to " + dp.getMainAddress() + " successful");
		}
	}

	private void issueLteCommand(final String addr, final String... s)
		throws KNXFormatException, KNXTimeoutException, KNXLinkClosedException {
		if (s.length < 5) {
			System.out.println("LTE-HEE command: r|w|i address IOT OI [\"company\" company] PID [hex values]");
			return;
		}
		final int iot = Integer.parseInt(s[2]);
		final int oi = Integer.parseInt(s[3]);
		final int privatePidOffset = "company".equals(s[4]) ? 2 : 0;
		final int company = privatePidOffset > 0 ? Integer.parseInt(s[5]) : 0;
		final int pid = Integer.parseInt(s[4 + privatePidOffset]);
		final String data = Arrays.asList(Arrays.copyOfRange(s, 5 + privatePidOffset, s.length)).stream()
				.collect(Collectors.joining("")).replaceAll("0x", "");

		final String cmd = s[0];
		final boolean read = cmd.equals("read") || cmd.equals("r");
		final boolean write = cmd.equals("write") || cmd.equals("w");
		final boolean info = cmd.equals("info") || cmd.equals("i");
		final int svc = write ? 0 : read ? 1 : info ? 2 : -1;
		if (svc == -1) {
			System.out.println("unknown command '" + cmd + "'");
			return;
		}
		if ((info || write) == data.isEmpty())
			System.out.println("data value(s) required for writing (but never for reading)!");
		else {
			readWrite(svc, addr, iot, oi, company, pid, data);
		}
	}

	private void readWrite(final int cmd, final String tag, final int iot, final int oi, final int company,
		final int pid, final String data) throws KNXFormatException, KNXTimeoutException, KNXLinkClosedException {

		// create asdu
		final int dataLen = data.length() / 2;
		final int asduLen = 4 + (company > 0 ? 3 : 0) + dataLen;
		final byte[] asdu = new byte[asduLen];
		int i = 0;
		asdu[i++] = (byte) (iot >> 8);
		asdu[i++] = (byte) iot;
		final int sendOi = cmd == 1 ? 0 : oi; // object instance is not used for reading
		asdu[i++] = (byte) sendOi;
		if (company > 0) {
			asdu[i++] = (byte) 255;
			asdu[i++] = (byte) (company >> 8);
			asdu[i++] = (byte) company;
		}
		asdu[i++] = (byte) pid;
		if (data.length() % 2 != 0) {
			System.out.println("error writing [" + data + "]: data length has to be even");
			return;
		}

		for (int k = 0; k < data.length(); k+= 2)
			asdu[i++] = (byte) Integer.parseInt(data.substring(k, k + 2), 16);

		// create tpdu
		final int groupPropRead = 0b1111101000;
		final int groupPropWrite = 0b1111101010;
		final int groupPropInfo = 0b1111101011;
		final int dataTagGroup = 0x04;
		final int service = cmd == 0 ? groupPropWrite : cmd == 1 ? groupPropRead : groupPropInfo;
		final byte[] tpdu = DataUnitBuilder.createAPDU(service, asdu);
		tpdu[0] |= dataTagGroup;
		final var tagAddr = LteHeeTag.from(tag);

		final boolean knxip = link.getKNXMedium().getMedium() == KNXMediumSettings.MEDIUM_KNXIP;
		final boolean tp1 = link.getKNXMedium().getMedium() == KNXMediumSettings.MEDIUM_TP1;
		final var priority = service == groupPropInfo || service == groupPropWrite ? Priority.NORMAL : Priority.LOW;
		final boolean domainBroadcast = tp1 ? true : false;

		final var ldata = CEMILDataEx.newLte(knxip ? CEMILData.MC_LDATA_IND : CEMILData.MC_LDATA_REQ,
				KNXMediumSettings.BackboneRouter, tagAddr, tpdu, priority, true, domainBroadcast, false, 6);

		final String svc = cmd == 0 ? "write" : cmd == 1 ? "read" : "info";
		final String scmp = company > 0 ? " company " + company : "";
		final String sdata = data.length() > 0 ? " data [" + data + "]" : "";
		System.out.println("send LTE-HEE " + svc + " " + tag + " IOT " + iot + " OI " + sendOi + scmp + " PID " + pid
				+ sdata);
		link.send(ldata, true);
	}

	private void checkForLteFrame(final FrameEvent e) {
		final CEMI cemi = e.getFrame();
		if (!(cemi instanceof CEMILDataEx))
			return;

		try {
			final CEMILDataEx f = (CEMILDataEx) cemi;
			final byte[] data = f.toByteArray();
			final int ctrl2 = data[3 + data[1]] & 0xff;
			if ((ctrl2 & 0x04) == 0)
				return;

			final byte[] tpdu = f.getPayload();
			onGroupEvent(new LteProcessEvent(pc, f.getSource(), ctrl2 & 0x0f, (GroupAddress) f.getDestination(), tpdu));
		}
		catch (final Exception ex) {
			out.error("decoding LTE frame", ex);
		}
	}

	protected String decodeLteFrame(final LteProcessEvent e) throws KNXFormatException {
		return decodeLteFrame(e.extFrameFormat(), e.getDestination(), e.getASDU());
	}

	private String decodeLteFrame(final int extFormat, final GroupAddress dst, final byte[] asdu)
			throws KNXFormatException {
		final StringBuilder sb = new StringBuilder();
		final var tag = LteHeeTag.from(extFormat, dst);
		sb.append(tag).append(' ');

		final int iot = ((asdu[0] & 0xff) << 8) | (asdu[1] & 0xff);
		final int ioi = asdu[2] & 0xff;
		final int pid = asdu[3] & 0xff;

		final byte[] data = Arrays.copyOfRange(asdu, pid == 0xff ? 7 : 4, asdu.length);

		String value = DataUnitBuilder.toHex(data, "");
		final var dp = datapoints.get(dst);
		if (dp != null) {
			final var dpt = dp.getDPT();
			value = decodeDpValue(dpt, data).or(() -> decodeLteZDpValue(dpt, data)).orElse(DataUnitBuilder.toHex(data, ""));
		}

		if (pid == 0xff) {
			final int companyCode = ((asdu[4] & 0xff) << 8) | (asdu[5] & 0xff);
			final int privatePid = asdu[6] & 0xff;
			sb.append("IOT " + iot + " OI " + ioi + " Company " + companyCode + " PID " + privatePid + ": " + value);
		}
		else
			sb.append("IOT " + iot + " OI " + ioi + " PID " + pid + ": " + value);

		return sb.toString();
	}

	private static Optional<String> decodeDpValue(final String dpt, final byte[] data) {
		try {
			return Optional.of(TranslatorTypes.createTranslator(dpt, data).getValue());
		}
		catch (KNXIllegalArgumentException | KNXException e) {
			return Optional.empty();
		}
	}

	private static Optional<String> decodeLteZDpValue(final String dpt, final byte[] data) {
		final var stdDpt = lteZToStdMode.get(dpt);
		if (stdDpt == null)
			return Optional.empty();
		return decodeDpValue(stdDpt, Arrays.copyOfRange(data, 0, data.length - 1)).map(v -> appendLteStatus(v, data));
	}

	private static String appendLteStatus(final String prefix, final byte[] data) {
		try {
			final var status = new DptXlator8BitSet(DptXlator8BitSet.DptGeneralStatus);
			status.setData(data, data.length - 1);
			return prefix + (status.getNumericValue() == 0 ? "" : " (" + status.getValue() + ")");
		}
		catch (final KNXFormatException e) {
			return prefix;
		}
	}

	// shows one DPT of each matching main type based on the length of the supplied ASDU
	private static String decodeAsduByLength(final byte[] asdu, final boolean optimized) throws KNXFormatException
	{
		final StringBuilder sb = new StringBuilder();
		final List<MainType> typesBySize = TranslatorTypes.getMainTypesBySize(optimized ? 0 : asdu.length);
		for (final Iterator<MainType> i = typesBySize.iterator(); i.hasNext();) {
			final MainType main = i.next();
			try {
				final String dptid = main.getSubTypes().keySet().iterator().next();
				final DPTXlator t = TranslatorTypes.createTranslator(main.getMainNumber(), dptid);
				t.setData(asdu);
				sb.append(t.getValue()).append(" [").append(dptid).append("]").append(i.hasNext() ? ", " : "");
			}
			catch (final KNXException | KNXIllegalArgumentException ignore) {}
		}
		return sb.toString();
	}

	private void runMonitorLoop() throws IOException, KNXException, InterruptedException
	{
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
			if (s.length > 1) {
				// expected order: <cmd> <addr> [<dpt>] [<value>]
				// cmd = ("r"|"read") | ("w"|"write")
				final String cmd = s[0];
				final String addr = s[1];

				final boolean read = cmd.equals("read") || cmd.equals("r");
				final boolean write = cmd.equals("write") || cmd.equals("w");
				final boolean info = cmd.equals("info") || cmd.equals("i");
				try {
					if (options.containsKey("lte") && (read || write || info))
						issueLteCommand(addr, s);
					else if (read || write) {
						final boolean withDpt = (read && s.length == 3) || (write && s.length >= 4);
						StateDP dp;

						final GroupAddress ga = new GroupAddress(addr);
						dp = new StateDP(ga, "tmp", 0, withDpt ? fromDptName(s[2]) : null);
						if (withDpt && !s[2].equals("-")) {
							datapoints.remove(dp);
							datapoints.add(dp);
						}
						dp = datapoints.contains(ga) ? datapoints.get(ga) : dp;
						readWrite(dp, write, write ? Arrays.asList(s).subList(withDpt ? 3 : 2, s.length).stream()
								.collect(Collectors.joining(" ")) : null);
					}
					else
						out("unknown command '" + cmd + "'");
				}
				catch (final KNXTimeoutException e) {
					out(e.getMessage());
				}
				catch (KNXException | RuntimeException e) {
					out.error("[{}] {}", line, e.toString());
				}
			}
		}
	}

	private void loadDatapoints() {
		if (options.containsKey("datapoints"))
			loadDatapoints((String) options.get("datapoints"));
		else if (Files.exists(Path.of(toolDatapointsFile)))
			loadDatapoints(toolDatapointsFile);
	}

	private void loadDatapoints(final String dpResource) {
		try (XmlReader r = XmlInputFactory.newInstance().createXMLReader(dpResource)) {
			datapoints.load(r);
		}
		catch (final KNXMLException e) {
			out.info("failed to load datapoint information from {}: {}", dpResource, e.getMessage());
		}
	}

	private void saveDatapoints()
	{
		// we never save user-supplied datapoint resources
		if (options.containsKey("datapoints"))
			return;
		final boolean possiblyModified = options.containsKey("monitor") || options.containsKey("dpt");
		if (((DatapointMap<?>) datapoints).getDatapoints().isEmpty() || !possiblyModified)
			return;
		try (XmlWriter w = XmlOutputFactory.newInstance().createXMLWriter(toolDatapointsFile)) {
			datapoints.save(w);
		}
		catch (final KNXMLException e) {
			out.warn("on saving datapoint information to " + toolDatapointsFile, e);
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
		if (args.length == 0) {
			options.put("about", (Runnable) ProcComm::showToolInfo);
			return;
		}

		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", new TPSettings());
		boolean lte = false;

		for (final var i = new Main.PeekingIterator<>(List.of(args).iterator()); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) ProcComm::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "compact", "c"))
				options.put("compact", null);
			else if (lte || Main.isOption(arg, "lte", null)) {
				lte = false;
				options.put("lte", null);
				if (options.containsKey("tag")) {
					final List<String> list = new ArrayList<>();
					list.add(options.containsKey("read") ? "read" : options.containsKey("write") ? "write" : "info");
					list.add((String) options.get("tag")); // tag
					list.add(arg); // IOT
					list.add(i.next()); // OI
					if ("company".equals(i.peek())) {
						list.add(i.next());
						list.add(i.next());
					}
					list.add(i.next()); // PID
					if (options.containsKey("write") || options.containsKey("info"))
						list.add(i.next()); // data
					options.put("lte-cmd", list.toArray(new String[0]));
				}
			}
			else if (arg.equals("read")) {
				if (!i.hasNext())
					break;
				options.put("read", null);
				lte = checkLte(i);
				if (lte)
					continue;
				try {
					options.put("dst", new GroupAddress(i.next()));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("read datapoint: " + e.getMessage());
				}
				if (i.hasNext()) {
					if ("-".equals(i.peek()))
						i.next();
					else if (isDpt(i.peek()))
						options.put("dpt", i.next());
				}
			}
			else if (arg.equals("write")) {
				if (!i.hasNext())
					break;
				options.put("write", null);
				lte = checkLte(i);
				if (lte)
					continue;

				try {
					options.put("dst", new GroupAddress(i.next()));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("write datapoint: " + e.getMessage());
				}
				if ("-".equals(i.peek()))
					i.next();
				else if (isDpt(i.peek()))
					options.put("dpt", i.next());
				final var value = i.next();
				options.put("value", value);
				if (isTwoPartValue(value))
					options.put("value", value + " " + i.next());
			}
			else if (arg.equals("info")) {
				if (!i.hasNext())
					break;
				options.put("info", null);
				lte = checkLte(i);
			}
			else if (arg.equals("monitor"))
				options.put("monitor", null);
			else if (Main.isOption(arg, "sn", null))
				options.put("sn", SerialNumber.of(Long.decode(i.next())));
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "timeout", "t"))
				options.put("timeout", Duration.ofSeconds(Integer.decode(i.next())));
			else if (Main.isOption(arg, "datapoints", null))
				options.put("datapoints", i.next());
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
		if (!(options.containsKey("monitor") || options.containsKey("read") || options.containsKey("write") || options.containsKey("info")))
			throw new KNXIllegalArgumentException("specify read, write, or group monitoring");
		if (options.containsKey("read") && options.containsKey("write"))
			throw new KNXIllegalArgumentException("either read or write - not both");

		setDomainAddress(options);
		setRfDeviceSettings();
	}

	private boolean checkLte(final Main.PeekingIterator<String> i) {
		if ("--lte".equals(i.peek())) {
			i.next();
			options.put("tag", i.next());
			return true;
		}
		if (options.containsKey("lte")) {
			options.put("tag", i.next());
			return true;
		}
		return false;
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

	private static boolean isDpt(final String s) {
		if (s.startsWith("-"))
			return false;
		final var id = fromDptName(s);
		final var regex = "[0-9][0-9]*\\.[0-9][0-9][0-9]";
		return Pattern.matches(regex, id);
	}

	private static boolean isTwoPartValue(final String value) {
		return List.of("decrease", "increase", "up", "down").contains(value);
	}

	private static void showToolInfo() {
		out(tool + " - KNX process communication & group monitor");
		Main.showVersion();
		out("Type --help for help message");
	}

	private static void showUsage()
	{
		final var joiner = new StringJoiner(sep);
		joiner.add("Usage: " + tool + " [options] <host|port> <command>");
		Main.printCommonOptions(joiner);
		joiner.add("  --compact -c               show incoming indications in compact format");
		joiner.add("  --lte                      enable LTE commands, decode LTE messages");
		Main.printSecureOptions(joiner);
		listCommandsAndDptAliases(joiner, true);
		out(joiner);
	}

	private static StringJoiner listCommandsAndDptAliases(final StringJoiner joiner, final boolean showMonitor) {
		joiner.add("Available commands for process communication:");
		joiner.add("  (read/write: omitting the DPT might require a '-' placeholder to avoid ambiguity)");
		joiner.add("  read  <KNX group address> [DPT]          read from datapoint (expecting the specified datapoint type)");
		joiner.add("  write <KNX group address> [DPT] <value>  write to datapoint (value formatted for specified datapoint type)");
		if (showMonitor)
			joiner.add("  monitor                                  enter group monitoring");
		joiner.add("Name aliases for common datapoint types:");
		joiner  .add("  1.001: switch {off, on}                         1.002: bool {false, true}")
				.add("  3.007: dimmer {decrease 0..7, increase 0..7}    3.008: blinds {up 0..7, down 0..7}")
				.add("  5.001: percent {1..100} or % {1..100}")
				.add("  5.003: angle {0..360}                           5.010: ucount {0..255}")
				.add("  9.001: temp {-273..+670760}                    13.001: int (2-byte integer)")
				.add("  9.002: float/float2 (2-byte float)             14.005: float4 (4-byte float)")
				.add(" 16.001: string (ISO-8859-1, max. length 14)");
		return joiner;
	}

	//
	// utility methods
	//

	private static void out(final Object s)
	{
		System.out.println(s);
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

		@Override
		public void run()
		{
			quit();
		}
	}

	private static final Map<String, String> lteZToStdMode = Map.ofEntries(
			entry("200.100", "1.100"),     // DPT_Heat/Cool_Z
			entry("200.101", "1.006"),     // DPT_BinaryValue_Z
			entry("201.100", "20.102"),    // DPT_HVACMode_Z
			entry("201.102", "20.103"),    // DPT_DHWMode_Z
			entry("201.104", "20.105"),    // DPT_HVACContrMode_Z
			entry("201.105", "20.1105"),   // DPT_EnablH/Cstage_Z
			entry("201.107", "20.002"),    // DPT_BuildingMode_Z
			entry("201.108", "20.003"),    // DPT_OccMode_Z
			entry("201.109", "20.106"),    // DPT_HVACEmergMode_Z
			entry("202.001", "5.004"),     // DPT_RelValue_Z
			entry("202.002", "5.010"),     // DPT_UCountValue8_Z
			entry("203.002", "7.002"),     // DPT_TimePeriodMsec_Z
			entry("203.003", "7.003"),     // DPT_TimePeriod10Msec_Z
			entry("203.004", "7.004"),     // DPT_TimePeriod100Msec_Z
			entry("203.005", "7.005"),     // DPT_TimePeriodSec_Z
			entry("203.006", "7.006"),     // DPT_TimePeriodMin_Z
			entry("203.007", "7.007"),     // DPT_TimePeriodHrs_Z
			entry("203.011", "14.077"),    // DPT_UFlowRateLiter/h_Z
			entry("203.012", "7.001"),     // DPT_UCountValue16_Z
			entry("203.013", "14.019"),    // DPT_UElCurrentμA_Z
			entry("203.014", "9.024"),     // DPT_PowerKW_Z
			entry("203.015", "9.006"),     // DPT_AtmPressureAbs_Z
			entry("203.017", "5.001"),     // DPT_PercentU16_Z
			entry("203.100", "9.008"),     // DPT_HVACAirQual_Z
			entry("203.101", "9.005"),     // DPT_WindSpeed_Z
			entry("203.102", "9.022"),     // DPT_SunIntensity_Z
			entry("203.104", "9.009"),     // DPT_HVACAirFlowAbs_Z
			entry("204.001", "6.001"),     // DPT_RelSignedValue_Z
			entry("205.002", "8.002"),     // DPT_DeltaTimeMsec_Z
			entry("205.003", "8.003"),     // DPT_DeltaTime10Msec_Z
			entry("205.004", "8.004"),     // DPT_DeltaTime100Msec_Z
			entry("205.005", "8.005"),     // DPT_DeltaTimeSec_Z
			entry("205.006", "8.006"),     // DPT_DeltaTimeMin_Z
			entry("205.007", "8.007"),     // DPT_DeltaTimeHrs_Z
			entry("205.017", "8.010"),     // DPT_Percent_V16_Z
			entry("205.100", "9.001"),     // DPT_TempHVACAbs_Z
			entry("205.101", "9.002"),     // DPT_TempHVACRel_Z
			entry("205.102", "9.009"),     // DPT_HVACAirFlowRel_Z
			entry("218.001", "14.076"),    // DPT_VolumeLiter_Z
			entry("218.002", "14.077")     // DPT_FlowRate_m3/h_Z
	);
}
