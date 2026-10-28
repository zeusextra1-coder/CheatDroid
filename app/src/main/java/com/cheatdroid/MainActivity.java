package com.cheatdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.io.*;
import rikka.shizuku.Shizuku;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BG = 0xFF0F1419, PN = 0xFF1F2A35, AC = 0xFF38BDF8, TX = 0xFFE6EDF3, MU = 0xFF98A6B3;
    static final String[] TN = {"4 Bytes", "2 Bytes", "1 Byte", "8 Bytes", "Float", "Double", "Text", "Array of bytes"};
    static final String[] TC = {"i32", "i16", "i8", "i64", "f32", "f64", "str", "aob"};
    static final String[] SN = {"Exact value", "Bigger than", "Smaller than", "Between", "Unknown initial value", "Increased", "Decreased", "Changed", "Unchanged", "Increased by", "Decreased by"};
    static final String[] SC = {"exact", "gt", "lt", "between", "unknown", "inc", "dec", "chg", "unchg", "incby", "decby"};

    static class E {
        String d, a, t, v = "?";
        boolean f;
        public String toString() { return (f ? "[x] " : "[ ] ") + d + "   " + a + " (" + t + ")   =   " + v; }
    }
    interface Job { void run() throws Exception; }
    interface Cb { void run(String s); }

    Process su;
    BufferedWriter wr;
    BufferedReader rd;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final List<E> table = new ArrayList<>();
    final List<String> all = new ArrayList<>();
    final LinearLayout[] panels = new LinearLayout[4];
    TextView attached, status, hexView;
    ArrayAdapter<String> procAd, resAd;
    ArrayAdapter<E> tabAd;
    EditText filter, val, val2, memAddr, memWrite;
    Spinner typeSp, scanSp;
    boolean useShizuku = false;
    Button shz;
    int tab = 0;
    long mem = 0;
    String pid = null;
    final Runnable tick = new Runnable() {
        public void run() { if (tab == 2) refreshTable(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = col();
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(28), dp(12), dp(8));
        TextView title = tv("Cheat Engine", 20, TX);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        FrameLayout fr = new FrameLayout(this);
        for (int i = 0; i < 4; i++) { panels[i] = col(); fr.addView(panels[i]); }
        root.addView(fr, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(row(tabBtn("Apps", 0), tabBtn("Scan", 1), tabBtn("List", 2), tabBtn("Memory", 3)));
        setContentView(root);

        // Apps
        filter = ed("Filter apps", "");
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            public void afterTextChanged(Editable e) { }
        });
        procAd = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        ListView pl = new ListView(this);
        pl.setAdapter(procAd);
        pl.setOnItemClickListener((pa, vw, pos, id) -> attach(procAd.getItem(pos)));
        panels[0].addView(btn("Connect (root) & load apps", x -> loadProcs()));
        shz = btn("Mode: su root (tap to use Shizuku)", x -> toggleShizuku());
        panels[0].addView(shz);
        panels[0].addView(filter);
        panels[0].addView(pl, new LinearLayout.LayoutParams(-1, 0, 1));

        // Scan
        attached = tv("Not attached. Pick an app in the Apps tab.", 13, MU);
        typeSp = sp(TN);
        scanSp = sp(SN);
        val = ed("Value (or text / hex bytes like 1E ?? 00)", "");
        val2 = ed("To value (only for Between)", "");
        status = tv("", 13, MU);
        resAd = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        ListView rl = new ListView(this);
        rl.setAdapter(resAd);
        rl.setOnItemClickListener((pa, vw, pos, id) -> {
            final String a = resAd.getItem(pos).split(" ")[0];
            new AlertDialog.Builder(this).setItems(new String[]{"Add to address list", "Change value", "View in memory"}, (dg, k) -> {
                if (k == 0) add(a);
                else if (k == 1) prompt("New value", "", s -> bg(() -> { ask("set|" + a + "|" + curType() + "|" + s); refresh(); }));
                else viewMem(a);
            }).show();
        });
        for (View v : new View[]{attached, typeSp, scanSp, val, val2, row(btn("First Scan", x -> scan(true)), btn("Next Scan", x -> scan(false))), status})
            panels[1].addView(v);
        panels[1].addView(rl, new LinearLayout.LayoutParams(-1, 0, 1));

        // Address list
        tabAd = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, table);
        ListView tl = new ListView(this);
        tl.setAdapter(tabAd);
        tl.setOnItemClickListener((pa, vw, pos, id) -> {
            final E e = table.get(pos);
            new AlertDialog.Builder(this).setTitle(e.d).setItems(new String[]{e.f ? "Unfreeze" : "Freeze", "Change value", "Rename", "Delete", "View in memory"}, (dg, k) -> {
                if (k == 0) toggle(e);
                else if (k == 1) prompt("New value", e.v, s -> bg(() -> { ask("set|" + e.a + "|" + e.t + "|" + s); if (e.f) ask("freeze|" + e.a + "|" + e.t + "|" + s); }));
                else if (k == 2) prompt("Name", e.d, s -> { e.d = s; tabAd.notifyDataSetChanged(); });
                else if (k == 3) { if (e.f) bg(() -> ask("unfreeze|" + e.a)); table.remove(e); tabAd.notifyDataSetChanged(); }
                else viewMem(e.a);
            }).show();
        });
        panels[2].addView(row(btn("Add address", x -> prompt("Address (hex)", "", s -> add(s))), btn("Save table", x -> save()), btn("Load table", x -> load())));
        panels[2].addView(tv("Tap an entry: freeze, change value, rename, delete.", 12, MU));
        panels[2].addView(tl, new LinearLayout.LayoutParams(-1, 0, 1));

        // Memory viewer
        memAddr = ed("Address (hex)", "");
        memWrite = ed("Hex bytes to write, e.g. 63 00 00 00", "");
        hexView = tv("", 11, TX);
        hexView.setTypeface(Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(hexView);
        panels[3].addView(memAddr);
        panels[3].addView(row(btn("Go", x -> goMem(0)), btn("Prev", x -> goMem(-128)), btn("Next", x -> goMem(128))));
        panels[3].addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        panels[3].addView(memWrite);
        panels[3].addView(btn("Write bytes at address", x -> writeBytes()));

        show(0);
        ui.postDelayed(tick, 1000);
    }

    // ---- helpers ----
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    TextView tv(String t, int sp, int c) { TextView x = new TextView(this); x.setText(t); x.setTextSize(sp); x.setTextColor(c); return x; }
    EditText ed(String hint, String t) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(t); e.setTextColor(TX); e.setHintTextColor(MU); e.setSingleLine(true);
        return e;
    }
    Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextColor(AC); b.setBackgroundColor(PN); b.setOnClickListener(l);
        return b;
    }
    Button tabBtn(String t, int i) { return btn(t, x -> show(i)); }
    Spinner sp(String[] o) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, o));
        return s;
    }
    LinearLayout row(View... vs) {
        LinearLayout l = new LinearLayout(this);
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            l.addView(v, lp);
        }
        return l;
    }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    String curType() { return TC[typeSp.getSelectedItemPosition()]; }
    void prompt(String title, String init, Cb cb) {
        final EditText e = ed("", init);
        new AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK", (dg, k) -> cb.run(e.getText().toString().trim()))
            .setNegativeButton("Cancel", null).show();
    }
    void show(int i) {
        tab = i;
        for (int j = 0; j < 4; j++) panels[j].setVisibility(j == i ? View.VISIBLE : View.GONE);
        if (i == 2) refreshTable();
    }

    // ---- root connection ----
    void bg(Job j) {
        ex.submit(() -> {
            try { j.run(); } catch (Exception e) { ui.post(() -> toast("Error: " + e.getMessage())); }
        });
    }

    List<String> ask(String cmd) throws IOException {
        if (su == null) {
            su = startServer();
            wr = new BufferedWriter(new OutputStreamWriter(su.getOutputStream()));
            rd = new BufferedReader(new InputStreamReader(su.getInputStream()));
        }
        wr.write(cmd + "\n");
        wr.flush();
        List<String> out = new ArrayList<>();
        String l;
        while ((l = rd.readLine()) != null && !l.equals("END")) out.add(l);
        if (l == null) { su = null; throw new IOException("Root server stopped. Is the phone rooted?"); }
        return out;
    }

    Process startServer() throws IOException {
        String cmd = "CLASSPATH=" + getPackageCodePath() + " exec app_process /system/bin com.cheatdroid.RootServer";
        if (!useShizuku) return new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        try {
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
        } catch (Exception e) { throw new IOException("Shizuku failed: " + e); }
    }

    void toggleShizuku() {
        if (su != null) { su.destroy(); su = null; }
        if (useShizuku) { useShizuku = false; shz.setText("Mode: su root (tap to use Shizuku)"); return; }
        try {
            if (!Shizuku.pingBinder()) { toast("Open Shizuku and start it first"); return; }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1);
                toast("Allow Cheat Engine in Shizuku, then tap again");
                return;
            }
            useShizuku = true;
            shz.setText("Mode: Shizuku (tap to go back to su)");
            toast(Shizuku.getUid() == 0 ? "Shizuku is running as root" : "Shizuku is in ADB mode, NOT root: scanning other apps will fail");
        } catch (Throwable t) { toast("Shizuku error: " + t.getMessage()); }
    }

    // ---- apps ----
    void loadProcs() {
        bg(() -> {
            final List<String> o = ask("procs");
            ui.post(() -> {
                all.clear();
                for (String s : o) { int i = s.indexOf(' '); if (i > 0) all.add(s.substring(i + 1) + " (" + s.substring(0, i) + ")"); }
                Collections.sort(all);
                applyFilter();
                toast(all.size() + " apps found");
            });
        });
    }

    void applyFilter() {
        String q = filter.getText().toString().toLowerCase();
        procAd.clear();
        for (String s : all) if (s.toLowerCase().contains(q)) procAd.add(s);
    }

    void attach(final String item) {
        final String p = item.substring(item.lastIndexOf('(') + 1, item.lastIndexOf(')'));
        bg(() -> {
            ask("attach|" + p);
            ui.post(() -> { pid = p; attached.setText("Attached: " + item); show(1); });
        });
    }

    // ---- scan ----
    void scan(boolean first) {
        if (pid == null) { toast("Attach an app first"); return; }
        final String cmd = (first ? "first|" : "next|") + curType() + "|" + SC[scanSp.getSelectedItemPosition()] + "|" + val.getText().toString().trim() + "|" + val2.getText().toString().trim();
        status.setText("Scanning...");
        bg(() -> {
            List<String> o = ask(cmd);
            refresh();
            if (!o.isEmpty()) { final String m = o.get(0); ui.post(() -> status.setText(m)); }
        });
    }

    void refresh() throws IOException {
        final List<String> o = ask("list");
        ui.post(() -> {
            resAd.clear();
            for (String s : o) {
                if (s.startsWith("COUNT")) status.setText(s.replace("COUNT", "Found:") + " (showing first 100)");
                else if (s.startsWith("ERR")) status.setText(s);
                else resAd.add(s);
            }
        });
    }

    // ---- address list ----
    void add(final String addr) {
        final String t = curType();
        if (t.equals("str") || t.equals("aob")) { toast("List supports number types only. Use Memory tab."); return; }
        prompt("Description", "No description", s -> {
            E e = new E();
            e.d = s; e.a = addr; e.t = t;
            table.add(e);
            tabAd.notifyDataSetChanged();
            toast("Added to list");
        });
    }

    void toggle(final E e) {
        if (e.f) { e.f = false; bg(() -> ask("unfreeze|" + e.a)); }
        else if (e.v.equals("?")) toast("Value not readable");
        else { e.f = true; final String v = e.v; bg(() -> ask("freeze|" + e.a + "|" + e.t + "|" + v)); }
        tabAd.notifyDataSetChanged();
    }

    void refreshTable() {
        if (table.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (E e : table) sb.append(e.a).append(':').append(e.t).append(',');
        final String args = sb.toString();
        bg(() -> {
            final List<String> o = ask("get|" + args);
            ui.post(() -> {
                for (int i = 0; i < o.size() && i < table.size(); i++) table.get(i).v = o.get(i);
                tabAd.notifyDataSetChanged();
            });
        });
    }

    File tf() { return new File(getFilesDir(), "table.txt"); }

    void save() {
        try (PrintWriter pw = new PrintWriter(tf())) {
            for (E e : table) pw.println(e.d.replace('|', ' ') + "|" + e.a + "|" + e.t);
            toast("Table saved");
        } catch (Exception e) { toast("Save failed"); }
    }

    void load() {
        try (BufferedReader r = new BufferedReader(new FileReader(tf()))) {
            table.clear();
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\\|");
                if (p.length < 3) continue;
                E e = new E();
                e.d = p[0]; e.a = p[1]; e.t = p[2];
                table.add(e);
            }
            tabAd.notifyDataSetChanged();
            toast("Table loaded");
        } catch (Exception e) { toast("No saved table"); }
    }

    // ---- memory viewer ----
    void viewMem(String a) {
        try { mem = Long.parseLong(a.trim(), 16); } catch (Exception e) { toast("Bad address"); return; }
        memAddr.setText(a);
        show(3);
        showMem();
    }

    void goMem(long delta) {
        try {
            mem = Long.parseLong(memAddr.getText().toString().trim(), 16) + delta;
            memAddr.setText(Long.toHexString(mem));
            showMem();
        } catch (Exception e) { toast("Bad address"); }
    }

    void showMem() {
        final long base = mem;
        bg(() -> {
            List<String> o = ask("hex|" + Long.toHexString(base) + "|128");
            String h = o.isEmpty() ? "" : o.get(0);
            StringBuilder sb = new StringBuilder();
            if (h.startsWith("ERR") || h.length() < 16) sb.append("Unreadable memory");
            else {
                for (int i = 0; i + 16 <= h.length(); i += 16) {
                    if (i > 0) sb.append('\n');
                    sb.append(String.format("%010x ", base + i / 2));
                    StringBuilder as = new StringBuilder();
                    for (int j = 0; j < 16; j += 2) {
                        int bt = Integer.parseInt(h.substring(i + j, i + j + 2), 16);
                        sb.append(h, i + j, i + j + 2).append(' ');
                        as.append(bt > 31 && bt < 127 ? (char) bt : '.');
                    }
                    sb.append(' ').append(as);
                }
            }
            final String txt = sb.toString();
            ui.post(() -> hexView.setText(txt));
        });
    }

    void writeBytes() {
        final String hx = memWrite.getText().toString().trim();
        try {
            final long a = Long.parseLong(memAddr.getText().toString().trim(), 16);
            bg(() -> { ask("wbytes|" + Long.toHexString(a) + "|" + hx); showMem(); });
        } catch (Exception e) { toast("Bad address"); }
    }
}
