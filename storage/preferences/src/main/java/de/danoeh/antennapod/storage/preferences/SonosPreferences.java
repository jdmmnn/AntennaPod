package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

public class SonosPreferences {

    private static final String TAG = "SonosPreferences";

    public static final String PREF_ENABLED = "prefSonosSupport";
    private static final String PREF_LAST_GROUP_ID = "prefSonosLastGroupId";

    private static SharedPreferences prefs;

    public static void init(@NonNull Context context) {
        Log.d(TAG, "Creating new instance of SonosPreferences");
        SonosPreferences.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    public static String getLastGroupId() {
        return prefs.getString(PREF_LAST_GROUP_ID, "");
    }

    public static void setLastGroupId(String groupId) {
        prefs.edit().putString(PREF_LAST_GROUP_ID, groupId).apply();
    }
}
