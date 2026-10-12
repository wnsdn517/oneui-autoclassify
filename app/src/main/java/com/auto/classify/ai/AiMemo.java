package com.auto.classify.ai;

import android.content.SharedPreferences;

import com.auto.classify.core.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Notes the AI keeps between requests: the user's standing preferences and facts ("루팅 앱은 항상
 * 2페이지", "ViPER4Android는 루팅 모듈"). They are sent with every request, the AI adds or removes
 * them on its own when the user states or withdraws something lasting, and the user can edit them.
 */
public final class AiMemo {

    private static final int MAX_LINES = 40;
    private static final int MAX_LINE = 160;

    private AiMemo() {
    }

    public static List<String> load(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        for (String l : p.getString(Config.KEY_AI_MEMO, "").split("\n")) {
            if (!l.trim().isEmpty()) out.add(l.trim());
        }
        return out;
    }

    /** The notes as an instruction block for the model; empty when there are none. */
    public static String prompt(SharedPreferences p) {
        List<String> notes = load(p);
        if (notes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Saved notes (the user's standing preferences and facts; follow them unless the request says otherwise):\n");
        for (String n : notes) sb.append("- ").append(n).append('\n');
        return sb.append('\n').toString();
    }

    /**
     * Applies the AI's additions and removals into [ed].
     * @return one readable line per real change
     */
    public static List<String> apply(SharedPreferences p, SharedPreferences.Editor ed, List<String> add, List<String> remove) {
        List<String> notes = load(p);
        List<String> changes = new ArrayList<>();
        for (String r : remove) {
            String key = r == null ? "" : r.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            for (int i = notes.size() - 1; i >= 0; i--) {
                if (notes.get(i).toLowerCase(Locale.ROOT).contains(key)) changes.add("메모 삭제: " + notes.remove(i));
            }
        }
        for (String a : add) {
            String n = a == null ? "" : a.replace('\n', ' ').trim();
            if (n.isEmpty()) continue;
            if (n.length() > MAX_LINE) n = n.substring(0, MAX_LINE);
            boolean dup = false;
            for (String old : notes) dup |= old.equalsIgnoreCase(n);
            if (dup) continue;
            notes.add(n);
            changes.add("메모 저장: " + n);
        }
        while (notes.size() > MAX_LINES) notes.remove(0);
        if (!changes.isEmpty()) ed.putString(Config.KEY_AI_MEMO, String.join("\n", notes));
        return changes;
    }
}
