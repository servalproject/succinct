package org.servalproject.succinct.networking;

import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class IPInterface implements Interface{
	private final String name;
	private final byte[] addrBytes;
	private final byte[] broadcastAddrBytes;
	final InetAddress address;
	private final int prefixLength;
	final InetAddress broadcastAddress;
	public boolean up = true;
	private final byte[] nextAddr;
	private final byte[] firstAddr;

	public static void test(){
		try {
			byte[] addr = new byte[]{(byte) 192, (byte) 168, 100, (byte) 185};
			byte[] brAddr = new byte[]{(byte) 192, (byte) 168, 101, (byte) 255};
			IPInterface network = new IPInterface("test", addr, brAddr, 23);
			Log.v("IPInterface", Hex.toString(network.addrBytes)+", "+
					Hex.toString(network.firstAddr)+", "+
					Hex.toString(network.nextAddr)+", "+
					Hex.toString(network.broadcastAddrBytes)
			);
			for (int i=0;i<512;i++){
				Log.v("IPInterface", i+" - "+network.nextAddress());
			}
		} catch (UnknownHostException e) {
			Log.e("IPInterface",e.getMessage(), e);
		}

	}

	IPInterface(String name, byte[] addr, byte[] broadcast, int prefixLength) throws UnknownHostException {
		this.name = name;
		this.addrBytes = addr;
		this.broadcastAddrBytes = broadcast;

		this.nextAddr = new byte[addr.length];
		System.arraycopy(addr, 0, nextAddr, 0, addr.length);
		this.firstAddr = new byte[addr.length];
		// copy prefixLength bits from addr to firstAddr
		int p = prefixLength;
		int i=0;
		for(;i<addr.length && p>=8;i++,p-=8)
			firstAddr[i] = addr[i];
		if (p>0)
			firstAddr[i] = (byte) (addr[i] & (0xFF00>>p));

		this.address = InetAddress.getByAddress(addr);
		this.broadcastAddress = InetAddress.getByAddress(broadcast);
		this.prefixLength = prefixLength;
		up = true;
	}

	private void decr(){
		int i = nextAddr.length;
		while (--i>=0 && nextAddr[i]--==0)
			;
	}

	private boolean eq(byte[] one, byte[] two){
		for (int i=0;i<one.length;i++)
			if (one[i]!=two[i])
				return false;
		return true;
	}

	public InetAddress nextAddress() throws UnknownHostException {
		decr();

		if (eq(nextAddr, firstAddr)) {
			System.arraycopy(broadcastAddrBytes, 0, nextAddr, 0, nextAddr.length);
			decr();
		}

		if (eq(nextAddr, addrBytes))
			decr();

		return InetAddress.getByAddress(nextAddr);
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
