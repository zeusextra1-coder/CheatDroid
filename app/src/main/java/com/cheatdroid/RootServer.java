package com.cheatdroid;

import java.io.*;
import java.nio.*;

/** Runs as root via app_process. Talks line-based over stdin/stdout. */
public class RootServer {
    static long[] addrs = new long[1024];
    static int n = 0, pid = -1, size = 4;
    static long target;
    static String type = "i32";

    public static void main(String[] a) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintStream out = new PrintStream(new BufferedOutputStream(System.out), false);
        String line;
        while ((line = in.readLine()) != null) {
            try { handle(line.split(" "), out); } catch (Throwable t) { out.println("ERR " + t); }
            out.println("END");
            out.flush();
        }
    }

    static void handle(String[] c, PrintStream out) throws Exception {
        switch (c[0]) {
            case "procs":
                String[] d = new File("/proc").list();
                for (String s : d) {
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
            case "first": pid = Integer.parseInt(c[1]); setType(c[2], c[3]); scan(); break;
            case "next": setType(c[1], c[2]); filter(); break;
            case "list":
                out.println("COUNT " + n);
                try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
                    for (int i = 0; i < Math.min(n, 100); i++) {
                        try { out.println(Long.toHexString(addrs[i]) + " " + dec(read(f, addrs[i]))); }
                        catch (IOException e) { out.println(Long.toHexString(addrs[i]) + " ?"); }
                    }
                }
                break;
            case "set":
                long bits = enc(type, c[2]);
                byte[] b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array();
                try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "rw")) {
                    f.seek(Long.parseLong(c[1], 16));
                    f.write(b, 0, size);
                }
                break;
        }
    }

    static void setType(String t, String v) {
        type = t;
        size = (t.equals("i32") || t.equals("f32")) ? 4 : 8;
        target = enc(t, v);
    }

    static long enc(String t, String v) {
        switch (t) {
            case "i32": return Integer.parseInt(v) & 0xffffffffL;
            case "i64": return Long.parseLong(v);
            case "f32": return Float.floatToIntBits(Float.parseFloat(v)) & 0xffffffffL;
            default: return Double.doubleToLongBits(Double.parseDouble(v));
        }
    }

    static String dec(long b) {
        switch (type) {
            case "i32": return String.valueOf((int) b);
            case "i64": return String.valueOf(b);
            case "f32": return String.valueOf(Float.intBitsToFloat((int) b));
            default: return String.valueOf(Double.longBitsToDouble(b));
        }
    }

    static long read(RandomAccessFile f, long addr) throws IOException {
        byte[] b = new byte[8];
        f.seek(addr);
        f.readFully(b, 0, size);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        return size == 4 ? bb.getInt() & 0xffffffffL : bb.getLong();
    }

    static void add(long a) {
        if (n >= 5000000) return;
        if (n == addrs.length) addrs = java.util.Arrays.copyOf(addrs, n * 2);
        addrs[n++] = a;
    }

    static void scan() throws Exception {
        n = 0;
        byte[] buf = new byte[1 << 20];
        try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r");
             BufferedReader m = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
            String l;
            while ((l = m.readLine()) != null) {
                String[] p = l.split("\\s+");
                if (p.length < 2 || !p[1].startsWith("rw") || l.contains("/dev/") || l.contains("[vvar")) continue;
                String[] r = p[0].split("-");
                long s = Long.parseLong(r[0], 16), e = Long.parseLong(r[1], 16);
                for (long a = s; a < e; a += buf.length) {
                    int len = (int) Math.min(buf.length, e - a);
                    try { f.seek(a); f.readFully(buf, 0, len); } catch (IOException x) { continue; }
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i + size <= len; i += size) {
                        long v = size == 4 ? bb.getInt(i) & 0xffffffffL : bb.getLong(i);
                        if (v == target) add(a + i);
                    }
                }
            }
        }
    }

    static void filter() throws Exception {
        int k = 0;
        try (RandomAccessFile f = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            for (int i = 0; i < n; i++) {
                try { if (read(f, addrs[i]) == target) addrs[k++] = addrs[i]; } catch (IOException e) { }
            }
        }
        n = k;
    }
}
