package org.servalproject.succinct.networking.messages;

import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.team.Team;

import java.io.IOException;

public class RequestTeam extends Message<RequestTeam> {
	public final PeerId teamId;
	private static final String TAG = "RequestTeam";

	public RequestTeam(PeerId teamId) {
		super(Type.RequestTeamMessage);
		this.teamId = teamId;
	}

	public static final Factory<RequestTeam> factory = new Factory<RequestTeam>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public RequestTeam create(DeSerialiser serialiser) {
			return new RequestTeam(new PeerId(serialiser));
		}

		@Override
		public void serialise(Serialiser serialiser, RequestTeam object) {
			object.teamId.serialise(serialiser);
		}
	};

	@Override
	protected Factory<RequestTeam> getFactory() {
		return factory;
	}

	@Override
	public void process(Peer peer) {
		App app = peer.appContext;
		if (app.teamStorage == null || !app.teamStorage.teamId.equals(teamId))
			return;
		try {
			Team myTeam = app.teamStorage.getLastRecord(Team.factory, PeerId.Team);
			if (myTeam!=null)
				peer.getConnection().queue(myTeam);
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
}
