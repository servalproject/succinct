package org.servalproject.succinct.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;

import org.servalproject.succinct.R;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class IntervalPreference extends DialogPreference {
	private NumberPicker hour;
	private NumberPicker minute;
	private NumberPicker second;
	private long defaultValue;

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	public IntervalPreference(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setPositiveButtonText(R.string.ok);
		setNegativeButtonText(R.string.cancel);
	}

	public IntervalPreference(Context context, AttributeSet attrs) {
		this(context, attrs, android.R.attr.dialogPreferenceStyle);
	}

	public void setDefault(long defaultValue){
		this.defaultValue = defaultValue;
		setSummary(formatValue(getPersistedLong(defaultValue)));
	}

	private NumberPicker.Formatter twoDigits = new NumberPicker.Formatter() {
		@Override
		public String format(int i) {
			return String.format("%02d", i);
		}
	};

	private NumberPicker picker(Context context, int max){
		NumberPicker p = new NumberPicker(context);
		LinearLayout.LayoutParams parms = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
		DisplayMetrics m = context.getResources().getDisplayMetrics();
		int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, m);
		parms.setMarginStart(margin);
		parms.setMarginEnd(margin);
		p.setLayoutParams(parms);
		p.setMinValue(0);
		p.setMaxValue(max);
		p.setFormatter(twoDigits);
		return p;
	}

	@Override
	protected View onCreateDialogView() {
		Context context = getContext();
		LinearLayout l = new LinearLayout(context);

		hour = picker(context, 23);
		minute = picker(context, 59);
		second = picker(context, 59);

		l.setOrientation(LinearLayout.HORIZONTAL);
		l.setHorizontalGravity(Gravity.CENTER_HORIZONTAL);
		l.addView(hour);
		l.addView(minute);
		l.addView(second);

		return l;
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);

		long value = getPersistedLong(defaultValue) / 1000;
		second.setValue((int) value%60);
		value = (value - (value%60))/60;
		minute.setValue((int) value%60);
		value = (value - (value%60))/60;
		hour.setValue((int) value);
	}

	@Override
	protected void onDialogClosed(boolean positiveResult) {
		super.onDialogClosed(positiveResult);

		if (positiveResult) {
			long value = (
					(hour.getValue()*60 +
					minute.getValue())*60 +
					second.getValue()) * 1000;

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
