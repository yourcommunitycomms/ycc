package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Calendar and notes (a specialised menu): My calendar, Team calendar (shared on the channel),
 * Both together (team entries in amber), and My notes. A month at a time, newest first within a day.
 */
public final class CalendarActivity extends Activity {
    private static final int MINE = 0, TEAM = 1, BOTH = 2, NOTES = 3;
    private static final String[] TABS = {"My calendar", "Team", "Both", "My notes"};
    private static final int TEAM_COLOUR = 0xffffb74d, MINE_COLOUR = 0xffa5d6a7;
    static final int[] REMIND_MIN = {0, 5, 10, 15, 30, 60, 120, 1440};
    static final String[] REMIND_LABELS = {"at the time", "5 min before", "10 min before", "15 min before", "30 min before",
            "1 hour before", "2 hours before", "1 day before"};

    private int tab = BOTH;
    private final Calendar month = Calendar.getInstance();
    private LinearLayout list;
    private TextView monthLabel;
    private Button[] tabButtons;
    private LinearLayout monthRow;
    private Button add;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Calendar and notes");
        tab = getSharedPreferences("settings", MODE_PRIVATE).getInt("calTab", BOTH);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (8 * d);
        root.setPadding(p, p, p, p);
        LinearLayout tabs = new LinearLayout(this);
        tabButtons = new Button[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            Button bt = new Button(this);
            bt.setAllCaps(false);
            bt.setText(TABS[i]);
            bt.setTextSize(13);
            bt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tab = t;
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("calTab", t).apply();
                    render();
                }
            });
            tabButtons[i] = bt;
            tabs.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(tabs);
        monthRow = new LinearLayout(this);
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = new Button(this), next = new Button(this);
        prev.setText("◀");
        next.setText("▶");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month.add(Calendar.MONTH, -1);
                render();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month.add(Calendar.MONTH, 1);
                render();
            }
        });
        monthLabel = new TextView(this);
        monthLabel.setGravity(Gravity.CENTER);
        monthLabel.setTextSize(18);
        monthRow.addView(prev);
        monthRow.addView(monthLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        monthRow.addView(next);
        root.addView(monthRow);
        add = new Button(this);
        add.setAllCaps(false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tab == NOTES) startActivity(new Intent(CalendarActivity.this, NoteActivity.class));
                else edit(null);
            }
        });
        root.addView(add);
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
        for (int i = 0; i < tabButtons.length; i++) tabButtons[i].setTextColor(i == tab ? 0xfff0c75e : 0xffffffff);
        list.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        if (tab == NOTES) {
            monthRow.setVisibility(View.GONE);
            add.setText("New note");
            List<CalStore.Note> notes = CalStore.notes(this);
            if (notes.isEmpty()) line("No notes yet.", 0xffbdbdbd, 15);
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());
            for (final CalStore.Note n : notes) {
                TextView t = line((n.name.length() > 0 ? n.name : "(no name)") + "\n" + f.format(new java.util.Date(n.date))
                        + "  ·  " + preview(n.body), 0xffffffff, 16);
                t.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(CalendarActivity.this, NoteActivity.class).putExtra(NoteActivity.EXTRA_ID, n.id));
                    }
                });
            }
            return;
        }
        monthRow.setVisibility(View.VISIBLE);
        add.setText(tab == TEAM ? "Add a team entry (shared on the channel)" : "Add an entry");
        monthLabel.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.getTime()));
        Calendar from = (Calendar) month.clone();
        from.set(Calendar.DAY_OF_MONTH, 1);
        from.set(Calendar.HOUR_OF_DAY, 0);
        from.set(Calendar.MINUTE, 0);
        from.set(Calendar.SECOND, 0);
        from.set(Calendar.MILLISECOND, 0);
        Calendar to = (Calendar) from.clone();
        to.add(Calendar.MONTH, 1);
        List<CalStore.Entry> all = CalStore.combined(this, tab != TEAM, tab != MINE);
        if (tab != MINE && (RadioService.instance() == null || RadioService.instance().engine() == null)) {
            line("Join a channel to see and share team entries.", 0xffbdbdbd, 13);
        }
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault()), hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String lastDay = "";
        int shown = 0;
        for (final CalStore.Entry e : all) {
            if (e.start < from.getTimeInMillis() || e.start >= to.getTimeInMillis()) continue;
            String ds = day.format(new java.util.Date(e.start));
            if (!ds.equals(lastDay)) {
                TextView h = line(ds, 0xfff0c75e, 14);
                h.setPadding(0, (int) (10 * d), 0, 0);
                lastDay = ds;
            }
            String time = e.allDay ? "All day" : hm.format(new java.util.Date(e.start)) + "–" + hm.format(new java.util.Date(e.start + e.durMin * 60000L));
            TextView t = line(time + "  " + e.title + (e.remind ? "  ⏰" : "") + (e.team ? "  (team" + (e.by.length() > 0 ? ", " + e.by : "") + ")" : "")
                    + (e.note.length() > 0 ? "\n" + e.note : ""), e.team ? TEAM_COLOUR : MINE_COLOUR, 16);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(e);
                }
            });
            shown++;
        }
        if (shown == 0) line("Nothing this month.", 0xffbdbdbd, 15);
    }

    private static String preview(String body) {
        String s = body.replaceAll("\\[img:[^\\]]*\\]", "(picture)").replace('\n', ' ').trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private TextView line(String s, int colour, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(size);
        float d = getResources().getDisplayMetrics().density;
        t.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        list.addView(t);
        return t;
    }

    /** Add (old == null) or change an entry. Team entries can only be changed by whoever made them. */
    private void edit(final CalStore.Entry old) {
        final boolean mineOld = old == null || !old.team || old.by.equalsIgnoreCase(new Prefs(this).callsign());
        if (old != null && !mineOld) {
            new AlertDialog.Builder(this).setTitle(old.title)
                    .setMessage("A team entry from " + old.by + ". Only they can change it.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final EditText title = new EditText(this), dur = new EditText(this), note = new EditText(this);
        title.setHint("What");
        title.setSingleLine(true);
        final Calendar when = Calendar.getInstance();
        if (old != null) when.setTimeInMillis(old.start);
        else {
            when.set(Calendar.MINUTE, 0);
            when.add(Calendar.HOUR_OF_DAY, 1);
            if (month.get(Calendar.MONTH) != when.get(Calendar.MONTH)) when.set(month.get(Calendar.YEAR), month.get(Calendar.MONTH), 1, 9, 0);
        }
        final Button dateB = new Button(this), timeB = new Button(this);
        dateB.setAllCaps(false);
        timeB.setAllCaps(false);
        final CheckBox allDay = new CheckBox(this), teamTick = new CheckBox(this), remind = new CheckBox(this), vibrate = new CheckBox(this);
        allDay.setText("All day");
        teamTick.setText("Team entry: share it on the channel (everyone on it sees it)");
        remind.setText("Remind me");
        vibrate.setText("Buzz with the reminder");
        final Spinner before = new Spinner(this);
        before.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, REMIND_LABELS));
        dur.setHint("How long (minutes)");
        dur.setInputType(InputType.TYPE_CLASS_NUMBER);
        note.setHint("Note (optional)");
        final Runnable showWhen = new Runnable() {
            @Override
            public void run() {
                dateB.setText(new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(when.getTime()));
                timeB.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(when.getTime()));
                timeB.setVisibility(allDay.isChecked() ? View.GONE : View.VISIBLE);
                dur.setVisibility(allDay.isChecked() ? View.GONE : View.VISIBLE);
            }
        };
        dateB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new DatePickerDialog(CalendarActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker p, int y, int m, int dd) {
                        when.set(y, m, dd);
                        showWhen.run();
                    }
                }, when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        timeB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new TimePickerDialog(CalendarActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker p, int h, int m) {
                        when.set(Calendar.HOUR_OF_DAY, h);
                        when.set(Calendar.MINUTE, m);
                        showWhen.run();
                    }
                }, when.get(Calendar.HOUR_OF_DAY), when.get(Calendar.MINUTE), true).show();
            }
        });
        allDay.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                showWhen.run();
            }
        });
        if (old != null) {
            title.setText(old.title);
            allDay.setChecked(old.allDay);
            dur.setText(String.valueOf(old.durMin));
            note.setText(old.note);
            remind.setChecked(old.remind);
            vibrate.setChecked(old.vibrate);
            teamTick.setChecked(old.team);
            teamTick.setEnabled(false); // a team entry stays a team entry (and a private one private)
            for (int i = 0; i < REMIND_MIN.length; i++) if (REMIND_MIN[i] == old.remindMin) before.setSelection(i);
        } else {
            dur.setText("60");
            teamTick.setChecked(tab == TEAM);
            vibrate.setChecked(true);
            before.setSelection(3);
        }
        box.addView(title);
        box.addView(dateB);
        box.addView(timeB);
        box.addView(allDay);
        box.addView(dur);
        box.addView(remind);
        box.addView(before);
        box.addView(vibrate);
        box.addView(teamTick);
        box.addView(note);
        showWhen.run();
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(old == null ? "New entry" : "Change entry").setView(sv)
                .setPositiveButton(old != null && old.team ? "Send" : "Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        CalStore.Entry e = old != null ? old : new CalStore.Entry();
                        if (old == null) e.id = CalStore.newId();
                        e.title = title.getText().toString().trim();
                        e.allDay = allDay.isChecked();
                        if (e.allDay) {
                            when.set(Calendar.HOUR_OF_DAY, 9);
                            when.set(Calendar.MINUTE, 0);
                        }
                        when.set(Calendar.SECOND, 0);
                        when.set(Calendar.MILLISECOND, 0);
                        e.start = when.getTimeInMillis();
                        try {
                            e.durMin = Math.max(1, Integer.parseInt(dur.getText().toString().trim()));
                        } catch (NumberFormatException x) {
                            e.durMin = 60;
                        }
                        e.note = note.getText().toString().trim();
                        e.remind = remind.isChecked();
                        e.remindMin = REMIND_MIN[before.getSelectedItemPosition()];
                        e.vibrate = vibrate.isChecked();
                        e.team = teamTick.isChecked();
                        store(e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (old != null) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    old.removed = true;
                    store(old);
                }
            });
        }
        b.show();
    }

    private void store(CalStore.Entry e) {
        if (e.team) {
            e.by = new Prefs(this).callsign();
            if (!CalStore.sendTeam(this, e)) {
                Toast.makeText(this, "Join a channel first: team entries are shared on it", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            CalStore.saveMine(this, e);
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(e.start);
        if (!e.removed) month.setTimeInMillis(e.start);
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, 500);
    }
}
