package org.communitycomms.app;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.audio.AudioLevels;
import org.communitycomms.app.audio.AudioRoute;
import org.communitycomms.app.audio.Capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Audio: the phone's volumes for radio audio and media, an in-app boost for what is heard, the
 * microphone level, and an echo test (records 5 s through the same mic path as a transmission,
 * then plays it back the way radio audio is played).
 */
public final class AudioActivity extends Activity {
    private static final int ECHO_SECONDS = 5;
    private static final int BOOST_MIN = 50, BOOST_MAX = 400;

    private AudioManager am;
    private Prefs prefs;
    private SeekBar rx, mic;
    private TextView rxLabel, micLabel, echoStatus;
    private ProgressBar meter;
    private Button echo;
    private final Handler handler = new Handler();
    private volatile boolean echoRunning;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Audio");
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = new Prefs(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (16 * d));

        // What is in use right now, each with a cog to choose.
        box.addView(label("Devices in use"));
        outRow = deviceRow(box, new Runnable() {
            @Override
            public void run() {
                chooseOut();
            }
        });
        micRow = deviceRow(box, new Runnable() {
            @Override
            public void run() {
                chooseMic();
            }
        });
        ringRow = deviceRow(box, new Runnable() {
            @Override
            public void run() {
                chooseRing();
            }
        });
        pttRow = deviceRow(box, new Runnable() {
            @Override
            public void run() {
                choosePtt();
            }
        });
        showDevices();

        box.addView(label("Radio volume (the phone's call volume, used for radio audio)"));
        box.addView(streamBar(AudioManager.STREAM_VOICE_CALL));
        box.addView(label("Media volume (stereo dual watch, Call ring tone, recordings)"));
        box.addView(streamBar(AudioManager.STREAM_MUSIC));

        rxLabel = label("");
        box.addView(rxLabel);
        rx = gainBar(prefs.rxGainPct());
        box.addView(rx);

        micLabel = label("");
        box.addView(micLabel);
        mic = gainBar(prefs.micGainPct());
        box.addView(mic);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        box.addView(meter);

        box.addView(label("Echo test"));
        echo = new Button(this);
        echo.setText("Echo test (record " + ECHO_SECONDS + " s, then play back)");
        echo.setAllCaps(false);
        box.addView(echo);
        echoStatus = new TextView(this);
        echoStatus.setText("Talk normally into the phone after pressing. It plays back what others would hear.");
        box.addView(echoStatus);

