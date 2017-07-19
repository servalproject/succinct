package org.servalproject.succinct.messaging.rock;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.messaging.IMessaging;

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

public class RockMessaging implements IMessaging {

	private final Context context;
	private final SharedPreferences preferences;

	private ConnectComms comms;

	private R7ConnectionState connectionState;

	private ConnectDevice connectedDevice;
	private R7GenericDevice genericDevice;

	private R7LockState lockState;
	private R7ActivationState activationState;

	private R7CommandType commandType;
	private R7DeviceError lastError;

	private short inboxCount;

	private Location lastFix;

	// scanned devices
	private Map<String, Device> devices = new HashMap<>();

	private static final String ID = "device_id";
	private static final String PIN = "device_pin";
	private static final String TAG = "RockMessaging";

	public final Observable observable = new Observable();
	public static final int MTU = 332;
	public static final int RAW_MTU = 338;

	public RockMessaging(Context context, SharedPreferences preferences){
		this.context = context;
		this.preferences = preferences;
		init();
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
		if (connectionState == null)
			return "Initialising";
		if (connectedDevice!=null){
			if (lockState != R7LockState.R7LockStateUnlocked)
				return "Locked: "+lockState;
			return "Connected to "+connectedDevice.getName()+", "+connectionState;
		}
		return null;
	}

	public String lastError(){
		if (lastError==null)
			return null;
		return lastError.toString();
	}

	public boolean isScanning(){
		return connectionState == R7ConnectionState.R7ConnectionStateDiscovering;
	}

	public void scan(){
		Log.v(TAG, "scan");
		comms.startDiscovery();
	}

	public void stopScanning(){
		Log.v(TAG, "stopScanning");
		comms.stopDiscovery();
	}

	public Collection<Device> getDevices(){
		return devices.values();
	}

	// Connect to this device, remember the device and auto reconnect
	public void connect(Device device){
		Log.v(TAG, "connect("+device.id+")");
		// TODO, only set preference after successful connection?
		SharedPreferences.Editor e = preferences.edit();
		e.putString(ID, device.id);
		e.apply();

		comms.connect(device.id);
	}

	// Do we have a device connection?
	public boolean isConnected(){
		return connectedDevice != null;
	}

	// Do we need to prompt for a pin?
	public boolean isLocked(){
		return lockState == R7LockState.R7LockStateLocked ||
				lockState == R7LockState.R7LockStateIncorrectPin;
	}

	public void enterPin(short pin){
		Log.v(TAG, "enterPin("+pin+")");
		SharedPreferences.Editor e = preferences.edit();
		e.putInt(PIN, pin);
		e.apply();
		comms.unlock(pin);
	}

	public void requestGpsFix(){
		Log.v(TAG, "requestGpsFix");
		comms.requestCurrentGpsPosition();
	}

	public void requestBeep(){
		Log.v(TAG, "requestBeep");
		comms.requestBeep();
	}

	public short sendMessage(byte bytes[], short messageId){
		return comms.sendMessageWithDataAndIdentifier(bytes, messageId);
	}

	public short sendRawMessage(byte bytes[], short messageId){
		return comms.sendRawMessageWithDataAndIdentifier(bytes, messageId);
	}

	public void onTrimMemory(int level){
		comms.trimMemory(level);
	}

	public ArrayList<DeviceParameter> getDeviceParameters(){
		return connectedDevice.parameters();
	}

	public void requestParameter(R7GenericDeviceParameter parameter){
		Log.v(TAG, "requestParameter("+parameter+")");
		genericDevice.requestParameter(parameter);
	}

	private static String toString(byte[] bytes){
		if (bytes==null)
			return "null";

		StringBuilder sb = new StringBuilder("[");
		for(int i=0;i<bytes.length;i++){
			if (i>0)
				sb.append(", ");
			sb.append(Integer.toHexString(bytes[i] & 0xFF));
		}
		sb.append("]");
		return sb.toString();
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

			// TODO remove once we have a UI
			connect(d);
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
			// not reliable?
			Log.v(TAG, "deviceReady");
			// first callback
			String id = preferences.getString(ID,null);
			if (id != null)
				comms.connect(id);
			else
				comms.startDiscovery();
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

			if (lockState == R7LockState.R7LockStateLocked){
				// always try a configured pin, or the default
				int pin = preferences.getInt(PIN, 1234);
				Log.v(TAG, "unlock("+pin+")");
				comms.unlock((short) pin);
			}
		}

		@Override
		public void deviceDisconnected() {
			Log.v(TAG, "deviceDisconnected");
			RockMessaging.this.connectedDevice = null;
			RockMessaging.this.activationState = null;
			RockMessaging.this.lockState = null;
			RockMessaging.this.genericDevice = null;
			observable.notifyObservers();
		}

		@Override
		public void deviceParameterUpdated(DeviceParameter deviceParameter) {
			int val = deviceParameter.getCachedValue();
			R7GenericDeviceParameter type = R7GenericDeviceParameter.values()[deviceParameter.getIdentifier()];
			Log.v(TAG, "deviceParameterUpdated("+
					type+" ("+deviceParameter.getLabel()+"), "+
					getValueLabel(deviceParameter)+")");
		}

		@Override
		public void deviceBatteryUpdated(int i, Date date) {
			Log.v(TAG, "deviceBatteryUpdated("+i+", "+date+")");
		}

		@Override
		public void deviceStateChanged(R7ConnectionState stateTo, R7ConnectionState stateFrom) {
			Log.v(TAG, "deviceStateChanged("+stateTo+", "+stateFrom+")");

			RockMessaging.this.connectionState = stateTo;
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
			observable.notifyObservers();

			if (genericDevice!=null && r7LockState == R7LockState.R7LockStateUnlocked){
				// device is now ready for more commands...
				requestBeep();
				Log.v(TAG, "requestLastKnownGpsPosition");
				comms.requestLastKnownGpsPosition();
				/* TODO request interesting parameter values?
				R7GenericDeviceParameter values[] = R7GenericDeviceParameter.values();
				for(DeviceParameter p: connectedDevice.parameters()){
					if (p.getAvailiable() && !p.isCachedValueUsable())
						requestParameter(values[p.getIdentifier()]);
				}*/
			}
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
			Log.v(TAG, "deviceSerialDump("+RockMessaging.toString(bytes)+")");
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

	private R7DeviceMessagingDelegate messaging = new R7DeviceMessagingDelegate() {
		@Override
		public void messageProgressCompleted(short i) {
			Log.v(TAG, "messageProgressCompleted("+i+")");
			observable.notifyObservers();
		}

		@Override
		public boolean messageStatusUpdated(short i, R7MessageStatus r7MessageStatus) {
			Log.v(TAG, "messageStatusUpdated("+i+", "+r7MessageStatus+")");
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
			Log.v(TAG, "messageReceived("+i+", "+RockMessaging.toString(bytes)+")");
			return true;
		}

		@Override
		public void messageProgressUpdated(short i, int part, int total) {
			Log.v(TAG, "messageProgressUpdated("+i+", "+part+", "+total+")");
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
