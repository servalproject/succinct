Succinct Data (Android)
======================

An application specifically built for Red Cross field teams to communicate and collect data, when limited 
connectivity options are available.


Basic Usage
-----------

A team leader must first start a team on their android device. This device will then be responsible for all 
communication between the team and EOC.

The team leader and team members must then connect their devices to the same WiFi networks. For example, by 
using a Serval Mesh Extender, or by turning a phone into a portable hotspot.

Once a network connection has been established, additional team members should be able to discover and join the team.

The software will display an annoying prompt to the user if GPS is disabled. It is expected that at least one team member 
(including the team leader) will have GPS location services enabled at all times. But there may be legitimate 
reasons for leaving GPS disabled, for example to preserve battery life.



Maps
----

Multiple offline map files can be used at the same time, including a low resolution map of the coastlines of the entire world. 
At this time, map files must be manually downloaded from http://download.mapsforge.org/ 
and copied to the internal storage of each device in the folder /sdcard/succinct/


Team to EOC Communications
--------------------------

The software supports three different data channels between the EOC web site and the team.

When the team leader has internet access available, all data messages from the team to EOC should be sent as soon as they are created.
The team leaders device will also check for incoming data messages once per minute.

If the team leader can send SMS messages, but has no internet access, then data messages will be sent this way. However, this is a one-way communication path.
There is no way for messages from EOC to be received.

If neither of the above channels are available, and the team leader has paired with a Rock 7 unit, then data messages will be sent via the Iridium satellite service.
If the most recent contact that EOC has received from a team is via their Rock 7 unit, then all outgoing data messages will also be sent via the Iridium network.

Note that the Rock unit must first contact the Iridium network in order to receive any incoming messages. 
Each rock unit can also be configured to periodically check for incoming messages, though this will also use up credit.
Ensuring that at least one team member has enabled location services, will guarentee that the Rock unit will be periodically used.

Primarily for testing purposes, each of the above data transports can be disabled by the team leader in the settings screen.
