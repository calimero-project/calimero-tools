/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2019 B. Malinowsky

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

import static tuwien.auto.calimero.tools.Main.setDomainAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.DetachEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.cemi.CEMI;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.cemi.CEMILDataEx;
import tuwien.auto.calimero.datapoint.Datapoint;
import tuwien.auto.calimero.datapoint.DatapointMap;
import tuwien.auto.calimero.datapoint.DatapointModel;
import tuwien.auto.calimero.datapoint.StateDP;
import tuwien.auto.calimero.dptxlator.DPTXlator;
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
	 * <li><code>--verbose -v</code> enable verbose status output</li>
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
	 * </ul>
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
			if (options.containsKey("help") || options.containsKey("version"))
				return;
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
		if (options.isEmpty()) {
			out(tool + " - KNX process communication & group monitor");
			showVersion();
			out("Type --help for help message");
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
				public void linkClosed(final CloseEvent e) {}
				@Override
				public void indication(final FrameEvent e) { checkForLteFrame(e); }
				@Override
				public void confirmation(final FrameEvent e) {}
			});
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
					final LteProcessEvent lteEvent = (LteProcessEvent) e;
					sb.append(decodeLteFrame(lteEvent.extFrameFormat, e.getDestination(), lteEvent.tpdu()));
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
		if ("percent".equals(id))
			return "5.001";
		if ("%".equals(id))
			return "5.001";
		return id;
	}

	private void readWrite() throws KNXException, InterruptedException
	{
		if (options.containsKey("lte")) {
			issueLteCommand((String) options.get("tag"), (String[]) options.get("lte-cmd"));
			if (options.containsKey("read"))
				Thread.sleep(pc.getResponseTimeout() * 1000);
			return;
		}
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
			System.out.println("read " + dp.getMainAddress() + " value: " + pc.read(dp));
		else {
			if (dp.getDPT() == null) {
				System.out.println("cannot write to " + dp.getMainAddress() + " because DPT is not known yet, retry and specify DPT once");
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
		asdu[i++] = (byte) oi;
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
		final byte[] tpdu = DataUnitBuilder.createAPDU(cmd == 0 ? groupPropWrite : cmd == 1 ? groupPropRead : groupPropInfo, asdu);
		tpdu[0] |= dataTagGroup;

		final Object[] parsed = parseLteTag(tag);
		final int tagClass = (int) parsed[0]; // 0=lower range geo, 1=upper range geo, 2=app. specific, 3=unassigned (peripheral)
		final GroupAddress group = (GroupAddress) parsed[1];

		final CEMILDataEx ldata = new CEMILDataEx(CEMILData.MC_LDATA_REQ, KNXMediumSettings.BackboneRouter, group, tpdu,
				Priority.LOW, true, false, false, 6) {{
				// adjust cEMI Ext Ctrl Field with frame format parameters for LTE
				final int lteExtAddrType = 0x04; // LTE-HEE extended address type
				ctrl2 |= lteExtAddrType;
				ctrl2 |= tagClass;
		}};
		final String svc = cmd == 0 ? "write" : cmd == 1 ? "read" : "info";
		System.out.println("send LTE-HEE " + svc + " " + tag + " IOT " + iot + " OI " + oi + " PID " + pid + " data [" + data + "]");
		link.send(ldata, true);
	}

	// currently only geo tags with wildcards, broadcast, and general peripheral in hex notation is supported
	// returns [tagClass, GroupAddress]
	private static Object[] parseLteTag(final String tag) throws KNXFormatException {
		final String[] split = tag.replaceAll("\\*", "0").split("/", -1);
		final List<Integer> levels = Arrays.stream(split).map(Integer::decode).collect(Collectors.toList());
		if (levels.size() == 1)
			return new Object[] { 3, new GroupAddress(levels.get(0)) };
		// bits 7/6/4
		final int address = (levels.get(0) << 10) | (levels.get(1) << 4) | levels.get(2);
		final int upperRangeGeoTag = address > 0xffff ? 1 : 0;
		return new Object[] { upperRangeGeoTag, new GroupAddress(address & 0xffff) };
	}

	protected static final class LteProcessEvent extends ProcessEvent {
		private static final long serialVersionUID = 1L;

		public final int extFrameFormat;
		private final byte[] tpdu;

		LteProcessEvent(final ProcessCommunicator source, final IndividualAddress src, final int eff,
			final GroupAddress tag, final byte[] tpdu) {
			super(source, src, tag, DataUnitBuilder.getAPDUService(tpdu), DataUnitBuilder.extractASDU(tpdu), false);

			this.extFrameFormat = eff;
			this.tpdu = tpdu;
		}

		public byte[] tpdu() {
			return tpdu.clone();
		}
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

	protected static String decodeLteFrame(final int extFormat, final KNXAddress dst, final byte[] tpdu)
		throws KNXFormatException {
		return NetworkMonitor.decodeLteFrame(extFormat, dst, tpdu);
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
		try (XmlReader r = XmlInputFactory.newInstance().createXMLReader(toolDatapointsFile)) {
			datapoints.load(r);
		}
		catch (final KNXMLException e) {
			out.trace("no monitor datapoint information loaded, " + e.getMessage());
		}
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
						System.out.println("unknown command '" + cmd + "'");
				}
				catch (final KNXTimeoutException e) {
					System.out.println(e.getMessage());
				}
				catch (KNXException | RuntimeException e) {
					out.error("[{}] {}", line, e.toString());
				}
			}
		}
	}

	private void saveDatapoints()
	{
		if (!options.containsKey("monitor"))
			return;
		try (XmlWriter w = XmlOutputFactory.newInstance().createXMLWriter(toolDatapointsFile)) {
			datapoints.save(w);
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
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("medium", TPSettings.TP1);

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
			if (Main.parseCommonOption(args, i, options))
				;
			else if (Main.isOption(arg, "verbose", "v"))
				options.put("verbose", null);
			else if (Main.isOption(arg, "compact", "c"))
				options.put("compact", null);
			else if (Main.isOption(arg, "lte", null)) {
				options.put("lte", null);
				if (options.containsKey("tag")) {
					final List<String> list = new ArrayList<>();
					list.add(options.containsKey("read") ? "read" : options.containsKey("write") ? "write" : "info");
					list.add(args[++i]); // tag
					list.add(args[++i]); // IOT
					list.add(args[++i]); // OI
					if ("company".equals(args[i + 1])) {
						list.add(args[++i]);
						list.add(args[++i]);
					}
					list.add(args[++i]); // PID
					if (options.containsKey("write") || options.containsKey("info"))
						list.add(args[++i]); // data
					options.put("lte-cmd", list.toArray(new String[0]));
				}
			}
			else if (arg.equals("read")) {
				if (i + 1 >= args.length)
					break;
				options.put("read", null);
				if ("--lte".equals(args[i + 1])) {
					options.put("tag", args[i + 2]);
					continue;
				}
				try {
					options.put("dst", new GroupAddress(args[++i]));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("read DPT: " + e.getMessage(), e);
				}
				if ((i + 1 < args.length) && !"-".equals(args[++i]))
					options.put("dpt", args[i]);
			}
			else if (arg.equals("write")) {
				if (i + 3 >= args.length)
					break;
				options.put("write", null);
				if ("--lte".equals(args[i + 1])) {
					options.put("tag", args[i + 2]);
					continue;
				}
				try {
					options.put("dst", new GroupAddress(args[++i]));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("write DPT: " + e.getMessage(), e);
				}
				options.put("dpt", args[++i]);
				options.put("value", args[++i]);
			}
			else if (arg.equals("info")) {
				if (i + 3 >= args.length)
					break;
				options.put("info", null);
				options.put("tag", args[i + 2]);
				continue;
			}
			else if (arg.equals("monitor"))
				options.put("monitor", null);
			else if (Main.isOption(arg, "localhost", null))
				options.put("localhost", Main.parseHost(args[++i]));
			else if (Main.isOption(arg, "localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "port", "p"))
				options.put("port", Integer.decode(args[++i]));
			else if (Main.isOption(arg, "nat", "n"))
				options.put("nat", null);
			else if (Main.isOption(arg, "ft12", "f"))
				options.put("ft12", null);
			else if (Main.isOption(arg, "usb", "u"))
				options.put("usb", null);
			else if (Main.isOption(arg, "tpuart", null))
				options.put("tpuart", null);
			else if (Main.isOption(arg, "medium", "m"))
				options.put("medium", Main.getMedium(args[++i]));
			else if (Main.isOption(arg, "domain", null))
				options.put("domain", Long.decode(args[++i]));
			else if (Main.isOption(arg, "sn", null))
				options.put("sn", Long.decode(args[++i]));
			else if (Main.isOption(arg, "knx-address", null))
				options.put("knx-address", Main.getAddress(args[++i]));
			else if (Main.isOption(arg, "timeout", "t"))
				options.put("timeout", Integer.decode(args[++i]));
			else if (Main.parseSecureOption(args, i, options))
				++i;
			else if (!options.containsKey("host"))
				options.put("host", arg);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (!options.containsKey("host") || (options.containsKey("ft12") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		if (!(options.containsKey("monitor") || options.containsKey("read") || options.containsKey("write") || options.containsKey("info")))
			throw new KNXIllegalArgumentException("specify read, write, or group monitoring");
		if (options.containsKey("read") && options.containsKey("write"))
			throw new KNXIllegalArgumentException("either read or write - not both");

		setDomainAddress(options);
		setRfDeviceSettings();
	}

	private void setRfDeviceSettings() {
		final Long value = (Long) options.get("sn");
		if (value == null)
			return;
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (medium.getMedium() != KNXMediumSettings.MEDIUM_RF)
			throw new KNXIllegalArgumentException(
					medium.getMediumString() + " networks don't use serial number, use --medium to specify KNX RF");
		final RFSettings rf = ((RFSettings) medium);
		final IndividualAddress device = (IndividualAddress) options.getOrDefault("knx-address", rf.getDeviceAddress());

		final byte[] sn = new byte[6];
		final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).putLong(value).position(2);
		buffer.get(sn);
		options.put("medium", new RFSettings(device, rf.getDomainAddress(), sn, rf.isUnidirectional()));
	}

	private static void showUsage()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("Usage: ").append(tool + " [options] <host|port>").append(sep);
		sb.append("Options:").append(sep);
		sb.append("  --help -h                show this help message").append(sep);
		sb.append("  --version                show tool/library version and exit").append(sep);
		sb.append("  --verbose -v             enable verbose status output").append(sep);
		sb.append("  --localhost <id>         local IP/host name").append(sep);
		sb.append("  --localport <number>     local UDP port (default system assigned)")
				.append(sep);
		sb.append("  --port -p <number>       UDP port on <host> (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT + ")").append(sep);
		sb.append("  --nat -n                 enable Network Address Translation").append(sep);
		sb.append("  --ft12 -f                use FT1.2 serial communication").append(sep);
		sb.append("  --usb -u                 use KNX USB communication").append(sep);
		sb.append("  --tpuart                 use TP-UART communication").append(sep);
		sb.append("  --medium -m <id>         KNX medium [tp1|p110|knxip|rf] (default tp1)").append(sep);
		sb.append("  --domain <address>       domain address on KNX PL/RF medium (defaults to broadcast domain)")
				.append(sep);
		sb.append("Available commands for process communication:").append(sep);
		sb.append("  read <DPT> <KNX address>           read from group address").append(sep);
		sb.append("  write <DPT> <value> <KNX address>  write to group address").append(sep);
		sb.append("  monitor                 enter group monitoring").append(sep);
		sb.append("Name aliases for common DPT numbers:").append(sep);
		sb.append("  switch (1.001) {off, on}, bool (1.002) {false, true}")
				.append(sep)
				.append("  dimmer (3.007) {decrease 0..7, increase 0..7}, "
						+ "blinds (3.008) {up 0..7, down 0..7}").append(sep)
				.append("  percent (5.001) {1..100}, % (5.001) {1..100}").append(sep)
				.append("  angle (5.003) {0..360}, ucount (5.010) {0..255}").append(sep)
				.append("  temp (9.001) {-273..+670760}, float/float2 (9.002)").append(sep)
				.append("  int (13.001), float4 (14.005), string (16.001)");
		out(sb.toString());
	}

	//
	// utility methods
	//

	private static void showVersion()
	{
		out(Settings.getLibraryHeader(false));
	}

	private static void out(final String s)
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
}
