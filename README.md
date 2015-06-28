Calimero Tools
==============

A collection of KNX network tools using Calimero.

The Java 8 equivalent is on branch [feat/jse-embd8-c1](https://github.com/calimero-project/calimero-tools/tree/feat/jse-embd8-c1).

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
	18:26:02:755 INFO Type -help for help message

or, relying on the Java Manifest information, show all available tools

	mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.Main
	
Run with option `-help` to show the help message for usage

	mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.ProcComm -Dexec.args="-help"


Discover KNXnet/IP devices

~~~ sh
# Variant which executes the jar relying on the Java MANIFEST 
$ mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.Main -Dexec.args=discover

# Variant which specifically refers to the tool class
$ mvn exec:java -Dexec.mainClass=tuwien.auto.calimero.tools.Discover -Dexec.args=-s
~~~
	
### Using Java

Replace the version in the examples (2.3-SNAPSHOT) with the exact version you are running. Make sure all dependencies are available, either by relying on the Calimero Tools MANIFEST file or the [Java class path](https://docs.oracle.com/javase/8/docs/technotes/tools/windows/classpath.html) settings (using the `-classpath` option or the [CLASSPATH](https://docs.oracle.com/javase/tutorial/essential/environment/paths.html) environment variable). The simplest way is to have all required `.jar` files in the same directory.

For an overview of tools, run

	java -jar calimero-tools-2.3-SNAPSHOT.jar

Discover KNXnet/IP servers, with Network Address Translation (NAT) enabled:

	java -jar calimero-tools-2.3-SNAPSHOT.jar discover -s -nat

Read a KNX datapoint value (switch button on/off) from a group address (`1/2/1`) using the FT1.2 protocol over the serial port `/dev/ttyS01`

	java -jar calimero-tools-2.3-SNAPSHOT.jar read switch 1/2/1 -serial /dev/ttyS01

Start process communication group monitoring for a TP1 KNX network (the default) using KNXnet/IP routing (`-routing`) in the multicast group `224.0.23.12`, and a specific local host address (`-localhost`, useful in multihoming to specify the outgoing network interface)

	java -jar calimero-tools-2.3-SNAPSHOT.jar groupmon -localhost 192.168.10.14 224.0.23.12 -routing

Read device information of KNX device `1.1.4` (**Prerequisite**: device implements Interface Objects!) in a TP1 network (default medium) using the KNXnet/IP server `192.168.10.12`

	java -cp "calimero-tools-2.3-SNAPSHOT.jar" tuwien.auto.calimero.tools.DeviceInfo 192.168.10.12 1.1.4
