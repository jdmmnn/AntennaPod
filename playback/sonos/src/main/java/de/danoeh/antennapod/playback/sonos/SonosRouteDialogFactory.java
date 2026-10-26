package de.danoeh.antennapod.playback.sonos;

import android.content.Context;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.app.MediaRouteChooserDialogFragment;
import androidx.mediarouter.app.MediaRouteDialogFactory;
import androidx.mediarouter.media.MediaRouter;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;

public final class SonosRouteDialogFactory extends MediaRouteDialogFactory {
    private static final SonosRouteDialogFactory INSTANCE = new SonosRouteDialogFactory();
    static final String LAST_ROUTE_MARK = "★ ";

    public static MediaRouteDialogFactory getInstance() {
        return INSTANCE;
    }

    @NonNull
    @Override
    public MediaRouteChooserDialogFragment onCreateChooserDialogFragment() {
        return new SonosChooserDialogFragment();
    }

    static boolean isLastSonosRoute(@Nullable String routeId, @Nullable String lastGroupId) {
        if (routeId == null || lastGroupId == null || lastGroupId.isEmpty()) {
            return false;
        }
        String target = SonosGroup.ROUTE_ID_PREFIX + lastGroupId;
        return routeId.equals(target) || routeId.endsWith(":" + target);
    }

    static boolean shouldShowNoDevicesHint(boolean sonosEnabled, boolean hasSonosRoute) {
        return sonosEnabled && !hasSonosRoute;
    }

    public static class SonosChooserDialogFragment extends MediaRouteChooserDialogFragment {
        @NonNull
        @Override
        public MediaRouteChooserDialog onCreateChooserDialog(@NonNull Context context,
                                                             @Nullable Bundle savedInstanceState) {
            return new SonosChooserDialog(context);
        }
    }

    static final class SonosChooserDialog extends MediaRouteChooserDialog {
        private TextView hintView;
        private ArrayAdapter<MediaRouter.RouteInfo> baseAdapter;
        private boolean reordering;

        SonosChooserDialog(@NonNull Context context) {
            super(context);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            ListView listView = findViewById(R.id.mr_chooser_list);
            if (listView == null) {
                return;
            }
            if (listView.getParent() instanceof ViewGroup) {
                hintView = new TextView(getContext());
                hintView.setText(R.string.sonos_no_devices_hint);
                int pad = (int) (24 * getContext().getResources().getDisplayMetrics().density);
                hintView.setPadding(pad, pad / 2, pad, pad);
                ((ViewGroup) listView.getParent()).addView(hintView);
            }
            if (listView.getAdapter() instanceof ArrayAdapter) {
                baseAdapter = (ArrayAdapter<MediaRouter.RouteInfo>) listView.getAdapter();
                baseAdapter.registerDataSetObserver(new DataSetObserver() {
                    @Override
                    public void onChanged() {
                        onRoutesChanged();
                    }
                });
                listView.setAdapter(new MarkingRouteAdapter(baseAdapter));
                onRoutesChanged();
            }
        }

        @Override
        public void refreshRoutes() {
            super.refreshRoutes();
            onRoutesChanged();
        }

        private void onRoutesChanged() {
            if (reordering || baseAdapter == null) {
                return;
            }
            reordering = true;
            try {
                boolean hasSonos = reorderAndCheckSonos(baseAdapter, SonosPreferences.getLastGroupId());
                if (hintView != null) {
                    boolean showHint = shouldShowNoDevicesHint(SonosPreferences.isEnabled(), hasSonos);
                    hintView.setVisibility(showHint ? View.VISIBLE : View.GONE);
                }
            } finally {
                reordering = false;
            }
        }

        static boolean reorderAndCheckSonos(ArrayAdapter<MediaRouter.RouteInfo> adapter,
                                            @Nullable String lastGroupId) {
            int firstSonosIndex = -1;
            int lastUsedIndex = -1;
            for (int i = 0; i < adapter.getCount(); i++) {
                MediaRouter.RouteInfo route = adapter.getItem(i);
                if (route == null || !route.supportsControlCategory(SonosMediaRouteProvider.CATEGORY_SONOS)) {
                    continue;
                }
                if (firstSonosIndex < 0) {
                    firstSonosIndex = i;
                }
                if (isLastSonosRoute(route.getId(), lastGroupId)) {
                    lastUsedIndex = i;
                }
            }
            if (lastUsedIndex > firstSonosIndex && firstSonosIndex >= 0) {
                MediaRouter.RouteInfo route = adapter.getItem(lastUsedIndex);
                adapter.remove(route);
                adapter.insert(route, firstSonosIndex);
            }
            return firstSonosIndex >= 0;
        }
    }

    private static final class MarkingRouteAdapter extends BaseAdapter {
        private final ArrayAdapter<MediaRouter.RouteInfo> delegate;

        MarkingRouteAdapter(ArrayAdapter<MediaRouter.RouteInfo> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getCount() {
            return delegate.getCount();
        }

        @Override
        public MediaRouter.RouteInfo getItem(int position) {
            return delegate.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            return delegate.getItemId(position);
        }

        @Override
        public boolean areAllItemsEnabled() {
            return delegate.areAllItemsEnabled();
        }

        @Override
        public boolean isEnabled(int position) {
            return delegate.isEnabled(position);
        }

        @Override
        public void registerDataSetObserver(DataSetObserver observer) {
            delegate.registerDataSetObserver(observer);
        }

        @Override
        public void unregisterDataSetObserver(DataSetObserver observer) {
            delegate.unregisterDataSetObserver(observer);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = delegate.getView(position, convertView, parent);
            MediaRouter.RouteInfo route = delegate.getItem(position);
            TextView nameView = view.findViewById(R.id.mr_chooser_route_name);
            if (route != null && nameView != null) {
                boolean isLast = isLastSonosRoute(route.getId(), SonosPreferences.getLastGroupId());
                nameView.setText(isLast ? LAST_ROUTE_MARK + route.getName() : route.getName());
                view.setActivated(isLast);
            }
            return view;
        }
    }
}
