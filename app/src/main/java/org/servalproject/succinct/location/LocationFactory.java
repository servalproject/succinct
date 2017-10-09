package org.servalproject.succinct.location;

import android.location.Location;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

public class LocationFactory extends Factory<Location>{
	private LocationFactory(){}

	public static final LocationFactory factory = new LocationFactory();

	@Override
	public String getFileName() {
		return "location";
	}

	@Override
	public Location create(DeSerialiser serialiser) {
		byte flags = serialiser.getByte();
		Location ret = new Location(serialiser.getString());
		ret.setTime(serialiser.getRawLong());
		ret.setLatitude(serialiser.getDouble());
		ret.setLongitude(serialiser.getDouble());
		if ((flags & 1)!=0)
			ret.setAccuracy(serialiser.getFloat());
		else
			ret.removeAccuracy();
		if ((flags & 2)!=0)
			ret.setAltitude(serialiser.getFloat());
		else
			ret.removeAltitude();
		if ((flags & 4)!=0)
			ret.setBearing(serialiser.getFloat());
		else
			ret.removeBearing();
		if ((flags & 8)!=0)
			ret.setSpeed(serialiser.getFloat());
		else
			ret.removeSpeed();
		return ret;
	}

	@Override
	public void serialise(Serialiser serialiser, Location obj) {
		byte flags =0;
		if (obj.hasAccuracy())
			flags|=1;
		if (obj.hasAltitude())
			flags|=2;
		if (obj.hasBearing())
			flags|=4;
		if (obj.hasSpeed())
			flags|=8;
		serialiser.putByte(flags);
		serialiser.putString(obj.getProvider());
		serialiser.putRawLong(obj.getTime());
		serialiser.putDouble(obj.getLatitude());
		serialiser.putDouble(obj.getLongitude());
		if (obj.hasAccuracy())
			serialiser.putFloat(obj.getAccuracy());
		if (obj.hasAltitude())
			serialiser.putFloat((float) obj.getAltitude());
		if (obj.hasBearing())
			serialiser.putFloat(obj.getBearing());
		if (obj.hasSpeed())
			serialiser.putFloat(obj.getSpeed());
	}

	/* packing format:
	    | (90 + lat) * 23301.686 | (180 + lng) * 23301.686 | accuracy |
	            22 bits                   23 bits             3 bits

         accuracy: 0       <= 10m
                   1       <= 20m
                   2       <= 50m
                   3       <= 100m
                   4       <= 200m
                   5       <= 500m
                   6       <= 1000m
                   7        > 1000m

	     note: 23301.686 = (2^23-1)/360 - eps
	 */
	private static final float[] accLookup = {10f, 20f, 50f, 100f, 200f, 500f, 1000f};
	private static final double latLngScale = 23301.686;

	public static byte[] packLatLngAcc(Location loc) {
		byte[] ret = new byte[6];
		packLatLngAcc(ret, 0, loc);
		return ret;
	}

	public static int packLatLngAcc(byte[] array, int offset, Location loc) {
		return packLatLngAcc(array, offset, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
	}

	public static int packLatLngAcc(byte[] array, int offset, double lat, double lng, float acc) {
		if (offset+5 >= array.length)
			throw new IllegalArgumentException();

		long pLat = (long) ((90.0+lat)*latLngScale);
		long pLng = (long) ((180.0+lng)*latLngScale);
		int pAcc = 0;
		while (pAcc < accLookup.length && acc > accLookup[pAcc]) pAcc++;

		if (pLat < 0 || pLat > 0x3fffff || pLng < 0 || pLng > 0x7fffff)
			throw new IllegalArgumentException();

		long data = (pLat << (23+3)) | (pLng << 3) | pAcc;

		array[offset+0] = (byte) ((data >> 40) & 0xff);
		array[offset+1] = (byte) ((data >> 32) & 0xff);
		array[offset+2] = (byte) ((data >> 24) & 0xff);
		array[offset+3] = (byte) ((data >> 16) & 0xff);
		array[offset+4] = (byte) ((data >> 8) & 0xff);
		array[offset+5] = (byte) (data & 0xff);

		return 6;
	}
}
