package com.cheatdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    Process su;
    BufferedWriter w;
    BufferedReader r;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final List<String> pids = new ArrayList<>();
    final String[] types = {"i32", "i64", "f32", "f64"};
    Spinner procSp, typeSp;
    EditText val;
    TextView status;
    ListView lv;
    ArrayAdapter<String> procAd, resAd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        Button load = new Button(this);
        load.setText("Connect (root) & load apps");
        procSp = new Spinner(this);
        procAd = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item);
        procSp.setAdapter(procAd);
        typeSp = new Spinner(this);
        typeSp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, types));
        val = new EditText(this);
        val.setHint("Value to search");
        val.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Button first = new Button(this), next = new Button(this);
        first.setText("First scan");
        next.setText("Next scan");
        LinearLayout row = new LinearLayout(this);
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        status = new TextView(this);
        status.setText("Needs a rooted phone. Tap a result to edit it.");
        lv = new ListView(this);
        resAd = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lv.setAdapter(resAd);

        for (View v : new View[]{load, procSp, typeSp, val, row, status}) root.addView(v);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        load.setOnClickListener(v -> bg(() -> {
            List<String> o = ask("procs");
            runOnUiThread(() -> {
                procAd.clear();
                pids.clear();
                for (String s : o) {
                    int i = s.indexOf(' ');
                    if (i < 0) continue;
                    pids.add(s.substring(0, i));
                    procAd.add(s.substring(i + 1) + " (" + s.substring(0, i) + ")");
                }
                status.setText(pids.size() + " apps found");
            });
        }));
        first.setOnClickListener(v -> scan(true));
        next.setOnClickListener(v -> scan(false));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            final String addr = resAd.getItem(pos).split(" ")[0];
            final EditText e = new EditText(this);
            e.setInputType(val.getInputType());
            new AlertDialog.Builder(this).setTitle("New value for " + addr).setView(e)
                .setPositiveButton("Write", (d, wh) -> bg(() -> {
                    ask("set " + addr + " " + e.getText());
                    refresh();
                })).show();
        });
    }

    void scan(boolean first) {
        final String t = types[typeSp.getSelectedItemPosition()], v = val.getText().toString().trim();
        final int sel = procSp.getSelectedItemPosition();
        if (v.isEmpty() || (first && sel < 0)) { status.setText("Pick an app and enter a value"); return; }
        runOnUiThread(() -> status.setText("Scanning..."));
        bg(() -> {
            ask(first ? "first " + pids.get(sel) + " " + t + " " + v : "next " + t + " " + v);
            refresh();
        });
    }

    void refresh() throws IOException {
        final List<String> o = ask("list");
        runOnUiThread(() -> {
            resAd.clear();
            for (String s : o) {
                if (s.startsWith("COUNT")) status.setText(s.replace("COUNT", "Matches:") + " (showing first 100)");
                else if (s.startsWith("ERR")) status.setText(s);
                else resAd.add(s);
            }
        });
    }

    interface Job { void run() throws Exception; }

    void bg(Job j) {
        ex.submit(() -> {
            try { j.run(); }
            catch (Exception e) { runOnUiThread(() -> status.setText("Error: " + e.getMessage())); }
        });
    }

    List<String> ask(String cmd) throws IOException {
        if (su == null) {
            su = new ProcessBuilder("su", "-c",
                "CLASSPATH=" + getPackageCodePath() + " exec app_process /system/bin com.cheatdroid.RootServer")
                .redirectErrorStream(true).start();
            w = new BufferedWriter(new OutputStreamWriter(su.getOutputStream()));
            r = new BufferedReader(new InputStreamReader(su.getInputStream()));
        }
        w.write(cmd + "\n");
        w.flush();
        List<String> out = new ArrayList<>();
        String l;
        while ((l = r.readLine()) != null && !l.equals("END")) out.add(l);
        if (l == null) { su = null; throw new IOException("Root server stopped. Is the phone rooted?"); }
        return out;
    }
}
