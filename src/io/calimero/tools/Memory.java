/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2021, 2025 B. Malinowsky

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

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

import java.lang.System.Logger;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import io.calimero.DataUnitBuilder;
import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.KNXFormatException;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.medium.TPSettings;
import io.calimero.log.LogService;
import io.calimero.mgmt.ManagementClient;
import io.calimero.mgmt.ManagementProcedures;
import io.calimero.mgmt.ManagementProceduresImpl;
import io.calimero.tools.Main.ShutdownHandler;

/**
 * A tool for accessing KNX device memory.
 * Memory is a {@link Runnable} tool implementation allowing a user to read or write memory in a KNX device.
 * <br>
 * This tool supports KNX network access using a KNXnet/IP, KNX IP, USB, FT1.2, or TP-UART connection. It uses the
 * {@link ManagementClient} functionality of the library to read/write memory locations.
 * <p>
 * When running this tool from the console, the <code>main</code>- method of this class is invoked, otherwise use this
 * class in the context appropriate to a {@link Runnable}.<br>
 * In console mode, the read memory as well as errors and problems during execution are written to
 * <code>System.out</code>.
 */
public class Memory implements Runnable {
	private static final String tool = "Memory";

	private static final Logger out = LogService.getLogger("io.calimero.tools");

	private ManagementProcedures mp;

	private final Map<String, Object> options = new HashMap<>();

	/**
	 * Creates a new Memory instance using the supplied options.
	 * <p>
	 * Mandatory arguments are the connection options depending on the type of connection to the KNX network, the
	 * KNX device address ("area.line.device"), and the read or write command.
	 * See {@link #main(String[])} for the list of options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public Memory(final String[] args) {
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
	 * Entry point for running Memory.
	 * <p>
	 * Syntax: Memory [options] &lt;host|port&gt; &lt;KNX device address&gt; read|write &lt;addr&gt; [bytes|data]
	 * <p>
	 * To show usage message of the tool on the console, supply the command line option --help (or -h). Command line
	 * options are treated case-sensitive. Available options for connecting to the KNX device in question:
	 * <ul>
	 * <li>no arguments: only show short description and version info</li>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local endpoint</li>
	 * <li><code>--dec</code> &nbsp;data is interpreted in unsigned decimal format</li>
	 * </ul>
	 * The <code>--knx-address</code> option is only necessary if an access protocol is selected that directly
	 * communicates with the KNX network, i.e., KNX IP or TP-UART. The selected KNX individual address shall be unique
	 * in a network, and the subnetwork address (area and line) should be set to match the network configuration.
	 * <p>
	 * Supported commands:
	 * <ul>
	 * <li><code>read</code> (or <code>r</code>) <i>addr [bytes]</i> &nbsp;&nbsp; read number of bytes (default 1) starting at memory address</li>
	 * <li><code>write</code> (or <code>w</code>) <i>addr data</i> &nbsp;&nbsp; write data to memory starting at address</li>
	 * </ul>
	 *
	 * @param args command line options for running the device info tool
	 */
	public static void main(final String... args) {
		try {
			final Memory d = new Memory(args);
			final ShutdownHandler sh = new ShutdownHandler().register();
			d.run();
			sh.unregister();
		}
		catch (final Throwable t) {
			out.log(ERROR, "parsing options", t);
		}
	}

