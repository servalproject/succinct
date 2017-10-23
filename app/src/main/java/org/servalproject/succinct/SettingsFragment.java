package org.servalproject.succinct;


import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.PreferenceFragment;

import org.servalproject.succinct.utils.IntervalPreference;


public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private SharedPreferences prefs;

    private void setEditText(SharedPreferences prefs, String prefName, String defaultValue){
        String value = prefs.getString(prefName, defaultValue);
        EditTextPreference editor = (EditTextPreference) findPreference(prefName);
        editor.setText(value);
        editor.setSummary(value);
    }

    private void setCheckbox(SharedPreferences prefs, String prefName, boolean defaultValue){
        boolean value = prefs.getBoolean(prefName, defaultValue);
        CheckBoxPreference preference = (CheckBoxPreference) findPreference(prefName);
        preference.setChecked(value);
    }

    @Override
    public void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(prefs, "");
    }

    @Override
    public void onStop() {
        super.onStop();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preferences);
        prefs = ((App)getActivity().getApplicationContext()).getPrefs();

        IntervalPreference locationInterval = (IntervalPreference) findPreference(App.LOCATION_INTERVAL);
        locationInterval.setDefault(App.DefaultLocationInterval, IntervalPreference.SCALE_MINUTES);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        setEditText(prefs, App.BASE_SERVER_URL, BuildConfig.directApiUrl);
        setEditText(prefs, App.SMS_DESTINATION, BuildConfig.smsDestination);
        setCheckbox(prefs, App.ENABLE_ROCK, true);
        setCheckbox(prefs, App.ENABLE_SMS, true);
        setCheckbox(prefs, App.ENABLE_HTTP, true);
    }
}
