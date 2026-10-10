package com.auto.classify.core;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Google Play category data built by tools/train_model.py:
 * an exact package lookup for popular apps and a Naive Bayes model for the rest.
 * Category ids index {@link #CATEGORIES}; the order must match the training script.
 */
public final class PlayModel {

    public static final String[] CATEGORIES = {
            "Action", "Adventure", "Arcade", "Board", "Card", "Casino", "Casual", "Educational", "Music", "Puzzle",
            "Racing", "Role Playing", "Simulation", "Strategy", "Trivia", "Word",
            "Art & Design", "Auto & Vehicles", "Beauty", "Books & Reference", "Business", "Comics", "Communication",
            "Dating", "Education", "Entertainment", "Events", "Finance", "Food & Drink", "Health & Fitness",
            "House & Home", "Libraries & Demo", "Lifestyle", "Maps & Navigation", "Medical", "Music & Audio",
            "News & Magazines", "Parenting", "Personalization", "Photography", "Productivity", "Shopping", "Social",
            "Sports", "Tools", "Travel & Local", "Video Players & Editors", "Weather",
    };
    public static final int GAME_COUNT = 16;

    /** A category guess with the model's probability (1.0 for an exact lookup hit). */
    public static final class Guess {
        public final int category;
        public final float confidence;
        public final boolean exact;

        Guess(int category, float confidence, boolean exact) {
            this.category = category;
            this.confidence = confidence;
            this.exact = exact;
        }
    }

    private static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList(
            "com", "org", "net", "io", "app", "apps", "android", "mobile", "www", "co", "kr", "jp", "de", "fr",
            "free", "pro", "lite", "the", "and", "for", "of", "a", "an", "my", "release", "prod", "v2"));
    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9]+");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z])(?=[A-Z])");

    // Lookup: '\0'-separated sorted package names, with start offsets for binary search.
    private byte[] names;
    private int[] offsets;
    private byte[] lookupCats;

    // Model
    private float[] priors;
    private Map<String, Integer> features;
    private byte[] weights; // [feature][category], int8
    private float scale;
    private float offset;

    public static PlayModel load(AssetManager assets) throws IOException {
        PlayModel m = new PlayModel();
        m.loadLookup(assets);
        m.loadModel(assets);
        return m;
    }

    private void loadLookup(AssetManager assets) throws IOException {
        try (DataInputStream in = new DataInputStream(assets.open("play_lookup.bin"))) {
            expectMagic(in, "ACL1");
            int count = in.readInt();
            names = inflate(readBytes(in, in.readInt()));
            lookupCats = readBytes(in, count);
            offsets = new int[count];
            int k = 0, start = 0;
            for (int i = 0; i < names.length && k < count; i++) {
                if (names[i] == 0) {
                    offsets[k++] = start;
                    start = i + 1;
                }
            }
        }
    }

    private void loadModel(AssetManager assets) throws IOException {
        try (DataInputStream in = new DataInputStream(assets.open("play_model.bin"))) {
            expectMagic(in, "ACM1");
            int nCat = in.readUnsignedShort();
            int nFeat = in.readInt();
            scale = in.readFloat();
            offset = in.readFloat();
            priors = new float[nCat];
            for (int i = 0; i < nCat; i++) priors[i] = in.readFloat();
            byte[] blob = inflate(readBytes(in, in.readInt()));
            features = new HashMap<>(nFeat * 2);
            int k = 0, start = 0;
            for (int i = 0; i < blob.length && k < nFeat; i++) {
                if (blob[i] == 0) {
                    features.put(new String(blob, start, i - start, StandardCharsets.UTF_8), k++);
                    start = i + 1;
                }
            }
            weights = readBytes(in, nFeat * nCat);
        }
    }

    /** Exact lookup first, then the model. Returns null when the model has nothing to go on. */
    public Guess guess(String pkg, String label) {
        int exact = lookup(pkg);
        if (exact >= 0) return new Guess(exact, 1f, true);
        int nCat = priors.length;
        double[] score = new double[nCat];
        for (int c = 0; c < nCat; c++) score[c] = priors[c];
        int used = 0;
        for (String f : tokens(pkg, label)) {
            Integer idx = features.get(f);
            if (idx == null) continue;
            used++;
            int base = idx * nCat;
            for (int c = 0; c < nCat; c++) score[c] += (weights[base + c] + 127) * scale + offset;
        }
        if (used == 0) return null;
        int best = 0;
        for (int c = 1; c < nCat; c++) if (score[c] > score[best]) best = c;
        double sum = 0;
        for (int c = 0; c < nCat; c++) sum += Math.exp(score[c] - score[best]);
        return new Guess(best, (float) (1.0 / sum), false);
    }

    public static boolean isGame(int category) {
        return category < GAME_COUNT;
    }

    private int lookup(String pkg) {
        byte[] key = pkg.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = offsets.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compare(offsets[mid], key);
            if (cmp == 0) return lookupCats[mid] & 0xff;
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /** Compares the stored name at {@code start} with key, bytewise unsigned (Python sort order). */
    private int compare(int start, byte[] key) {
        int i = 0;
        while (true) {
            int a = names[start + i] & 0xff;
            int b = i < key.length ? key[i] & 0xff : 0;
            if (a == 0 || b == 0) return a - b;
            if (a != b) return a - b;
            i++;
        }
    }

    /** Must match tokens() in tools/train_model.py. */
    static Set<String> tokens(String pkg, String label) {
        Set<String> out = new HashSet<>();
        String[] parts = pkg.split("\\.");
        if (parts.length >= 2) out.add("ns:" + parts[1].toLowerCase(Locale.ROOT));
        for (String part : parts) {
            String norm = CAMEL.matcher(part).replaceAll(" ").toLowerCase(Locale.ROOT).replace('_', ' ');
            Matcher m = TOKEN.matcher(norm);
            while (m.find()) if (!STOP.contains(m.group())) out.add("p:" + m.group());
        }
        if (label != null) {
            Matcher m = TOKEN.matcher(label.toLowerCase(Locale.ROOT));
            while (m.find()) if (!STOP.contains(m.group())) out.add("l:" + m.group());
        }
        return out;
    }

    private static void expectMagic(DataInputStream in, String magic) throws IOException {
        byte[] b = readBytes(in, 4);
        if (!magic.equals(new String(b, StandardCharsets.US_ASCII))) throw new IOException("bad magic " + magic);
    }

    private static byte[] readBytes(DataInputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] buf = new byte[64 * 1024];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                out.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }
}
