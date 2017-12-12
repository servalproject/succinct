package org.servalproject.succinct.messaging;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.utils.AndroidObserver;
import org.servalproject.succinct.utils.WakeAlarm;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Observable;

class HttpTransport {
	private final MessageQueue queue;
	private final TeamStorage store;
	private final App app;
	private final WakeAlarm httpRecvAlarm;
	private final ConnectivityManager connectivityManager;
	private long nextHttpCheck;
	private static final String TAG = "HttpTransport";
	private final Map<String, RecordStore> newFormDefinitions = new HashMap<>();
	private final AndroidObserver formDefinitionWatcher = new AndroidObserver() {
		@Override
		public void observe(Observable observable, Object o) {
			RecordStore file = (RecordStore)o;
			if (!file.filename.getParentFile().getName().equals("forms"))
				return;
			String name = file.filename.getName();
			if (newFormDefinitions.containsKey(name))
				return;
			if (!Hex.isHex(name))
				return;
			if ("true".equals(file.getProperty("uploaded")))
				return;
			newFormDefinitions.put(file.filename.getName(), file);
		}
	};

	HttpTransport(MessageQueue queue, TeamStorage store, App appContext){
		this.queue = queue;
		this.app = appContext;
		this.store = store;
		connectivityManager = (ConnectivityManager)appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
		httpRecvAlarm = WakeAlarm.getAlarm(appContext, "HttpReceive", App.backgroundHandler, new Runnable() {
			@Override
			public void run() {
				receiveHttpFragments();
			}
		});
		appContext.getPrefs().registerOnSharedPreferenceChangeListener(prefsChanged);
		IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
		appContext.registerReceiver(receiver, filter);
		httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
	}

	void monitor(boolean enable){
		if (enable) {
			store.observable.addObserver(formDefinitionWatcher);
			File[] files = new File(store.root, "forms").listFiles();
			if (files != null) {
				for (File f : files) {
					try {
						formDefinitionWatcher.observe(null, store.openFile("forms/" + f.getName()));
					} catch (IOException e) {
						Log.e(TAG, e.getMessage(), e);
					}
				}
			}
		}else{
			store.observable.deleteObserver(formDefinitionWatcher);
		}
	}

