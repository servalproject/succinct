package org.servalproject.succinct;


import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.PreferenceFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;


public class SettingsFragment extends PreferenceFragment {

    private void setEditText(SharedPreferences prefs, String prefName, String defaultValue){
        EditTextPreference editor = (EditTextPreference) findPreference(prefName);
        editor.setText(prefs.getString(prefName, defaultValue));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preferences);
        SharedPreferences prefs = ((App)getActivity().getApplicationContext()).getPrefs();

        setEditText(prefs, App.BASE_SERVER_URL, BuildConfig.directApiUrl);
        setEditText(prefs, App.SMS_DESTINATION, BuildConfig.smsDestination);
    }
}
