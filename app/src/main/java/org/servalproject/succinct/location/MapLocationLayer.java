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
import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.StorageWatcher;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by kieran on 25/07/17.
 */

public class MapLocationLayer extends Layer {
    private static String TAG = "MapLocationLayer";
    private MapViewPosition mapViewPosition;
    private final Circle radius;
    private final Marker marker;
    private boolean waitingToCenter = false;
    private boolean alwaysCenter = false;
    private boolean haveDisplayModel = false;
    private Context context;
    private MarkerLocation myLocation;
    private HashMap<PeerId, MarkerLocation> markers = new HashMap<>();

    private StorageWatcher<Location> locationWatcher;

    private class MarkerLocation{
        private final PeerId peer;
        private final Location location;
        private final LatLong latLong;

        private MarkerLocation(PeerId peer, Location location) {
            this.peer = peer;
            this.location = location;
            this.latLong = new LatLong(location.getLatitude(), location.getLongitude());
        }
    }

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
        marker = new Marker(null, myLocationMarker, 0, 0);

    }

    public synchronized void center(MapViewPosition mapViewPosition, boolean fix) {
        this.mapViewPosition = mapViewPosition;
        if (mapViewPosition == null) {
            alwaysCenter = false;
            waitingToCenter = false;
            return;
        }
        if (myLocation!=null) {
            mapViewPosition.setCenter(myLocation.latLong);
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
        if (locationWatcher == null){
            final App app = (App)context.getApplicationContext();
            locationWatcher = new StorageWatcher<Location>(app.teamStorage, LocationFactory.factory) {
                @Override
                protected void Visit(PeerId peer, RecordIterator<Location> records) throws IOException {
                    // We only need the last location for each peer
                    records.end();
                    if (records.prev()) {
                        MarkerLocation l = new MarkerLocation(peer, records.read());
                        markers.put(peer, l);
                        if (app.networks.myId.equals(peer)){
                            myLocation = l;
                            if (waitingToCenter || alwaysCenter){
                                mapViewPosition.setCenter(l.latLong);
                                waitingToCenter = false;
                            }
                        }
                        if (haveDisplayModel)
                            requestRedraw();
                    }
                }
            };
        }
        markers.clear();
        locationWatcher.activate();

        this.context = context;
    }

    /**
     * Stop waiting for location updates from the LocationService
     */
    public void deactivate() {
        Log.d(TAG, "deactivate");
        locationWatcher.deactivate();
        markers.clear();
    }

    /**
     * Add Layer to a map with map.getLayerManager().getLayers()
     * Always use this instead of directly using {@link Layers#add(Layer)}
     * @param layers
     */
    public synchronized void addToLayers(Layers layers) {
        layers.add(this);
        radius.setDisplayModel(getDisplayModel());
        marker.setDisplayModel(getDisplayModel());
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
        for(Map.Entry<PeerId, MarkerLocation> e : markers.entrySet()){
            MarkerLocation l = e.getValue();
            this.radius.setLatLong(l.latLong);
            float accuracy = l.location.getAccuracy();
            if (accuracy == 0)
                accuracy = 40;
            this.radius.setRadius(accuracy);
            this.marker.setLatLong(l.latLong);
            this.radius.draw(boundingBox, zoomLevel, canvas, topLeftPoint);
            this.marker.draw(boundingBox, zoomLevel, canvas, topLeftPoint);
        }
    }

    @Override
    public synchronized void onDestroy() {
        Log.d(TAG, "onDestroy");
        haveDisplayModel = false;
        radius.onDestroy();
        marker.onDestroy();
        super.onDestroy();
    }

}
