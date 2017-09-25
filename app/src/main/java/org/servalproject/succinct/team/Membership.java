package org.servalproject.succinct.team;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by jeremy on 25/09/17.
 */

public class Membership {
	public final PeerId peerId;
	public final boolean enroll;

	Membership(PeerId peerId, boolean enroll){
		this.peerId = peerId;
		this.enroll = enroll;
	}

	public static final Factory<Membership> factory = new Factory<Membership>() {
		@Override
		public String getFileName() {
			return "members";
		}

		@Override
		public Membership create(DeSerialiser serialiser) {
			PeerId id = new PeerId(serialiser);
			boolean enroll = !serialiser.hasRemaining();
			return new Membership(id, enroll);
		}

		@Override
		public void serialise(Serialiser serialiser, Membership object) {
			object.peerId.serialise(serialiser);
			if (!object.enroll)
				serialiser.putByte((byte)1);
		}
	};
}
