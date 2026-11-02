package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.net.FileTransfer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** Tasks and notes for the channel, a team or one person. Advanced mode adds and edits its own. */
public final class TasksActivity extends Activity {
    private static final int REQ_PHOTO = 21;
    private LinearLayout list;
    private String pendingPhoto = "";
    private TextView photoLine;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tasks and notes");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (10 * d);
        root.setPadding(p, p, p, p);
        if (new Prefs(this).appMode() == Prefs.MODE_ADVANCED) {
            Button add = new Button(this);
            add.setAllCaps(false);
            add.setText("Add a task or note");
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(null);
                }
            });
            root.addView(add);
        }
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        List<Tasks.Task> all = Tasks.current(this);
        if (all.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No tasks on this channel. Join a channel to see them.");
            list.addView(t);
        }
        Prefs pr = new Prefs(this);
        String me = pr.airName();
        for (final Tasks.Task t : all) {
            TextView v = new TextView(this);
            boolean forMe = t.scope.length() == 0 || t.scope.equalsIgnoreCase(pr.callsign()) || t.scope.equalsIgnoreCase(pr.teamName()) || t.scope.equalsIgnoreCase(me);
            StringBuilder sb = new StringBuilder();
            sb.append(t.scope.length() == 0 ? "Everyone" : t.scope).append(" · from ").append(t.by);
            if (t.due > 0) sb.append(" · ").append(t.due < System.currentTimeMillis() ? "time is up" : "due " + DateUtils.getRelativeTimeSpanString(t.due));
            sb.append('\n').append(t.text);
            if (t.photo.length() > 0) sb.append("\nPhoto in Chat: ").append(t.photo);
            v.setText(sb);
            v.setTextSize(forMe ? 17 : 14);
            v.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            list.addView(v);
            if (pr.appMode() == Prefs.MODE_ADVANCED && t.by.equals(me)) {
                Button e = new Button(this);
                e.setAllCaps(false);
                e.setText("Edit");
                e.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        edit(t);
                    }
                });
                list.addView(e);
            }
        }
    }

    private void edit(final Tasks.Task old) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final EditText scope = new EditText(this), text = new EditText(this), mins = new EditText(this);
        scope.setHint("For: team or callsign (empty = the whole channel)");
        scope.setSingleLine(true);
        text.setHint("Task or note");
        mins.setHint("Time limit in minutes (empty = none)");
        mins.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pendingPhoto = old == null ? "" : old.photo;
        if (old != null) {
            scope.setText(old.scope);
            text.setText(old.text);
            if (old.due > System.currentTimeMillis()) mins.setText(String.valueOf((old.due - System.currentTimeMillis()) / 60000));
        }
        box.addView(scope);
        box.addView(text);
        box.addView(mins);
        photoLine = new TextView(this);
        photoLine.setText(pendingPhoto);
        box.addView(photoLine);
        Button photo = new Button(this);
        photo.setAllCaps(false);
        photo.setText("Add a photo or screenshot");
        photo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Choose"), REQ_PHOTO);
            }
        });
        box.addView(photo);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(old == null ? "New task or note" : "Edit").setView(box)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        Tasks.Task t = old != null ? old : new Tasks.Task();
                        if (old == null) t.id = Long.toHexString(new java.security.SecureRandom().nextLong());
                        t.scope = scope.getText().toString().trim();
                        t.text = text.getText().toString().trim();
                        t.photo = pendingPhoto;
                        String m = mins.getText().toString().trim();
                        t.due = m.length() == 0 ? 0 : System.currentTimeMillis() + Math.min(Long.parseLong(m), 525600) * 60000;
                        finishSend(t);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (old != null) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    old.removed = true;
                    finishSend(old);
                }
            });
        }
        b.show();
    }

    private void finishSend(Tasks.Task t) {
        if (!Tasks.send(t)) Toast.makeText(this, "Join a channel first", Toast.LENGTH_LONG).show();
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, 500);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_PHOTO || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final FileTransfer ft = RadioService.files();
        if (ft == null) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_LONG).show();
            return;
        }
        final String name = "task-" + System.currentTimeMillis() + ".jpg";
        pendingPhoto = name;
        if (photoLine != null) photoLine.setText(name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(getCacheDir(), "outgoing");
                    dir.mkdirs();
                    File f = new File(dir, name);
                    InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream o = new FileOutputStream(f);
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    o.close();
                    in.close();
                    RadioService.logFileSent(TasksActivity.this, ft.send(f, name, "image/jpeg"), "image/jpeg", f);
                } catch (Exception e) {
                    android.util.Log.w("Tasks", "photo", e);
                }
            }
        }, "task-photo").start();
    }
}
