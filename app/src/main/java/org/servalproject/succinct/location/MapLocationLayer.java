package org.servalproject.succinct.location;

import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.util.Log;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.Layers;
import org.mapsforge.map.layer.overlay.Circle;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.model.MapViewPosition;
import org.servalproject.succinct.location.LocationService.LocationBroadcastReceiver;

/**
 * Created by kieran on 25/07/17.
 */

public class MapLocationLayer extends Layer {
    private static String TAG = "MapLocationLayer";
    private Location lastLocation;
    private LatLong lastLatLong;
    private boolean locationValid = false;
    private MapViewPosition mapViewPosition;
    private final Circle radius;
    private final Marker me;
    private boolean waitingToCenter = false;
    private boolean alwaysCenter = false;
    private boolean haveDisplayModel = false;
    private Context context;

    public MapLocationLayer(Bitmap myLocationMarker) {
        super();

        Paint radiusFill = AndroidGraphicFactory.INSTANCE.createPaint();
        radiusFill.setColor(Color.argb(50, 0, 0, 255));
        radiusFill.setStyle(Style.FILL);
        Paint radiusStroke = AndroidGraphicFactory.INSTANCE.createPaint();
        radiusStroke.setColor(Color.argb(150, 0, 0, 255));
        radiusStroke.setStrokeWidth(2f);
        radiusStroke.setStyle(Style.STROKE);
        radius = new Circle(null, 0, radiusFill, radiusStroke);

        me = new Marker(null, myLocationMarker, 0, 0);
    }

    public synchronized void center(MapViewPosition mapViewPosition, boolean fix) {
        this.mapViewPosition = mapViewPosition;
        if (mapViewPosition == null) {
            alwaysCenter = false;
            waitingToCenter = false;
            return;
        }
        if (locationValid) {
            mapViewPosition.setCenter(lastLatLong);
            waitingToCenter = false;
            if (haveDisplayModel) {
                requestRedraw();
            }
        }
        else {
            waitingToCenter = true;
        }
        alwaysCenter = fix;
    }

    public void center(MapView map, boolean fix) {
        if (map != null) {
            center(map.getModel().mapViewPosition, fix);
        }
    }

    /**
     * Wait for location updates from the LocationService
     * @param context
     */
    public void activate(Context context) {
        Log.d(TAG, "activate");
        this.context = context;
        if (context != null) {
            locationBroadcastReceiver.register(context);
        }
        locationBroadcastReceiver.onEnabled();
    }

    /**
     * Stop waiting for location updates from the LocationService
     */
    public void deactivate() {
        Log.d(TAG, "deactivate");
        if (context != null) {
            locationBroadcastReceiver.unregister(context);
        }
        locationBroadcastReceiver.onDisabled();
    }

    /**
     * Add Layer to a map with map.getLayerManager().getLayers()
     * Always use this instead of directly using {@link Layers#add(Layer)}
     * @param layers
     */
    public synchronized void addToLayers(Layers layers) {
        layers.add(this);
        radius.setDisplayModel(getDisplayModel());
        me.setDisplayModel(getDisplayModel());
        haveDisplayModel = true;
    }

    /**
     * Add MapLocationLayer to a {@link MapView}.
     * @param map
     */
    public void addToMap(MapView map) {
        addToLayers(map.getLayerManager().getLayers());
    }

    @Override
    public synchronized void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint) {
        if (haveDisplayModel && locationValid) {
            this.radius.draw(boundingBox, zoomLevel, canvas, topLeftPoint);
            this.me.draw(boundingBox, zoomLevel, canvas, topLeftPoint);
        }
    }

    @Override
    public synchronized void onDestroy() {
        Log.d(TAG, "onDestroy");
        haveDisplayModel = false;
        radius.onDestroy();
        me.onDestroy();
        if (context != null) {
            locationBroadcastReceiver.unregister(context);
        }
        super.onDestroy();
    }

    private final LocationBroadcastReceiver locationBroadcastReceiver = new LocationBroadcastReceiver() {
        @Override
        public void onDisabled() {
            Log.d(TAG, "location broadcast receiver onDisabled");
            synchronized (MapLocationLayer.this) {
                locationValid = false;
                if (haveDisplayModel) {
                    requestRedraw();
                }
            }
        }

        @Override
        public void onEnabled() {
            Log.d(TAG, "location broadcast receiver onEnabled");
        }

        @Override
        public void onNewLocation(Location location) {
            Log.d(TAG, "onNewLocation " + location);
            if (location == null) return;
            synchronized (MapLocationLayer.this) {
                lastLocation = location;
                lastLatLong = new LatLong(location.getLatitude(), location.getLongitude());
                radius.setLatLong(lastLatLong);
                me.setLatLong(lastLatLong);

                float accuracy = location.getAccuracy();
                if (accuracy == 0) accuracy = 40;
                radius.setRadius(accuracy);

                locationValid = true;

                if (waitingToCenter || alwaysCenter) {
                    mapViewPosition.setCenter(lastLatLong);
                    waitingToCenter = false;
                }

                if (haveDisplayModel) {
                    requestRedraw();
                }
            }
        }
    };
}
