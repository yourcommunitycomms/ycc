package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.LocationHelper;
import org.communitycomms.app.geo.Poi;
import org.communitycomms.app.msg.MessageLog;
import org.communitycomms.app.msg.Report;
import org.communitycomms.app.msg.ReportTemplates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Start a new report from a template, and see reports sent, received and scheduled. */
public final class ReportsActivity extends Activity {
    private ListView list;
    private TextView empty;
    private final List<MessageLog.Entry> rows = new ArrayList<MessageLog.Entry>();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Reports");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), 0);
        Button create = new Button(this);
        create.setText("New report…");
        create.setTextSize(18);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTemplate();
            }
        });
        root.addView(create);
        TextView label = new TextView(this);
        label.setText("Reports on this channel (tap to read, or send one as a map point)");
        label.setTextSize(12);
        label.setTextColor(0xff99aabb);
        label.setPadding(0, (int) (8 * d), 0, 0);
        root.addView(label);
        empty = new TextView(this);
        empty.setText("None yet.");
        root.addView(empty);
        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                MessageLog.Entry m = rows.get(pos);
                if (m.state == MessageLog.STATE_MISSING) {
                    Toast.makeText(ReportsActivity.this, "Missed while away; asking the channel for it.", Toast.LENGTH_SHORT).show();
                } else {
                    showReport(ReportsActivity.this, m);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        MessageLog.ui = refresh;
        render();
    }

    @Override
    protected void onPause() {
        if (MessageLog.ui == refresh) MessageLog.ui = null;
        super.onPause();
    }

    private void render() {
        rows.clear();
        List<String> labels = new ArrayList<String>();
        List<MessageLog.Entry> all = MessageLog.get(this).all(ChatActivity.channelOnAir());
        for (int i = all.size() - 1; i >= 0; i--) {
            MessageLog.Entry m = all.get(i);
            if (m.type != MessageLog.TYPE_REPORT) continue;
            rows.add(m);
            Report r = Report.parse(m.body);
            String title = r != null ? r.title : m.body.length() > 0 ? m.body : "report";
            String state = m.state == MessageLog.STATE_SCHEDULED ? "  scheduled for " + ChatActivity.when(m.time)
                    : m.state == MessageLog.STATE_MISSING ? "  missed, fetching…" : "  " + ChatActivity.when(m.time);
            labels.add(title + "  ·  " + (m.mine ? "me" : m.creator) + state);
        }
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
    }

    static List<ReportTemplates.Template> templates(Activity a) {
        List<ReportTemplates.Template> t = ReportTemplates.builtIn();
        t.addAll(ReportTemplates.custom(a));
        return t;
    }

    private void pickTemplate() {
        final List<ReportTemplates.Template> t = templates(this);
        final String mission = LanSync.mission(this);
        String[] names = new String[t.size() + 2];
        for (int i = 0; i < t.size(); i++) {
            names[i] = t.get(i).title + (t.get(i).custom ? " (custom)" : "") + "\n" + t.get(i).about;
        }
        names[t.size()] = "+ Make a custom template…";
        names[t.size() + 1] = mission.length() > 0 ? "Video report\nRecord a video for mission " + mission
                + "; it goes to the LAN storage on the home network"
                : "Video report (needs a mission: Settings > Mission)";
        new AlertDialog.Builder(this)
                .setTitle("Choose a report")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == t.size() + 1) {
                            if (mission.length() == 0) {
                                Toast.makeText(ReportsActivity.this, "Choose a mission first (Settings > Mission)", Toast.LENGTH_LONG).show();
                            } else {
                                recordVideo(mission);
                            }
                        } else if (which == t.size()) {
                            editCustom(null);
                        } else if (t.get(which).custom) {
                            customChoice(t.get(which), which);
                        } else {
                            compose(which);
                        }
                    }
                })
                .show();
    }

    private static final int REQ_VIDEO = 31;
    private java.io.File pendingVideo;

    /** Video report: the phone's camera app records into the mission's videos (not sent over the radio). */
    private void recordVideo(String mission) {
        String me = new Prefs(this).callsign().replaceAll("[^A-Za-z0-9_-]", "_");
        String stamp = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).format(new java.util.Date());
        pendingVideo = new java.io.File(LanSync.videosDir(this), stamp + "_video_" + (me.length() == 0 ? "me" : me) + ".mp4");
        Intent i = new Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE);
        android.net.Uri out = FilesProvider.video(pendingVideo);
        i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= 16) i.setClipData(android.content.ClipData.newRawUri("video", out));
        try {
            startActivityForResult(i, REQ_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, "No camera app for video on this phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VIDEO) return;
        if (res == RESULT_OK && pendingVideo != null && pendingVideo.length() > 0) {
            Toast.makeText(this, "Video saved for mission " + LanSync.mission(this) + " ("
                    + (pendingVideo.length() / (1024 * 1024)) + " MB). It goes to the LAN storage on the home network.",
                    Toast.LENGTH_LONG).show();
        } else if (pendingVideo != null) {
            pendingVideo.delete();
        }
        pendingVideo = null;
    }

    private void compose(int index) {
        startActivity(new Intent(this, ReportComposeActivity.class).putExtra("template", index));
    }

    private void customChoice(final ReportTemplates.Template t, final int index) {
        new AlertDialog.Builder(this)
                .setTitle(t.title)
                .setItems(new String[]{"Fill in", "Edit template", "Delete template"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) compose(index);
                        else if (w == 1) editCustom(t);
                        else ReportTemplates.deleteCustom(ReportsActivity.this, t.title);
                    }
                })
                .show();
    }

    private void editCustom(ReportTemplates.Template old) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final EditText title = new EditText(this);
        title.setHint("Template name, e.g. WATER POINT");
        title.setSingleLine(true);
        final EditText fields = new EditText(this);
        fields.setHint("One field per line, e.g.\nLocation\nFlow rate\nTime checked");
        fields.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        fields.setMinLines(5);
        if (old != null) {
            title.setText(old.title);
            StringBuilder sb = new StringBuilder();
            for (ReportTemplates.Field f : old.fields) sb.append(f.label).append('\n');
            fields.setText(sb.toString().trim());
        }
        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setText("Fields named with \"location\", \"grid\" or \"position\" get an Insert my location button; "
                + "\"time\", \"date\" or \"DTG\" get Insert time now.");
        box.addView(title);
        box.addView(fields);
        box.addView(note);
        new AlertDialog.Builder(this)
                .setTitle(old == null ? "Custom template" : "Edit template")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String t = title.getText().toString().trim();
                        List<String> f = new ArrayList<String>();
                        for (String s : fields.getText().toString().split("\n")) if (s.trim().length() > 0) f.add(s.trim());
                        if (t.length() == 0 || f.isEmpty()) {
                            Toast.makeText(ReportsActivity.this, "Give it a name and at least one field", Toast.LENGTH_LONG).show();
                            return;
                        }
                        ReportTemplates.saveCustom(ReportsActivity.this, t, f);
                        Toast.makeText(ReportsActivity.this, "Saved \"" + t + "\"", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- shared with the chat screen ----

    /** Full report in a dialog, with "Send as map point". */
    static void showReport(final Activity a, final MessageLog.Entry m) {
        final Report r = Report.parse(m.body);
        if (r == null) return;
        String head = (m.mine ? "Sent by me" : "From " + m.creator) + ", "
                + (m.state == MessageLog.STATE_SCHEDULED ? "scheduled for " : "") + ChatActivity.when(m.time) + "\n\n";
        new AlertDialog.Builder(a)
                .setTitle(r.title)
                .setMessage(head + r.readable(false))
                .setPositiveButton("Close", null)
                .setNeutralButton("Send as map point", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        sendAsPoi(a, r);
                    }
                })
                .show();
    }

    private static final String[] LIFE_LABELS = {"1 hour", "6 hours", "24 hours", "7 days", "Never expires"};
    private static final long[] LIFE_MS = {3600000L, 6 * 3600000L, 24 * 3600000L, 7 * 24 * 3600000L, 0};

    /**
     * A report as a POI: placed at the first lat/lon in its fields, or at this phone's position;
     * the note carries the report text.
     */
    static void sendAsPoi(final Activity a, final Report r) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final double[] at = r.findLatLon();
        TextView where = new TextView(a);
        where.setText(at != null ? "At " + org.communitycomms.app.geo.Geo.latLon(at[0], at[1]) + " (from the report)"
                : "No lat/lon in the report: it will be placed at this phone's position.");
        box.addView(where);
        final android.widget.Spinner icon = new android.widget.Spinner(a);
        icon.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, Poi.ICON_NAMES));
        icon.setSelection(Arrays.asList(Poi.ICON_NAMES).indexOf(defaultIcon(r)));
        box.addView(icon);
        final android.widget.Spinner life = new android.widget.Spinner(a);
        life.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, LIFE_LABELS));
        life.setSelection(2);
        box.addView(life);
        new AlertDialog.Builder(a)
                .setTitle("Send report as map point")
                .setView(box)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        final int ic = icon.getSelectedItemPosition();
                        final long lifeMs = LIFE_MS[life.getSelectedItemPosition()];
                        if (at != null) {
                            placePoi(a, r, at[0], at[1], ic, lifeMs);
                            return;
                        }
                        Toast.makeText(a, "Getting a GPS fix…", Toast.LENGTH_SHORT).show();
                        LocationHelper.getFix(a, 15000, new LocationHelper.Callback() {
                            @Override
                            public void onFix(Location l, String problem) {
                                if (l == null) Toast.makeText(a, problem, Toast.LENGTH_LONG).show();
                                else placePoi(a, r, l.getLatitude(), l.getLongitude(), ic, lifeMs);
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String defaultIcon(Report r) {
        if (r.code.equals("MEDEVAC") || r.code.equals("CASEVAC") || r.code.equals("MIST")) return "Ambulance";
        if (r.code.equals("SALUTE") || r.code.equals("SPOTREP")) return "Person (red)";
        return "Person (blue)";
    }

    private static void placePoi(Activity a, Report r, double lat, double lon, int icon, long lifeMs) {
        String me = new Prefs(a).callsign();
        String note = r.title + "\n" + r.readable(true);
        Poi p = Poi.create(lat, lon, icon, note, lifeMs, me.length() == 0 ? "?" : me);
        RadioService rs = RadioService.instance();
        boolean sent = rs != null && rs.sendPoi(p);
        Toast.makeText(a, sent ? "Sent as a map point" : "Saved on this phone as a map point. Join a channel to share it.",
                Toast.LENGTH_LONG).show();
    }
}
