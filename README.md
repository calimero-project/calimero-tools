# Calimero Tools [![Java CI with Gradle](https://github.com/calimero-project/calimero-tools/actions/workflows/gradle.yml/badge.svg)](https://github.com/calimero-project/calimero-tools/actions/workflows/gradle.yml)


~~~ sh
git clone https://github.com/calimero-project/calimero-tools.git
~~~

A collection of KNX network tools based on Calimero for (secure) process communication, monitoring, and management.

With Maven, execute

	$ mvn install

With Gradle, execute

	./gradlew build

### Docker image

Pre-built Docker images for running the tools are available on [Docker Hub](https://hub.docker.com/r/calimeroproject/knxtools).


Available Tools
---------------
Use `./gradlew run` or `mvn exec:java` to list available commands.

* Discover - KNXnet/IP discovery and self description
* DeviceInfo - shows device information of a device in a KNX network (using the device's interface objects)
* IPConfig - read/write the IP configuration of a KNXnet/IP server using KNX properties
* NetworkMonitor - busmonitor for KNX networks (monitor raw frames on the network, completely passive)
* ProcComm - process communication, read or write a KNX datapoint, or group monitor KNX datapoints
* ProgMode - shows the KNX devices currently in programming mode
* PropClient - a property client for KNX device property descriptions, get or set KNX device properties
* Property - get/set a single KNX device interface object property
* BaosClient - communicate with a KNX BAOS device
* ScanDevices - list KNX devices, or check whether a specific KNX individual address is currently assigned to a KNX device
* Restart - performs a basic restart or master reset of a KNX interface or KNX device
* DatapointImporter - import datapoint information from a KNX project (.knxproj) or group addresses file (.xml or .csv) for use with Calimero 


Examples
-------------

Note, using KNX Secure requires a keyring (`--keyring`) and keyring password (`--keyring-pwd`); by default the keyring in the current working directory is used. Alternatively, for KNX IP Secure, the following command-line options are supported:

* Secure Multicast: `--group-key` _<16 bytes hex key>_
* Secure Unicast:
    * `--user` _&lt;user ID>_
    * `--user-pwd` _&lt;pwd>_, or `--user-key` _<16 bytes hex key>_
    * (optional) `--device-pwd` _&lt;auth>_, or `--device-key` _<16 bytes hex key>_

### Using Gradle

Show all supported tools

    ./gradlew run

Discover KNX IP devices

    ./gradlew run --args discover

Run group monitor (using KNXnet/IP Routing)

	./gradlew run --args="groupmon 224.0.23.12"

Use `-h` or `--help` for help with a tool (here, _scan_ for scanning devices)

	./gradlew run --args="scan -h"
	
KNX IP Secure multicast group monitor using a keyring

	./gradlew run --args="groupmon 224.0.23.12 --keyring mykeys.knxkeys --keyring-pwd quack"

### Using Maven

Show all supported tools

	mvn exec:java

Run a command with option `--help` to show the help message for usage

	mvn exec:java -Dexec.args="groupmon --help"

The equivalent of the above command using explicit invocation would be

	mvn exec:java -Dexec.mainClass=io.calimero.tools.ProcComm -Dexec.args="--help"

**Discover KNXnet/IP devices**

~~~ sh
# Variant which executes the `discover` command
$ mvn exec:java -Dexec.args=discover

# Variant which specifically refers to the tool class
$ mvn exec:java -Dexec.mainClass=io.calimero.tools.Discover -Dexec.args=--search
~~~

**Process Communication**

Start process communication for group monitoring (command `groupmon`), accessing a KNX power-line network (`--medium p110` or `-m p110`) using a USB interface with name `busch-jaeger` (or any other KNX vendor or product name, e.g., `siemens`).

	mvn exec:java -Dexec.args="groupmon --usb busch-jaeger -m p110"

With USB, you can also specify the USB interface using the vendor and product ID as `VendorID:ProductID`. If you don't know any identification yet, run the tool using a bogus ID and debug settings to print the available USB interfaces.

Start process communication for group monitoring, accessing a RF network using a Weinzierl USB interface. Adjust the slf4 [Simple Logger](http://www.slf4j.org/api/org/slf4j/impl/SimpleLogger.html) logging level for `debug` output using `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`:

	mvn exec:java -Dexec.args="groupmon --usb weinzierl -m rf" -Dorg.slf4j.simpleLogger.defaultLogLevel=debug

**Local Device Management**

Access the KNX properties of your KNXnet/IP server with control endpoint `192.168.10.10` using  _local_ device management

	mvn exec:java -Dexec.args="properties 192.168.10.10"

**Remote Device Management**

Remote property services (this example only works if the KNX device implements _Interface Objects_): open a client to a remote (`-r`) KNX device with the device address `1.1.5`, via KNXnet/IP tunneling to a KNXnet/IP server with control endpoint `192.168.10.10`

	mvn exec:java -Dexec.args="properties 192.168.10.10 -r 1.1.5"

Once you enter the CLI of the property client, execute, e.g., `scan all` to scan all KNX properties of that device.



### Using Java

Replace the version in the examples (2.6-SNAPSHOT) with the exact version you are running. Make sure all dependencies are available, either by relying on the Calimero Tools MANIFEST file or the [Java class path](https://docs.oracle.com/javase/8/docs/technotes/tools/windows/classpath.html) settings (using the `-classpath` option or the [CLASSPATH](https://docs.oracle.com/javase/tutorial/essential/environment/paths.html) environment variable). The simplest way is to have all required `.jar` files in the same directory.

For an overview of tools, run

	java -jar calimero-tools-2.6-SNAPSHOT.jar

**Discover KNXnet/IP devices**

Discover KNXnet/IP servers, with Network Address Translation (NAT) enabled:

	java -jar calimero-tools-2.6-SNAPSHOT.jar discover search --nat

**Process Communication**

Read a KNX datapoint value (switch button on/off) from a group address (`1/2/1`) using the FT1.2 protocol over the serial port `/dev/ttyS01`

	java -jar calimero-tools-2.6-SNAPSHOT.jar read switch 1/2/1 --ft12 /dev/ttyS01

Start process communication group monitoring for a TP1 KNX network (the default) using KNXnet/IP Routing in the multicast group `224.0.23.12`, and a specific local host address (`--localhost`, useful in multihoming to specify the outgoing network interface)

	java -jar calimero-tools-2.6-SNAPSHOT.jar groupmon --localhost 192.168.10.14 224.0.23.12

**Busmonitor**

Start a KNX busmonitor on a KNX TP1 (Twisted Pair) network, using a compact (`-c` or `--compact`) busmonitor indication output format

	java -jar calimero-tools-2.6-SNAPSHOT.jar monitor -c --usb busch-jaeger

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

	java -cp "calimero-tools-2.6-SNAPSHOT.jar" devinfo 192.168.10.12 1.1.4


Run tools using Gradle without source code
-------

With Gradle installed, copy the following snippet into a file named *build.gradle*, and execute `gradle` in the same directory (see [Examples](#Examples), e.g., `gradle run --args="monitor 192.168.115.6"`).

```gradle
plugins { id 'application' }
repositories { mavenCentral() }
mainClassName = "io.calimero.tools.Main"
dependencies {
  runtimeOnly group: 'io.calimero', name: 'calimero-tools', version: '2.6-SNAPSHOT'
}
```

Logging
-------

Calimero Tools use the [Simple Logging Facade for Java (slf4j)](http://www.slf4j.org/). Bind any desired logging frameworks of your choice. By default, the [Simple Logger](http://www.slf4j.org/api/org/slf4j/impl/SimpleLogger.html) is used. It logs everything to standard output. The simple logger can be configured via the resource file `simplelogger.properties`, or -- with Maven -- using command line arguments, e.g., `-Dorg.slf4j.simpleLogger.defaultLogLevel=warn`.

Extending Tools
---------------

All tools implement the interface `Runnable` and can be extended.
Override the method that provides the result and customize its behavior. For example, with KNXnet/IP discovery

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
