package org.servalproject.succinct.storage;

public class BlobFactory implements Factory<byte[]>{
	private final String filename;

	public BlobFactory(String filename){
		this.filename = filename;
	}

	@Override
	public String getFileName() {
		return filename;
	}

	@Override
	public byte[] create(DeSerialiser serialiser) {
		return serialiser.getBytes();
	}

	@Override
	public void serialise(Serialiser serialiser, byte[] object) {
		serialiser.putBytes(object);
	}
}
