package org.communitycomms.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Settings → About: who made the app and how. Shows the bundled assets/about.txt (attribution,
 * the full rebuild prompt and a one-line-per-change list of later updates; built by
 * docs/build-about.ps1) with a button that copies all of it.
 */
public final class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("About");
        final String text = read();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * d);
        root.setPadding(pad, pad, pad, 0);

        TextView title = new TextView(this);
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        title.setText(getString(R.string.app_name) + "\n" + getString(R.string.app_tagline) + "\nVersion " + v);
        title.setTextSize(18);
        title.setTextColor(getResources().getColor(R.color.gold));
        root.addView(title);

        Button copy = new Button(this);
        copy.setText("Copy to clipboard");
        copy.setAllCaps(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copy(text);
            }
        });
        root.addView(copy);

        ScrollView sv = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(13);
        body.setTextIsSelectable(true);
        body.setPadding(0, pad / 2, 0, pad);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private String read() {
        try {
            InputStream in = getAssets().open("about.txt");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "About text missing from this build.";
        }
    }

    @SuppressWarnings("deprecation")
    private void copy(String text) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), text));
        }
        Toast.makeText(this, "Copied: paste it into your AI tool to build your own", Toast.LENGTH_LONG).show();
    }
}
