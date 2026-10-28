package org.communitycomms.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Project NOMAD (a local offline server: AI, Wikipedia, maps) on the MANET. Ask its AI assistant
 * questions over the local network (its Ollama service), or open its Command Center in the browser
 * for the library and maps. Nothing goes to the internet.
 */
public final class NomadActivity extends Activity {
    private SharedPreferences sp;
    private EditText host, question;
    private Spinner model;
    private TextView out;
    private final List<String> models = new ArrayList<String>();
    private final List<JSONObject> history = new ArrayList<JSONObject>();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Project NOMAD");
        sp = getSharedPreferences("nomad", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * d);
        box.setPadding(p, p, p, p);
        TextView intro = new TextView(this);
        intro.setText("A Project NOMAD server on the MANET: ask its AI, or open its library and maps. Give the server's address.");
        box.addView(intro);
        host = new EditText(this);
        host.setSingleLine(true);
        host.setHint("10.41.x.x");
        host.setText(sp.getString("host", ""));
        box.addView(host);
        model = new Spinner(this);
        box.addView(model);
        Button refresh = button("Connect / list AI models", new Runnable() {
            @Override
            public void run() {
                listModels();
            }
        });
        Button open = button("Open Command Center", new Runnable() {
            @Override
            public void run() {
                save();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + server() + ":8080")));
                } catch (Exception e) {
                    out.setText("No browser on this phone");
                }
            }
        });
        box.addView(FilesActivity.row(refresh, open));
        question = new EditText(this);
        question.setHint("Ask the AI (e.g. how do I treat a snake bite?)");
        question.setMinLines(2);
        box.addView(question);
        Button ask = button("Ask", new Runnable() {
            @Override
            public void run() {
                ask();
            }
        });
        Button clear = button("New conversation", new Runnable() {
            @Override
            public void run() {
                history.clear();
                out.setText("");
            }
        });
        box.addView(FilesActivity.row(ask, clear));
        out = new TextView(this);
        out.setTextIsSelectable(true);
        out.setTextSize(15);
        box.addView(out);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
        model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos < models.size()) sp.edit().putString("model", models.get(pos)).commit();
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) {
            }
        });
        if (host.length() > 0) listModels();
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private String server() {
        String h = host.getText().toString().trim();
        if (h.startsWith("http://")) h = h.substring(7);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    private void save() {
        sp.edit().putString("host", host.getText().toString().trim()).commit();
    }

    private void show(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                out.setText(s);
            }
        });
    }

    private void listModels() {
        save();
        final String base = "http://" + server() + ":11434";
        out.setText("Connecting…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray a = new JSONObject(http(base + "/api/tags", null, 5000)).optJSONArray("models");
                    final List<String> names = new ArrayList<String>();
                    for (int i = 0; a != null && i < a.length(); i++) names.add(a.getJSONObject(i).optString("name"));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            models.clear();
                            models.addAll(names);
                            ArrayAdapter<String> ad = new ArrayAdapter<String>(NomadActivity.this,
                                    android.R.layout.simple_spinner_item, models);
                            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            model.setAdapter(ad);
                            int sel = models.indexOf(sp.getString("model", ""));
                            if (sel >= 0) model.setSelection(sel);
                            out.setText(names.isEmpty() ? "Connected, but the server has no AI models downloaded yet."
                                    : "Connected: " + names.size() + " AI model(s).");
                        }
                    });
                } catch (Exception e) {
                    show("Could not reach the AI on " + server() + " (port 11434): " + e.getMessage()
                            + "\nOn the server, Ollama must listen on the network (OLLAMA_HOST=0.0.0.0).");
                }
            }
        }, "nomad").start();
    }

    private void ask() {
        final String q = question.getText().toString().trim();
        if (q.length() == 0 || models.isEmpty()) {
            out.setText(models.isEmpty() ? "Connect first" : "Type a question");
            return;
        }
        final String m = models.get(Math.max(0, model.getSelectedItemPosition()));
        final String base = "http://" + server() + ":11434";
        out.setText("Thinking… (a small server can take a minute)");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    history.add(new JSONObject().put("role", "user").put("content", q));
                    JSONArray msgs = new JSONArray();
                    for (JSONObject o : history) msgs.put(o);
                    JSONObject req = new JSONObject().put("model", m).put("messages", msgs).put("stream", false);
                    JSONObject r = new JSONObject(http(base + "/api/chat", req.toString(), 300000));
                    String answer = r.getJSONObject("message").optString("content");
                    history.add(new JSONObject().put("role", "assistant").put("content", answer));
                    StringBuilder all = new StringBuilder();
                    for (JSONObject o : history) {
                        all.append("user".equals(o.optString("role")) ? "You: " : "AI: ").append(o.optString("content")).append("\n\n");
                    }
                    show(all.toString().trim());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            question.setText("");
                        }
                    });
                } catch (Exception e) {
                    if (!history.isEmpty()) history.remove(history.size() - 1);
                    show("The AI did not answer: " + e.getMessage());
                }
            }
        }, "nomad-ask").start();
    }

    private static String http(String url, String body, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(readMs);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
