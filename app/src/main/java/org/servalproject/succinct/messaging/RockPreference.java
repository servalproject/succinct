package org.servalproject.succinct.messaging;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.servalproject.succinct.App;
import org.servalproject.succinct.R;
import org.servalproject.succinct.messaging.rock.Device;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.utils.AndroidObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Observable;

import uk.rock7.connect.enums.R7LockState;

public class RockPreference extends DialogPreference {
	private RockMessaging rock;
	private RecyclerView deviceList;
	private TextView status;
	private Button positive;
	private final DeviceAdapter adapter = new DeviceAdapter();
	private static final String TAG = "RockPreference";

	public RockPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	public RockPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		init();
	}

	private void init(){
		App app = (App)getContext().getApplicationContext();
		rock = app.getRock();
		setDialogLayoutResource(R.layout.settings_rock);
		setPositiveButtonText(R.string.ok);
		setNegativeButtonText(R.string.cancel);
	}

	private List<Device> rockDevices = new ArrayList<>();

	private final AndroidObserver rockObserver = new AndroidObserver() {
		@Override
		public void observe(Observable observable, Object o) {
			rebind();
		}
	};

	@Override
	protected void showDialog(Bundle state) {
		super.showDialog(state);
		positive = ((AlertDialog)getDialog()).getButton(AlertDialog.BUTTON_POSITIVE);
		rebind();
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);

		deviceList = (RecyclerView) view.findViewById(R.id.device_list);
		status = (TextView) view.findViewById(R.id.status);

		deviceList.setAdapter(adapter);
		deviceList.setLayoutManager(new LinearLayoutManager(
				getContext(), LinearLayoutManager.VERTICAL, false));

		rock.observable.addObserver(rockObserver);
	}

	@Override
	protected void onDialogClosed(boolean positiveResult) {
		super.onDialogClosed(positiveResult);
		rock.observable.deleteObserver(rockObserver);
		if (positiveResult && selected!=null) {
			Log.v(TAG, "Remembering connection to "+selected.id+" for automatic messaging");
			persistString(selected.id);
		}
	}

	// TODO string resources
	private String lastStatus;
	private void rebind(){
		if (positive == null)
			return;
		positive.setEnabled(shouldEnable());
		status.setText(lastStatus);
		rockDevices.clear();
		rockDevices.addAll(rock.getDevices());
		Collections.sort(rockDevices);
		adapter.notifyDataSetChanged();
	}

	// push state towards scanning or connecting
	private boolean shouldEnable(){
		if (!rock.isEnabled()){
			lastStatus = "Enabling";
			rock.enable();
			return false;
		}
		if (selected != null){
			rock.checkState();
			Device connected = rock.getConnectedDevice();
			if (connected == null){
				if (rock.canConnect()) {
					lastStatus = "Connecting to "+selected.getName();
					rock.connect(selected);
				}
				return false;
			}
			if (!connected.id.equals(selected.id)){
				lastStatus = "Disconnecting from "+connected.getName();
				rock.disconnect();
				return false;
			}

			R7LockState lockState = rock.getLockState();
			if (lockState == null)
				return false;
			if (lockState == R7LockState.R7LockStateLocked){
				lastStatus = "Entering pin";
				rock.enterPin((short) 1234);
				return false;
			}
			if(lockState != R7LockState.R7LockStateUnlocked){
				// TODO pin entry?
				lastStatus = "Pin entry failed";
				selected = null;
				return false;
			}
			lastStatus = "Connected";
			return rock.canSendRawMessage();
		}else {
			if (rock.isConnected())
				return false;
			if (!rock.isScanning())
				rock.scan();
			lastStatus = "Scanning";
		}
		return false;
	}

	private Device selected;

	private class DeviceHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
		private LinearLayout root;
		private TextView id;
		private TextView name;
		private Device device;

		public DeviceHolder(View itemView) {
			super(itemView);
			root = (LinearLayout)itemView;
			id = (TextView)itemView.findViewById(R.id.id);
			name = (TextView)itemView.findViewById(R.id.name);

			itemView.setOnClickListener(this);
		}

		private void bind(Device device){
			this.device = device;
			this.id.setText(device.id);
			this.name.setText(device.getName());
			root.setSelected(device == selected);
		}

		@Override
		public void onClick(View view) {
			selected = device;
			rebind();
		}
	}

	private class DeviceAdapter extends RecyclerView.Adapter<DeviceHolder> {
		@Override
		public DeviceHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			return new DeviceHolder(LayoutInflater.from(getContext())
							.inflate(R.layout.item_rock_device, null));
		}

		@Override
		public void onBindViewHolder(DeviceHolder holder, int position) {
			holder.bind(rockDevices.get(position));
		}

		@Override
		public int getItemCount() {
			return rockDevices.size();
		}
	}
}
