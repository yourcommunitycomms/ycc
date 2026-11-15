package org.communitycomms.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

/**
 * Shows one or more QR codes full screen. With several (a batch of sheets) it can step through
 * them on its own, so the other phone just keeps its camera pointed at this screen.
 */
public final class QrShowActivity extends Activity {
    private static final String EXTRA_PAYLOADS = "payloads";
    private static final String EXTRA_TITLES = "titles";
    private static final String EXTRA_COLOURS = "colours";
    private static final long AUTO_MS = 2500;

    private ArrayList<String> payloads, titles;
    private int[] colours;
    private int index;
    private ImageView image;
    private TextView title, counter;
    private CheckBox auto;
    private final Handler handler = new Handler();
    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            show((index + 1) % payloads.size());
            handler.postDelayed(this, AUTO_MS);
        }
    };

    private static final String EXTRA_SAVE = "save";
    private static final int REQ_WRITE = 7;

    /** One QR made from any text (Settings > Tools), with Save and Share buttons. */
    public static void startWithSave(Context c, String payload, String title) {
        ArrayList<String> p = new ArrayList<String>();
        p.add(payload);
        ArrayList<String> t = new ArrayList<String>();
        t.add(title);
        Intent i = new Intent(c, QrShowActivity.class);
        i.putStringArrayListExtra(EXTRA_PAYLOADS, p);
        i.putStringArrayListExtra(EXTRA_TITLES, t);
        i.putExtra(EXTRA_SAVE, true);
        c.startActivity(i);
    }

    public static void start(Context c, ArrayList<String> payloads, ArrayList<String> titles, int[] colours) {
        Intent i = new Intent(c, QrShowActivity.class);
        i.putStringArrayListExtra(EXTRA_PAYLOADS, payloads);
        i.putStringArrayListExtra(EXTRA_TITLES, titles);
        i.putExtra(EXTRA_COLOURS, colours);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Scan with this app");
        payloads = getIntent().getStringArrayListExtra(EXTRA_PAYLOADS);
        titles = getIntent().getStringArrayListExtra(EXTRA_TITLES);
        colours = getIntent().getIntArrayExtra(EXTRA_COLOURS);

        // Bright, awake screen: dim screens are the usual reason a QR will not scan.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        title = new TextView(this);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        image = new ImageView(this);
        image.setBackgroundColor(Color.WHITE);
        image.setAdjustViewBounds(true);
        root.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        counter = new TextView(this);
        counter.setGravity(Gravity.CENTER);
        root.addView(counter);

        LinearLayout nav = new LinearLayout(this);
        Button prev = new Button(this);
        prev.setText("Previous");
        Button next = new Button(this);
        next.setText("Next");
        auto = new CheckBox(this);
        auto.setText("Auto");
        nav.addView(prev, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(auto);
        nav.addView(next, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (payloads.size() > 1) root.addView(nav);
        if (getIntent().getBooleanExtra(EXTRA_SAVE, false)) {
            setTitle("QR code");
            LinearLayout row = new LinearLayout(this);
            Button save = new Button(this);
            save.setText("Save image");
            Button share = new Button(this);
            share.setText("Share image");
            row.addView(save, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(share, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            root.addView(row);
            save.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveImage();
                }
            });
            share.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareImage();
                }
            });
        }
        setContentView(root);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                auto.setChecked(false);
                show((index + payloads.size() - 1) % payloads.size());
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                auto.setChecked(false);
                show((index + 1) % payloads.size());
            }
        });
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                handler.removeCallbacks(advance);
                if (on) handler.postDelayed(advance, AUTO_MS);
            }
        });
        show(0);
        auto.setChecked(payloads.size() > 1);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(advance);
        super.onDestroy();
    }

    private void show(int i) {
        index = i;
        title.setText(titles.get(i));
        title.setTextColor(colours != null && colours[i] != 0 ? colours[i] : Color.WHITE);
        counter.setText(payloads.size() > 1 ? (i + 1) + " / " + payloads.size() : "");
        try {
            image.setImageBitmap(render(payloads.get(i)));
        } catch (WriterException e) {
            title.setText("Too big for a QR code: " + titles.get(i));
        }
    }

    // ---- Save / Share (Tools QR only) ----

    private String fileName() {
        return "qr-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".png";
    }

    private Bitmap current() {
        try {
            return render(payloads.get(index));
        } catch (WriterException e) {
            android.widget.Toast.makeText(this, "Too big for a QR code", android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void shareImage() {
        Bitmap b = current();
        if (b == null) return;
        try {
            java.io.File f = new java.io.File(FilesProvider.exportDir(this), fileName());
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
            }
            android.net.Uri u = FilesProvider.export(f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, u);
            send.setClipData(android.content.ClipData.newRawUri("QR code", u));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share QR code"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Could not share: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void saveImage() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT < 29 && !canWrite()) {
            askWrite();
            return;
        }
        Bitmap b = current();
        if (b == null) return;
        String name = fileName();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                saveQ(b, name);
            } else {
                if (!android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState())) {
                    android.widget.Toast.makeText(this, "No storage to save to on this phone. Use Share image instead.",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES), "YCC");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                try {
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                } finally {
                    out.close();
                }
                android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()},
                        new String[]{"image/png"}, null);
            }
            android.widget.Toast.makeText(this, "Saved to Pictures/YCC/" + name, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Could not save: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @android.annotation.TargetApi(29)
    private void saveQ(Bitmap b, String name) throws java.io.IOException {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/YCC");
        android.net.Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new java.io.IOException("no media store");
        java.io.OutputStream out = getContentResolver().openOutputStream(u);
        if (out == null) throw new java.io.IOException("no stream");
        try {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
        }
    }

    @android.annotation.TargetApi(23)
    private boolean canWrite() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @android.annotation.TargetApi(23)
    private void askWrite() {
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code != REQ_WRITE) return;
        if (r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) saveImage();
        else android.widget.Toast.makeText(this, "Storage permission is needed to save the picture. Share image still works.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    static Bitmap render(String payload) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        // Low correction keeps a 720-byte sheet at a size phone cameras read easily off a screen.
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints);
        int w = m.getWidth(), h = m.getHeight();
        int scale = Math.max(1, 900 / w);
        int[] px = new int[w * scale * h * scale];
        for (int y = 0; y < h * scale; y++) {
            for (int x = 0; x < w * scale; x++) {
                px[y * w * scale + x] = m.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.RGB_565);
        bmp.setPixels(px, 0, w * scale, 0, 0, w * scale, h * scale);
        return bmp;
    }
}
