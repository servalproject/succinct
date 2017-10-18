package org.servalproject.succinct.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TimePicker;

import org.servalproject.succinct.R;

public class IntervalPreference extends DialogPreference {
	private TimePicker picker;
	private long defaultValue;
	private long scale;

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	public IntervalPreference(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setPositiveButtonText(R.string.ok);
		setNegativeButtonText(R.string.cancel);
	}

	public IntervalPreference(Context context, AttributeSet attrs) {
		this(context, attrs, android.R.attr.dialogPreferenceStyle);
	}

	public static long SCALE_SECONDS = 1000;
	public static long SCALE_MINUTES = 1000*60;

	public void setDefault(long defaultValue, long scale){
		this.defaultValue = defaultValue;
		this.scale = scale;
		setSummary(formatValue(getPersistedLong(defaultValue)));
	}

	@Override
	protected View onCreateDialogView() {
		// TODO use number pickers directly
		picker = new TimePicker(getContext());
		picker.setIs24HourView(true);
		return picker;
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);

		long value = getPersistedLong(defaultValue) / scale;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			picker.setMinute((int) (value%60));
		}else{
			picker.setCurrentMinute((int) (value%60));
		}
		value = (value - (value%60))/60;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			picker.setHour((int) value);
		}else{
			picker.setCurrentHour((int) value);
		}
	}

	@Override
	protected void onDialogClosed(boolean positiveResult) {
		super.onDialogClosed(positiveResult);

		if (positiveResult) {
			int hour;
			int minute;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				hour = picker.getHour();
				minute = picker.getMinute();
			} else {
				hour = picker.getCurrentHour();
				minute = picker.getCurrentMinute();
			}
			long value = ((hour*60)+minute)*scale;
			persistLong(value);
			setSummary(formatValue(value));
		}
	}

	private String[] labels={"ms ","s ","m ","h ","d "};
	private int[] units={1000,60,60,24,1000};

	public CharSequence formatValue(long value){
		StringBuilder sb = new StringBuilder();
		int i=0;
		while(value!=0 && i<units.length){
			int part = (int) (value%units[i]);
			if (part!=0){
				sb.insert(0, labels[i]);
				sb.insert(0, part);
				value -= part;
			}
			value /= units[i++];
		}
		return sb.toString().trim();
	}
}
