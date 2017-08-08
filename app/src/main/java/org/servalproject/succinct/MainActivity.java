package org.servalproject.succinct;

import android.Manifest;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.NavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.layer.cache.TileCache;
import org.servalproject.succinct.location.LocationService;
import org.servalproject.succinct.location.LocationService.LocationBroadcastReceiver;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String TAG = "MainActivity";

    private String[] permissionsRequired = new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final int PERMISSION_CALLBACK_CONSTANT = 100;
    private static final int PERMISSION_SETTINGS_INTENT_ID = 101;

    private LocationService locationService;
    private boolean locationServiceBound;

    public enum PermissionState {
        PERMITTED,
        DENIED,
        WAITING
    }

    // for fast switching between map and other fragments, keep the tile cache in parent activity
    private static TileCache tileCache;
    interface Supplier<T> {
        public T get();
    }

    private int selectedFragment=-1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        Log.d(TAG, "onCreate checkAllPermissions");
        PermissionState state = checkAllPermissions();
        // todo handle state (should be either PERMITTED or WAITING)

        Log.d(TAG, "starting LocationService");
        startService(new Intent(this, LocationService.class));

        // Initialise AndroidGraphicFactory (used by maps)
        AndroidGraphicFactory.createInstance(getApplication());
    }

    @Override
    protected void onDestroy() {
        AndroidGraphicFactory.clearResourceMemoryCache();
        // todo later we will want to keep the location service running e.g. if in active team state
        Log.d(TAG, "stopping LocationService");
        stopService(new Intent(this, LocationService.class));
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "binding to LocationService");
        bindService(new Intent(this, LocationService.class), locationServiceConnection, Context.BIND_IMPORTANT);
        Log.d(TAG, "registering receiver for GPS status");
        locationBroadcastReceiver.register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (locationServiceBound) {
            Log.d(TAG, "unbinding from LocationService");
            unbindService(locationServiceConnection);
        }
        Log.d(TAG, "un-registering receiver for GPS status");
        locationBroadcastReceiver.unregister(this);
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);

        if (id == selectedFragment)
            return true;

        selectedFragment = id;

        Fragment newFragment = null;
        CharSequence newTitle = item.getTitle();
        switch (id){
            case R.id.nav_team:
                newFragment = new TeamFragment();
                break;

            case R.id.nav_map:
                newFragment = new MapFragment();
                break;

            case R.id.nav_chat:
                newFragment = new ChatFragment();
                break;

            case R.id.nav_settings:
                newFragment = new SettingsFragment();
                break;

            case R.id.nav_rock:
                newFragment = new RockFragment();
                break;
        }

        if (newFragment != null) {
            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.replace(R.id.main_frame, newFragment);
            // transaction.addToBackStack(null);
            transaction.commit();
            toolbar.setTitle(newTitle);
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    public @Nullable LocationService getLocationService() {
        return locationServiceBound ? locationService : null;
    }

    private PermissionState checkAllPermissions() {
        boolean allGranted = true;
        for (String perm : permissionsRequired) {
            if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "checkAllPermissions: not granted " + perm);
                allGranted = false;
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    Log.d(TAG, "checkAllPermissions: show rationale " + perm);
                    showPermissionsRationale(false);
                    return PermissionState.WAITING;
                }
            }
        }
        if (allGranted) {
            return PermissionState.PERMITTED;
        } else {
            Log.d(TAG, "checkAllPermissions: requesting permissions");
            ActivityCompat.requestPermissions(MainActivity.this, permissionsRequired, PERMISSION_CALLBACK_CONSTANT);
            return PermissionState.WAITING;
        }
    }

    private void onPermissionStateChanged(PermissionState state) {
        // todo handle when permissions now available
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CALLBACK_CONSTANT) {
            Log.d(TAG, "onRequestPermissionResult received");
            boolean allGranted = true;
            for (int i=0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "onRequestPermissionResult: not granted " + permissions[i]);
                    allGranted = false;
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                        Log.d(TAG, "onRequestPermissionResult: show rationale " + permissions[i]);
                        showPermissionsRationale(false);
                        return;
                    }
                }
            }
            if (allGranted) {
                onPermissionStateChanged(PermissionState.PERMITTED);
            } else {
                Log.d(TAG, "onRequestPermissionResult: show rationale (appsettings)");
                showPermissionsRationale(true);
            }
        }
    }

    private void showPermissionsRationale(final boolean sendToSettings) {
        Log.d(TAG, "showPermissionsRationale: send to settings ? " + sendToSettings);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Need Permission(s)");
        String message = "This app requires permission to access location to provide key functionality. Permission to access storage is also required for reading map files.";
        if (sendToSettings) {
            message = message + " Please go to Application Settings and open “Permissions” and ensure all are on.";
        }
        builder.setMessage(message);
        if (sendToSettings) {
            builder.setPositiveButton("Application Settings", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                    Log.d(TAG, "showPermissionsRationale appsettings dialog: starting intent");
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivityForResult(intent, PERMISSION_SETTINGS_INTENT_ID);
                }
            });
        } else {
            builder.setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                    Log.d(TAG, "showPermissionsRationale grant dialog: requesting permissions");
                    ActivityCompat.requestPermissions(MainActivity.this, permissionsRequired, PERMISSION_CALLBACK_CONSTANT);
                }
            });
        }
        builder.setCancelable(false);
        builder.show();
    }

    private void showGPSDisabledMessage() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setMessage("GPS appears to be disabled, do you want to enable it?")
                .setCancelable(false)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.cancel();
                    }
                });
        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PERMISSION_SETTINGS_INTENT_ID) {
            Log.d(TAG, "onActivityResult checkAllPermissions");
            checkAllPermissions();
        }
    }

    protected TileCache getMapTileCache(MainActivity.Supplier<TileCache> creator) {
        if (tileCache == null) {
            tileCache = creator.get();
        }
        return tileCache;
    }

    private final ServiceConnection locationServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            LocationService.LocationBinder binder = (LocationService.LocationBinder) iBinder;
            locationService = binder.getService();
            locationServiceBound = true;

            if (!locationService.isGPSEnabled()) {
                showGPSDisabledMessage();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            locationServiceBound = false;
            locationService = null;
        }
    };

    private final LocationBroadcastReceiver locationBroadcastReceiver = new LocationBroadcastReceiver() {
        @Override
        public void onDisabled() {
            showGPSDisabledMessage();
        }

        @Override
        public void onEnabled() {
        }

        @Override
        public void onNewLocation(Location location) {
            Toast.makeText(MainActivity.this, location.toString(), Toast.LENGTH_LONG).show();
        }
    };
}
