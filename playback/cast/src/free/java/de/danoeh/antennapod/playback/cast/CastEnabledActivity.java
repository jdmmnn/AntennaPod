package de.danoeh.antennapod.playback.cast;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.core.view.MenuItemCompat;
import androidx.mediarouter.app.MediaRouteActionProvider;
import de.danoeh.antennapod.playback.sonos.SonosMediaRouteProvider;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;

/**
 * Activity that allows for showing the MediaRouter button whenever there's a cast device in the
 * network.
 */
public abstract class CastEnabledActivity extends AppCompatActivity {
    public static final String TAG = "CastEnabledActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SonosMediaRouteProvider.registerIfEnabled(this);
    }

    public final void requestCastButton(Menu menu) {
        requestCastButton(menu, true);
    }

    public final void requestCastButton(Menu menu, boolean includeSonos) {
        SonosMediaRouteProvider.registerIfEnabled(this);
        MenuItem item = menu.findItem(R.id.media_route_menu_item);
        if (!includeSonos || !SonosPreferences.isEnabled()) {
            if (item != null) {
                item.setVisible(false);
            }
            return;
        }
        if (item == null) {
            getMenuInflater().inflate(R.menu.cast_button, menu);
            item = menu.findItem(R.id.media_route_menu_item);
        }
        item.setVisible(true);
        MediaRouteActionProvider provider = (MediaRouteActionProvider) MenuItemCompat.getActionProvider(item);
        if (provider != null) {
            provider.setRouteSelector(SonosMediaRouteProvider.selector());
            provider.setDialogFactory(SonosMediaRouteProvider.dialogFactory());
            provider.setAlwaysVisible(true);
        }
    }
}
