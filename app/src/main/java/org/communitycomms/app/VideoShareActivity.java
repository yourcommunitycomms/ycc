package org.communitycomms.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Share my camera: small, low-rate pictures (1 to 5 a second, or up to 15 when Tiny) to everyone on
 * the channel, for the Tactical videos screen, with optional audio (off by default) and a telemetry
 * overlay. The source is this phone's back or front camera, or a network camera on the MANET (a
 * "float cam", dash cam or IP camera) that gives a still JPEG at a web address.
 * <p>
 * The sharing itself runs in {@link CameraShareService}: it carries on after Home, another app or a
 * blacked-out screen, with a red-dot notification and its Stop button. This screen sets it up and
 * shows the pictures as they go out; Torch lights the back camera's flash at full power.
 */
public final class VideoShareActivity extends Activity {
    private static final int REQ_CAMERA = 3;
    private static final String[] SOURCES = {"Back camera", "Front camera", "Network camera (web address)"};
    private static final String[] SIZES = {"Tiny (160 wide)", "Small (240 wide)", "Medium (320 wide)"};
    private static final int[] WIDTHS = {160, 240, 320};
    private static final String[] RATES = {"1 a second", "2 a second", "5 a second", "10 a second (Tiny only)",
            "15 a second (Tiny only)"};
    private static final int[] FPS = {1, 2, 5, 10, 15};
    /** Above this many a second only Tiny pictures are sent, which keeps the channel usable. */
    private static final int MAX_FPS_LARGER = 5;
    private static final int MENU_BLACKOUT = 1;

    private SharedPreferences sp;
    private Spinner source, size, rate;
    private android.widget.CheckBox audio, overlay;
    private EditText url;
    private Button go, torch;
    private TextView status;
    private ImageView preview;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Share my camera");
        sp = getSharedPreferences("video", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        source = spinner(root, "Camera", SOURCES, sp.getInt("source", 0));
        url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("http://10.41.x.x/snapshot.jpg");
        url.setText(sp.getString("url", ""));
        root.addView(url);
        size = spinner(root, "Picture size", SIZES, sp.getInt("size", 1));
        rate = spinner(root, "Pictures", RATES, sp.getInt("rate", 2));
        audio = new android.widget.CheckBox(this);
        audio.setText("Send audio too (only heard by people on the Tactical videos screen)");
        audio.setChecked(sp.getBoolean("audio", false));
        root.addView(audio);
        overlay = new android.widget.CheckBox(this);
        overlay.setText("Telemetry overlay on the picture (position, compass heading, tilt and roll, speed, time)");
        overlay.setChecked(sp.getBoolean("telemetry", true));
        root.addView(overlay);

        go = new Button(this);
        go.setAllCaps(false);
        torch = new Button(this);
        torch.setAllCaps(false);
        Button black = new Button(this);
        black.setAllCaps(false);
        black.setText("Blackout");
        LinearLayout row = new LinearLayout(this);
        row.addView(go, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        row.addView(torch, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(black, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);
        status = new TextView(this);
        root.addView(status);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xff000000);
        root.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        url.setVisibility(source.getSelectedItemPosition() == 2 ? View.VISIBLE : View.GONE);
        source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos != sp.getInt("source", 0) && CameraShareService.running()) {
                    CameraShareService.stop(VideoShareActivity.this); // a different camera: start again with it
                }
                sp.edit().putInt("source", pos).commit();
                url.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
                render();
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) {
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (CameraShareService.running()) CameraShareService.stop(VideoShareActivity.this);
                else startSharing();
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                }, 400);
            }
        });
        torch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean want = !CameraShareService.torchOn();
                if (!CameraShareService.running() || source.getSelectedItemPosition() == 2) {
                    Toast.makeText(VideoShareActivity.this, "Start sharing with the back camera first: the torch is its flash",
                            Toast.LENGTH_LONG).show();
                } else if (!CameraShareService.setTorch(want)) {
                    Toast.makeText(VideoShareActivity.this, "This camera has no torch (front cameras usually do not)",
                            Toast.LENGTH_LONG).show();
                }
                render();
            }
        });
        black.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                blackout();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, MENU_BLACKOUT, 0, "Blackout").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_BLACKOUT) {
            blackout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Screen black; the camera keeps sharing (it runs in its own service). */
    private void blackout() {
        if (LightDiscipline.legacyBlackout(this)) Toast.makeText(this, "Double-tap to wake", Toast.LENGTH_SHORT).show();
        LightDiscipline.blackout(this);
    }

    private Spinner spinner(LinearLayout root, String label, String[] items, int sel) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.min(sel, items.length - 1));
        root.addView(s);
        return s;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23 && !hasCamera()) askCamera();
        CameraShareService.onChange = new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
        };
        render();
    }

    @Override
    protected void onPause() {
        CameraShareService.onChange = null; // sharing carries on; only the screen stops updating
        super.onPause();
    }

    @TargetApi(23)
    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @TargetApi(23)
    private void askCamera() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private void startSharing() {
        RadioService svc = RadioService.instance();
        RadioEngine e = svc == null ? null : svc.engine();
        if (e == null) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_LONG).show();
            return;
        }
        if (e.listenOnly()) {
            Toast.makeText(this, "This channel is listen only", Toast.LENGTH_LONG).show();
            return;
        }
        int src = source.getSelectedItemPosition();
        if (src < 2 && Build.VERSION.SDK_INT >= 23 && !hasCamera()) {
            askCamera();
            return;
        }
        if (FPS[rate.getSelectedItemPosition()] > MAX_FPS_LARGER && size.getSelectedItemPosition() != 0) {
            size.setSelection(0);
            Toast.makeText(this, "More than " + MAX_FPS_LARGER + " a second sends Tiny pictures", Toast.LENGTH_LONG).show();
        }
        sp.edit().putInt("size", size.getSelectedItemPosition()).putInt("rate", rate.getSelectedItemPosition())
                .putString("url", url.getText().toString().trim()).putBoolean("audio", audio.isChecked())
                .putBoolean("telemetry", overlay.isChecked()).commit();
        CameraShareService.start(this, src, WIDTHS[Math.max(0, size.getSelectedItemPosition())],
                FPS[Math.max(0, rate.getSelectedItemPosition())], url.getText().toString().trim(), audio.isChecked(),
                overlay.isChecked());
        Toast.makeText(this, "Sharing keeps running after Home or Blackout. Stop it here or from the notification.",
                Toast.LENGTH_LONG).show();
    }

    private void render() {
        boolean on = CameraShareService.running();
        go.setText(on ? "● Stop sharing" : "Start sharing");
        go.setTextColor(on ? 0xffff5252 : 0xffffffff);
        torch.setText(CameraShareService.torchOn() ? "Torch off" : "Torch");
        String s = CameraShareService.statusLine;
        status.setText(on ? (s == null || s.length() == 0 ? "Sharing" : s)
                : "Pictures go to everyone on your current channel. Others see them under Tactical videos. "
                + "Sharing keeps going after Home or Blackout (a red dot notification shows it).");
        Bitmap bm = CameraShareService.lastPicture;
        if (on && bm != null && !bm.isRecycled()) preview.setImageBitmap(bm);
        else if (!on) preview.setImageDrawable(null);
    }
}
