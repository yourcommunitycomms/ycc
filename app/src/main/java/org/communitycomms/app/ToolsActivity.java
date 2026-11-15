package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings > Tools: everyday helpers that are not radio features.
 * 1 Create QR from text (full screen, save or share the picture).
 * 2 Scan QR code (any QR, shown as plain text, with Copy / Open in browser).
 * 3 Open in browser (http:// and https:// only open straight away; anything else gets a warning).
 */
public final class ToolsActivity extends Activity {
    private static final int REQ_SCAN = 1;

    private EditText qrText, url;
    private TextView scanned;
    private Button copy, openScanned;
    private String lastScan;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tools");
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // ---- 1 Create QR from text ----
        root.addView(head("Create QR from text"));
        root.addView(note("Type or paste text, a web address or a WiFi note. The QR code fills the screen; you can save or share the picture."));
        qrText = new EditText(this);
        qrText.setHint("Text for the QR code");
        qrText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        qrText.setMinLines(2);
        qrText.setMaxLines(6);
        root.addView(qrText);
        LinearLayout r1 = row(root);
        Button paste = button(r1, "Paste");
        Button make = button(r1, "Make QR");
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = clip();
                if (t == null) toast("Nothing to paste");
                else qrText.setText(t);
            }
        });
        make.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = qrText.getText().toString();
                if (t.trim().length() == 0) {
                    toast("Type some text first");
                    return;
                }
                String title = t.trim().replace('\n', ' ');
                if (title.length() > 60) title = title.substring(0, 57) + "...";
                QrShowActivity.startWithSave(ToolsActivity.this, t, title);
            }
        });

        // ---- 2 Scan QR code ----
        root.addView(head("\nScan QR code"));
        root.addView(note("Reads any QR code with the camera and shows its text. Nothing is opened until you tap a button."));
        Button scan = new Button(this);
        scan.setText("Scan QR code");
        scan.setAllCaps(false);
        root.addView(scan);
        scanned = new TextView(this);
        scanned.setTextSize(16);
        scanned.setTextIsSelectable(true);
        scanned.setPadding(0, pad / 2, 0, pad / 2);
        scanned.setText("(nothing scanned yet)");
        root.addView(scanned);
        LinearLayout r2 = row(root);
        copy = button(r2, "Copy");
        openScanned = button(r2, "Open in browser");
        LinearLayout r3 = row(root);
        final Button toQr = button(r3, "Use as QR text");
        final Button toUrl = button(r3, "Put in browser field");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(ToolsActivity.this, QrScanActivity.class)
                        .putExtra(QrScanActivity.EXTRA_ANY_TEXT, true), REQ_SCAN);
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastScan == null) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("QR text", lastScan));
                toast("Copied");
            }
        });
        openScanned.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastScan != null) open(lastScan);
            }
        });
        toQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastScan != null) qrText.setText(lastScan);
            }
        });
        toUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastScan != null) url.setText(lastScan.trim());
            }
        });

        // ---- 3 Open in browser ----
        root.addView(head("\nOpen in browser"));
        root.addView(note("Opens a web address in the phone's browser. Only addresses that start with http:// or https:// open straight away."));
        url = new EditText(this);
        url.setHint("https://...");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(url);
        LinearLayout r4 = row(root);
        Button pasteUrl = button(r4, "Paste");
        Button fromQr = button(r4, "From QR text");
        Button go = button(r4, "Open");
        pasteUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = clip();
                if (t == null) toast("Nothing to paste");
                else url.setText(t.trim());
            }
        });
        fromQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                url.setText(qrText.getText().toString().trim());
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(url.getText().toString());
            }
        });

        setContentView(scroll);
        if (b != null) {
            lastScan = b.getString("lastScan");
        }
        showScan();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (lastScan != null) out.putString("lastScan", lastScan);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SCAN && res == RESULT_OK && data != null) {
            lastScan = data.getStringExtra(QrScanActivity.EXTRA_RESULT);
            showScan();
        }
    }

    private void showScan() {
        boolean have = lastScan != null;
        scanned.setText(have ? lastScan : "(nothing scanned yet)");
        copy.setEnabled(have);
        openScanned.setEnabled(have && isWeb(lastScan));
    }

    /** True for text that starts with http:// or https:// (any case), the only kind opened without a warning. */
    static boolean isWeb(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(java.util.Locale.US);
        return (t.startsWith("http://") && t.length() > 7) || (t.startsWith("https://") && t.length() > 8);
    }

    private void open(String raw) {
        final String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) {
            toast("Type a web address first");
            return;
        }
        if (isWeb(s) && !s.contains(" ") && !s.contains("\n")) {
            launch(s);
            return;
        }
        boolean noScheme = !s.contains(":") && !s.contains(" ") && !s.contains("\n");
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Not a web address")
                .setMessage("This does not start with http:// or https://:\n\n" + (s.length() > 200 ? s.substring(0, 200) + "..." : s)
                        + "\n\nIt may open another app, a file or something unexpected. Only open it if you know what it is.")
                .setNegativeButton(android.R.string.cancel, null);
        if (noScheme) {
            d.setPositiveButton("Add https:// and open", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    launch("https://" + s);
                }
            });
        } else if (!s.contains("\n")) {
            d.setPositiveButton("Open anyway", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    launch(s);
                }
            });
        }
        d.show();
    }

    private void launch(String s) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(s));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No app on this phone can open that");
        } catch (Exception e) {
            toast("Could not open: " + e.getMessage());
        }
    }

    private String clip() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        return t == null || t.length() == 0 ? null : t.toString();
    }

    private TextView head(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTextColor(0xffa5d6a7);
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private Button button(LinearLayout r, String label) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setAllCaps(false);
        r.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return bt;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
