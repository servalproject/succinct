package org.servalproject.succinct.messaging.rock;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.util.Log;

import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.messaging.IMessaging;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.utils.ChangedObservable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Observable;

import uk.rock7.connect.ConnectComms;
import uk.rock7.connect.device.ConnectDevice;
import uk.rock7.connect.device.DeviceParameter;
import uk.rock7.connect.device.R7GenericDevice;
import uk.rock7.connect.device.r7generic.R7GenericDeviceParameter;
import uk.rock7.connect.enums.R7ActivationDesistStatus;
import uk.rock7.connect.enums.R7ActivationError;
import uk.rock7.connect.enums.R7ActivationMethod;
import uk.rock7.connect.enums.R7ActivationState;
import uk.rock7.connect.enums.R7CommandType;
import uk.rock7.connect.enums.R7ConnectionState;
import uk.rock7.connect.enums.R7DeviceError;
import uk.rock7.connect.enums.R7LockState;
import uk.rock7.connect.enums.R7MessageStatus;
import uk.rock7.connect.protocol.R7DeviceActivationDelegate;
import uk.rock7.connect.protocol.R7DeviceDiscoveryDelegate;
import uk.rock7.connect.protocol.R7DeviceFileTransferDelegate;
import uk.rock7.connect.protocol.R7DeviceGprsDelegate;
import uk.rock7.connect.protocol.R7DeviceMessagingDelegate;
import uk.rock7.connect.protocol.R7DeviceResponseDelegate;

public class RockMessaging {

	private final Context context;

	private ConnectComms comms;

	private R7ConnectionState connectionState;

	private BluetoothAdapter adapter;
	private int blueToothState;
	private String deviceId;
	private ConnectDevice connectedDevice;
	private R7GenericDevice genericDevice;

	private R7LockState lockState;
	private R7ActivationState activationState;

	private R7CommandType commandType;
	private String lastAction = null;
	private R7DeviceError lastError;
	private RockMessage lastMessage;

	private short inboxCount;

	private int batteryLevel;
	private int iridiumStatus=-1;

	private Location lastFix;

	// scanned devices
	private Map<String, Device> devices = new HashMap<>();

	private static final String TAG = "RockMessaging";

	public final Observable observable = new ChangedObservable();

