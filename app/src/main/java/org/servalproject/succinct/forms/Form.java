package org.servalproject.succinct.forms;

import android.content.Context;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.storage.BlobFactory;
import org.servalproject.succinct.storage.RecordIterator;

import java.io.IOException;

public class Form {
	private Form(){}

	private static native String stripForm(long recipe, String instance);
	private static native byte[] compressForm(long stats, long recipe, String strippedForm);

	public static final BlobFactory factory = new BlobFactory("forms");
	private static final String TAG = "Forms";

	public static byte[] compress(Stats stats, String definition, String instance){
		Recipe recipe = new Recipe(definition);
		// TODO write out recipe by hash
		// cache recipe?
		try{
			Log.v(TAG, "stripForm");
			String stripped = stripForm(recipe.ptr, instance);
			Log.v(TAG, "stripped; "+stripped);
			return compressForm(stats.ptr, recipe.ptr, stripped);
		}finally{
			recipe.close();
		}
	}

	public static void compress(Context context, String formSpecification, String completedRecord){
		Stats stats = new Stats(context, "smac.dat");
		try{
			byte[] compressed = Form.compress(stats, formSpecification, completedRecord);
			App app = (App)context.getApplicationContext();
			RecordIterator<byte[]> records = app.teamStorage.openIterator(factory, app.networks.myId);
			records.append(compressed);
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		} finally {
			stats.close();
		}
	}
}
