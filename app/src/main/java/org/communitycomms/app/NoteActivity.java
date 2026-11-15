package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One of my notes: a name, a date and text with pictures in it (a screenshot or photo, shown where it
 * was put). Pictures are kept on this phone; the text holds a marker "[img:name]" where each one goes.
 */
public final class NoteActivity extends Activity {
    static final String EXTRA_ID = "id";
    /** The note is a protected one (ProtectedStore; only opens while unlocked). */
    static final String EXTRA_PROT = "prot";
    private static final int REQ_PIC = 31;
    private static final Pattern IMG = Pattern.compile("\\[img:([A-Za-z0-9._-]+)\\]");

    private CalStore.Note note;
    private EditText name, body;
    private Button dateB;
    private final Calendar date = Calendar.getInstance();
    private boolean isNew;
    private android.widget.CheckBox protTick;
    private Button pic;
    /** The picture chooser is open: leaving for it does not lock. */
    private boolean picking;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Note");
        float d = getResources().getDisplayMetrics().density;
        String id = getIntent().getStringExtra(EXTRA_ID);
        boolean prot = getIntent().getBooleanExtra(EXTRA_PROT, false);
        if (prot) {
            note = id == null ? null : ProtectedStore.note(id);
            if (note == null) { // locked meanwhile
                Toast.makeText(this, "Protected entries are locked", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            setTitle("Protected note");
        } else if (id != null) for (CalStore.Note n : CalStore.notes(this)) if (n.id.equals(id)) note = n;
        if (note == null) {
            note = new CalStore.Note();
            note.id = CalStore.newId();
            note.date = System.currentTimeMillis();
            isNew = true;
        }
        date.setTimeInMillis(note.date);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (10 * d);
        root.setPadding(p, p, p, p);
        name = new EditText(this);
        name.setHint("Note name");
        name.setSingleLine(true);
        name.setText(note.name);
        root.addView(name);
        LinearLayout row = new LinearLayout(this);
        dateB = new Button(this);
        dateB.setAllCaps(false);
        dateB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new DatePickerDialog(NoteActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker dp, int y, int m, int dd) {
                        date.set(y, m, dd);
                        showDate();
                    }
                }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        pic = new Button(this);
        pic.setAllCaps(false);
        pic.setText("Insert picture / screenshot");
        pic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                picking = true;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Choose a picture"), REQ_PIC);
            }
        });
        row.addView(dateB, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(pic, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);
        if (note.prot) pic.setVisibility(View.GONE); // pictures are kept unencrypted, so not in protected notes
        if (isNew && ProtectedStore.unlocked()) {
            protTick = new android.widget.CheckBox(this);
            protTick.setText("Protected (this device only, no pictures)");
            protTick.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    if (on && body.getText().toString().contains("[img:")) {
                        Toast.makeText(NoteActivity.this, "Protected notes cannot hold pictures: remove them first", Toast.LENGTH_LONG).show();
                        b.setChecked(false);
                        return;
                    }
                    pic.setVisibility(on ? View.GONE : View.VISIBLE);
                }
            });
            root.addView(protTick);
        }
        body = new EditText(this);
        body.setGravity(Gravity.TOP);
        body.setMinLines(10);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setText(withPictures(note.body));
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        Button save = new Button(this), del = new Button(this);
        save.setAllCaps(false);
        del.setAllCaps(false);
        save.setText("Save");
        del.setText("Delete");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                finish();
            }
        });
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(NoteActivity.this).setTitle("Delete this note?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                if (note.prot) {
                                    if (!ProtectedStore.saveNote(NoteActivity.this, note, true)) {
                                        Toast.makeText(NoteActivity.this, "Protected entries are locked: not deleted", Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                } else {
                                    for (String f : pictures(note.body)) new File(CalStore.noteImages(NoteActivity.this), f).delete();
                                    CalStore.saveNote(NoteActivity.this, note, true);
                                }
                                finish();
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }
        });
        buttons.addView(save, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!isNew) buttons.addView(del, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);
        setContentView(root);
        showDate();
    }

    private void showDate() {
        dateB.setText(new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(date.getTime()));
    }

    /** Leaving with Back keeps what was typed too. */
    @Override
    public void onBackPressed() {
        save();
        super.onBackPressed();
    }

    private void save() {
        String text = body.getText().toString();
        if (isNew && name.getText().toString().trim().length() == 0 && text.trim().length() == 0) return; // nothing typed
        note.name = name.getText().toString().trim();
        note.body = text;
        note.date = date.getTimeInMillis();
        note.edited = System.currentTimeMillis();
        if (isNew && protTick != null && protTick.isChecked() && ProtectedStore.unlocked()) note.prot = true;
        if (note.prot) {
            if (!ProtectedStore.saveNote(this, note, false)) {
                Toast.makeText(this, "Protected entries are locked: not saved", Toast.LENGTH_LONG).show();
                return;
            }
        } else CalStore.saveNote(this, note, false);
        isNew = false;
        if (protTick != null) protTick.setEnabled(false); // saved: it stays what it is
    }

    @Override
    protected void onResume() {
        super.onResume();
        picking = false;
        if (note != null && note.prot && !ProtectedStore.unlocked()) finish(); // was saved when it locked
        if (protTick != null && !ProtectedStore.unlocked() && isNew) {
            protTick.setChecked(false);
            protTick.setVisibility(View.GONE);
        }
    }

    /** Leaving the app (Home, screen off) saves a protected note, then locks. */
    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing() || picking || note == null) return;
        if (note.prot || (protTick != null && protTick.isChecked())) save();
        ProtectedUi.paused();
        ProtectedStore.lock();
    }

    private static java.util.List<String> pictures(String s) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        Matcher m = IMG.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** The text with each "[img:name]" shown as its picture (the marker stays underneath, so saving keeps it). */
    private CharSequence withPictures(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        Matcher m = IMG.matcher(s);
        while (m.find()) {
            ImageSpan span = span(m.group(1));
            if (span != null) sb.setSpan(span, m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private ImageSpan span(String file) {
        File f = new File(CalStore.noteImages(this), file);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int maxW = Math.max(200, getResources().getDisplayMetrics().widthPixels - (int) (60 * getResources().getDisplayMetrics().density));
        int sample = 1;
        while (o.outWidth / sample > maxW * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
        if (bm == null) return null;
        if (bm.getWidth() > maxW) bm = Bitmap.createScaledBitmap(bm, maxW, bm.getHeight() * maxW / bm.getWidth(), true);
        return new ImageSpan(this, bm);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PIC || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in);
            if (in != null) in.close();
            if (bm == null) throw new Exception("not a picture");
            if (bm.getWidth() > 1600) bm = Bitmap.createScaledBitmap(bm, 1600, bm.getHeight() * 1600 / bm.getWidth(), true);
            String file = "n" + System.currentTimeMillis() + ".jpg";
            FileOutputStream o = new FileOutputStream(new File(CalStore.noteImages(this), file));
            bm.compress(Bitmap.CompressFormat.JPEG, 85, o);
            o.close();
            String marker = "[img:" + file + "]";
            Editable e = body.getText();
            int at = Math.max(0, body.getSelectionStart());
            String before = at > 0 && e.charAt(at - 1) != '\n' ? "\n" : "";
            e.insert(at, before + marker + "\n");
            ImageSpan span = span(file);
            int s = at + before.length();
            if (span != null) e.setSpan(span, s, s + marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Exception ex) {
            Toast.makeText(this, "Could not add that picture: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        } catch (OutOfMemoryError ex) {
            Toast.makeText(this, "That picture is too big for this phone", Toast.LENGTH_LONG).show();
        }
    }
}
