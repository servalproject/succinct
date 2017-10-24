package org.servalproject.succinct;

import android.app.Fragment;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.PeerLink;
import org.servalproject.succinct.utils.AndroidObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

public class DebugFragment extends Fragment {
	private App app;
	private RecyclerView list;
	private final PeerAdapter adapter = new PeerAdapter();
	private final List<Peer> peers = new ArrayList<>();
	private final Map<PeerId, Integer> indexes = new HashMap<>();
	private static final String TAG = "DebugFragment";

	public DebugFragment(){

	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		app = (App)getActivity().getApplication();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		// Inflate the layout for this fragment
		View view = inflater.inflate(R.layout.fragment_debug, container, false);
		list = (RecyclerView) view.findViewById(R.id.list);

		list.setAdapter(adapter);
		list.setLayoutManager(new LinearLayoutManager(
				getActivity(), LinearLayoutManager.VERTICAL, false));

		return view;
	}

	private final Observer observer = new AndroidObserver() {
		@Override
		public void observe(Observable observable, Object o) {
			if (o instanceof Peer){
				Peer p = (Peer)o;
				Integer index = indexes.get(p.id);
				if (index==null){
					int i = peers.size();
					indexes.put(p.id, i);
					peers.add(p);
					adapter.notifyItemInserted(i);
				}else{
					adapter.notifyItemChanged(index);
				}
			}
		}
	};

	@Override
	public void onStart() {
		super.onStart();
		app.networks.observePeers.addObserver(observer);
		peers.clear();
		indexes.clear();
		for (Peer p: app.networks.getPeers()){
			int i = peers.size();
			indexes.put(p.id, i);
			peers.add(p);
		}
		adapter.notifyDataSetChanged();
	}

	@Override
	public void onStop() {
		super.onStop();
		app.networks.observePeers.deleteObserver(observer);
	}

	private class PeerHolder extends RecyclerView.ViewHolder{
		private int position;
		private Peer peer;
		private TextView line1;
		private TextView line2;

		private final Observer observer = new AndroidObserver() {
			@Override
			public void observe(Observable observable, Object o) {
				if (peer!=null && position>=0)
					adapter.notifyItemChanged(position);
			}
		};

		public PeerHolder(View itemView) {
			super(itemView);
			line1 = (TextView)itemView.findViewById(android.R.id.text1);
			line2 = (TextView)itemView.findViewById(android.R.id.text2);
		}

		private void bind(){
			if (this.peer==null)
				return;
			line1.setText(peer.toString());
			StringBuilder sb = new StringBuilder();
			for(PeerLink l : peer.getLinks()){
				if (sb.length()>0)
					sb.append('\n');
				sb.append(l.toString());
			}
			line2.setText(sb.toString());
		}

		public void setPeer(Peer peer, int position){
			this.position = position;
			if (peer != this.peer){
				if (this.peer!=null)
					this.peer.observable.deleteObserver(observer);
				this.peer = peer;
				if (peer!=null)
					peer.observable.addObserver(observer);
			}
			bind();
		}
	}

	private class PeerAdapter extends RecyclerView.Adapter<PeerHolder>{
		@Override
		public PeerHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			return new PeerHolder(LayoutInflater.from(getActivity())
					.inflate(android.R.layout.simple_list_item_2, null));
		}

		@Override
		public void onBindViewHolder(PeerHolder holder, int position) {
			holder.setPeer(peers.get(position), position);
		}

		@Override
		public void onViewRecycled(PeerHolder holder) {
			holder.setPeer(null, -1);
		}

		@Override
		public int getItemCount() {
			return peers.size();
		}
	}
}
