package org.servalproject.succinct;

import android.content.Context;
import android.os.Bundle;
import android.app.Fragment;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;

import java.io.File;

public class MapFragment extends Fragment {

    private static final String MAP_FILE = "succinct/australia.map";

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
        MapView map = (MapView) view.findViewById(R.id.map_view);
        map.setClickable(true);
        map.getMapScaleBar().setVisible(true);
        map.setBuiltInZoomControls(true);
        map.setZoomLevelMin((byte) 10);
        map.setZoomLevelMax((byte) 20);

        // create tile cache
        TileCache tileCache = AndroidUtil.createTileCache(getActivity(), "mapcache",
                map.getModel().displayModel.getTileSize(), 1f,
                map.getModel().frameBufferModel.getOverdrawFactor());

        // tile renderer layer
        File mapFile = new File(Environment.getExternalStorageDirectory(), MAP_FILE);
        if (mapFile.isFile()) {
            MapDataStore mapDataStore = new MapFile(mapFile);
            TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCache, mapDataStore,
                    map.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
            tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

            map.getLayerManager().getLayers().add(tileRendererLayer);
            map.setCenter(new LatLong(-34.9285, 138.6007));
            map.setZoomLevel((byte) 12);
        }
        return view;
    }
}
