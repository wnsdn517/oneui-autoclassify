package com.auto.classify.hook;

import android.content.Context;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Snapshots of the launcher's layout databases, taken from inside the launcher process.
 * Restoring copies a snapshot back and kills the launcher so it reloads from disk.
 */
final class Backup {

    /** Layout DBs per home mode; icon caches are left out on purpose. */
    private static final String[] DBS = {"OneUI.db", "HomeOnly.db", "Easy.db"};
    private static final String[] SUFFIXES = {"", "-wal", "-shm"};
    private static final int KEEP = 10;
    /** Rules and AI instructions saved with a snapshot; restored only when the user asks for it. */
    private static final String INSTRUCTIONS = "instructions.json";

    private final Context context;
    private final File root;

    Backup(Context context) {
        this.context = context;
        this.root = new File(context.getFilesDir(), "autoclassify_backup");
    }

    /**
     * @param instructions the user's rules and AI instructions as JSON (see Config.INSTRUCTION_KEYS),
     *                     saved next to the layout so a restore can bring them back; null for none
     * @return the snapshot name, e.g. 20261010-031500
     */
    String snapshot(String instructions) throws IOException {
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File dir = new File(root, name);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("mkdir " + dir);
        for (String db : DBS) {
            for (String sfx : SUFFIXES) {
                File src = context.getDatabasePath(db + sfx);
                if (src.exists()) copy(src, new File(dir, db + sfx));
            }
        }
        if (instructions != null) {
            try (OutputStream out = new FileOutputStream(new File(dir, INSTRUCTIONS))) {
                out.write(instructions.getBytes(StandardCharsets.UTF_8));
            }
        }
        prune();
        return name;
    }

    /** The instructions saved with a snapshot, or null when it has none (older snapshots). */
    String instructions(String name) {
        File f = new File(new File(root, name), INSTRUCTIONS);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    File file(String name, String db) {
        return new File(new File(root, name), db);
    }

    /**
     * A throwaway copy of a snapshot's OneUI.db (+wal) for reading, so opening it never
     * checkpoints or otherwise touches the snapshot itself.
     */
    File workingCopy(String name) throws IOException {
        File dir = new File(context.getCacheDir(), "autoclassify_preview");
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("mkdir " + dir);
        for (String sfx : SUFFIXES) {
            File out = new File(dir, "OneUI.db" + sfx);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            File src = file(name, "OneUI.db" + sfx);
            if (src.exists() && !sfx.equals("-shm")) copy(src, out);
        }
        return new File(dir, "OneUI.db");
    }

    /** Snapshot names are yyyyMMdd-HHmmss; returns 0 when unparsable. */
    long timeOf(String name) {
        try {
            Date d = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).parse(name);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    String latest() {
        String[] names = list();
        return names.length == 0 ? null : names[names.length - 1];
    }

    String[] list() {
        String[] names = root.list();
        if (names == null) return new String[0];
        Arrays.sort(names);
        return names;
    }

    /** Copies the snapshot over the live DBs and kills the launcher; it does not return on success. */
    void restoreAndRestart(String name) throws IOException {
        File dir = new File(root, name);
        if (!dir.isDirectory()) throw new IOException("no backup " + name);
        for (String db : DBS) {
            File snap = new File(dir, db);
            if (!snap.exists()) continue;
            for (String sfx : SUFFIXES) {
                File live = context.getDatabasePath(db + sfx);
                File from = new File(dir, db + sfx);
                if (from.exists()) copy(from, live);
                else if (live.exists() && !live.delete()) throw new IOException("delete " + live);
            }
        }
        // Room keeps the old pages in memory; a fresh process is the only clean reload.
        Process.killProcess(Process.myPid());
    }

    private void prune() {
        String[] names = list();
        for (int i = 0; i < names.length - KEEP; i++) deleteTree(new File(root, names[i]));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
