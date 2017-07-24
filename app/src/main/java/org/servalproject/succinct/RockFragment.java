package org.servalproject.succinct;

import android.app.Fragment;
import android.location.Location;
import android.os.Bundle;
import android.support.v7.util.SortedList;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.servalproject.succinct.messaging.rock.Device;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.utils.AndroidObserver;

import java.util.Observable;

import uk.rock7.connect.enums.R7LockState;

public class RockFragment extends Fragment implements View.OnClickListener {
	private RockMessaging rock;
	private final RecyclerView.Adapter<DeviceHolder> adapter;
	private final SortedList<Device> devices;
	private RecyclerView deviceList;
	private TextView status;
	private TextView lastError;
	private Button scan;
	private Button disconnect;
	private Button beep;
	private Button unlock;
	private Button send;
	private Button sendRaw;
	private Button enable;

	private static final String TAG = "RockFrag";

	private final AndroidObserver observer = new AndroidObserver() {
		@Override
		public void observe(Observable observable, Object o) {
			bind();
		}
	};

	public RockFragment() {
		// Required empty public constructor
		adapter = new DeviceAdapter();
		devices = new SortedList<>(Device.class, new SortedList.Callback<Device>() {
			@Override
			public int compare(Device o1, Device o2) {
				return o1.compareTo(o2);
			}

			@Override
			public void onChanged(int position, int count) {

			}

			@Override
			public boolean areContentsTheSame(Device oldItem, Device newItem) {
				return compare(oldItem, newItem)==0;
			}

			@Override
			public boolean areItemsTheSame(Device item1, Device item2) {
				return item1 == item2;
			}

			@Override
			public void onInserted(int position, int count) {

			}

			@Override
			public void onRemoved(int position, int count) {

			}

			@Override
			public void onMoved(int fromPosition, int toPosition) {

			}
		});
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//noinspection StatementWithEmptyBody
		if (getArguments() != null) {
		}
		App app = (App)getActivity().getApplication();
		rock = app.getRock();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		// Inflate the layout for this fragment
		View v = inflater.inflate(R.layout.fragment_rock, container, false);

		deviceList = (RecyclerView) v.findViewById(R.id.device_list);
		status = (TextView) v.findViewById(R.id.status);
		lastError = (TextView) v.findViewById(R.id.last_error);
		scan = (Button) v.findViewById(R.id.scan);
		beep = (Button) v.findViewById(R.id.beep);
		disconnect = (Button) v.findViewById(R.id.disconnect);
		unlock = (Button) v.findViewById(R.id.unlock);
		send = (Button) v.findViewById(R.id.send);
		sendRaw = (Button) v.findViewById(R.id.send_raw);
		enable = (Button) v.findViewById(R.id.enable);

		enable.setOnClickListener(this);
		scan.setOnClickListener(this);
		beep.setOnClickListener(this);
		disconnect.setOnClickListener(this);
		unlock.setOnClickListener(this);
		send.setOnClickListener(this);
		sendRaw.setOnClickListener(this);

		deviceList.setAdapter(adapter);
		deviceList.setLayoutManager(new LinearLayoutManager(
				v.getContext(), LinearLayoutManager.VERTICAL, false));

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		rock.observable.addObserver(observer);
		bind();
	}

	@Override
	public void onStop() {
		super.onStop();
		rock.observable.deleteObserver(observer);
	}

	private void bind() {
		status.setText(rock.getStatus());

		String error = rock.lastAction();
		lastError.setText(error);
		lastError.setVisibility((error==null) ? View.GONE : View.VISIBLE);

		Location location = rock.getLatestFix();

		boolean enabled = rock.isEnabled();
		boolean isAllGood = enabled && rock.isConnected() && rock.getLockState() == R7LockState.R7LockStateUnlocked;
		enable.setEnabled(!enabled);
		scan.setEnabled(enabled && rock.canToggleScan());
		disconnect.setEnabled(enabled && rock.canDisconnect());
		beep.setEnabled(isAllGood);
		unlock.setEnabled(enabled && rock.isConnected() && rock.getLockState() != R7LockState.R7LockStateUnlocked);
		send.setEnabled(enabled && rock.canSendMessage());
		sendRaw.setEnabled(enabled && rock.canSendRawMessage());

		devices.clear();
		devices.addAll(rock.getDevices());
		adapter.notifyDataSetChanged();
	}

	@Override
	public void onClick(View view) {
		switch (view.getId()){
			case R.id.enable:
				rock.enable();
				break;

			case R.id.scan:
				if (rock.isScanning())
					rock.stopScanning();
				else
					rock.scan();
				break;

			case R.id.disconnect:
				rock.disconnect();
				break;

			case R.id.beep:
				rock.requestBeep();
				break;

			case R.id.unlock:
				rock.enterPin((short) 1234);
				break;

			case R.id.send:
				rock.sendMessage("Hello".getBytes());
				break;

			case R.id.send_raw:
				rock.sendRawMessage("Hello".getBytes());
				break;
		}
	}

	private class DeviceHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
		private TextView id;
		private TextView name;
		private Device device;

		public DeviceHolder(View itemView) {
			super(itemView);
			id = (TextView)itemView.findViewById(R.id.id);
			name = (TextView)itemView.findViewById(R.id.name);
			itemView.setOnClickListener(this);
		}

		private void bind(Device device){
			this.device = device;
			this.id.setText(device.id);
			this.name.setText(device.getName());
		}

		@Override
		public void onClick(View view) {
			rock.connect(device);
		}
	}

	private class DeviceAdapter extends RecyclerView.Adapter<DeviceHolder> {
		@Override
		public DeviceHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			return new DeviceHolder(
					getActivity().getLayoutInflater()
							.inflate(R.layout.item_rock_device, null));
		}

		@Override
		public void onBindViewHolder(DeviceHolder holder, int position) {
			holder.bind(devices.get(position));
		}

		@Override
		public int getItemCount() {
			return devices.size();
		}
	}
}
