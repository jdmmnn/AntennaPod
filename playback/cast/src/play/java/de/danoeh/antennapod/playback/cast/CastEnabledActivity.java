package de.danoeh.antennapod.playback.cast;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuItemCompat;
import androidx.mediarouter.app.MediaRouteActionProvider;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import de.danoeh.antennapod.playback.sonos.SonosMediaRouteProvider;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;

/**
 * Activity that allows for showing the MediaRouter button whenever there's a cast device in the
 * network.
 */
public abstract class CastEnabledActivity extends AppCompatActivity {
    private boolean canCast = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        canCast = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS;
        if (canCast) {
            try {
                CastContext.getSharedInstance(this);
            } catch (Exception e) {
                e.printStackTrace();
                canCast = false;
            }
        }
        SonosMediaRouteProvider.registerIfEnabled(this);
    }

    public void requestCastButton(Menu menu) {
        requestCastButton(menu, true);
    }

    public void requestCastButton(Menu menu, boolean includeSonos) {
        SonosMediaRouteProvider.registerIfEnabled(this);
        boolean canSonos = includeSonos && SonosPreferences.isEnabled();
        if (!canCast && !canSonos) {
            MenuItem item = menu.findItem(R.id.media_route_menu_item);
            if (item != null) {
                item.setVisible(false);
            }
            return;
        }
        if (menu.findItem(R.id.media_route_menu_item) == null) {
            getMenuInflater().inflate(R.menu.cast_button, menu);
            if (canCast) {
                CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.media_route_menu_item);
            }
        }
        MediaRouteSelector.Builder selector = new MediaRouteSelector.Builder();
        if (canCast) {
            selector.addSelector(CastContext.getSharedInstance(this).getMergedSelector());
        }
        if (canSonos) {
            selector.addSelector(SonosMediaRouteProvider.selector());
        }
        MenuItem item = menu.findItem(R.id.media_route_menu_item);
        item.setVisible(true);
        MediaRouteActionProvider provider = (MediaRouteActionProvider) MenuItemCompat.getActionProvider(item);
        if (provider != null) {
            provider.setRouteSelector(selector.build());
            provider.setDialogFactory(SonosMediaRouteProvider.dialogFactory());
            provider.setAlwaysVisible(true);
        }
    }
}
