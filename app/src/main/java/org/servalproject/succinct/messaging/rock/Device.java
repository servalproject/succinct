package org.servalproject.succinct.messaging.rock;

public class Device {
	public final String id;
	String name;

	Device(String id, String name){
		this.id = id;
		this.name = name;
	}

	public String getName(){
		return name;
	}
}
