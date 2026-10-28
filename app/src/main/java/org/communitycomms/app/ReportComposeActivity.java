package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.LocationHelper;
import org.communitycomms.app.msg.MessageCenter;
import org.communitycomms.app.msg.Report;
import org.communitycomms.app.msg.ReportTemplates;

import java.util.Calendar;
import java.util.List;

/**
 * Guided report entry: one field per page with its prompt, quick-pick codes and helpers
 * (insert my location / time now), then a review page to send now, schedule for a comms window,
 * or send as a map point.
 */
public final class ReportComposeActivity extends Activity {
    private ReportTemplates.Template t;
    private String[] values;
    /** Field shown, or fields.size() for the review page. */
    private int page;

    private TextView step, label, prompt, review;
    private EditText input;
    private LinearLayout options, helpers;
    private Button back, next;
    private View fieldPage;
    private ScrollView reviewPage;
    private LinearLayout reviewButtons;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        List<ReportTemplates.Template> all = ReportsActivity.templates(this);
        int idx = getIntent().getIntExtra("template", 0);
        if (idx < 0 || idx >= all.size()) {
            finish();
            return;
        }
        t = all.get(idx);
        setTitle(t.title);
        values = new String[t.fields.size()];
        for (int i = 0; i < values.length; i++) values[i] = "";
        if (b != null && b.getStringArray("values") != null && b.getStringArray("values").length == values.length) {
            values = b.getStringArray("values");
            page = b.getInt("page");
        }
        build();
        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        keep();
        out.putStringArray("values", values);
        out.putInt("page", page);
    }

    private void build() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        step = new TextView(this);
        step.setTextSize(12);
        step.setTextColor(0xff99aabb);
        root.addView(step);

        // Small phones (e.g. 480x854) with the keyboard up leave little room: smaller text, and the
        // quick-pick codes and helpers sit above the text box so they stay in view.
        boolean small = getResources().getConfiguration().screenHeightDp < 640;
        LinearLayout fp = new LinearLayout(this);
        fp.setOrientation(LinearLayout.VERTICAL);
        label = new TextView(this);
        label.setTextSize(small ? 19 : 24);
        label.setPadding(0, (int) (4 * d), 0, 0);
        fp.addView(label);
        prompt = new TextView(this);
        prompt.setTextSize(small ? 14 : 16);
        fp.addView(prompt);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        options = new LinearLayout(this);
        hs.addView(options);
        fp.addView(hs);
        HorizontalScrollView hh = new HorizontalScrollView(this);
        helpers = new LinearLayout(this);
        hh.addView(helpers);
        fp.addView(hh);
        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setMinLines(small ? 1 : 2);
        input.setMaxLines(6);
        fp.addView(input);
        ScrollView fieldScroll = new ScrollView(this);
        fieldScroll.addView(fp);
        fieldPage = fieldScroll;
        root.addView(fieldScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        reviewPage = new ScrollView(this);
        LinearLayout rp = new LinearLayout(this);
        rp.setOrientation(LinearLayout.VERTICAL);
        review = new TextView(this);
        review.setTextSize(16);
        review.setTextIsSelectable(true);
        rp.addView(review);
        reviewButtons = new LinearLayout(this);
        reviewButtons.setOrientation(LinearLayout.VERTICAL);
        reviewButtons.addView(big("Send now", new Runnable() {
            @Override
            public void run() {
                send(0);
            }
        }));
        reviewButtons.addView(big("Send at a set time…", new Runnable() {
            @Override
            public void run() {
                schedule();
            }
        }));
        reviewButtons.addView(big("Send as map point…", new Runnable() {
            @Override
            public void run() {
                ReportsActivity.sendAsPoi(ReportComposeActivity.this, report());
            }
        }));
        reviewButtons.addView(big("Cancel (discard this report)", new Runnable() {
            @Override
            public void run() {
                new android.app.AlertDialog.Builder(ReportComposeActivity.this)
                        .setTitle("Discard this report?")
                        .setMessage("Nothing has been sent. What you typed will be lost.")
                        .setPositiveButton("Discard", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                finish();
                            }
                        })
                        .setNegativeButton("Keep editing", null)
                        .show();
            }
        }));
        rp.addView(reviewButtons);
        reviewPage.addView(rp);
        root.addView(reviewPage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keep();
                if (page > 0) {
                    page--;
                    show();
                }
            }
        });
        next = new Button(this);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keep();
                if (page < t.fields.size()) {
                    page++;
                    show();
                }
            }
        });
        jump = new Button(this);
        jump.setText("Skip to review");
        jump.setAllCaps(false);
        jump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keep();
                page = t.fields.size();
                show();
            }
        });
        nav.addView(back, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(jump, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(nav);
        setContentView(root);
    }

    /** "Skip to review": hidden on the last field (Next already says Review) and on the review page. */
    private Button jump;

    private Button big(String text, final Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private Button small(String text, final Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private void keep() {
        if (page < t.fields.size()) values[page] = input.getText().toString().trim();
    }

    private void show() {
        int n = t.fields.size();
        back.setEnabled(page > 0);
        jump.setVisibility(page >= n - 1 ? View.INVISIBLE : View.VISIBLE);
        if (page >= n) {
            fieldPage.setVisibility(View.GONE);
            reviewPage.setVisibility(View.VISIBLE);
            step.setText("Review: " + n + " fields");
            next.setEnabled(false);
            next.setText("Next");
            Report r = report();
            int size = MessageCenter.reportSize(r);
            review.setText(r.title + "\n\n" + r.readable(false) + "\n\nSize on air: " + size + " of " + MessageCenter.MAX_BODY
                    + " bytes" + (size > MessageCenter.MAX_BODY ? ". TOO LONG: shorten some fields." : "."));
            hideKeyboard();
            return;
        }
        fieldPage.setVisibility(View.VISIBLE);
        reviewPage.setVisibility(View.GONE);
        next.setEnabled(true);
        next.setText(page == n - 1 ? "Review" : "Next");
        final ReportTemplates.Field f = t.fields.get(page);
        step.setText(t.title + ": field " + (page + 1) + " of " + n);
        label.setText(f.label);
        prompt.setText(f.prompt);
        input.setText(values[page]);
        input.setSelection(input.getText().length());
        input.requestFocus();
        helpers.removeAllViews();
        if (f.kind == ReportTemplates.LOCATION) {
            helpers.addView(small("Insert my location", new Runnable() {
                @Override
                public void run() {
                    insertLocation();
                }
            }));
        }
        if (f.kind == ReportTemplates.DTG) {
            helpers.addView(small("Insert time now (DTG)", new Runnable() {
                @Override
                public void run() {
                    append(ReportTemplates.dtgNow());
                }
            }));
        }
        if (values[page].length() > 0) {
            helpers.addView(small("Clear", new Runnable() {
                @Override
                public void run() {
                    input.setText("");
                }
            }));
        }
        options.removeAllViews();
        for (final String o : f.options) {
            options.addView(small(o, new Runnable() {
                @Override
                public void run() {
                    append(o);
                }
            }));
        }
    }

    private void append(String s) {
        String cur = input.getText().toString().trim();
        input.setText(cur.length() == 0 ? s : cur + ", " + s);
        input.setSelection(input.getText().length());
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private static final int REQ_LOCATION = 30;

    private void insertLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        Toast.makeText(this, "Getting a GPS fix…", Toast.LENGTH_SHORT).show();
        LocationHelper.getFix(this, 15000, new LocationHelper.Callback() {
            @Override
            public void onFix(Location l, String problem) {
                if (l == null) Toast.makeText(ReportComposeActivity.this, problem, Toast.LENGTH_LONG).show();
                else append(Geo.latLon(l.getLatitude(), l.getLongitude()) + " (±" + Math.round(l.getAccuracy()) + " m)");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_LOCATION && r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            insertLocation();
        }
    }

    private Report report() {
        Report r = new Report();
        r.code = t.code;
        r.title = t.title;
        for (int i = 0; i < t.fields.size(); i++) {
            r.labels.add(t.fields.get(i).label);
            r.values.add(values[i]);
        }
        return r;
    }

    private RadioService onAir() {
        RadioService s = RadioService.instance();
        if (s == null || s.engine() == null || s.messages() == null) {
            Toast.makeText(this, "Join the channel to send on first", Toast.LENGTH_LONG).show();
            return null;
        }
        if (MessageCenter.reportSize(report()) > MessageCenter.MAX_BODY) {
            Toast.makeText(this, "Too long for one message: shorten some fields", Toast.LENGTH_LONG).show();
            return null;
        }
        return s;
    }

    private void send(long at) {
        RadioService s = onAir();
        if (s == null) return;
        s.messages().sendReport(report(), at, s.engine().channelTag());
        Toast.makeText(this, at > 0 ? "Scheduled for " + ChatActivity.when(at) : "Report sent", Toast.LENGTH_LONG).show();
        finish();
    }

    /** "Comms window": in 15 / 30 / 60 minutes or at a time of day (tomorrow if already past). */
    private void schedule() {
        if (onAir() == null) return;
        final String[] labels = {"In 15 minutes", "In 30 minutes", "In 1 hour", "At a time…"};
        final long[] delays = {15 * 60000L, 30 * 60000L, 60 * 60000L, -1};
        new AlertDialog.Builder(this)
                .setTitle("Send the report")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i) {
                        if (delays[i] > 0) {
                            send(System.currentTimeMillis() + delays[i]);
                            return;
                        }
                        Calendar now = Calendar.getInstance();
                        new TimePickerDialog(ReportComposeActivity.this, new TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet(TimePicker tp, int h, int m) {
                                Calendar at = Calendar.getInstance();
                                at.set(Calendar.HOUR_OF_DAY, h);
                                at.set(Calendar.MINUTE, m);
                                at.set(Calendar.SECOND, 0);
                                if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_MONTH, 1);
                                send(at.getTimeInMillis());
                            }
                        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                                android.text.format.DateFormat.is24HourFormat(ReportComposeActivity.this)).show();
                    }
                })
                .show();
    }
}
