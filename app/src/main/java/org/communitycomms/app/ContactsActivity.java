package org.communitycomms.app;

import android.app.Activity;
import android.os.Bundle;

/** Everyone heard, on its own page (see ContactsPanel). */
public final class ContactsActivity extends Activity {
    private ContactsPanel panel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.contacts);
        panel = new ContactsPanel(this);
        setContentView(panel.view());
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
}