	public static final int MTU = 332;
	public static final int RAW_MTU = 338;

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)){
				setBlueToothState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
			}
		}
	};

	public RockMessaging(Context context){
		this.context = context;
		IntentFilter i = new IntentFilter();
		i.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		context.registerReceiver(receiver, i);
		adapter = BluetoothAdapter.getDefaultAdapter();
		setBlueToothState(adapter.getState());
	}

	private void setBlueToothState(int state){
		blueToothState = state;
		Log.v(TAG, "Bluetooth state == "+state);

		if (comms == null) {
			if (state == BluetoothAdapter.STATE_ON)
				init();
		}else{
			checkState();
		}
		observable.notifyObservers();
	}

	private void init(){
		Log.v(TAG, "init");
		ConnectComms.init(context);
		comms = ConnectComms.getConnectComms();
		comms.discoveryDelegate = new WeakReference<>(this.discovery);
		comms.responseDelegate = new WeakReference<>(this.response);
		comms.fileTransferDelegate = new WeakReference<>(this.file);
		comms.activationDelegate = new WeakReference<>(this.activation);
		comms.messagingDelegate = new WeakReference<>(this.messaging);
		comms.gprsDelegate = new WeakReference<>(this.gprs);
		comms.enableWithApplicationIdentifier(BuildConfig.rockAppId);
	}

	public String getStatus(){
		if (!adapter.isEnabled())
			return "Bluetooth is off";
		if (connectionState == null)
			return "Initialising";
		if (connectedDevice!=null){
			return comms.isConnected()+" "+connectionState+
					" to "+connectedDevice.getName()+" "+lockState+
					(isIridiumAvailable()?"":" NO SAT");
		}
		return comms.isConnected()+" "+connectionState;
	}

	private void setLastAction(String action){
		Log.v(TAG, action);
		lastAction = action;
		lastError = null;
		observable.notifyObservers();
	}

	public R7DeviceError getLastError(){
		return lastError;
	}

	public String lastAction(){
		if (lastAction == null)
			return null;
		return lastAction+(lastError==null ? "" : " "+lastError);
	}

	public boolean canToggleScan(){
		return adapter.isEnabled() && comms != null
				&& (connectionState == R7ConnectionState.R7ConnectionStateReady
				|| connectionState == R7ConnectionState.R7ConnectionStateDiscovering
				|| connectionState == R7ConnectionState.R7ConnectionStateIdle
				|| connectionState == R7ConnectionState.R7ConnectionStateOff);
	}

	public boolean canDisconnect(){
		return isConnecting(connectionState);
	}

	public boolean isScanning(){
		return connectionState == R7ConnectionState.R7ConnectionStateDiscovering;
	}

	public boolean isEnabled(){
		return comms != null
				&& adapter.isEnabled();
	}

	public int getBatteryLevel() {
		return batteryLevel;
	}

	public short getInboxCount() {
		return inboxCount;
	}

	public void enable(){
		setLastAction("Enabling");

		if (comms == null){
			init();
		}else{
			adapter.enable();
		}
	}

	public void scan(){
		setLastAction("Scanning");
		checkState();
		comms.startDiscovery();
	}

	public void stopScanning(){
		setLastAction("Stop Scanning");
		comms.stopDiscovery();
	}

	public Collection<Device> getDevices(){
		return devices.values();
	}

	public void checkState(){
		if ((connectionState == R7ConnectionState.R7ConnectionStateIdle
				||connectionState == R7ConnectionState.R7ConnectionStateOff)
				&& adapter.isEnabled()) {
			// (mostly) harmless method that should trigger a bluetooth enabled check
			Log.v(TAG, "Fixing state by enabling again");
			comms.enableWithApplicationIdentifier(BuildConfig.rockAppId);
		}
	}

	public Device getConnectedDevice(){
		if (!isConnected())
			return null;
		if (devices.containsKey(deviceId))
			return devices.get(deviceId);
		Device d = new Device(deviceId, null);
		devices.put(deviceId, d);
		return d;
	}

	public boolean canConnect(){
		return connectionState == R7ConnectionState.R7ConnectionStateReady ||
				connectionState == R7ConnectionState.R7ConnectionStateDiscovering;
	}

	// Connect to this device, remember the device and auto reconnect
	public void connect(Device device){
		connect(device.id);
	}

	public void connect(String deviceId){
		setLastAction("Connecting to "+deviceId);
		checkState();
		this.deviceId = deviceId;
		this.lastError = null;
		this.lockState = null;
		comms.connect(deviceId);
	}

	private boolean isConnecting(R7ConnectionState state){
		return state == R7ConnectionState.R7ConnectionStateConnecting ||
				state == R7ConnectionState.R7ConnectionStateConnected;
	}


	public void disconnect(){
		if (isConnecting(connectionState)) {
			setLastAction("Disconnecting");
			comms.disconnect();
		}
	}

	// Do we have a device connection?
	public boolean isConnected(){
		return connectedDevice != null;
	}

	// Do we need to prompt for a pin?
	public R7LockState getLockState(){
		return lockState;
	}

	public void enterPin(short pin){
		setLastAction("Entering PIN");
		lockState = null;
		comms.unlock(pin);
	}

	public void requestNewGpsFix(){
		setLastAction("Requesting new fix");
		comms.requestCurrentGpsPosition();
	}
	public void requestGpsFix(){
		setLastAction("Requesting last fix");
		comms.requestLastKnownGpsPosition();
	}
	public Location getLatestFix(){
		return lastFix;
	}

	public void requestBeep(){
		setLastAction("Requesting BEEP");
		comms.requestBeep();
	}

	public boolean isIridiumAvailable(){
		return iridiumStatus>0;
	}

	public void disableTimeout(){
		Log.v(TAG, "Disabling usage timeout!");
		if (comms == null) return;
		comms.disableUsageTimeout();
	}

	public void enableTimeout(){
		Log.v(TAG, "Enabling usage timeout");
		if (comms == null) return;
		comms.enableUsageTimeout();
	}

	public boolean canSendMessage(){
		if (comms == null)
			return false;
		Boolean b = comms.isMessagingReady();
		return b!=null && b;
	}

	public boolean canSendRawMessage(){
		if (comms == null)
			return false;
		Boolean b = comms.rawMessagingAvailable();
		return b!=null && b;
	}

	public RockMessage sendMessage(byte bytes[]){
		setLastAction("Sending Message");
		return newMessage(comms.sendMessageWithData(bytes), false);
	}

	public RockMessage sendMessage(short seq, byte bytes[]){
		setLastAction("Sending Message");
		return newMessage(comms.sendMessageWithDataAndIdentifier(bytes, seq), false);
	}

	public RockMessage sendRawMessage(byte bytes[]){
		setLastAction("Sending Raw Message");
		return newMessage(comms.sendRawMessageWithData(bytes), false);
	}

	public RockMessage sendRawMessage(short seq, byte bytes[]){
		setLastAction("Sending Raw Message");
		return newMessage(comms.sendRawMessageWithDataAndIdentifier(bytes, seq), false);
	}

	private RockMessage newMessage(short id, boolean incoming){
		RockMessage ret = new RockMessage(id, incoming);
		messages.put(id, ret);
		lastMessage = ret;
		return ret;
	}

	private RockMessage getMessage(short id, boolean incoming){
		if (messages.containsKey(id))
			return (lastMessage = messages.get(id));
		return newMessage(id, incoming);
	}

	public RockMessage lastUpdatedMessage(){
		return lastMessage;
	}

	public void onTrimMemory(int level){
		Log.v(TAG, "onTrimMemory("+level+")");
		if (comms == null) return;
		comms.trimMemory(level);
	}

	public ArrayList<DeviceParameter> getDeviceParameters(){
		return connectedDevice.parameters();
	}

	public void requestParameter(R7GenericDeviceParameter parameter){
		setLastAction("Requesting Parameter");
		genericDevice.requestParameter(parameter);
	}

	public void updateParameter(R7GenericDeviceParameter parameter, int value){
		setLastAction("Updating Parameter");
		genericDevice.updateParameter(parameter, value);
	}

	private R7DeviceDiscoveryDelegate discovery = new R7DeviceDiscoveryDelegate(){
		@Override
		public void discoveryStopped() {
			// Not called?
			Log.v(TAG, "discoveryStopped");
		}

		@Override
		public void discoveryFoundDevice(String deviceId, String name) {
			Log.v(TAG, "discoveryFoundDevice("+deviceId+", "+name+")");
			Device d;
			if (devices.containsKey(deviceId)){
				d = devices.get(deviceId);
				d.name = name;
			}else{
				devices.put(deviceId, (d = new Device(deviceId, name)));
			}
			observable.notifyObservers();
		}

		@Override
		public void discoveryStarted() {
			// Not called?
			Log.v(TAG, "discoveryStarted");
		}

		@Override
		public void discoveryUpdatedDevice(String deviceId, String name, int i) {
			Log.v(TAG, "discoveryUpdatedDevice("+deviceId+", "+name+", "+i+")");
			if (devices.containsKey(deviceId)){
				Device d = devices.get(deviceId);
				d.name = name;
			}else{
				devices.put(deviceId, new Device(deviceId, name));
			}
			observable.notifyObservers();
		}
	};

	private String getValueLabel(DeviceParameter p){
		if (!p.getAvailiable())
			return "UNAVAILABLE";
		if (!p.isCachedValueUsable())
			return "UNUSABLE";
		int val = p.getCachedValue();
		String label = p.labelForValue(val);
		if (label!=null)
			return label+" ("+val+")";
		Hashtable<Integer, String> options = p.getOptions();
		if (options.containsKey(val))
			return options.get(val)+" ("+val+")";
		return Integer.toString(val);
	}

	private R7DeviceResponseDelegate response = new R7DeviceResponseDelegate(){
		@Override
		public void deviceReady() {
			Log.v(TAG, "deviceReady");
		}

		@Override
		public void deviceConnected(ConnectDevice connectDevice, R7ActivationState activationState, R7LockState lockState) {
			Log.v(TAG, "deviceConnected ("+connectDevice.getName()+", "+activationState+", "+lockState+")");
			RockMessaging.this.connectedDevice = connectDevice;
			RockMessaging.this.activationState = activationState;
			RockMessaging.this.lockState = lockState;
			RockMessaging.this.genericDevice = (connectDevice instanceof R7GenericDevice) ?
						(R7GenericDevice) connectDevice : null;
			observable.notifyObservers();
		}

		@Override
		public void deviceDisconnected() {
			Log.v(TAG, "deviceDisconnected");
		}

		@Override
		public void deviceParameterUpdated(DeviceParameter deviceParameter) {
			R7GenericDeviceParameter type = R7GenericDeviceParameter.values()[deviceParameter.getIdentifier()];
			Boolean ro = deviceParameter.getReadonly();
			if (deviceParameter.getAvailiable() && deviceParameter.isCachedValueUsable()) {
				int val = deviceParameter.getCachedValue();

				switch (type) {
					case R7GenericDeviceParameterIridiumStatus:
						RockMessaging.this.iridiumStatus = val;
						observable.notifyObservers();
						break;
				}
			}

			Log.v(TAG, "deviceParameterUpdated("+
					type+" ("+deviceParameter.getLabel()+
					(ro!=null && ro?" RO":"")+
					"), "+
					getValueLabel(deviceParameter)+")");
		}

		@Override
		public void deviceBatteryUpdated(int i, Date date) {
			RockMessaging.this.batteryLevel = i;
			Log.v(TAG, "deviceBatteryUpdated("+i+", "+date+")");
		}

		@Override
		public void deviceStateChanged(R7ConnectionState stateTo, R7ConnectionState stateFrom) {
			Log.v(TAG, "deviceStateChanged("+stateTo+", "+stateFrom+")");
			RockMessaging.this.connectionState = stateTo;

			// Don't notify
			if (stateFrom == stateTo)
				return;
			// Probably due to us re-enabling, so ignore that too
			if (stateFrom == R7ConnectionState.R7ConnectionStateIdle
					&& stateTo == R7ConnectionState.R7ConnectionStateReady)
				return;

			RockMessaging.this.lastAction = null;
			RockMessaging.this.lastError = null;

			if (isConnecting(stateFrom) && !isConnecting(stateTo)){
				RockMessaging.this.connectedDevice = null;
				RockMessaging.this.activationState = null;
				RockMessaging.this.lockState = null;
				RockMessaging.this.genericDevice = null;
				RockMessaging.this.lastMessage = null;
				RockMessaging.this.iridiumStatus = -1;
				deviceId = null;
				observable.notifyObservers();
			}
			observable.notifyObservers();
		}

		@Override
		public void deviceError(R7DeviceError r7DeviceError) {
			Log.v(TAG, "deviceError("+r7DeviceError+")");
			RockMessaging.this.lastError = r7DeviceError;
			observable.notifyObservers();
		}

		@Override
		public void deviceLockStatusUpdated(R7LockState r7LockState) {
			Log.v(TAG, "deviceLockStatusUpdated("+r7LockState+")");
			RockMessaging.this.lockState = r7LockState;

			if (genericDevice!=null && r7LockState == R7LockState.R7LockStateUnlocked){
				RockMessaging.this.lastAction = null;
				RockMessaging.this.lastError = null;
			/*
				// device is now ready for more commands...
				requestGpsFix();
				// TODO request interesting parameter values?
				R7GenericDeviceParameter values[] = R7GenericDeviceParameter.values();
				for(DeviceParameter p: connectedDevice.parameters()){
					if (p.getAvailiable() && !p.isCachedValueUsable())
						requestParameter(values[p.getIdentifier()]);
				}
			*/
			}

			observable.notifyObservers();
		}

		@Override
		public void deviceCommandReceived(R7CommandType r7CommandType) {
			Log.v(TAG, "deviceCommandReceived("+r7CommandType+")");
			// R7CommandTypeActionRequest == beep??
			RockMessaging.this.commandType = r7CommandType;
			observable.notifyObservers();
		}

		@Override
		public void deviceUsageTimeout() {
			Log.v(TAG, "deviceUsageTimeout");
		}

		@Override
		public void deviceSerialDump(byte[] bytes) {
			Log.v(TAG, "deviceSerialDump("+Hex.toString(bytes)+")");
		}

		@Override
		public void deviceNameUpdated(String s) {
			Log.v(TAG, "deviceNameUpdated("+s+")");
			observable.notifyObservers();
		}

		@Override
		public void locationUpdated(Location location) {
			Log.v(TAG, "locationUpdated("+location+" "+new Date(location.getTime())+")");
			RockMessaging.this.lastFix = location;
			RockMessaging.this.lastAction = null;
			RockMessaging.this.lastError = null;
			observable.notifyObservers();
		}

		@Override
		public void creditBalanceUpdated(int i) {
			Log.v(TAG, "creditBalanceUpdated("+i+")");
		}
	};

	private R7DeviceActivationDelegate activation = new R7DeviceActivationDelegate(){
		@Override
		public void activationCompleted(String s, String s1, String s2) {
			Log.v(TAG, "activationCompleted("+s+", "+s1+", "+s2+")");
		}

		@Override
		public void activationFailed(R7ActivationError r7ActivationError) {
			Log.v(TAG, "activationFailed("+r7ActivationError+")");
		}

		@Override
		public void activationDesist(R7ActivationDesistStatus r7ActivationDesistStatus, String s) {
			Log.v(TAG, "activationDesist("+r7ActivationDesistStatus+", "+s+")");
		}

		@Override
		public void activationStarted(R7ActivationMethod r7ActivationMethod) {
			Log.v(TAG, "activationStarted("+r7ActivationMethod+")");
		}

		@Override
		public void activationStateUpdated(R7ActivationState r7ActivationState) {
			Log.v(TAG, "activationStateUpdated("+r7ActivationState+")");
		}
	};

	private final HashMap<Short, RockMessage> messages = new HashMap<>();

	private R7DeviceMessagingDelegate messaging = new R7DeviceMessagingDelegate() {
		@Override
		public void messageProgressCompleted(short i) {
			Log.v(TAG, "messageProgressCompleted("+i+")");
			RockMessage message = getMessage(i, false);
			message.completed = true;
			observable.notifyObservers(message);
		}

		@Override
		public boolean messageStatusUpdated(short i, R7MessageStatus r7MessageStatus) {
			Log.v(TAG, "messageStatusUpdated("+i+", "+r7MessageStatus+")");
			RockMessage message = getMessage(i, false);
			// which status values indicate that we will not see callbacks about this message again?
			message.status = r7MessageStatus;
			observable.notifyObservers(message);

			if (r7MessageStatus == R7MessageStatus.R7MessageStatusTransmitted)
				messages.remove(i);
			// if we return false, the callback should happen again
			return true;
		}

		@Override
		public void inboxUpdated(short i) {
			Log.v(TAG, "inboxUpdated("+i+")");

			RockMessaging.this.inboxCount = i;
			observable.notifyObservers();

			if (i>0) {
				Log.v(TAG, "requestNextMessage");
				comms.requestNextMessage();
			}
		}

		@Override
		public boolean messageReceived(short i, byte[] bytes) {
			// Does this id come from the remote end?
			// do we see this id before now?
			Log.v(TAG, "messageReceived("+i+", "+Hex.toString(bytes)+")");

			RockMessage message = new RockMessage(i, true);
			message.bytes = bytes;
			observable.notifyObservers(message);

			return true;
		}

		@Override
		public void messageProgressUpdated(short i, int part, int total) {
			// part>total indicates complete?
			Log.v(TAG, "messageProgressUpdated("+i+", "+part+", "+total+")");
			RockMessage message = getMessage(i, false);
			message.part = part;
			message.total = total;
			observable.notifyObservers(message);
		}

		@Override
		public void messageCheckFinished() {
			Log.v(TAG, "messageCheckFinished");
		}
	};

	private R7DeviceGprsDelegate gprs = new R7DeviceGprsDelegate() {
		@Override
		public void gprsConfigResponse(Map<Integer, String> map) {
			Log.v(TAG, "gprsConfigResponse ...");
		}

		@Override
		public void gprsConfigured() {
			Log.v(TAG, "gprsConfigured");
		}
	};

	private R7DeviceFileTransferDelegate file = new R7DeviceFileTransferDelegate() {
		@Override
		public void fileTransferStarted() {
			Log.v(TAG, "fileTransferStarted");
		}

		@Override
		public void fileTransferProgressUpdate(int i, int i1) {
			Log.v(TAG, "fileTransferProgressUpdate("+i+", "+i1+")");
		}

		@Override
		public void fileTransferError() {
			Log.v(TAG, "fileTransferError");
		}

		@Override
		public void fileTransferCompleted() {
			Log.v(TAG, "fileTransferCompleted");
		}
	};
}
