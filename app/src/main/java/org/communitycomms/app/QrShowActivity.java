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
