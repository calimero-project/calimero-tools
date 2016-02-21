Calimero Tools
==============

A collection of KNX network tools based on Calimero.

Download
--------

~~~ sh
# Either using git
$ git clone https://github.com/calimero-project/calimero-tools.git

# Or using hub
$ hub clone calimero-project/calimero-tools
~~~

Compile and execute the tools using your favorite Java IDE, e.g., import into Eclipse or NetBeans. Alternatively, with maven available on the terminal, execute

~~~ sh
$ mvn clean install -DskipTests
~~~


Available Tools
---------------

Currently, the following tools are provided:

* DeviceInfo - shows device information of a device in a KNX network (using the device's interface objects)
* Discover - KNXnet/IP discovery and self description
* IPConfig - read/write the IP configuration of a KNXnet/IP server using KNX properties
* NetworkMonitor - busmonitor for KNX networks (monitor raw frames on the network, completely passive)
* ProcComm - process communication, read or write a KNX datapoint, or group monitor KNX datapoints
* PropClient - a property client for KNX device property descriptions, get or set KNX device properties
* Property - get/set a single KNX device interface object property
* ScanDevices - list KNX devices, or check whether a specific KNX individual address is currently assigned to a KNX device


Tool Examples
-------------

### Using Maven

Run a tool with no arguments for basic info

	mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.ProcComm 

Output

	18:26:02:753 INFO ProcComm - KNX process communication
	18:26:02:755 INFO Calimero 2 version 2.3-dev
	18:26:02:755 INFO Type --help for help message

or, relying on the Maven POM information, show all supported commands

	mvn exec:java
	
Run a command with option `--help` to show the help message for usage

	mvn exec:java -Dexec.args="groupmon --help"

The equivalent of the above command using explicit invocation would be

	mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.ProcComm -Dexec.args="--help"

**Discover KNXnet/IP devices**

~~~ sh
# Variant which executes the `discover` command 
$ mvn exec:java -Dexec.args=discover

# Variant which specifically refers to the tool class
$ mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.Discover -Dexec.args=--search
~~~

**Process Communication**

Start process communication for group monitoring (command `groupmon`), accessing a KNX power-line network (`--medium p110` or `-m p110`) using a USB interface with name `busch-jaeger` (or any other KNX vendor or product name, e.g., `siemens`).

	mvn exec:java -Dexec.args="groupmon -v --usb busch-jaeger -m p110"
	
The option `-v` sets logging to `--verbose`. With USB, you can also specify the USB interface using the vendor and product ID as `VendorID:ProductID`. If you don't know any identification yet, run the tool using a bogus ID and debug settings to print the available USB interfaces. 
	
Start process communication for group monitoring, accessing a RF network using a Weinzierl USB interface. Adjust the slf4 [Simple Logger](http://www.slf4j.org/api/org/slf4j/impl/SimpleLogger.html) logging level for `debug` output using `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`:

	mvn exec:java -Dexec.args="groupmon --usb weinzierl -m rf" -Dorg.slf4j.simpleLogger.defaultLogLevel=debug

**Local Device Management**

Open a client for _local_ device management of your KNXnet/IP server with control endpoint `192.168.10.10`

	mvn exec:java -Dexec.args="properties 192.168.10.10 -d resources/properties.xml"

**Remote Device Management**

Remote property services (this example only works if the KNX device implements _Interface Objects_): open a client to a 
remote (`-r`) KNX device with the device address `1.1.5`, via KNXnet/IP tunneling to a KNXnet/IP server with control 
endpoint `192.168.10.10`

	mvn exec:java -Dexec.args="properties 192.168.10.10 -r 1.1.5 -d resources/properties.xml"

Once you enter the CLI of the property client, execute, e.g., `scan all` to scan all KNX properties of that device.



### Using Java

Replace the version in the examples (2.3-SNAPSHOT) with the exact version you are running. Make sure all dependencies are available, either by relying on the Calimero Tools MANIFEST file or the [Java class path](https://docs.oracle.com/javase/8/docs/technotes/tools/windows/classpath.html) settings (using the `-classpath` option or the [CLASSPATH](https://docs.oracle.com/javase/tutorial/essential/environment/paths.html) environment variable). The simplest way is to have all required `.jar` files in the same directory.

For an overview of tools, run

	java -jar calimero-tools-2.3-SNAPSHOT.jar

**Discover KNXnet/IP devices**

Discover KNXnet/IP servers, with Network Address Translation (NAT) enabled:

	java -jar calimero-tools-2.3-SNAPSHOT.jar discover -s --nat

**Process Communication**

Read a KNX datapoint value (switch button on/off) from a group address (`1/2/1`) using the FT1.2 protocol over the serial port `/dev/ttyS01`

	java -jar calimero-tools-2.3-SNAPSHOT.jar read switch 1/2/1 --serial /dev/ttyS01

Start process communication group monitoring for a TP1 KNX network (the default) using KNXnet/IP routing (`--routing`) in the multicast group `224.0.23.12`, and a specific local host address (`--localhost`, useful in multihoming to specify the outgoing network interface)

	java -jar calimero-tools-2.3-SNAPSHOT.jar groupmon --routing --localhost 192.168.10.14 224.0.23.12

**Busmonitor**

Start a KNX busmonitor on a KNX TP1 (Twisted Pair) network, using a compact (`-c` or `--compact`) busmonitor indication output format

	java -jar calimero-tools-2.3-SNAPSHOT.jar monitor -c --usb busch-jaeger

Calimero busmonitor output in compact mode looks like

~~~ sh
02:22:09.457 Seq 0 L-Data.req 7.1.13->2/1/0, low priority FCS 0x4e domain 0x6f, tpdu 00 81: T_Group, A_Group.write 01
02:22:09.475 Seq 1 ACK
02:22:09.810 Seq 2 L-Data.req 7.1.13->2/1/20, low priority FCS 0xa7 domain 0x6f, tpdu 00 80 ff: T_Group, A_Group.write ff
02:22:09.828 Seq 3 ACK
02:22:10.726 Seq 4 L-Data.req 7.1.13->2/1/11, low priority FCS 0x44 domain 0x6f, tpdu 00 80: T_Group, A_Group.write 00
02:22:10.744 Seq 5 ACK
~~~

**Device Information**

Read device information of KNX device `1.1.4` in a TP1 network (default medium) using the KNXnet/IP server `192.168.10.12`

	java -cp "calimero-tools-2.3-SNAPSHOT.jar" devinfo 192.168.10.12 1.1.4


Logging
-------

Calimero Tools use the [Simple Logging Facade for Java (slf4j)](http://www.slf4j.org/). Bind any desired logging frameworks of your choice. By default, the [Simple Logger](http://www.slf4j.org/api/org/slf4j/impl/SimpleLogger.html) is used. It logs everything to standard output. The simple logger can be configured via the resource file `simplelogger.properties`, or--with Maven--using command line arguments, e.g., `-Dorg.slf4j.simpleLogger.defaultLogLevel=warn`.

Extending Tools
---------------

All tools implement the interface `Runnable` and can be extended.
Override the method that provides the result and customize behavior. For example, with KNXnet/IP discovery

```
public class MyDiscovery extends Discover {
	public MyDiscovery(String[] args) throws KNXException {
		super(args);
	}

	@Override
	protected void onEndpointReceived(Result<SearchResponse> result) {
		// Use result ...
	}
}
```
