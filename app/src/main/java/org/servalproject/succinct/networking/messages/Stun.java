package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jeremy on 22/11/17.
 */

public class Stun extends Message<Stun> {
	public final List<SocketAddress> addresses;

	public static final Factory<Stun> factory = new Factory<Stun>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public Stun create(DeSerialiser serialiser) {
			List<SocketAddress> addresses = new ArrayList<>();
			while(serialiser.hasRemaining()){
				try {
					addresses.add(new InetSocketAddress(
							InetAddress.getByAddress(serialiser.getBytes()),
							Networks.PORT));
				} catch (UnknownHostException e) {
					throw new IllegalStateException(e);
				}
			}
			return new Stun(addresses);
		}

		@Override
		public void serialise(Serialiser serialiser, Stun object) {
			for(SocketAddress a : object.addresses){
				if (a instanceof InetSocketAddress){
					InetSocketAddress i = (InetSocketAddress)a;
					serialiser.putBytes(i.getAddress().getAddress());
				} else {
					throw new IllegalStateException();
				}
			}
		}
	};

	public Stun(List<SocketAddress> addresses) {
		super(Type.StunMessage);
		this.addresses = addresses;
	}

	@Override
	protected Factory<Stun> getFactory() {
		return factory;
	}

	@Override
	public void process(Peer peer) {

	}
}
