package org.communitycomms.app;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;

/**
 * A house button at the right of the title bar on every screen except the home screen (radio or
 * map style): one tap back to the home screen from however deep you are.
 */
final class HomeButton implements Application.ActivityLifecycleCallbacks {
    @Override
    public void onActivityStarted(final Activity a) {
        if (a instanceof MainActivity || a instanceof IntercomActivity) return;
        ActionBar ab = a.getActionBar();
        if (ab == null || ab.getCustomView() != null) return;
        ImageButton b = new ImageButton(a);
        b.setImageResource(R.drawable.ic_home);
        b.setBackgroundColor(0); // flat, like the bar
        b.setContentDescription("Home");
        int p = (int) (10 * a.getResources().getDisplayMetrics().density);
        b.setPadding(p, p / 2, p, p / 2);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.startActivity(new Intent(a, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        });
        ab.setCustomView(b, new ActionBar.LayoutParams(ActionBar.LayoutParams.WRAP_CONTENT,
                ActionBar.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL));
        ab.setDisplayShowCustomEnabled(true);
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityResumed(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
