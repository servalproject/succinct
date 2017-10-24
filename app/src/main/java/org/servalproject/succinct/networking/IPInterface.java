package org.servalproject.succinct.networking;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class IPInterface implements Interface{
	final String name;
	final byte[] addrBytes;
	final InetAddress address;
	final int prefixLength;
	final InetAddress broadcastAddress;
	public boolean up = true;

	IPInterface(String name, byte[] addr, byte[] broadcast, int prefixLength) throws UnknownHostException {
		this.name = name;
		this.addrBytes = addr;
		this.address = InetAddress.getByAddress(addr);
		this.broadcastAddress = InetAddress.getByAddress(broadcast);
		this.prefixLength = prefixLength;
		up = true;
	}

	public boolean isInSubnet(InetAddress testAddress){
		byte[] test=testAddress.getAddress();
		if (test.length != addrBytes.length)
			return false;
		int len = prefixLength;
		int i=0;
		while(len>8){
			if (test[i]!=addrBytes[i])
				return false;
			i++;
			len -=8;
		}
		if (len>0){
			int mask = (0xFF00>>len)&0xFF;
			if ((test[i] & mask) != (addrBytes[i] & mask))
				return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		return name.hashCode() ^ address.hashCode() ^ prefixLength;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof IPInterface))
			return false;
		IPInterface other = (IPInterface) obj;
		return this.name.equals(other.name)
				&& this.address.equals(other.address)
				&& this.prefixLength == other.prefixLength;
	}

	@Override
	public String toString() {
		return name + ", " + address.getHostAddress() + "/" + prefixLength + " (" + broadcastAddress.getHostAddress() + ")";
	}
}
