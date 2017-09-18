package org.servalproject.succinct.storage;

public class BlobFactory extends Factory<byte[]>{
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
		return serialiser.getFixedBytes(DeSerialiser.REMAINING);
	}

	@Override
	public void serialise(Serialiser serialiser, byte[] object) {
		serialiser.putFixedBytes(object);
	}
}
