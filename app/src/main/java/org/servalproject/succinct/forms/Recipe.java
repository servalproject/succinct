package org.servalproject.succinct.forms;

import android.util.Log;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

public class Recipe {
	private long ptr;
	private String recipe;
	byte[] hash;
	String formName;

	private native void buildRecipe(String content);
	private static native void closeRecipe(long ptr);
	public static native String stripForm(String instance);
	private static native byte[] compressForm(long stats, long recipe, String strippedForm);

	private static String TAG = "Recipe";

	Recipe(String content){
		Log.v(TAG, "buildRecipe");
		buildRecipe(content);
	}

	private void callback(String formName, byte[] hash, String recipe, long ptr){
		this.formName = formName;
		this.hash = hash;
		this.recipe = recipe;
		this.ptr = ptr;
	}

	public static Map<String,String> parse(String stripped){
		Map<String, String> ret = new HashMap<>();
		for(String line : stripped.split("\n")){
			int pos = line.indexOf('=');
			String key = line.substring(0,pos);
			String value = line.substring(pos+1);
			ret.put(key, value);
		}
		return ret;
	}

	public byte[] compress(Stats stats, String stripped){
		byte[] ret = compressForm(stats.ptr, this.ptr, stripped);
		if (ret == null)
			throw new IllegalStateException("Failed to compress form");
		return ret;
	}

	void close(){
		Log.v(TAG, "closeRecipe");
		closeRecipe(ptr);
		ptr = 0;
	}
}
