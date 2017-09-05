package org.servalproject.succinct;

import android.app.Fragment;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.support.graphics.drawable.VectorDrawableCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import org.servalproject.succinct.location.LocationService;
import org.servalproject.succinct.location.MapLocationLayer;

import java.io.File;

public class MapFragment extends Fragment {

    public MapFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("MapFragment", "onCreate");
        //noinspection StatementWithEmptyBody
        if (getArguments() != null) {
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_map, container, false);
        final MapView map = (MapView) view.findViewById(R.id.map_view);
        map.setClickable(true);
        map.getMapScaleBar().setVisible(true);
        map.setBuiltInZoomControls(true);
        map.setZoomLevelMin((byte) 10);
        map.setZoomLevelMax((byte) 20);

        MainActivity mainActivity = (MainActivity) getActivity();
        TileCache tileCache = mainActivity.getMapTileCache(new MainActivity.Supplier<TileCache>() {
            @Override
            public TileCache get() {
                return AndroidUtil.createTileCache(getActivity(), "mapcache",
                        map.getModel().displayModel.getTileSize(), 1f,
                        map.getModel().frameBufferModel.getOverdrawFactor(), true);
            }
        });

        // tile renderer layer
        MultiMapDataStore store = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);

        // combine all /sdcard/succinct/*.map
        File maps = new File(Environment.getExternalStorageDirectory(), "succinct");
        boolean first=true;
        for(File mapFile : maps.listFiles()){
            if (mapFile.isFile() && mapFile.getName().endsWith(".map")){
                boolean defaults = first || mapFile.getName().contains("world");
                store.addMapDataStore(new MapFile(mapFile), defaults, defaults);
                first = false;
            }
        }

        TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCache, store,
                map.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

        map.getLayerManager().getLayers().add(tileRendererLayer);
        map.setZoomLevelMin((byte) 4);
        map.setCenter(store.startPosition());
        map.setZoomLevel(store.startZoomLevel());

        Drawable drawable = VectorDrawableCompat.create(getResources(), R.drawable.marker_mylocation, null);

        Bitmap bitmap = AndroidGraphicFactory.convertToBitmap(drawable);
        MapLocationLayer mapLocationLayer = new MapLocationLayer(bitmap);
        mapLocationLayer.addToMap(map);
        mapLocationLayer.center(map, true);
        LocationService locationService = mainActivity.getLocationService();
        mapLocationLayer.activate(mainActivity);

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // myLocationOverlay.disableMyLocation();
    }
}