	private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = new SharedPreferences.OnSharedPreferenceChangeListener() {
		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
			httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
		}
	};

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)){
				httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextHttpCheck);
				queue.onStateChanged();
			}
		}
	};

	private String readString(URLConnection connection) throws IOException {
		final char[] buffer = new char[512];
		final StringBuilder out = new StringBuilder();
		Reader in = new InputStreamReader(connection.getInputStream(), "UTF-8");
		while(true) {
			int rsz = in.read(buffer, 0, buffer.length);
			if (rsz < 0)
				break;
			out.append(buffer, 0, rsz);
		}
		in.close();
		return out.toString();
	}

	private byte[] readBytes(URLConnection connection) throws IOException {
		int len = connection.getContentLength();
		if (len <=0)
			return null;

		byte[] ret = new byte[len];
		int offset=0;
		InputStream stream = connection.getInputStream();
		while(offset < ret.length){
			int r = stream.read(ret, offset, ret.length - offset);
			if (r<0)
				throw new EOFException();
			offset+=r;
		}
		return ret;
	}

	private String getBaseUrl(){
		if (!app.getPrefs().getBoolean(App.ENABLE_HTTP, true))
			return null;
		String baseUrl = app.getPrefs().getString(App.BASE_SERVER_URL,
				BuildConfig.directApiUrl);
		if (baseUrl == null || "".equals(baseUrl))
			return null;

		NetworkInfo network = connectivityManager.getActiveNetworkInfo();
		if (network == null || !network.isConnected())
			return null;
		return baseUrl;
	}

	private void markAck(int seq) throws IOException {
		if (seq <-1 || seq >= queue.nextFragmentSeq)
			throw new IllegalStateException("Sequence out of range ("+seq+", "+queue.nextFragmentSeq+")");

		RecordIterator<Fragment> fragments = queue.fragments;

		if (seq <0){
			fragments.start();
			fragments.next();
			fragments.mark("http_acked");
			return;
		}

		int first = 0;
		int last = queue.nextFragmentSeq -1;
		{
			Fragment current = fragments.read();
			if (current != null) {
				int currentSeq = current.seq;
				if (seq >= currentSeq)
					first = currentSeq;
				else
					last = currentSeq;
			}
		}
		boolean forwards = (seq - first) < (last - seq);
		if (forwards && first == 0) {
			fragments.start();
			if (!fragments.next())
				throw new IllegalStateException("Seq "+seq+" not found!");
		}
		if (!forwards && last == queue.nextFragmentSeq -1) {
			fragments.end();
			if (!fragments.prev())
				throw new IllegalStateException("Seq "+seq+" not found!");
		}
		while(true){
			Fragment fragment = fragments.read();
			if (fragment.seq == seq) {
				fragments.next();
				fragments.mark("http_acked");
				return;
			}
			if (!(forwards ? fragments.next() : fragments.prev()))
				break;
		}
		throw new IllegalStateException("Seq "+seq+" not found!");
	}

	void sendViaHttp(){
		String baseUrl = getBaseUrl();
		if (baseUrl == null)
			return;

		try {
			RecordIterator<Fragment> fragments = queue.fragments;

			fragments.reset("http_acked");
			// If we've already acked them all, skip the connection to the server
			if (fragments.next()){
				// double check the latest ack sequence with the server
				URL url = new URL(baseUrl+"/succinct/api/v1/ack/"+store.teamId+"?key="+BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				try {
					connection.setRequestProperty("Connection", "keep-alive");
					connection.connect();
					int response = connection.getResponseCode();
					if (response == 404) {
						markAck(-1);
					} else if (response == 200) {
						markAck(Integer.parseInt(readString(connection)));
					} else {
						Log.e(TAG, "Unexpected http response code " + response);
						return;
					}
				} finally {
					connection.disconnect();
				}
			}

			while(true){
				Fragment sendFragment = fragments.read();
				if (sendFragment == null){
					// If we reach the end of the fragment list, we can avoid other transports
					fragments.mark("sending");
					if (!(queue.nextMessage(true) && fragments.next())) {
						break;
					}
					continue;
				}

				URL url = new URL(baseUrl+"/succinct/api/v1/uploadFragment/"+store.teamId+"?key="+BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				try {
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Connection", "keep-alive");
					connection.setRequestProperty("Content-Type", "application/octet-stream");
					connection.setFixedLengthStreamingMode(sendFragment.bytes.length);
					connection.connect();
					OutputStream out = connection.getOutputStream();
					out.write(sendFragment.bytes);
					out.close();
					int response = connection.getResponseCode();
					if (response != 200) {
						Log.e(TAG, "Unexpected http response code " + response);
						return;
					}

					markAck(Integer.parseInt(readString(connection)));
				}finally{
					connection.disconnect();
				}
			}

		} catch (NumberFormatException | IOException e){
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void uploadFormDefinitions() {
		String baseUrl = getBaseUrl();
		if (baseUrl == null)
			return;

		Iterator<Map.Entry<String,RecordStore>> i = newFormDefinitions.entrySet().iterator();
		while(i.hasNext()){
			try {
				Map.Entry<String, RecordStore> e = i.next();
				RecordStore store = e.getValue();
				String hash = store.filename.getName();
				URL url = new URL(baseUrl + "/succinct/api/v1/haveForm/" + hash + "?key=" + BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				String result = null;
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				try {
					connection.setRequestProperty("Connection", "keep-alive");
					connection.connect();
					int response = connection.getResponseCode();
					if (response != 200) {
						Log.v(TAG, "Unexpected http response code " + response);
						return;
					}
					result = readString(connection);
				}finally{
					connection.disconnect();
				}

				if ("false".equals(result)){
					url = new URL(baseUrl + "/succinct/api/v1/uploadForm/" + hash + "?key=" + BuildConfig.directApiKey);
					Log.v(TAG, "Connecting to "+url);
					connection = (HttpURLConnection) url.openConnection();
					try {
						connection.setRequestMethod("POST");
						connection.setRequestProperty("Connection", "keep-alive");
						connection.setRequestProperty("Content-Type", "application/octet-stream");
						connection.setFixedLengthStreamingMode((int) store.EOF);
						connection.connect();

						OutputStream out = connection.getOutputStream();
						byte buff[] = new byte[1024];
						long offset = 0;
						while (true) {
							int read = store.read(offset, buff);
							if (read <= 0)
								break;
							out.write(buff, 0, read);
							offset+=read;
						}
						out.close();
						int response = connection.getResponseCode();
						if (response != 200) {
							Log.e(TAG, "Unexpected http response code " + response);
							return;
						}
					}finally{
						connection.disconnect();
					}
				}
				store.putProperty("uploaded", "true");
				i.remove();
			}catch (IOException e){
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}

	private void receiveHttpFragments() {
		if (SystemClock.elapsedRealtime() < nextHttpCheck)
			return;

		String baseUrl = getBaseUrl();
		if (baseUrl == null)
			return;

		try {
			while (true) {
				int nextSeq = queue.incomingTracker.nextMissing();
				URL url = new URL(baseUrl + "/succinct/api/v1/receiveFragment/" + store.teamId + "/" + nextSeq + "?key=" + BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				try {
					connection.setRequestProperty("Connection", "keep-alive");
					connection.connect();
					int response = connection.getResponseCode();
					Log.v(TAG, "Status code "+response);
					nextHttpCheck = SystemClock.elapsedRealtime() + 60000;
					if (response == 404)
						break;
					if (response != 200) {
						Log.e(TAG, "Unexpected http response " + response);
						break;
					}

					byte[] message = readBytes(connection);
					Fragment fragment = new Fragment(System.currentTimeMillis(), message);
					queue.storeFragment(fragment);
				}finally{
					connection.disconnect();
				}
			}
			// look for incoming content every 60s, unless we run into a networking problem.
			httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextHttpCheck);
		}catch (Exception e){
			Log.e(TAG, e.getMessage(), e);
		}

		try {
			queue.processFragments();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}

		uploadFormDefinitions();
	}
}
