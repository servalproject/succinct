package org.servalproject.succinct.location;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.storage.RecordIterator;

import java.io.IOException;

/**
 * Created by kieran on 21/07/17.
 */

public class LocationService extends Service {

    private static final String TAG = "LocationService";
    private static final String GPS_STATUS = "gps-status";
    private static final String GPS_STATUS_EXTRA_ENABLED = "enabled";
    private static final String GPS_STATUS_EXTRA_LOCATION = "location";
    private final IBinder binder = new LocationBinder();
    private LocationManager locationManager;
    private Location lastLocation;
    private long minTime = 2 * 60 * 1000;
    private long minTimePassive = 10 * 1000;
    private float minDistance = 0f;
    private boolean gpsEnabled = false;
    private RecordIterator<Location> iterator;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean isGPSEnabled() {
        return gpsEnabled;
    }

    public @Nullable Location getLastLocation() {
        return lastLocation;
    }

    public class LocationBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }

    public abstract static class LocationBroadcastReceiver extends BroadcastReceiver {
        public abstract void onDisabled();
        public abstract void onEnabled();
        public abstract void onNewLocation(Location location);

        public void register(Context context) {
            LocalBroadcastManager.getInstance(context).registerReceiver(this, new IntentFilter(LocationService.GPS_STATUS));
        }

        public void unregister(Context context) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(GPS_STATUS_EXTRA_ENABLED)) {
                boolean enabled = intent.getBooleanExtra(GPS_STATUS_EXTRA_ENABLED, false);
                if (enabled) {
                    onEnabled();
                } else {
                    onDisabled();
                }
            } else if (intent.hasExtra(GPS_STATUS_EXTRA_LOCATION)) {
                Location location = intent.getParcelableExtra(GPS_STATUS_EXTRA_LOCATION);
                if (location != null) {
                    onNewLocation(location);
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand intent is null ? " + (intent == null));
        super.onStartCommand(intent, flags, startId);
        // the system should restart the service if it is killed before finishing
        return Service.START_STICKY;
    }

    private RecordIterator<Location> getIterator(){
		if (iterator == null){
			try {
				App app = (App) getApplication();
				if (app.teamStorage!=null)
					iterator = app.teamStorage.openIterator(LocationFactory.factory, app.networks.myId);
			} catch (IOException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
		return iterator;
	}

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

		try {
			RecordIterator<Location> iterator =getIterator();
			if (iterator!=null)
				lastLocation = iterator.readLast();
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}

		locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            // Perhaps there was a location update we missed?
            updateLocation(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER), false);
            Log.d(TAG, "getLastKnownLocation (GPS) " + lastLocation);
            for(String name:locationManager.getAllProviders()){
                LocationProvider provider = locationManager.getProvider(name);
                long time = (name.equals(LocationManager.PASSIVE_PROVIDER) ? minTimePassive : minTime);
                Log.d(TAG, "register "+name+" ("
                                +(provider.getAccuracy() == Criteria.ACCURACY_COARSE ? "coarse" : "fine")
                                +(provider.requiresSatellite() ? ", sat":"")
                                +(provider.requiresNetwork() ? ", network":"")
                                +(provider.requiresCell() ? ", cell":"")
                        +") updates every " + time + "ms, or " + minDistance + "m");
                locationManager.requestLocationUpdates(name, time, minDistance, locationListener);
            }
        } catch (SecurityException e) {
            Log.e(TAG, e.getMessage(), e);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (locationManager != null) {
            Log.d(TAG, "onDestroy un-registering for GPS updates");
            locationManager.removeUpdates(locationListener);
        }
    }

    private void updateLocation(Location newLocation, boolean fresh){
        if (newLocation==null)
            return;

        // ignore NULL island
        // TODO better test?
        if (newLocation.getLatitude() == 0 && newLocation.getLongitude() == 0)
            return;

        long elapsed = newLocation.getElapsedRealtimeNanos();
        long elapsedNow = SystemClock.elapsedRealtimeNanos();
        long now = System.currentTimeMillis();


        // Filter fixes that we might have seen before
        if (lastLocation!=null) {
            long oldElapsed = lastLocation.getElapsedRealtimeNanos();
            // if we can, filter based on time since boot
            if(oldElapsed != 0 && elapsed <= oldElapsed) {
                Log.v(TAG, "Ignoring stale fix ("+fresh+", "+(lastLocation.getTime() - newLocation.getTime())+"): "+lastLocation+" vs "+newLocation);
                return;
            }else if (oldElapsed == 0 && newLocation.getTime() <= lastLocation.getTime()) {
                Log.v(TAG, "Ignoring stale fix (" + fresh + ", " + (lastLocation.getTime() - newLocation.getTime()) + "): " + lastLocation + " vs " + newLocation);
                return;
            }
        }

        // todo logic with accuracy and timing to determine if new location should be used
        try {
			RecordIterator<Location> iterator =getIterator();
			if (iterator!=null) {
				iterator.append(newLocation);
				lastLocation = newLocation;
			}
            LocalBroadcastManager.getInstance(LocationService.this).sendBroadcast(
                    new Intent(GPS_STATUS).putExtra(GPS_STATUS_EXTRA_LOCATION, newLocation));
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            Log.d(TAG, "onLocationChanged " + location.toString());
            updateLocation(location, true);
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {
            Log.d(TAG, "onStatusChanged " + s + " (" + i + ") " + bundle.toString());
        }

        @Override
        public void onProviderEnabled(String s) {
            Log.d(TAG, "onProviderEnabled " + s + " current state " + gpsEnabled);
            if (LocationManager.GPS_PROVIDER.equals(s) && !gpsEnabled) {
                gpsEnabled = true;
                LocalBroadcastManager.getInstance(LocationService.this).sendBroadcast(
                        new Intent(GPS_STATUS).putExtra(GPS_STATUS_EXTRA_ENABLED, gpsEnabled));
            }
        }

        @Override
        public void onProviderDisabled(String s) {
            Log.d(TAG, "onProviderDisabled " + s + " current state " + gpsEnabled);
            if (LocationManager.GPS_PROVIDER.equals(s) && gpsEnabled) {
                gpsEnabled = false;
                LocalBroadcastManager.getInstance(LocationService.this).sendBroadcast(
                        new Intent(GPS_STATUS).putExtra(GPS_STATUS_EXTRA_ENABLED, gpsEnabled));
            }
        }
    };
}
