package org.servalproject.succinct.storage;

public abstract class Factory<T> {
	public abstract String getFileName();

	public T create(byte[] bytes){
		DeSerialiser serialiser = new DeSerialiser(bytes);
		return create(serialiser);
	}

	public abstract T create(DeSerialiser serialiser);

	public byte[] serialise(T object){
		Serialiser serialiser = new Serialiser();
		serialise(serialiser, object);
		return serialiser.getResult();
	}

	public abstract void serialise(Serialiser serialiser, T object);
}
