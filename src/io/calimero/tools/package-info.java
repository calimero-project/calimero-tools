/**		
Contains a collection of Calimero command-line tools for KNX network communication and management. 
Each tool provides a ready-to-use functionality of the Calimero-core library.
<p>

Access to the KNX network is supported using KNXnet/IP tunneling, KNXnet/IP routing or KNX IP over an 
IP network connection, KNX USB and KNX RF USB over a USB connection, and FT1.2 or TP-UART over a serial port connection.
<p>

KNXnet/IP address considerations:
<ul>
<li>If the local endpoint has several active network 
interfaces (also known as multi-homed host), the default chosen local network interface and IP address
might not be the one expected by the user. In that situation, the local IP
address has to be supplied via a command-line option.</li>  
<li>The KNXnet/IP protocol expects IPv4 addresses by default.</li> 
<li>Network Address Translation (NAT) aware communication can only be used if the KNXnet/IP server supports it. 
Otherwise, connection timeouts will occur.</li>
<li>With NAT enabled, KNXnet/IP also accepts IPv6 addresses.</li>
</ul>
*/
package io.calimero.tools;