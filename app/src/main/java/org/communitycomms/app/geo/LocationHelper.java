package org.communitycomms.app.geo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** One-shot position: a fresh GPS fix if one arrives in time, else the best recent known fix. */
public final class LocationHelper {
    public interface Callback {
        /** On the main thread; loc is null if nothing could be found. */
        void onFix(Location loc, String problem);
    }

    private LocationHelper() {
    }

    @SuppressLint("MissingPermission")
    public static void getFix(Context c, long timeoutMs, final Callback cb) {
        Location fake = FakeGps.location(c);
        if (fake != null) {
            cb.onFix(fake, null);
            return;
        }
        final LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            cb.onFix(null, "No location service on this phone");
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        final LocationListener[] holder = new LocationListener[1];
        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (done[0]) return;
                done[0] = true;
                if (holder[0] != null) lm.removeUpdates(holder[0]);
                Location best = bestKnown(lm);
                cb.onFix(best, best == null ? "No GPS fix yet. Go outside or try again." : null);
            }
        };
        holder[0] = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (done[0]) return;
                if (l.getAccuracy() > 100 && LocationManager.NETWORK_PROVIDER.equals(l.getProvider())) return;
                done[0] = true;
                lm.removeUpdates(this);
                h.removeCallbacks(finish);
                cb.onFix(l, null);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle e) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        boolean any = false;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.getAllProviders().contains(p) && lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0, 0, holder[0], Looper.getMainLooper());
                    any = true;
                }
            }
        } catch (SecurityException e) {
            cb.onFix(null, "Location permission is needed");
            return;
        }
        if (!any) {
            done[0] = true;
            Location best = bestKnown(lm);
            cb.onFix(best, best == null ? "Location is switched off in phone settings" : null);
            return;
        }
        h.postDelayed(finish, timeoutMs);
    }

    @SuppressLint("MissingPermission")
    public static Location bestKnown(LocationManager lm) {
        Location fake = FakeGps.location();
        if (fake != null) return fake;
        Location best = null;
        try {
            for (String p : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime() + 60000
                        || (Math.abs(l.getTime() - best.getTime()) <= 60000 && l.getAccuracy() < best.getAccuracy())) {
                    best = l;
                }
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }
}
