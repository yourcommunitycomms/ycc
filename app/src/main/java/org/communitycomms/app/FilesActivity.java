package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.net.FileTransfer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Send files, photos and screenshots to everyone on the channel, watch progress, open what
 * arrived. Also the target of "Share → Your Community Communications" from the gallery or a screenshot.
 */
public final class FilesActivity extends Activity {
    private static final int REQ_PICK_FILE = 10, REQ_PICK_IMAGE = 11, REQ_CAMERA = 12;
    private static final int MAX_SIDE = 1600;

    private SharedPreferences prefs;
    private CheckBox shrink;
    private TextView status;
    private ListView list;
    private final List<File> rowFiles = new ArrayList<File>();
    private File pendingCamera;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Data");
        prefs = getSharedPreferences("files", MODE_PRIVATE);
        if (b != null && b.getString("cam") != null) pendingCamera = new File(b.getString("cam"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        // ---- location ----
        // Two equal buttons per row, all the same height, so the grid lines up on small phones.
        root.addView(Prefs.radioSilentBox(this));
        root.addView(row(button("Mission and data settings…", new Runnable() {
            @Override
            public void run() {
                SettingsActivity.open(FilesActivity.this, "data"); // mission, fake GPS, clearing data
            }
        })));
        root.addView(label("Location"));
        here = new TextView(this);
        here.setTextSize(15);
        here.setText("Current location: tap Refresh");
        root.addView(row(button("Send my location (GPS)", new Runnable() {
            @Override
            public void run() {
                sendLocation();
            }
        }), button("Refresh my location", new Runnable() {
            @Override
            public void run() {
                showCurrent();
            }
        })));
        root.addView(here);
        root.addView(row(button("Open map", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(FilesActivity.this, MapActivity.class));
            }
        }), button("Map options", new Runnable() {
            @Override
            public void run() {
                mapOptions();
            }
        })));

        root.addView(label("Files"));
        root.addView(row(button("Send file", new Runnable() {
            @Override
            public void run() {
                pick("*/*", REQ_PICK_FILE);
            }
        }), button("Take photo", new Runnable() {
            @Override
            public void run() {
                takePhoto();
            }
        })));
        root.addView(row(button("Picture / screenshot", new Runnable() {
            @Override
            public void run() {
                pick("image/*", REQ_PICK_IMAGE);
            }
        }), button("Send map tiles", new Runnable() {
            @Override
            public void run() {
                sendTiles();
            }
        })));
        cameraSource = button("", new Runnable() {
            @Override
            public void run() {
                chooseCameraSource();
            }
        });
        renderCameraSource();
        root.addView(row(cameraSource));
        shrink = new CheckBox(this);
        shrink.setText("Shrink photos before sending (much faster)");
        shrink.setChecked(prefs.getBoolean("shrink", true));
        shrink.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.edit().putBoolean("shrink", on).commit();
            }
        });
        root.addView(shrink);
        photoPoi = new CheckBox(this);
        photoPoi.setText("Camera photos also drop a map point here, linked to the picture");
        photoPoi.setChecked(prefs.getBoolean("photoPoi", true));
        photoPoi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.edit().putBoolean("photoPoi", on).commit();
            }
        });
        root.addView(photoPoi);
        status = new TextView(this);
        root.addView(status);
        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < rowFiles.size() && rowFiles.get(pos) != null) open(rowFiles.get(pos));
            }
        });
        handleShare(getIntent());
        if (b == null && getIntent().getBooleanExtra(EXTRA_PHOTO, false)) takePhoto(); // camera shortcut on the home screen
    }

    /** Opens this screen and goes straight to taking a photo (the home screen's camera icon). */
    public static final String EXTRA_PHOTO = "photo";

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingCamera != null) out.putString("cam", pendingCamera.getPath());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleShare(i);
        if (i.getBooleanExtra(EXTRA_PHOTO, false)) takePhoto();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioService.filesUi = refresh;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        if (RadioService.filesUi == refresh) RadioService.filesUi = null;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    // ---- location ----

    private TextView here;
    private static final int REQ_LOCATION = 20;
    private Runnable afterLocationPermission;

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        v.setTextColor(0xff99aabb);
        v.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }

    /** Runs r once location permission is granted (asks on Android 6+). */
    private void withLocation(Runnable r) {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            afterLocationPermission = r;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        r.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_CAMERA_PERMISSION) {
            if (r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) takePhoto();
            else Toast.makeText(this, "Camera permission is needed to take photos (Settings > Permissions)", Toast.LENGTH_LONG).show();
            return;
        }
        if (code == REQ_LOCATION && r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                && afterLocationPermission != null) {
            afterLocationPermission.run();
        } else if (code == REQ_LOCATION) {
            Toast.makeText(this, "Location permission is needed", Toast.LENGTH_LONG).show();
        }
        afterLocationPermission = null;
    }

    private void sendLocation() {
        final RadioService rs = RadioService.instance();
        if (rs == null || rs.engine() == null) {
            Toast.makeText(this, "Join a channel on the radio screen first", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioEngine.radioSilent) {
            Toast.makeText(this, "Radio silent: location not sent", Toast.LENGTH_LONG).show();
            return;
        }
        withLocation(new Runnable() {
            @Override
            public void run() {
                here.setText("Getting a GPS fix…");
                org.communitycomms.app.geo.LocationHelper.getFix(FilesActivity.this, 20000,
                        new org.communitycomms.app.geo.LocationHelper.Callback() {
                            @Override
                            public void onFix(final android.location.Location l, String problem) {
                                if (l == null) {
                                    here.setText(problem);
                                    return;
                                }
                                transmitLocation(l);
                            }
                        });
            }
        });
    }

    private CheckBox photoPoi;
    /** Direction and width of view of the last photo (NaN = unknown), for its map point's view cone. */
    private float photoAim = Float.NaN, photoFov = 60;
    private boolean photoAimMagnetic;
    private org.communitycomms.app.geo.CameraAim cameraAim;

    /** After the camera app: which way the camera faced when the picture was written, and how wide it sees. */
    private void notePhotoAim(File f) {
        photoAim = Float.NaN;
        if (f == null || !f.exists()) return;
        float exif = org.communitycomms.app.geo.CameraAim.exifDirection(f.getPath());
        if (!Float.isNaN(exif)) {
            photoAim = exif;
            photoAimMagnetic = false;
        } else if (cameraAim != null) {
            photoAim = cameraAim.at(f.lastModified());
            photoAimMagnetic = true;
        }
        if (cameraAim != null) cameraAim.stop();
        photoFov = org.communitycomms.app.geo.CameraAim.fieldOfView(f.getPath());
    }

    /** A "Photo" POI at this phone's position whose note links to the picture just sent. */
    private void dropPhotoPoi(final long fileKey) {
        withLocation(new Runnable() {
            @Override
            public void run() {
                here.setText("Getting a GPS fix for the photo's map point…");
                org.communitycomms.app.geo.LocationHelper.getFix(FilesActivity.this, 20000,
                        new org.communitycomms.app.geo.LocationHelper.Callback() {
                            @Override
                            public void onFix(android.location.Location l, String problem) {
                                if (l == null) {
                                    here.setText("Photo sent, but no map point: " + problem);
                                    return;
                                }
                                String me = new Prefs(FilesActivity.this).callsign();
                                if (me.length() == 0) me = "?";
                                String aim = "";
                                if (!Float.isNaN(photoAim)) {
                                    float b = photoAim;
                                    if (photoAimMagnetic) b += new android.hardware.GeomagneticField((float) l.getLatitude(),
                                            (float) l.getLongitude(), (float) l.getAltitude(), System.currentTimeMillis()).getDeclination();
                                    aim = "\n" + org.communitycomms.app.geo.Poi.aimTag(b, photoFov);
                                }
                                org.communitycomms.app.geo.Poi p = org.communitycomms.app.geo.Poi.create(l.getLatitude(), l.getLongitude(),
                                        org.communitycomms.app.geo.Poi.ICON_PHOTO, "Photo by " + me + "\n"
                                                + org.communitycomms.app.geo.Poi.photoTag(fileKey) + aim, 24 * 3600000L, me);
                                RadioService rs = RadioService.instance();
                                boolean sent = rs != null && rs.sendPoi(p);
                                here.setText(sent ? "Photo map point sent at " + org.communitycomms.app.geo.Geo.latLon(l.getLatitude(), l.getLongitude())
                                        : "Photo map point saved on this phone only");
                            }
                        });
            }
        });
    }

    private void transmitLocation(android.location.Location l) {
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        if (e == null) {
            here.setText("Left the channel; not sent");
            return;
        }
        e.sendLocation(l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.getTime(), "");
        here.setText("Sent: " + org.communitycomms.app.geo.Geo.latLon(l.getLatitude(), l.getLongitude())
                + "  ±" + org.communitycomms.app.geo.Geo.distance(l.getAccuracy()));
    }

    private void showCurrent() {
        withLocation(new Runnable() {
            @Override
            public void run() {
                here.setText("Getting a GPS fix…");
                org.communitycomms.app.geo.LocationHelper.getFix(FilesActivity.this, 20000,
                        new org.communitycomms.app.geo.LocationHelper.Callback() {
                            @Override
                            public void onFix(final android.location.Location l, String problem) {
                                if (l == null) {
                                    here.setText(problem);
                                    return;
                                }
                                here.setText("Current location: " + org.communitycomms.app.geo.Geo.latLon(l.getLatitude(), l.getLongitude())
                                        + "  ±" + org.communitycomms.app.geo.Geo.distance(l.getAccuracy()));
                            }
                        });
            }
        });
    }

    /** Map picker options: offline tile set, online tiles on/off and source. */
    private void mapOptions() {
        final org.communitycomms.app.geo.MapPrefs mp = new org.communitycomms.app.geo.MapPrefs(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);

        box.addView(label("Offline map tiles"));
        final java.util.List<String> sets = mp.offlineSets();
        final android.widget.Spinner offline = new android.widget.Spinner(this);
        java.util.List<String> setLabels = new java.util.ArrayList<String>(sets);
        if (setLabels.isEmpty()) setLabels.add("None found");
        offline.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, setLabels));
        offline.setSelection(Math.max(0, sets.indexOf(mp.offlineSetName())));
        box.addView(offline);
        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setText("Copy z/x/y tile folders to:\n" + org.communitycomms.app.geo.MapPrefs.mapsRoot(this).getPath() + "/NAME/");
        box.addView(hint);

        box.addView(label("Online map tiles"));
        final CheckBox online = new CheckBox(this);
        online.setText("Fetch tiles from the internet (off = fully offline)");
        online.setChecked(mp.onlineEnabled());
        box.addView(online);
        final android.widget.Spinner preset = new android.widget.Spinner(this);
        preset.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                org.communitycomms.app.geo.MapPrefs.ONLINE_NAMES));
        preset.setSelection(mp.onlinePreset());
        box.addView(preset);
        final android.widget.EditText custom = new android.widget.EditText(this);
        custom.setHint("Custom URL with {z} {x} {y}");
        custom.setText(mp.customUrl());
        custom.setSingleLine(true);
        box.addView(custom);

        box.addView(label("Layer on top (e.g. topographic over satellite)"));
        final java.util.List<String> ovKeys = new java.util.ArrayList<String>();
        java.util.List<String> ovNames = new java.util.ArrayList<String>();
        ovKeys.add("");
        ovNames.add("None");
        for (int i = 0; i < org.communitycomms.app.geo.MapPrefs.ONLINE_NAMES.length; i++) {
            if (i == org.communitycomms.app.geo.MapPrefs.CUSTOM) continue;
            ovKeys.add("web:" + i);
            ovNames.add("Online: " + org.communitycomms.app.geo.MapPrefs.ONLINE_NAMES[i]);
        }
        for (String s : sets) {
            ovKeys.add("set:" + s);
            ovNames.add("Offline set: " + s);
        }
        final android.widget.Spinner overlay = new android.widget.Spinner(this);
        overlay.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ovNames));
        overlay.setSelection(Math.max(0, ovKeys.indexOf(mp.overlay())));
        box.addView(overlay);
        final TextView opLabel = new TextView(this);
        box.addView(opLabel);
        final android.widget.SeekBar opacity = new android.widget.SeekBar(this);
        opacity.setMax(90);
        opacity.setProgress(Math.max(10, mp.overlayOpacity()) - 10);
        opLabel.setText("Layer strength: " + (opacity.getProgress() + 10) + "%");
        opacity.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                opLabel.setText("Layer strength: " + (p + 10) + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        box.addView(opacity);
        TextView ovHint = new TextView(this);
        ovHint.setTextSize(12);
        ovHint.setText("Online layers download only while online tiles are on; areas viewed are kept for offline use.");
        box.addView(ovHint);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Map options")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        if (!sets.isEmpty()) mp.setOfflineSet(sets.get(offline.getSelectedItemPosition()));
                        mp.setOnlineEnabled(online.isChecked());
                        mp.setOnline(preset.getSelectedItemPosition(), custom.getText().toString().trim());
                        mp.setOverlay(ovKeys.get(overlay.getSelectedItemPosition()), opacity.getProgress() + 10);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Button button(String text, final Runnable r) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setTextSize(13);
        bt.setAllCaps(false); // small phones: keeps "Picture / screenshot" from breaking mid-word
        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return bt;
    }

    /** Buttons side by side, equal widths and all as tall as the tallest (no baseline shifts). */
    static LinearLayout row(Button... buttons) {
        LinearLayout r = new LinearLayout(buttons[0].getContext());
        r.setBaselineAligned(false);
        for (Button b : buttons) r.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return r;
    }

    // ---- choosing what to send ----

    private void pick(String type, int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(type);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choose"), req);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show();
        }
    }

    // ---- camera: phone camera app, a chosen camera app, a USB camera*, or a network camera* ----

    private static final int REQ_CAMERA_PERMISSION = 21;
    static final int CAM_PHONE = 0, CAM_CHOOSE = 1, CAM_USB = 2, CAM_NETWORK = 3;
    private static final String[] CAM_NAMES = {"Phone camera app", "Choose a camera app each time (e.g. a USB camera app)",
            "USB camera* (untested: phones that list USB cameras, usually Android 9+)",
            "Network camera* (untested: a JPEG snapshot address on the local network)"};
    private static final String[] CAM_SHORT = {"phone camera", "choose app", "USB camera*", "network camera*"};
    private Button cameraSource;

    private void renderCameraSource() {
        cameraSource.setText("Camera: " + CAM_SHORT[prefs.getInt("camSource", CAM_PHONE)] + " (tap to change)");
    }

    private void chooseCameraSource() {
        new AlertDialog.Builder(this)
                .setTitle("Take photos with")
                .setSingleChoiceItems(CAM_NAMES, prefs.getInt("camSource", CAM_PHONE), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        prefs.edit().putInt("camSource", which).commit();
                        renderCameraSource();
                        if (which == CAM_NETWORK) askCameraUrl();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askCameraUrl() {
        final android.widget.EditText url = new android.widget.EditText(this);
        url.setSingleLine(true);
        url.setHint("http://192.168.1.50/snapshot.jpg");
        url.setText(prefs.getString("camUrl", ""));
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p / 2, p, 0);
        TextView t = new TextView(this);
        t.setText("Many IP, night vision and thermal cameras give a still picture at an address like this "
                + "(see the camera's manual). user:password@ before the address is allowed. Local network only.");
        box.addView(t);
        box.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("Network camera* snapshot address")
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("camUrl", url.getText().toString().trim()).commit();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Android 6+: a camera app refuses to start for an app that declares CAMERA but has not been granted it. */
    private boolean needCameraPermission() {
        if (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        return true;
    }

    private void takePhoto() {
        int src = prefs.getInt("camSource", CAM_PHONE);
        photoAim = Float.NaN; // network and USB cameras: direction unknown
        pendingCamera = new File(FilesProvider.cameraDir(this), "photo-" + System.currentTimeMillis() + ".jpg");
        if (src == CAM_NETWORK) {
            networkSnapshot(pendingCamera);
            return;
        }
        if (needCameraPermission()) return;
        if (src == CAM_USB) {
            usbSnapshot(pendingCamera);
            return;
        }
        Uri out = FilesProvider.camera(pendingCamera);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 16) i.setClipData(ClipData.newRawUri("photo", out));
        try {
            if (cameraAim == null) cameraAim = new org.communitycomms.app.geo.CameraAim(this);
            cameraAim.start(); // keeps sampling while the camera app is in front
            startActivityForResult(src == CAM_CHOOSE ? Intent.createChooser(i, "Take the photo with") : i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No camera app on this phone", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            // Never crash over the camera: say why and point at the permissions screen.
            Toast.makeText(this, "The camera would not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void sendCameraFile(File f) {
        fromCamera = true;
        prepareAndSend(Uri.fromFile(f), f.getName(), "image/jpeg");
        fromCamera = false;
    }

    private void usbSnapshot(final File out) {
        if (Build.VERSION.SDK_INT < 21) {
            Toast.makeText(this, "USB cameras need Android 5 or newer here; try \"Choose a camera app\"", Toast.LENGTH_LONG).show();
            return;
        }
        status.setText("Taking a picture with the USB camera*…");
        org.communitycomms.app.camera.ExternalCamera.capture(this, out, new org.communitycomms.app.camera.ExternalCamera.Done() {
            @Override
            public void onDone(File f, String problem) {
                if (f == null) {
                    status.setText("USB camera*: " + problem);
                    return;
                }
                status.setText("");
                sendCameraFile(f);
            }
        });
    }

    private void networkSnapshot(final File out) {
        final String url = prefs.getString("camUrl", "");
        if (url.length() == 0) {
            askCameraUrl();
            return;
        }
        status.setText("Fetching a picture from the network camera*…");
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                return org.communitycomms.app.camera.NetworkCamera.snapshot(url, out);
            }

            @Override
            protected void onPostExecute(String problem) {
                if (problem != null) {
                    status.setText("Network camera*: " + problem);
                    return;
                }
                status.setText("");
                sendCameraFile(out);
            }
        }.execute();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_CAMERA && cameraAim != null && result != RESULT_OK) cameraAim.stop();
        if (result != RESULT_OK) return;
        if (req == REQ_CAMERA && pendingCamera != null && pendingCamera.length() > 0) {
            notePhotoAim(pendingCamera);
            sendCameraFile(pendingCamera);
        } else if ((req == REQ_PICK_FILE || req == REQ_PICK_IMAGE) && data != null && data.getData() != null) {
            prepareAndSend(data.getData(), null, null);
        }
    }

    private void handleShare(Intent i) {
        if (i == null) return;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) prepareAndSend(u, null, i.getType());
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> us = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) for (Uri u : us) prepareAndSend(u, null, i.getType());
        }
        setIntent(new Intent()); // don't re-send on rotation
    }

    // ---- sending ----

    private void prepareAndSend(final Uri uri, final String knownName, final String knownMime) {
        if (RadioService.files() == null) {
            Toast.makeText(this, "Join a channel on the radio screen first", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioEngine.radioSilent) {
            Toast.makeText(this, "Radio silent: files are not sent", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioService.onPublic()) {
            new AlertDialog.Builder(this)
                    .setTitle("You are on Public")
                    .setMessage("Anyone with this app can receive files sent on Public. Send anyway?")
                    .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            doPrepare(uri, knownName, knownMime);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            doPrepare(uri, knownName, knownMime);
        }
    }

    private boolean fromCamera;

    // ---- map tiles ----

    private static final double[] TILE_RADII_KM = {1, 2, 5, 10, 25};

    /** Packs the tiles around the last map view and sends them; the other phone can import them. */
    private void sendTiles() {
        if (RadioService.files() == null) {
            Toast.makeText(this, "Join a channel on the radio screen first", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioEngine.radioSilent) {
            Toast.makeText(this, "Radio silent: files are not sent", Toast.LENGTH_LONG).show();
            return;
        }
        final org.communitycomms.app.geo.MapPrefs mp = new org.communitycomms.app.geo.MapPrefs(this);
        final List<String> names = new ArrayList<String>();
        final List<File> roots = new ArrayList<File>();
        for (String s : mp.offlineSets()) {
            names.add("Offline set \"" + s + "\"");
            roots.add(new File(org.communitycomms.app.geo.MapPrefs.mapsRoot(this), s));
        }
        File cache = mp.onlineCache();
        String[] cached = cache.list();
        if (cached != null && cached.length > 0) {
            names.add("Online tiles already viewed (" + org.communitycomms.app.geo.MapPrefs.ONLINE_NAMES[mp.onlinePreset()].split(" \\(")[0] + ")");
            roots.add(cache);
        }
        if (roots.isEmpty()) {
            Toast.makeText(this, "No map tiles on this phone yet", Toast.LENGTH_LONG).show();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final double lat = mp.centerLat(), lon = mp.centerLon();
        TextView where = new TextView(this);
        where.setText("Around the last map view: " + org.communitycomms.app.geo.Geo.latLon(lat, lon));
        box.addView(where);
        box.addView(label("Tiles from"));
        final android.widget.Spinner src = new android.widget.Spinner(this);
        src.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        box.addView(src);
        box.addView(label("Distance around it"));
        String[] radii = new String[TILE_RADII_KM.length];
        for (int i = 0; i < radii.length; i++) radii[i] = org.communitycomms.app.geo.Geo.imperial ? String.format(java.util.Locale.US, "%.0f mi", TILE_RADII_KM[i] / 1.609344) : (int) TILE_RADII_KM[i] + " km";
        final android.widget.Spinner radius = new android.widget.Spinner(this);
        radius.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, radii));
        radius.setSelection(1);
        box.addView(radius);
        box.addView(label("Most detail (zoom)"));
        final String[] zooms = {"14 (town)", "15", "16 (streets)", "17", "18 (buildings)"};
        final android.widget.Spinner zoom = new android.widget.Spinner(this);
        zoom.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, zooms));
        zoom.setSelection(2);
        box.addView(zoom);
        final TextView est = new TextView(this);
        est.setPadding(0, (int) (8 * d), 0, 0);
        box.addView(est);
        final Runnable estimate = new Runnable() {
            @Override
            public void run() {
                long[] c = org.communitycomms.app.geo.TilePack.count(new File[]{roots.get(src.getSelectedItemPosition())},
                        tileArea(lat, lon, radius, zoom));
                est.setText(c[0] + " tiles, " + size(c[1])
                        + (c[1] > 40L * 1048576 ? "\nToo big: pick a smaller distance or less detail."
                        : c[1] > 5L * 1048576 ? "\nLarge: this will take a while on the radio." : ""));
            }
        };
        AdapterView.OnItemSelectedListener re = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                estimate.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        src.setOnItemSelectedListener(re);
        radius.setOnItemSelectedListener(re);
        zoom.setOnItemSelectedListener(re);
        new AlertDialog.Builder(this)
                .setTitle("Send map tiles")
                .setView(box)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        File root = roots.get(src.getSelectedItemPosition());
                        String set = root.equals(mp.onlineCache()) ? "Online tiles" : root.getName();
                        buildAndSendTiles(root, set, tileArea(lat, lon, radius, zoom));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static org.communitycomms.app.geo.TilePack.Area tileArea(double lat, double lon, android.widget.Spinner radius,
                                                                 android.widget.Spinner zoom) {
        int zMax = 14 + zoom.getSelectedItemPosition();
        return new org.communitycomms.app.geo.TilePack.Area(lat, lon, TILE_RADII_KM[radius.getSelectedItemPosition()], 6, zMax);
    }

    private void buildAndSendTiles(final File root, final String set, final org.communitycomms.app.geo.TilePack.Area area) {
        status.setText("Packing map tiles…");
        new AsyncTask<Void, Void, String>() {
            File pack;

            @Override
            protected String doInBackground(Void... v) {
                try {
                    File dir = new File(getCacheDir(), "outgoing");
                    dir.mkdirs();
                    pack = new File(dir, set.replaceAll("[^A-Za-z0-9 _.()-]", "") + org.communitycomms.app.geo.TilePack.SUFFIX);
                    int n = org.communitycomms.app.geo.TilePack.build(new File[]{root}, area, set, pack);
                    if (n == 0) return "No tiles in that area";
                    if (pack.length() > 40L * 1048576) return "Too big (" + size(pack.length()) + "): pick a smaller area";
                    return null;
                } catch (IOException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String err) {
                if (err != null) {
                    status.setText("Could not send map tiles: " + err);
                    if (pack != null) pack.delete();
                    return;
                }
                prepareAndSend(Uri.fromFile(pack), pack.getName(), org.communitycomms.app.geo.TilePack.MIME);
            }
        }.execute();
    }

    private void doPrepare(final Uri uri, final String knownName, final String knownMime) {
        final boolean doShrink = shrink.isChecked();
        final boolean camera = fromCamera;
        final long[] sentKey = {0};
        status.setText("Preparing…");
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    String name = knownName != null ? knownName : displayName(uri);
                    String mime = knownMime != null && !knownMime.contains("*") ? knownMime : getContentResolver().getType(uri);
                    File dir = new File(getCacheDir(), "outgoing");
                    dir.mkdirs();
                    File copy = new File(dir, System.currentTimeMillis() + "-" + name);
                    copy(uri, copy);
                    if (doShrink && mime != null && (mime.equals("image/jpeg") || mime.equals("image/png"))) {
                        File small = shrinkImage(copy);
                        if (small != null) {
                            copy.delete();
                            copy = small;
                            name = stripExt(name) + ".jpg";
                            mime = "image/jpeg";
                        }
                    }
                    FileTransfer ft = RadioService.files();
                    if (ft == null) return "Left the channel";
                    FileTransfer.Outgoing o = ft.send(copy, name, mime);
                    RadioService.logFileSent(FilesActivity.this, o, mime, copy);
                    RadioService rs = RadioService.instance();
                    if (rs != null && rs.engine() != null) sentKey[0] = RadioService.fileKey(rs.engine().myId(), o.id);
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String err) {
                status.setText(err == null ? "" : "Could not send: " + err);
                render();
                if (err == null && camera && sentKey[0] != 0 && photoPoi.isChecked()) dropPhotoPoi(sentKey[0]);
            }
        }.execute();
    }

    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "file-" + System.currentTimeMillis();
    }

    private void copy(Uri uri, File to) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot read");
        OutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    /** Longest side down to 1600 px, JPEG quality 80, upright. Returns null if not worth it. */
    private static File shrinkImage(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
        if (bmp == null) return null;
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        try {
            int orient = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (orient == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
            else if (orient == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
            else if (orient == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
        } catch (IOException ignored) {
        }
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        File small = new File(f.getParentFile(), stripExt(f.getName()) + "-small.jpg");
        try {
            FileOutputStream os = new FileOutputStream(small);
            out.compress(Bitmap.CompressFormat.JPEG, 80, os);
            os.close();
        } catch (IOException e) {
            return null;
        }
        if (small.length() >= f.length()) {
            small.delete();
            return null;
        }
        return small;
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    // ---- list ----

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void render() {
        List<String> rows = new ArrayList<String>();
        rowFiles.clear();
        FileTransfer ft = RadioService.files();
        if (ft == null) {
            status.setText("Not on a channel. Join A or B on the radio screen to send and receive.");
        } else if (status.getText().toString().startsWith("Not on a channel")) {
            status.setText("");
        }
        java.util.Set<String> shown = new java.util.HashSet<String>();
        if (ft != null) {
            for (FileTransfer.Outgoing o : ft.outgoing()) {
                rows.add("↑ " + o.name + "  " + size(o.size) + "  sent " + o.percentSent() + "%"
                        + (o.repairs > 0 ? ", " + o.repairs + " pieces re-sent" : ""));
                rowFiles.add(null);
            }
            for (FileTransfer.Incoming in : ft.incoming()) {
                if (in.done) {
                    rows.add("✓ " + in.result.getName() + "  from " + in.from + "  " + size(in.size) + "  (tap to open)");
                    rowFiles.add(in.result);
                    shown.add(in.result.getName());
                } else if (in.failed) {
                    rows.add("✗ " + in.name + "  from " + in.from + ": " + in.error);
                    rowFiles.add(null);
                } else {
                    rows.add("↓ " + in.name + "  from " + in.from + "  " + size(in.size) + "  " + in.percent() + "%");
                    rowFiles.add(null);
                }
            }
        }
        File[] old = RadioService.receivedDir(this).listFiles();
        if (old != null) {
            Arrays.sort(old, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
                }
            });
            for (File f : old) {
                if (shown.contains(f.getName())) continue;
                rows.add("• " + f.getName() + "  " + size(f.length()));
                rowFiles.add(f);
            }
        }
        if (rows.isEmpty()) {
            rows.add("Nothing sent or received yet");
            rowFiles.add(null);
        }
        int pos = list.getFirstVisiblePosition();
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows));
        list.setSelection(pos);
    }

    private void open(File f) {
        if (ConfigShare.isConfig(f)) {
            ConfigShare.offerApply(this, f, "", null);
            return;
        }
        if (org.communitycomms.app.geo.TilePack.isTilePack(f)) {
            org.communitycomms.app.geo.TilePack.offerImport(this, f);
            return;
        }
        if (org.communitycomms.app.geo.AtakTiles.isTileDb(f)) {
            org.communitycomms.app.geo.AtakTiles.offerImport(this, f);
            return;
        }
        Uri u = FilesProvider.received(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, getContentResolver().getType(u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app on this phone opens that file type", Toast.LENGTH_LONG).show();
        }
    }
}