        // Left / right: a short tone in one ear at a time (stereo dual watch puts channels in each ear).
        box.addView(label("Left and right"));
        LinearLayout lrRow = new LinearLayout(this);
        Button left = new Button(this), right = new Button(this);
        left.setText("Test left");
        right.setText("Test right");
        left.setAllCaps(false);
        right.setAllCaps(false);
        lrRow.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        lrRow.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(lrRow);
        left.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                sideTone(true);
            }
        });
        right.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                sideTone(false);
            }
        });
        final android.widget.CheckBox swap = new android.widget.CheckBox(this);
        swap.setText("Swap left and right (if the tones come out of the wrong side)");
        swap.setChecked(prefs.swapLeftRight());
        swap.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.setSwapLeftRight(on);
                org.communitycomms.app.audio.AudioLevels.swapLeftRight = on;
            }
        });
        box.addView(swap);

        SeekBar.OnSeekBarChangeListener save = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                prefs.setGains(pct(rx), pct(mic));
                showGains();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        };
        rx.setOnSeekBarChangeListener(save);
        mic.setOnSeekBarChangeListener(save);
        showGains();
        echo.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                startEcho();
            }
        });
        addBurst(box);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    /** Live voice, or burst: record while PTT is held and send it as one file at the chosen quality. */
    private void addBurst(LinearLayout box) {
        box.addView(label("Voice mode"));
        final android.widget.CheckBox burst = new android.widget.CheckBox(this);
        burst.setText("Burst voice: record while PTT is held, send when let go (not live). Plays by itself on "
                + "every phone once it arrives; best for weak or patchy links.");
        burst.setChecked(prefs.burstVoice());
        box.addView(burst);
        // Received bursts wait for a clear channel (nobody talking, not on air, no other burst playing).
        final int[] waits = {0, 15, 30, 60, 120, 300};
        final String[] waitNames = {"Play at once", "Wait up to 15 s", "Wait up to 30 s", "Wait up to 1 min",
                "Wait up to 2 min", "Wait up to 5 min"};
        final android.widget.Button wait = new android.widget.Button(this);
        wait.setAllCaps(false);
        final Runnable showWait = new Runnable() {
            @Override
            public void run() {
                int at = 3;
                for (int i = 0; i < waits.length; i++) if (waits[i] == prefs.burstWaitSec()) at = i;
                wait.setText("Received bursts while the channel is busy: " + waitNames[at]);
            }
        };
        showWait.run();
        wait.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                new android.app.AlertDialog.Builder(AudioActivity.this)
                        .setTitle("Wait for the channel to clear")
                        .setItems(waitNames, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                prefs.setBurstWaitSec(waits[which]);
                                org.communitycomms.app.audio.BurstVoice.waitClearSec = waits[which];
                                showWait.run();
                            }
                        })
                        .show();
            }
        });
        box.addView(wait);
        final android.widget.CheckBox tone = new android.widget.CheckBox(this);
        tone.setText("Call-waiting beep when a burst is waiting to play");
        tone.setChecked(prefs.burstWaitTone());
        tone.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.setBurstWaitTone(on);
                org.communitycomms.app.audio.BurstVoice.waitTone = on;
            }
        });
        box.addView(tone);
        final android.widget.CheckBox clean = new android.widget.CheckBox(this);
        clean.setText("Clean up received bursts before playing: less background noise, quiet ones turned up, loud ones "
                + "evened out (an attempt; adds a moment before each plays)");
        clean.setChecked(prefs.burstCleanup());
        clean.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.setBurstCleanup(on);
                org.communitycomms.app.audio.BurstCleanup.enabled = on;
            }
        });
        box.addView(clean);
        final TextView q = label("");
        box.addView(q);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(org.communitycomms.app.audio.BurstVoice.QUALITY_NAMES.length - 1);
        bar.setProgress(prefs.burstQuality());
        box.addView(bar);
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                q.setText("Burst voice quality: " + org.communitycomms.app.audio.BurstVoice.QUALITY_NAMES[bar.getProgress()]
                        + " (smaller sends faster on a weak link)");
                bar.setEnabled(burst.isChecked());
            }
        };
        show.run();
        burst.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.setBurstVoice(on);
                show.run();
            }
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                prefs.setBurstQuality(p);
                show.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    private TextView outRow, micRow, ringRow, pttRow;
    private final Runnable deviceTick = new Runnable() {
        @Override
        public void run() {
            showDevices();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(deviceTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(deviceTick);
        super.onPause();
    }

    /** A line of text with a cog button at its right. */
    private TextView deviceRow(LinearLayout box, final Runnable cog) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // The system's settings cog (a font cog glyph is missing on Android 4.x).
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(android.R.drawable.ic_menu_preferences);
        b.setContentDescription("Choose");
        b.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                cog.run();
            }
        });
        row.addView(b, new LinearLayout.LayoutParams((int) (52 * d), (int) (48 * d)));
        box.addView(row);
        return t;
    }

    private void showDevices() {
        outRow.setText("Sound: " + AudioRoute.describeOut(this, am));
        micRow.setText("Microphone: " + AudioRoute.describeMic(this, am));
        ringRow.setText("Ringer (Call alerts): " + AudioRoute.describeRing(am));
        int k = prefs.pttKey();
        String key = k == android.view.KeyEvent.KEYCODE_UNKNOWN ? "none set" : android.view.KeyEvent.keyCodeToString(k);
        String bt = prefs.btButton().length() == 0 ? "" : "\nBluetooth PTT: " + BtPtt.buttonState;
        pttRow.setText("PTT button: " + key + bt);
    }

    private void chooseOut() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Sound output for radio audio")
                .setSingleChoiceItems(AudioRoute.OUT_NAMES, AudioRoute.out(this), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        AudioRoute.setOut(AudioActivity.this, w);
                        RadioService s = RadioService.instance();
                        if (s != null) s.applyAudioRoute();
                        di.dismiss();
                        showDevices();
                    }
                })
                .setNeutralButton("Bluetooth headset…", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        startActivity(new android.content.Intent(AudioActivity.this, SettingsActivity.class));
                        Toast.makeText(AudioActivity.this, "Bluetooth headset: under Talking in Settings", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @android.annotation.TargetApi(23)
    private void chooseMic() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "Choosing a microphone needs Android 6 or newer. This phone uses the headset's microphone when one is plugged in.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<android.media.AudioDeviceInfo> ins = AudioRoute.inputs(am);
        final String[] names = new String[ins.size() + 1];
        final int[] types = new int[ins.size() + 1];
        names[0] = "Automatic (follows the sound output)";
        int sel = 0;
        for (int i = 0; i < ins.size(); i++) {
            types[i + 1] = ins.get(i).getType();
            names[i + 1] = AudioRoute.typeName(types[i + 1]);
            if (types[i + 1] == AudioRoute.micType) sel = i + 1;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Microphone for transmitting")
                .setSingleChoiceItems(names, sel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        AudioRoute.setMicType(AudioActivity.this, types[w]);
                        di.dismiss();
                        showDevices();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseRing() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "The ring follows media audio. Choosing the loudspeaker needs Android 6 or newer.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = {"Automatic (follows media audio: headset when plugged in)", "Loudspeaker always (heard even with headphones in)"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Ringer for Call alerts")
                .setSingleChoiceItems(names, AudioRoute.ringSpeaker ? 1 : 0, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        AudioRoute.setRingSpeaker(AudioActivity.this, w == 1);
                        di.dismiss();
                        showDevices();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Same PTT key as in Settings: press the button to learn it, or clear it. */
    private void choosePtt() {
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("PTT button")
                .setMessage("Press the hardware PTT button now (headset, side key or a Bluetooth keyboard-type button).\n\n"
                        + "Bluetooth Low Energy buttons (e.g. FalconClaw) are paired under Talking in Settings.")
                .setNeutralButton("Clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        prefs.setPttKey(android.view.KeyEvent.KEYCODE_UNKNOWN);
                        showDevices();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface di, int code, android.view.KeyEvent ev) {
                if (code == android.view.KeyEvent.KEYCODE_BACK || ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                prefs.setPttKey(code);
                Toast.makeText(AudioActivity.this, "PTT button set: " + android.view.KeyEvent.keyCodeToString(code), Toast.LENGTH_SHORT).show();
                di.dismiss();
                showDevices();
                return true;
            }
        });
        dlg.show();
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }

    /** A slider straight onto one of the phone's volume streams (the same as its volume keys). */
    private SeekBar streamBar(final int stream) {
        SeekBar s = new SeekBar(this);
        s.setMax(am.getStreamMaxVolume(stream));
        s.setProgress(am.getStreamVolume(stream));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean user) {
                if (user) am.setStreamVolume(stream, p, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        return s;
    }

    private SeekBar gainBar(int pct) {
        SeekBar s = new SeekBar(this);
        s.setMax(BOOST_MAX - BOOST_MIN);
        s.setProgress(Math.max(0, Math.min(BOOST_MAX, pct) - BOOST_MIN));
        return s;
    }

    private static int pct(SeekBar s) {
        // Snap near 100 % so "unchanged" is easy to hit.
        int p = s.getProgress() + BOOST_MIN;
        return Math.abs(p - 100) <= 5 ? 100 : p;
    }

    private void showGains() {
        rxLabel.setText("Boost what you hear (in this app): " + pct(rx) + "%");
        micLabel.setText("Microphone level (what others hear from you): " + pct(mic) + "%");
    }

    /** About a second of a beep in the left or the right ear (after Swap), on the media stream. */
    private void sideTone(final boolean leftEar) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int rate = 16000, n = rate;
                short[] lr = new short[n * 2];
                boolean left = leftEar != org.communitycomms.app.audio.AudioLevels.swapLeftRight;
                for (int i = 0; i < n; i++) {
                    double env = Math.min(1, Math.min(i, n - i) / 800.0) * ((i / (rate / 4)) % 2 == 0 ? 1 : 0); // two beeps
                    short s = (short) (12000 * env * Math.sin(2 * Math.PI * (leftEar ? 660 : 880) * i / rate));
                    lr[2 * i] = left ? s : 0;
                    lr[2 * i + 1] = left ? 0 : s;
                }
                android.media.AudioTrack t = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate,
                        android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                        lr.length * 2, android.media.AudioTrack.MODE_STATIC);
                try {
                    t.write(lr, 0, lr.length);
                    t.play();
                    Thread.sleep(1200);
                } catch (Exception ignored) {
                } finally {
                    t.release();
                }
            }
        }, "side-tone").start();
        Toast.makeText(this, leftEar ? "Left ear (lower beeps)" : "Right ear (higher beeps)", Toast.LENGTH_SHORT).show();
    }

    // ---- echo test ----

    private void startEcho() {
        if (echoRunning) return;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        if ((e != null && (e.isTransmitting() || e.voxOn())) || (rs != null && rs.compat() != null && rs.compat().isTransmitting())) {
            Toast.makeText(this, "The microphone is in use (transmitting or VOX). Stop that first.", Toast.LENGTH_LONG).show();
            return;
        }
        echoRunning = true;
        echo.setEnabled(false);
        final List<short[]> frames = new ArrayList<short[]>();
        final Capture cap = new Capture(RadioEngine.FRAME_SAMPLES, new Capture.FrameSink() {
            @Override
            public void onFrame(short[] pcm, int samples) {
                synchronized (frames) {
                    frames.add(pcm.clone());
                }
                final int level = (int) (AudioLevels.peak(pcm, samples) * 100);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        meter.setProgress(level);
                    }
                });
            }
        });
        final boolean[] restore = new boolean[1];
        final int oldMode = am.getMode();
        final boolean oldSpeaker = am.isSpeakerphoneOn();
        if (e == null) {
            // Not on air: set the audio up the way the radio does, so the test sounds the same.
            restore[0] = true;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(!am.isWiredHeadsetOn() && !am.isBluetoothScoOn());
        }
        cap.start();
        final long end = System.currentTimeMillis() + ECHO_SECONDS * 1000L;
        handler.post(new Runnable() {
            @Override
            public void run() {
                long left = end - System.currentTimeMillis();
                if (left > 0) {
                    echoStatus.setText("Recording… talk now (" + ((left + 999) / 1000) + " s)");
                    handler.postDelayed(this, 200);
                    return;
                }
                echoStatus.setText("Playing back…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        cap.stop();
                        play(frames);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                RadioService now = RadioService.instance();
                                if (restore[0] && (now == null || now.engine() == null)) { // still not on air
                                    am.setSpeakerphoneOn(oldSpeaker);
                                    am.setMode(oldMode);
                                }
                                meter.setProgress(0);
                                echoStatus.setText(frames.isEmpty() ? "Nothing was recorded: check the microphone permission."
                                        : "Done. Adjust the levels and try again if needed.");
                                echo.setEnabled(true);
                                echoRunning = false;
                            }
                        });
                    }
                }, "echo-play").start();
            }
        });
    }

    /** Plays the recording the way radio audio is played (call stream, in-app boost applied). */
    private void play(List<short[]> frames) {
        int rate = RadioEngine.SAMPLE_RATE;
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_VOICE_CALL, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate), AudioTrack.MODE_STREAM);
        if (t.getState() != AudioTrack.STATE_INITIALIZED) return;
        t.play();
        try {
            for (short[] f : frames) {
                if (isFinishing()) break;
                short[] out = f.clone();
                AudioLevels.apply(out, out.length, AudioLevels.rxGain);
                t.write(out, 0, out.length);
                final int level = (int) (AudioLevels.peak(out, out.length) * 100);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        meter.setProgress(level);
                    }
                });
            }
            t.write(new short[rate / 2], 0, rate / 2); // let the tail play out
        } finally {
            t.stop();
            t.release();
        }
    }
}