	@Override
	public void run() {
		Exception thrown = null;
		boolean canceled = false;

		try {
			if (options.isEmpty()) {
				out(tool + " - Access KNX device memory");
				Main.showVersion();
				out("Type --help for help message");
				return;
			}
			if (options.containsKey("about")) {
				((Runnable) options.get("about")).run();
				return;
			}

			// setup for reading device info of remote device
			try (var link = Main.newLink(options);
				 var mpImpl = new ManagementProceduresImpl(link)) {
				mp = mpImpl;
				readWriteMemory();
			}
		}
		catch (KNXException | RuntimeException e) {
			thrown = e;
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		finally {
			onCompletion(thrown, canceled);
		}
	}

	public sealed interface Command {}
	public record Read(int startAddress, int length) implements Command {}
	public record Write(int startAddress, byte... data) implements Command {}
	private record Done() implements Command {}
	public static final Command Done = new Done();

	protected Command fetchCommand() {
		if (options.remove("read") instanceof final Integer startAddr)
			return new Read(startAddr, (int) options.get("bytes"));
		if (options.remove("write") instanceof final Integer startAddr) {
			final String s = (String) options.get("data");
			final byte[] data = options.containsKey("dec") ? new BigInteger(s).toByteArray() : DataUnitBuilder.fromHex(s);
			return new Write(startAddr, data);
		}
		return Done;
	}

	/**
	 * Invoked on each successfully memory read.
	 *
	 * @param data memory data
	 */
	protected void onMemoryRead(final int address, final byte[] data) {
		if (options.containsKey("json")) {
			record JsonMemory(String startAddress, int length, byte[] data) implements Json {}
			out(new JsonMemory(Integer.toHexString(address), data.length, data).toJson());
			return;
		}
		out(data);
	}

	/**
	 * Called by this tool on completion.
	 *
	 * @param thrown the thrown exception if operation completed due to a raised exception, <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled) {
		if (canceled)
			out.log(INFO, "memory access canceled");
		if (thrown != null)
			out.log(ERROR, "completed with error", thrown);
	}

	private void out(final byte[] data) {
		final String s;
		if (options.containsKey("dec"))
			s = new BigInteger(1, data).toString();
		else
			s = "0x" + HexFormat.of().formatHex(data);
		out(s);
	}

	private void readWriteMemory() throws KNXException, InterruptedException {
		final IndividualAddress device = (IndividualAddress) options.get("device");
		for (var cmd = fetchCommand(); cmd != Done; cmd = fetchCommand()) {
			if (cmd instanceof final Read read) {
				out.log(DEBUG, "read {0} 0x{1}..0x{2}", device, Long.toHexString(read.startAddress()),
						Long.toHexString(read.startAddress() + read.length() - 1));
				onMemoryRead(read.startAddress(), mp.readMemory(device, read.startAddress(), read.length()));
			}
			else {
				final var write = (Write) cmd;
				final int startAddr = write.startAddress();
				final byte[] data = write.data();
				out.log(DEBUG, "write to {0} 0x{1}..0x{2}: {3}", device, Long.toHexString(startAddr),
						Long.toHexString(startAddr + data.length - 1), HexFormat.ofDelimiter(" ").formatHex(data));
				mp.writeMemory(device, startAddr, data, false, false);
			}
		}
	}

	/**
	 * Reads all command line options, and puts those options into the options map.
	 * Default option values are added; on unknown options, a KNXIllegalArgumentException is thrown.
	 *
	 * @param args array with command line options
	 */
	private void parseOptions(final String[] args) {
		if (args.length == 0)
			return;

		// add defaults
		options.put("port", KNXnetIPConnection.DEFAULT_PORT);
		options.put("bytes", 1);

		for (final var i = List.of(args).iterator(); i.hasNext();) {
			final String arg = i.next();
			if (Main.isOption(arg, "help", "h")) {
				options.put("about", (Runnable) Memory::showUsage);
				return;
			}
			if (Main.parseCommonOption(arg, i, options))
				;
			else if (Main.parseSecureOption(arg, i, options))
				;
			else if (Main.isOption(arg, "knx-address", "k"))
				options.put("knx-address", Main.getAddress(i.next()));
			else if (Main.isOption(arg, "dec", null))
				options.put("dec", null);
			else if (arg.equals("read") || arg.equals("r")) {
				options.put("read", Integer.decode(i.next()));
				if (i.hasNext())
					options.put("bytes", Integer.parseUnsignedInt(i.next()));
			}
			else if (arg.equals("write") || arg.equals("w")) {
				options.put("write", Integer.decode(i.next()));
				options.put("data", i.next());
			}
			else if (!options.containsKey("host"))
				// otherwise add a host key with argument as host
				options.put("host", arg);
			else if (!options.containsKey("device"))
				// otherwise create the KNX device address from the argument
				try {
					options.put("device", new IndividualAddress(arg));
				}
				catch (final KNXFormatException e) {
					throw new KNXIllegalArgumentException("KNX device " + e, e);
				}
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		// we allow a default usb config where the first found knx usb device is used
		if (options.containsKey("usb") && !options.containsKey("host"))
			options.put("host", "");

		if (!options.containsKey("host") || (options.containsKey("ft12") && options.containsKey("usb")))
			throw new KNXIllegalArgumentException("specify either IP host, serial port, or device");
		if (!options.containsKey("device"))
			throw new KNXIllegalArgumentException("missing remote KNX device address");
		if (!options.containsKey("medium"))
			options.put("medium", new TPSettings());
		Main.setDomainAddress(options);
	}

	private static void showUsage() {
		final var joiner = new StringJoiner(System.lineSeparator());
		joiner.add("Usage: " + tool + " [options] <host|port> <KNX device address> read|write addr ...");
		joiner.add("Commands:");
		joiner.add("  read <address> [bytes]     read number of bytes (default 1) starting at memory address");
		joiner.add("  write <address> data       write data (hex default) to memory starting at address");
		joiner.add(Main.printCommonOptions());
		joiner.add("  --dec                      interpret memory data in decimal format");
		joiner.add(Main.printSecureOptions());
		out(joiner.toString());
	}

	private static void out(final String s) {
		System.out.println(s);
	}
}
