package org.communitycomms.app;

import android.app.Application;

public final class CommsApp extends Application {
    private static CommsApp app;

    public static android.content.Context context() {
        return app;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
        org.communitycomms.app.geo.FakeGps.init(this);
        Cw.restore(this); // CW mode survives a restart or a crash
        org.communitycomms.app.geo.Geo.loadUnits(this);
        registerActivityLifecycleCallbacks(new LightDiscipline());
        registerActivityLifecycleCallbacks(new HomeButton());
        Prefs p = new Prefs(this);
        RadioEngine.radioSilent = p.radioSilent(); // a silent phone stays silent after a restart
        org.communitycomms.app.audio.AudioLevels.rxGain = p.rxGainPct() / 100f;
        org.communitycomms.app.audio.AudioLevels.micGain = p.micGainPct() / 100f;
        org.communitycomms.app.audio.AudioRoute.load(this);
        RadioEngine.startTone = p.startTone();
        org.communitycomms.app.audio.AudioLevels.swapLeftRight = p.swapLeftRight();
        org.communitycomms.app.audio.BurstVoice.waitClearSec = p.burstWaitSec();
        org.communitycomms.app.audio.BurstVoice.waitTone = p.burstWaitTone();
        org.communitycomms.app.audio.BurstCleanup.enabled = p.burstCleanup();
        RadioEngine.myIcon = p.myIcon();
        registerActivityLifecycleCallbacks(new IntercomReturn());
        registerActivityLifecycleCallbacks(new FocusCatchUp());
    }

    /**
     * Android may pause a backgrounded app, so live messages can be missed while it is not on screen.
     * When it comes back to the front after more than half a minute (and is not radio silent), it
     * asks the others at once for what was sent meanwhile (catch-up sync); it shows up unread in Chat.
     */
    private final class FocusCatchUp implements ActivityLifecycleCallbacks {
        private int started;
        private long awaySince;

        @Override
        public void onActivityStarted(android.app.Activity a) {
            if (started++ > 0) return;
            long away = awaySince == 0 ? 0 : System.currentTimeMillis() - awaySince;
            awaySince = 0;
            if (away < 30000) return;
            RadioService rs = RadioService.instance();
            int min = rs == null ? 0 : rs.catchUpAfterAway(away);
            if (min > 0) {
                android.widget.Toast.makeText(a, "Catching up on the last " + (min > 90 ? (min + 30) / 60 + " h" : min + " min") + ": missed messages show unread in Chat",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onActivityStopped(android.app.Activity a) {
            started = Math.max(0, started - 1);
            if (started == 0) awaySince = System.currentTimeMillis();
        }

        @Override
        public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
        }

        @Override
        public void onActivityResumed(android.app.Activity a) {
        }

        @Override
        public void onActivityPaused(android.app.Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
        }

        @Override
        public void onActivityDestroyed(android.app.Activity a) {
        }
    }

    /**
     * Intercom mode is a wall fixture: if the app is left (home button, another app) it comes back
     * by itself after a minute. Starting from the background needs "display over other apps" on
     * Android 10 and newer (asked on the Permissions screen).
     */
    private final class IntercomReturn implements ActivityLifecycleCallbacks {
        private static final long BACK_MS = 60000;
        private int started;
        private final android.os.Handler h = new android.os.Handler();
        private final Runnable comeBack = new Runnable() {
            @Override
            public void run() {
                Prefs p = new Prefs(CommsApp.this);
                if (started > 0 || p.appMode() != Prefs.MODE_INTERCOM || !p.setupDone()) return;
                IntercomActivity.showFullRadio = false;
                try {
                    startActivity(new android.content.Intent(CommsApp.this, MainActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
                h.postDelayed(this, BACK_MS); // try again if Android did not let it come up
            }
        };

        @Override
        public void onActivityStarted(android.app.Activity a) {
            started++;
            h.removeCallbacks(comeBack);
        }

        @Override
        public void onActivityStopped(android.app.Activity a) {
            started = Math.max(0, started - 1);
            if (started == 0 && new Prefs(CommsApp.this).appMode() == Prefs.MODE_INTERCOM) h.postDelayed(comeBack, BACK_MS);
        }

        @Override
        public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
        }

        @Override
        public void onActivityResumed(android.app.Activity a) {
        }

        @Override
        public void onActivityPaused(android.app.Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
        }

        @Override
        public void onActivityDestroyed(android.app.Activity a) {
        }
    }
}
