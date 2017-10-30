package org.servalproject.succinct.team;

import android.location.Location;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.servalproject.succinct.App;
import org.servalproject.succinct.R;
import org.servalproject.succinct.TeamFragment;
import org.servalproject.succinct.location.LocationFactory;
import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.utils.AndroidObserver;

import java.io.IOException;
import java.util.Observable;
import java.util.Observer;

/**
 * Created by jeremy on 30/10/17.
 */

public class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.MemberHolder> {

	private final App app;
	private final TeamFragment fragment;
	private TeamStorage store;
	private MembershipList list;

	public MemberAdapter(App app, TeamFragment fragment){
		this.fragment = fragment;
		this.app = app;
		setHasStableIds(true);
	}

	public void onStart(){
		store = app.teamStorage;
		if (store==null || !store.isTeamActive()){
			store = null;
			list = null;
			return;
		}
		try {
			list = store.getMembers();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		store.observable.addObserver(observer);
		app.networks.observePeers.addObserver(peerObserver);
		notifyDataSetChanged();
	}

	public void onStop(){
		if (list==null)
			return;
		store.observable.deleteObserver(observer);
		app.networks.observePeers.deleteObserver(peerObserver);
		list = null;
	}

	private final AndroidObserver observer = new AndroidObserver() {
		@Override
		public void observe(Observable observable, Object o) {
			RecordStore file = (RecordStore)o;
			String name = file.filename.getName();
			if (name.equals(TeamMember.factory.getFileName())
					|| name.equals(Membership.factory.getFileName())
					|| name.equals(LocationFactory.factory.getFileName()))
				notifyDataSetChanged();
		}
	};

	private final AndroidObserver peerObserver = new AndroidObserver() {
		@Override
		public void observe(Observable observable, Object o) {
			try {
				Peer p = (Peer)o;
				if (list!=null && list.getPosition(p.id)>=0)
					notifyDataSetChanged();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
	};

	@Override
	public MemberHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.team_member, parent, false);
		return new MemberHolder(view);
	}

	@Override
	public void onBindViewHolder(MemberHolder holder, int position) {
		try {
			if (list == null)
				return;
			// Off by one to ignore EOC

			PeerId id = list.getPeerId(position+1);
			TeamMember teamMember = list.getTeamMember(position+1);
			boolean active = list.isActive(position+1);
			Location lastLocation = store.getLastRecord(LocationFactory.factory, id);
			Peer networkPeer = app.networks.getPeer(id);

			holder.name.setText(teamMember == null ? null : teamMember.name +
					(active?"":" "+fragment.getString(R.string.member_inactive)));

			holder.lastFix.setText(lastLocation == null ? null :
					Location.convert(lastLocation.getLatitude(),Location.FORMAT_SECONDS)+
					", "+Location.convert(lastLocation.getLongitude(), Location.FORMAT_SECONDS));

			if (id.equals(app.networks.myId))
				holder.connectivity.setText(null);
			else if (networkPeer == null || !networkPeer.isAlive())
				holder.connectivity.setText(fragment.getString(R.string.peer_not_connected));
			else
				holder.connectivity.setText(fragment.getString(R.string.peer_connected));

		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public int getItemCount() {
		try {
			// Off by one to ignore EOC
			return list == null ? 0 : list.getMembers().size() -1;
		}catch (IOException e){
			throw new IllegalStateException(e);
		}
	}

	public class MemberHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
		private final TextView name;
		private final TextView lastFix;
		private final TextView connectivity;

		public MemberHolder(View itemView) {
			super(itemView);
			name = (TextView) itemView.findViewById(R.id.member_name);
			lastFix = (TextView) itemView.findViewById(R.id.last_fix);
			connectivity = (TextView) itemView.findViewById(R.id.network_connectivity);
			//itemView.setOnClickListener(this);
		}

		@Override
		public void onClick(View view) {

		}
	}
}
