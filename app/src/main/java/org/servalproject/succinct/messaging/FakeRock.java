package org.servalproject.succinct.messaging;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by jeremy on 6/12/17.
 *
 * Fake rock core http requests for delivered fragments
 */

public class FakeRock implements IMessaging {
	private final URL url;
	private final ConnectivityManager connectivityManager;
	private static final String TAG = "FakeRock";

	FakeRock(App app) throws MalformedURLException {
		url = new URL(app.getPrefs().getString(App.BASE_SERVER_URL,BuildConfig.directApiUrl)+
				"/succinct/rock7/receive.php?key="+
				BuildConfig.rockCallbackKey);
		connectivityManager = (ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	@Override
	public int getMTU() {
		return 338;
	}

	@Override
	public int checkAvailable() {
		NetworkInfo network = connectivityManager.getActiveNetworkInfo();
		if (network == null || !network.isConnected())
			return UNAVAILABLE;
		return SUCCESS;
	}

	@Override
	public int trySend(Fragment fragment) {
		int available = checkAvailable();
		if (available != SUCCESS)
			return available;

		Log.v(TAG, "Connecting to "+url);
		byte[] postData =
				("device_type=FAKEUNIT" +
				"&serial=99999" +
				"&momsn=999" +
				"&trigger=BLE_RAW" +
				"&userData=" + Hex.toString(fragment.bytes))
					.getBytes(Serialiser.UTF_8);

		try {
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Connection", "keep-alive");
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			connection.setFixedLengthStreamingMode(postData.length);
			connection.connect();

			OutputStream out = connection.getOutputStream();
			out.write(postData);
			out.close();

			int response = connection.getResponseCode();
			if (response != 200) {
				Log.e(TAG, "Unexpected http response code " + response);
				return UNAVAILABLE;
			}

			return SUCCESS;
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
			return UNAVAILABLE;
		}
	}

	@Override
	public void done() {

	}

	@Override
	public void close() {

	}
}
