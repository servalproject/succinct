package org.servalproject.succinct.storage;

public interface Factory<T> {
	T create(byte[] bytes);
	byte[] serialise(T object);
}
