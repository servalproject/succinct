package org.servalproject.succinct.messaging.rock;

import android.support.annotation.NonNull;

public class Device implements Comparable<Device>{
	public final String id;
	String name;

	Device(String id, String name){
		this.id = id;
		this.name = name;
	}

	public String getName(){
		return name;
	}

	@Override
	public int compareTo(@NonNull Device device) {
		return id.compareTo(device.id);
	}
}
