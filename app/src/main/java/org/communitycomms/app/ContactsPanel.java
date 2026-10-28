package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Everyone heard: callsign, status (green available, yellow away, red stealth, grey offline),
 * last seen, battery, distance and direction to their last advertised position (tap: show on the
 * map), and a private note kept on this phone. Notes are hidden while the home screen is locked.
 * Shown on its own page (ContactsActivity) or in the tablet commander view's side panel.
 */
final class ContactsPanel {
    private final Activity a;
    private final LinearLayout root;
    private final ListView list;
    private final TextView header;
    private final List<Contacts.Contact> rows = new ArrayList<Contacts.Contact>();
    private Location me;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 3000);
        }
    };

    ContactsPanel(Activity a) {
        this.a = a;
        float d = a.getResources().getDisplayMetrics().density;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), 0);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header = new TextView(a);
        header.setTextSize(13);
        header.setPadding(0, 0, 0, (int) (4 * d));
        top.addView(header, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // Less: one line per person (callsign, status, battery, distance, last seen); More: everything.
        final android.widget.Button detail = new android.widget.Button(a);
        detail.setAllCaps(false);
        detail.setTextSize(13);
        detail.setText(compact() ? "More details" : "Less details");
        detail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.getSharedPreferences("settings", Activity.MODE_PRIVATE).edit().putBoolean("contactsCompact", !compact()).commit();
                detail.setText(compact() ? "More details" : "Less details");
                render();
            }
        });
        top.addView(detail);
        root.addView(top);
        list = new ListView(a);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < items.size() && items.get(pos) instanceof Contacts.Contact) details((Contacts.Contact) items.get(pos));
            }
        });
    }

    View view() {
        return root;
    }

    void start() {
        readMyPosition();
        handler.post(tick);
    }

    void stop() {
        handler.removeCallbacks(tick);
    }

    private boolean locked() {
        return new Prefs(a).locked();
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void readMyPosition() {
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager lm = (LocationManager) a.getSystemService(Activity.LOCATION_SERVICE);
        Location l = lm == null ? null : org.communitycomms.app.geo.LocationHelper.bestKnown(lm);
        if (l != null && System.currentTimeMillis() - l.getTime() < 30 * 60000L) me = l;
    }

    /** Teams: everyone grouped by the channel they were last heard on, the channel on air first. */
    private final List<Object> items = new ArrayList<Object>();

    private static int slotOf(String letter) {
        for (int s : Prefs.order()) if (Prefs.letter(s).equals(letter)) return s;
        return -1;
    }

    private void group() {
        items.clear();
        Prefs pr = new Prefs(a);
        List<String> teams = new ArrayList<String>();
        teams.add(Prefs.letter(pr.activeSlot()));
        for (int s : Prefs.order()) if (!teams.contains(Prefs.letter(s))) teams.add(Prefs.letter(s));
        for (Contacts.Contact c : rows) if (!teams.contains(c.heardOn)) teams.add(c.heardOn);
        for (String t : teams) {
            boolean header = false;
            for (Contacts.Contact c : rows) {
                if (!t.equals(c.heardOn)) continue;
                if (!header) {
                    items.add(t);
                    header = true;
                }
                items.add(c);
            }
        }
    }

    void render() {
        readMyPosition();
        rows.clear();
        rows.addAll(Contacts.get(a).all());
        group();
        long now = System.currentTimeMillis();
        int online = 0;
        for (Contacts.Contact c : rows) if (c.online(now)) online++;
        header.setText(online + " online, " + rows.size() + " known"
                + (locked() ? ". Locked: notes are hidden." : ". Tap someone for the map and notes.")
                + (me == null ? "\nNo recent GPS fix of your own, so no distances yet." : ""));
        header.setTextColor(online == 0 ? 0xffff8a80 : 0xffcfe3d4);
        int pos = list.getFirstVisiblePosition();
        adapter.notifyDataSetChanged();
        list.setSelection(pos);
    }

    private boolean compact() {
        return a.getSharedPreferences("settings", Activity.MODE_PRIVATE).getBoolean("contactsCompact", false);
    }

    /** One line: "● NAME  Away · 87% · ↗ 1.2 km NE · 2m ago". */
    private CharSequence describeShort(Contacts.Contact c) {
        long now = System.currentTimeMillis();
        boolean on = c.online(now);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("● ").append(c.name);
        String st = on ? Contacts.statusName(c.status) : "offline";
        if (st.length() > 0) sb.append("  ").append(st);
        sb.setSpan(new ForegroundColorSpan(Contacts.statusColour(c.status, on)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (c.battery >= 0) sb.append(" · ").append(String.valueOf(c.battery)).append('%');
        if (c.hasFix() && me != null) {
            double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), c.lat, c.lon);
            sb.append(" · ").append(Geo.arrow(brg)).append(' ')
                    .append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), c.lat, c.lon)))
                    .append(' ').append(Geo.compass(brg));
        }
        if (!on && c.lastSeenMs > 0) sb.append(" · ").append(Geo.age(now - c.lastSeenMs));
        return sb;
    }

    private CharSequence describe(Contacts.Contact c) {
        if (compact()) return describeShort(c);
        long now = System.currentTimeMillis();
        boolean on = c.online(now);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append("● ").append(c.name);
        String st = Contacts.statusName(c.status);
        if (on && st.length() > 0) sb.append("  ").append(st);
        if (!on) sb.append("  offline");
        sb.setSpan(new ForegroundColorSpan(Contacts.statusColour(c.status, on)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(1.15f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\nlast seen ").append(c.lastSeenMs == 0 ? "never" : Geo.age(now - c.lastSeenMs));
        if (c.heardOn.length() > 0) sb.append(" on ").append(c.heardOn);
        if (c.battery >= 0) sb.append(" · battery ").append(String.valueOf(c.battery)).append('%');
        if (c.hasFix()) {
            sb.append('\n');
            if (me != null) {
                double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), c.lat, c.lon);
                sb.append(Geo.arrow(brg)).append(' ').append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), c.lat, c.lon)))
                        .append(' ').append(Geo.compass(brg)).append(' ').append(String.valueOf(Math.round(brg))).append('°');
            } else {
                sb.append(Geo.latLon(c.lat, c.lon));
            }
            sb.append("  (").append(c.fake ? "fake " : "").append("position ").append(Geo.age(now - c.fixTimeMs)).append(')');
        } else {
            sb.append("\nno position shared");
        }
        if (!locked()) {
            String note = Contacts.get(a).note(c.name);
            if (note.length() > 0) {
                int ns = sb.length();
                sb.append("\nNote: ").append(note.length() > 120 ? note.substring(0, 120) + "…" : note);
                sb.setSpan(new ForegroundColorSpan(0xffcfe3d4), ns, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.isEmpty() ? 1 : items.size();
        }

        @Override
        public Object getItem(int i) {
            return i < items.size() ? items.get(i) : null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            int p = (int) (8 * a.getResources().getDisplayMetrics().density);
            if (items.isEmpty()) {
                TextView t = new TextView(a);
                t.setPadding(p, p, p, p);
                t.setText("Nobody heard yet. Join a channel: everyone on it appears here.");
                return t;
            }
            Object o = items.get(pos);
            if (o instanceof String) return teamRow((String) o, p);
            final Contacts.Contact c = (Contacts.Contact) o;
            boolean small = compact();
            TextView t = new TextView(a);
            t.setPadding(p, small ? p / 2 : p, p, small ? p / 2 : p);
            t.setTextSize(14);
            t.setSingleLine(small);
            t.setEllipsize(small ? android.text.TextUtils.TruncateAt.END : null);
            t.setText(describe(c));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(small ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(t, small ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout buttons = new LinearLayout(a);
            buttons.setGravity(android.view.Gravity.END);
            buttons.addView(small(small ? "Msg" : "Message", new Runnable() {
                @Override
                public void run() {
                    messagePerson(c);
                }
            }));
            buttons.addView(small("Call", new Runnable() {
                @Override
                public void run() {
                    callPerson(c);
                }
            }));
            if (!small && !locked()) {
                // More details: notes and removal right on the row.
                buttons.addView(small(Contacts.get(a).note(c.name).length() > 0 ? "Edit note" : "Add note", new Runnable() {
                    @Override
                    public void run() {
                        editNote(c);
                    }
                }), 0);
                buttons.addView(small("Remove", new Runnable() {
                    @Override
                    public void run() {
                        confirmRemove(c);
                    }
                }), 0);
            }
            row.addView(buttons, new LinearLayout.LayoutParams(small ? LinearLayout.LayoutParams.WRAP_CONTENT
                    : LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            return row;
        }

        @Override
        public boolean isEnabled(int pos) {
            return pos < items.size() && items.get(pos) instanceof Contacts.Contact;
        }
    };

    /** "Team B · listening · 3 online" with Message team and Call team. */
    private View teamRow(final String letter, int p) {
        final int slot = slotOf(letter);
        RadioService rs = RadioService.instance();
        boolean reach = rs != null && slot >= 0 && rs.engineFor(slot) != null;
        boolean onAir = rs != null && rs.engine() != null && slot == new Prefs(a).activeSlot();
        int online = 0;
        long now = System.currentTimeMillis();
        for (Contacts.Contact c : rows) if (letter.equals(c.heardOn) && c.online(now)) online++;
        LinearLayout row = new LinearLayout(a);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xff1f3d2b);
        row.setPadding(p, p / 2, p / 2, p / 2);
        TextView t = new TextView(a);
        t.setTextSize(15);
        t.setTextColor(0xfff0c75e);
        t.setText("Team " + (letter.length() == 0 ? "?" : letter) + (onAir ? " · talking" : reach ? " · listening" : "")
                + " · " + online + " online");
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Button m = small("Message team", new Runnable() {
            @Override
            public void run() {
                List<Integer> one = new ArrayList<Integer>();
                one.add(slot);
                MainActivity.messageTeams(a, one);
            }
        });
        android.widget.Button c = small("Call team", new Runnable() {
            @Override
            public void run() {
                confirmCall(slot, null, "everyone in team " + letter);
            }
        });
        m.setEnabled(reach);
        c.setEnabled(reach);
        row.addView(m);
        row.addView(c);
        return row;
    }

    private android.widget.Button small(String text, final Runnable r) {
        android.widget.Button b = new android.widget.Button(a);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        int p = (int) (10 * a.getResources().getDisplayMetrics().density);
        b.setPadding(p, p / 2, p, p / 2);
        b.setFocusable(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    /** Private message when they shared a key on the channel on air; otherwise a team message "@NAME …". */
    private void messagePerson(final Contacts.Contact c) {
        final RadioService rs = RadioService.instance();
        final int slot = slotOf(c.heardOn);
        if (rs == null || slot < 0 || rs.engineFor(slot) == null) {
            Toast.makeText(a, "Not on team " + c.heardOn + ": talk or listen on it first", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean pm = rs.engineFor(slot) == rs.engine() && rs.messages().privateRecipients().contains(c.name);
        final EditText e = new EditText(a);
        e.setMinLines(2);
        int p = (int) (16 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(pm ? "Private message to " + c.name : "Message " + c.name + " (team " + c.heardOn + " sees it)")
                .setView(box)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String text = e.getText().toString().trim();
                        if (text.length() == 0) return;
                        String problem = pm ? rs.messages().sendPrivate(c.name, text)
                                : rs.sendTextTo(slot, "@" + c.name + " " + text) ? null : "Not sent (radio silent?)";
                        Toast.makeText(a, problem == null ? "Sent to " + c.name : problem, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void callPerson(Contacts.Contact c) {
        confirmCall(slotOf(c.heardOn), c.name, c.name);
    }

    private void confirmCall(final int slot, final String name, String who) {
        new AlertDialog.Builder(a)
                .setTitle("Call " + who + "?")
                .setMessage("Rings their phone.")
                .setPositiveButton("Call", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        RadioService rs = RadioService.instance();
                        boolean ok = rs != null && slot >= 0 && !RadioEngine.radioSilent && rs.callOn(slot, name);
                        Toast.makeText(a, ok ? "Calling" : "Not sent: talk or listen on that team first", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void details(final Contacts.Contact c) {
        final boolean locked = locked();
        List<String> actions = new ArrayList<String>();
        final List<Runnable> run = new ArrayList<Runnable>();
        if (c.hasFix()) {
            actions.add("Show on map");
            run.add(new Runnable() {
                @Override
                public void run() {
                    a.startActivity(new Intent(a, MapActivity.class).putExtra(MapActivity.EXTRA_CONTACT, c.name));
                }
            });
        }
        if (!locked) {
            actions.add("Edit note (kept on this phone only)");
            run.add(new Runnable() {
                @Override
                public void run() {
                    editNote(c);
                }
            });
            actions.add("Remove from the list");
            run.add(new Runnable() {
                @Override
                public void run() {
                    Contacts.get(a).remove(c.name);
                    render();
                }
            });
        }
        if (actions.isEmpty()) {
            Toast.makeText(a, "Locked: hold the lock button on the home screen to see notes", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(c.name)
                .setItems(actions.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        run.get(which).run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(final Contacts.Contact c) {
        new AlertDialog.Builder(a)
                .setMessage("Remove " + c.name + " from the list? Their note is deleted too; they come back if heard again.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Contacts.get(a).remove(c.name);
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editNote(final Contacts.Contact c) {
        final EditText e = new EditText(a);
        e.setText(Contacts.get(a).note(c.name));
        e.setMinLines(3);
        e.setHint("Anything you want to remember about " + c.name);
        int p = (int) (16 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle("Note on " + c.name)
                .setMessage("Stays on this phone; never sent. Erased by the app-data wipe.")
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Contacts.get(a).setNote(c.name, e.getText().toString().trim());
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
