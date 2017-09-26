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
}
