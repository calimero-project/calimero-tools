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

package io.calimero.tools;

import static java.lang.System.Logger.Level.ERROR;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;

import io.calimero.KNXIllegalArgumentException;
import io.calimero.log.LogService;
import io.calimero.mgmt.PropertyAdapter;
import io.calimero.mgmt.PropertyClient;

/**
 * A tool for Calimero showing features of the {@link PropertyClient} used for KNX property access.
 * <p>
 * PropClient is a console based tool implementation for reading and writing KNX properties. It
 * supports network access using a KNXnet/IP, USB, or FT1.2 connection. To start the PropClient,
 * invoke the <code>main</code>-method of this class. Take a look at the command line options to
 * configure the tool with the desired communication settings.
 * <p>
 * The main part of this tool implementation interacts with the PropertyClient interface, which
 * offers high level access to KNX property information. It also shows creation of the
 * {@link PropertyAdapter}, necessary for a property client to work. All queried property values, as
 * well as occurring problems are written to <code>System.out
 * </code>.
 *
 * @author B. Malinowsky
 */
public class PropClient implements Runnable
{
	class PropertyEx extends io.calimero.tools.Property
	{
		PropertyEx(final String[] args)
		{
			super(args);
		}

		@Override
		protected void runCommand(final String... cmd) throws InterruptedException
		{
			// ignore any command supplied on command line
			options.remove("command");
			// show some command info
			super.runCommand("?");
			out("exit - close connection and exit");
			runReaderLoop(PropClient.this);
		}

		private void runReaderLoop(final PropClient propClient)
		{
			// create reader for user input
			final BufferedReader r = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
			String[] args;
			try {
				while ((args = propClient.readLine(r)) != null) {
					if (args.length > 0 && !(args.length == 1 && args[0].isEmpty())) {
						if ("exit".equalsIgnoreCase(args[0]))
							break;
						super.runCommand(args);
					}
				}
			}
			catch (InterruptedException | InterruptedIOException e) {
				System.out.println("received interrupt, closing ...");
			}
			catch (final IOException e) {
				System.out.println("I/O error, " + e.getMessage());
			}
		}
	}

	private final PropertyEx property;

	/**
	 * Constructs a new PropClient.
	 * <p>
	 *
	 * @param args options for the property client tool, see {@link #main(String[])}
	 * @throws KNXIllegalArgumentException on missing or wrong formatted option value
	 */
	public PropClient(final String[] args)
	{
		property = new PropertyEx(args);
	}

	/**
	 * Entry point for running the PropClient.
	 * <p>
	 * An IP host or port identifier has to be supplied to specify the endpoint for the KNX network
	 * access.<br>
	 * To show the usage message of this tool on the console, supply the command line option -help
	 * (or -h).<br>
	 * Command line options are treated case sensitive. Available options for the property client:
	 * <ul>
	 * <li><code>--help -h</code> show help message</li>
	 * <li><code>--version</code> show tool/library version and exit</li>
	 * <li><code>--local -l</code> local device management</li>
	 * <li><code>--remote -r</code> <i>KNX addr</i> &nbsp;remote property service</li>
	 * <li><code>--definitions -d</code> <i>file</i> &nbsp;use property definition file</li>
	 * <li><code>--localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>--localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>--port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>--nat -n</code> enable Network Address Translation</li>
	 * <li><code>--ft12 -f</code> use FT1.2 serial communication</li>
	 * <li><code>--usb -u</code> use KNX USB communication</li>
	 * <li><code>--tpuart</code> use TP-UART communication</li>
	 * </ul>
	 * For local device management these options are available:
	 * <ul>
	 * <li><code>--emulatewriteenable -e</code> check write-enable of a property</li>
	 * </ul>
	 * For remote property service these options are available:
	 * <ul>
	 * <li><code>--medium -m</code> <i>id</i> &nbsp;KNX medium [tp1|p110|knxip|rf] (defaults to tp1)</li>
	 * <li><code>--domain</code> <i>address</i> &nbsp;domain address on open KNX medium (PL or RF)</li>
	 * <li><code>--knx-address -k</code> <i>KNX address</i> &nbsp;KNX device address of local endpoint</li>
	 * <li><code>--connect -c</code> connection oriented mode</li>
	 * <li><code>--authorize -a</code> <i>key</i> &nbsp;authorize key to access KNX device</li>
	 * </ul>
	 *
	 * @param args command line options for property client
	 */
	public static void main(final String[] args)
	{
		Property.out = LogService.getLogger("io.calimero.tools");
		try {
			final PropClient pc = new PropClient(args);
			pc.run();
		}
		catch (final Throwable t) {
			Property.out.log(ERROR, "client error", t);
		}
	}

	@Override
	public void run()
	{
		property.run();
	}

	/**
	 * Writes command prompt and waits for a command request from the user.
	 * <p>
	 *
	 * @param r input reader
	 * @return array with command and command arguments
	 * @throws IOException on I/O error
	 * @throws InterruptedException on interrupted thread
	 */
	private String[] readLine(final BufferedReader r) throws IOException, InterruptedException
	{
		System.out.print("> ");
		synchronized (this) {
			while (property.pc.isOpen() && !r.ready())
				wait(100);
		}
		final String line = r.readLine();
		return line != null ? line.trim().split("\\s") : null;
	}
}
