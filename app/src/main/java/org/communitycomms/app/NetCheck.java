package org.communitycomms.app;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import org.communitycomms.app.net.Tunnel;

/**
 * Is there any way for the radio to reach anyone? A local network (WiFi or Ethernet, e.g. the
 * MANET) is enough. Without one, only a working tunnel to a remote relay counts.
 */
public final class NetCheck {
    private NetCheck() {
    }

    public static boolean hasLocalNetwork(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        try {
            if (Build.VERSION.SDK_INT >= 23) return modern(cm);
            return old(cm, ConnectivityManager.TYPE_WIFI) || old(cm, ConnectivityManager.TYPE_ETHERNET);
        } catch (RuntimeException e) {
            return true; // cannot tell: do not warn
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean old(ConnectivityManager cm, int type) {
        NetworkInfo i = cm.getNetworkInfo(type);
        return i != null && i.isConnected();
    }

    @TargetApi(23)
    private static boolean modern(ConnectivityManager cm) {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) continue;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true;
        }
        return false;
    }

    /** Local network, or the tunnel to a remote relay is heard from (this phone is the remote). */
    public static boolean canReachAnyone(Context c) {
        if (hasLocalNetwork(c)) return true;
        Tunnel.Client t = Tunnel.client;
        if (t != null && t.connected()) return true;
        // Remote peer addresses set up (Settings > extra peers) and some network is up (e.g. mobile data).
        return new Prefs(c).extraPeers().trim().length() > 0 && anyNetwork(c);
    }

    /** Mobile data is connected (no WiFi needed). */
    @SuppressWarnings("deprecation")
    public static boolean mobileData(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true;
                }
                return false;
            }
            return old(cm, ConnectivityManager.TYPE_MOBILE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean anyNetwork(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo i = cm == null ? null : cm.getActiveNetworkInfo();
        return i != null && i.isConnected();
    }
}
