package org.servalproject.succinct.storage;

public interface Factory<T> {
	String getFileName();
	T create(DeSerialiser serialiser);
	void serialise(Serialiser serialiser, T object);
}
