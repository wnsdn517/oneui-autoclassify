package com.auto.classify.hook;

import android.os.SystemClock;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Remembers which apps this module filed and where. If the user moves one out of its folder
 * the app becomes "detached" and is left alone; moving it back into that folder re-attaches it.
 * Keyed by package name; stored in the launcher's files dir.
 */
final class Tracker {

    static final class Entry {
        String folder;
        int folderId;
        int itemId = -1;
        boolean detached;
    }

    private static final long OWN_CHANGE_WINDOW_MS = 4000;

    private final AtomicFile file;
    private final Map<String, Entry> entries = new HashMap<>();
    /** Packages we are moving right now; DB updates for them are ours, not the user's. */
    private final Map<String, Long> ownChanges = new HashMap<>();

    Tracker(File dir) {
        file = new AtomicFile(new File(dir, "autoclassify_tracking.json"));
        load();
    }

    synchronized boolean isDetached(String pkg) {
        Entry e = entries.get(pkg);
        return e != null && e.detached;
    }

    synchronized void beginOwnChange(String pkg) {
        ownChanges.put(pkg, SystemClock.uptimeMillis() + OWN_CHANGE_WINDOW_MS);
    }

    synchronized void filed(String pkg, String folder, int folderId) {
        Entry e = new Entry();
        e.folder = folder;
        e.folderId = folderId;
        entries.put(pkg, e);
        save();
    }

    /**
     * Called for every apps-screen item update the launcher writes.
     * @param inFolderId folder id the item now sits in, or -1 if it is top level
     * @return a short description of a state change, or null
     */
    synchronized String onItemUpdated(String pkg, int itemId, int inFolderId) {
        Entry e = entries.get(pkg);
        if (e == null) return null;
        Long until = ownChanges.get(pkg);
        if (until != null && SystemClock.uptimeMillis() < until) {
            if (inFolderId == e.folderId) e.itemId = itemId;
            save();
            return null;
        }
        if (e.folderId == -1) {
            // Filed by a DB-level organize: learn the folder id from the first sighting.
            if (inFolderId == -1) return null;
            e.folderId = inFolderId;
            e.itemId = itemId;
            save();
            return null;
        }
        if (e.itemId != -1 && e.itemId != itemId) return null; // another copy (e.g. home screen)
        if (!e.detached && inFolderId != e.folderId) {
            e.detached = true;
            save();
            return pkg + " 직접 폴더 밖으로 옮김";
        }
        if (e.detached && inFolderId == e.folderId) {
            e.detached = false;
            save();
            return pkg + " 다시 '" + e.folder + "' 폴더로";
        }
        return null;
    }

    synchronized void forget(String pkg) {
        if (entries.remove(pkg) != null) save();
    }

    synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> me : entries.entrySet()) {
            Entry e = me.getValue();
            sb.append(me.getKey()).append(" -> ").append(e.folder).append('#').append(e.folderId)
                    .append(e.detached ? " (detached)" : "").append('\n');
        }
        return sb.toString();
    }

    private void load() {
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject o = root.getJSONObject(pkg);
                Entry e = new Entry();
                e.folder = o.optString("folder");
                e.folderId = o.optInt("folderId", -1);
                e.itemId = o.optInt("itemId", -1);
                e.detached = o.optBoolean("detached");
                entries.put(pkg, e);
            }
        } catch (Exception ignored) {
            // first run or unreadable file: start empty
        }
    }

    private void save() {
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                Entry e = me.getValue();
                root.put(me.getKey(), new JSONObject()
                        .put("folder", e.folder)
                        .put("folderId", e.folderId)
                        .put("itemId", e.itemId)
                        .put("detached", e.detached));
            }
            out = file.startWrite();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception ex) {
            if (out != null) file.failWrite(out);
        }
    }
}
