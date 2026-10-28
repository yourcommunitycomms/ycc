package org.communitycomms.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ScrollView;

/**
 * Channel control on its own page, kept off the home screen so nobody leaves a channel by accident
 * (see ChannelsPanel). Join / leave are handed to the home screen; listening changes apply when you
 * go back to it.
 */
public final class ChannelsActivity extends Activity implements ChannelsPanel.Host {
    static final String EXTRA_ACTION = "channelAction", EXTRA_SLOT = "slot";
    static final String JOIN = "join", LEAVE = "leave";

    private ChannelsPanel panel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Channels");
        panel = new ChannelsPanel(this, this);
        ScrollView sv = new ScrollView(this);
        sv.addView(panel.view());
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.start();
    }

    @Override
    protected void onPause() {
        panel.stop();
        super.onPause();
    }

    /** The home screen does the joining and leaving (permissions, PIN), then shows the result. */
    @Override
    public void channelAction(String action, int slot) {
        startActivity(new Intent(this, MainActivity.class).putExtra(EXTRA_ACTION, action).putExtra(EXTRA_SLOT, slot)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    public void channelsChanged() {
        // applied by the home screen when you go back to it (it re-joins if needed)
    }
}
