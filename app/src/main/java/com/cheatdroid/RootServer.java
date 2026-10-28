package com.cheatdroid;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs as root via app_process. Line protocol, fields separated by '|'. */
public class RootServer {
    static long[] addrs = new long[1024], vals = new long[1024];
    static int n = 0, pid = -1, size = 4, plen = 0;
    static boolean capped = false;
    static String type = "i32";
    static long target;
    static double v1, v2;
    static byte[] pat;
    static boolean[] wild;
    static final int CAP = 3000000, CH = 1 << 20;
    static final ConcurrentHashMap<Long, byte[]> frozen = new ConcurrentHashMap<>();

    public static void main(String[] a) throws Exception {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(50);
                    if (pid > 0 && !frozen.isEmpty()) {
                        try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "rw")) {
                            for (Map.Entry<Long, byte[]> e : frozen.entrySet()) { f.seek(e.getKey()); f.write(e.getValue()); }
                        }
                    }
                } catch (Exception x) { }
            }
        });
        t.setDaemon(true);
        t.start();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintStream out = new PrintStream(new BufferedOutputStream(System.out), false);
        String line;
        while ((line = in.readLine()) != null) {
            try { handle(line.split("\\|", -1), out); } catch (Throwable x) { out.println("ERR " + x); }
            out.println("END");
            out.flush();
        }
    }

    static void handle(String[] c, PrintStream out) throws Exception {
        switch (c[0]) {
            case "procs": {
                for (String s : new File("/proc").list()) {
                    if (!s.matches("\\d+")) continue;
                    try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + s + "/cmdline"))) {
                        String name = r.readLine();
                        if (name == null) continue;
                        int z = name.indexOf('\0');
                        if (z >= 0) name = name.substring(0, z);
                        if (name.contains(".") && !name.startsWith("/")) out.println(s + " " + name);
                    } catch (IOException e) { }
                }
                break;
            }
            case "attach": pid = Integer.parseInt(c[1]); frozen.clear(); n = 0; break;
            case "first": first(c[1], c[2], c[3], c[4]); break;
            case "next": next(c[2], c[3], c[4]); break;
            case "list": {
                out.println("COUNT " + n + (capped ? "+" : ""));
                try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
                    boolean isPat = type.equals("str") || type.equals("aob");
                    for (int i = 0; i < Math.min(n, 100); i++) {
                        String v;
                        try {
                            if (isPat) { byte[] b = new byte[plen]; f.seek(addrs[i]); f.readFully(b); v = hexs(b); }
                            else v = dec(type, rd(f, addrs[i], size));
                        } catch (IOException e) { v = "?"; }
                        out.println(Long.toHexString(addrs[i]) + " " + v);
                    }
                }
                break;
            }
            case "get": {
                try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
                    for (String it : c[1].split(",")) {
                        if (it.isEmpty()) continue;
                        String[] p = it.split(":");
                        String v;
                        try { v = dec(p[1], rd(f, Long.parseLong(p[0], 16), sz(p[1]))); } catch (IOException e) { v = "?"; }
                        out.println(v);
                    }
                }
                break;
            }
            case "set": writeAt(Long.parseLong(c[1], 16), bytesOf(c[2], c[3])); break;
            case "freeze": frozen.put(Long.parseLong(c[1], 16), bytesOf(c[2], c[3])); break;
            case "unfreeze": frozen.remove(Long.parseLong(c[1], 16)); break;
            case "hex": {
                byte[] b = new byte[Integer.parseInt(c[2])];
                try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r")) { f.seek(Long.parseLong(c[1], 16)); f.readFully(b); }
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x & 0xff));
                out.println(sb);
                break;
            }
            case "wbytes": writeAt(Long.parseLong(c[1], 16), hexb(c[2])); break;
        }
    }

    static void writeAt(long addr, byte[] b) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "rw")) { f.seek(addr); f.write(b); }
    }

    static int sz(String t) {
        switch (t) { case "i8": return 1; case "i16": return 2; case "i32": case "f32": return 4; default: return 8; }
    }

    static long enc(String t, String v) {
        switch (t) {
            case "i8": return Long.parseLong(v) & 0xffL;
            case "i16": return Long.parseLong(v) & 0xffffL;
            case "i32": return Long.parseLong(v) & 0xffffffffL;
            case "i64": return Long.parseLong(v);
            case "f32": return Float.floatToIntBits(Float.parseFloat(v)) & 0xffffffffL;
            default: return Double.doubleToLongBits(Double.parseDouble(v));
        }
    }

    static String dec(String t, long b) {
        switch (t) {
            case "i8": return String.valueOf((byte) b);
            case "i16": return String.valueOf((short) b);
            case "i32": return String.valueOf((int) b);
            case "i64": return String.valueOf(b);
            case "f32": return String.valueOf(Float.intBitsToFloat((int) b));
            default: return String.valueOf(Double.longBitsToDouble(b));
        }
    }

    static double num(String t, long b) {
        switch (t) {
            case "i8": return (byte) b;
            case "i16": return (short) b;
            case "i32": return (int) b;
            case "i64": return b;
            case "f32": return Float.intBitsToFloat((int) b);
            default: return Double.longBitsToDouble(b);
        }
    }

    static byte[] bytesOf(String t, String v) {
        long bits = enc(t, v);
        byte[] b = new byte[sz(t)];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (bits >>> (8 * i));
        return b;
    }

    static byte[] hexb(String s) {
        s = s.replaceAll("\\s", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static String hexs(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x & 0xff));
        return sb.toString().trim();
    }

    static long rd(RandomAccessFile f, long addr, int s) throws IOException {
        byte[] b = new byte[8];
        f.seek(addr);
        f.readFully(b, 0, s);
        return rdb(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN), 0, s);
    }

    static long rdb(ByteBuffer bb, int i, int s) {
        switch (s) {
            case 1: return bb.get(i) & 0xffL;
            case 2: return bb.getShort(i) & 0xffffL;
            case 4: return bb.getInt(i) & 0xffffffffL;
            default: return bb.getLong(i);
        }
    }

    static void add(long a, long v) {
        if (n >= CAP) { capped = true; return; }
        if (n == addrs.length) { addrs = Arrays.copyOf(addrs, n * 2); vals = Arrays.copyOf(vals, n * 2); }
        addrs[n] = a;
        vals[n++] = v;
    }

    static void buildPat(String t, String a) throws Exception {
        if (t.equals("str")) {
            pat = a.getBytes("UTF-8");
            wild = new boolean[pat.length];
        } else {
            String[] tk = a.trim().split("\\s+");
            pat = new byte[tk.length];
            wild = new boolean[tk.length];
            for (int i = 0; i < tk.length; i++) {
                if (tk[i].startsWith("?")) wild[i] = true; else pat[i] = (byte) Integer.parseInt(tk[i], 16);
            }
        }
        plen = pat.length;
        if (plen == 0) throw new IllegalArgumentException("empty pattern");
    }

    static boolean matchPat(byte[] buf, int i) {
        for (int j = 0; j < plen; j++) if (!wild[j] && buf[i + j] != pat[j]) return false;
        return true;
    }

    static boolean ok1(long v, String st) {
        double d = num(type, v);
        switch (st) {
            case "exact": return v == target;
            case "gt": return d > v1;
            case "lt": return d < v1;
            case "between": return d >= v1 && d <= v2;
            default: return true;
        }
    }

    static void first(String t, String st, String a, String b) throws Exception {
        type = t;
        n = 0;
        capped = false;
        boolean isPat = t.equals("str") || t.equals("aob");
        int need;
        if (isPat) { buildPat(t, a); need = plen; size = 1; }
        else {
            size = sz(t); need = size; target = 0; v1 = 0; v2 = 0;
            if (!a.isEmpty()) { target = enc(t, a); v1 = Double.parseDouble(a); }
            if (!b.isEmpty()) v2 = Double.parseDouble(b);
        }
        byte[] buf = new byte[CH + Math.max(need, 8)];
        try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r");
             BufferedReader m = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
            String l;
            while ((l = m.readLine()) != null) {
                String[] p = l.split("\\s+");
                if (p.length < 2 || !p[1].startsWith("rw") || l.contains("/dev/") || l.contains("[vvar")) continue;
                String[] r = p[0].split("-");
                long s = Long.parseLong(r[0], 16), e = Long.parseLong(r[1], 16);
                for (long a0 = s; a0 < e; a0 += CH) {
                    int len = (int) Math.min(CH + (isPat ? need - 1 : 0), e - a0);
                    try { f.seek(a0); f.readFully(buf, 0, len); } catch (IOException x) { continue; }
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
                    int step = isPat ? 1 : size;
                    for (int i = 0; i < CH && i + need <= len; i += step) {
                        if (isPat) { if (matchPat(buf, i)) add(a0 + i, 0); }
                        else { long v = rdb(bb, i, size); if (ok1(v, st)) add(a0 + i, v); }
                    }
                }
            }
        }
    }

    static void next(String st, String a, String b) throws Exception {
        int k = 0;
        try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            if (type.equals("str") || type.equals("aob")) {
                byte[] bt = new byte[plen];
                for (int i = 0; i < n; i++) {
                    try { f.seek(addrs[i]); f.readFully(bt); if (matchPat(bt, 0)) addrs[k++] = addrs[i]; } catch (IOException e) { }
                }
            } else {
                if (!a.isEmpty()) { target = enc(type, a); v1 = Double.parseDouble(a); }
                if (!b.isEmpty()) v2 = Double.parseDouble(b);
                for (int i = 0; i < n; i++) {
                    try {
                        long c = rd(f, addrs[i], size), p = vals[i];
                        double dc = num(type, c), dp = num(type, p);
                        boolean ok;
                        switch (st) {
                            case "exact": ok = c == target; break;
                            case "gt": ok = dc > v1; break;
                            case "lt": ok = dc < v1; break;
                            case "between": ok = dc >= v1 && dc <= v2; break;
                            case "inc": ok = dc > dp; break;
                            case "dec": ok = dc < dp; break;
                            case "chg": ok = c != p; break;
                            case "unchg": ok = c == p; break;
                            case "incby": ok = Math.abs(dc - dp - v1) < 1e-4; break;
                            case "decby": ok = Math.abs(dp - dc - v1) < 1e-4; break;
                            default: ok = true;
                        }
                        if (ok) { addrs[k] = addrs[i]; vals[k++] = c; }
                    } catch (IOException e) { }
                }
            }
        }
        n = k;
    }
}
