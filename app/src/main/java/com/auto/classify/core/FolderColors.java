package com.auto.classify.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Folder colors. One UI stores a folder's color as ARGB in item.color with bit 8 of
 * item.options set for a custom color; 0 with the bit clear is the default color.
 */
public final class FolderColors {

    /** "No color": the launcher's default folder look. */
    public static final int DEFAULT = 0;
    /** item.options flag for a custom (picker) color. */
    public static final int CUSTOM_FLAG = 8;

    /** Soft, muted tones: folder colors should tint, not shout. */
    private static final Map<String, Integer> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("빨강", 0xFFE8B4B4);
        NAMES.put("주황", 0xFFEDC9A8);
        NAMES.put("노랑", 0xFFEDE0A8);
        NAMES.put("초록", 0xFFB9D8B5);
        NAMES.put("청록", 0xFFAED6D6);
        NAMES.put("파랑", 0xFFB3C8E6);
        NAMES.put("보라", 0xFFCDBBE0);
        NAMES.put("분홍", 0xFFE8BCD0);
        NAMES.put("회색", 0xFFCFCFD2);
        NAMES.put("흰색", 0xFFF2F2F2);
        NAMES.put("검정", 0xFF4A4A4E);
    }

    /** Category folder -> its color for "!폴더색 카테고리". */
    private static final Map<String, String> CATEGORY = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"게임", "보라"}, {"소통", "초록"}, {"소셜", "분홍"}, {"인터넷", "파랑"}, {"사진·디자인", "주황"},
                {"동영상", "빨강"}, {"음악", "분홍"}, {"엔터테인먼트", "보라"}, {"생산성", "파랑"}, {"AI", "보라"}, {"미디어", "빨강"}, {"생활", "주황"}, {"도구", "회색"},
                {"꾸미기", "청록"}, {"금융", "초록"}, {"쇼핑", "주황"}, {"음식", "주황"}, {"건강", "초록"},
                {"교통·지도", "청록"}, {"여행", "청록"}, {"날씨", "파랑"}, {"뉴스", "회색"}, {"책·학습", "노랑"},
                {"라이프", "노랑"}, {"스포츠", "빨강"}, {"스토어", "파랑"}, {"루팅·모듈", "검정"}, {"기타", "회색"},
        };
        for (String[] p : pairs) CATEGORY.put(p[0], p[1]);
    }

    private FolderColors() {
    }

    /** "빨강", "빨간색", "#FF5722", "FF5722", "기본" -> ARGB (DEFAULT for 기본); null if unknown. */
    public static Integer parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace("색", "").replace("#", "").trim();
        if (t.isEmpty()) return null;
        if (t.equals("기본") || t.equals("없음") || t.equals("default")) return DEFAULT;
        if (t.matches("[0-9a-f]{6}")) return 0xFF000000 | Integer.parseInt(t, 16);
        if (t.matches("[0-9a-f]{8}")) return (int) Long.parseLong(t, 16);
        for (Map.Entry<String, Integer> e : NAMES.entrySet()) {
            String n = e.getKey();
            // 빨강/빨간, 노랑/노란, 파랑/파란, 초록, 보라, 분홍, 회, 흰, 검정/검은
            if (t.equals(n) || t.startsWith(n.substring(0, 1)) && t.length() <= 3) return e.getValue();
        }
        return null;
    }

    public static Integer forCategory(String folder) {
        String name = CATEGORY.get(folder);
        if (name == null) {
            // Color-mode folders are named after their color.
            Integer direct = NAMES.get(folder == null ? "" : folder.replaceAll("\\s*\\d+$", "")
                    .replaceAll("^(밝은|짙은)\\s*", "").replace("흑백", "회색"));
            return direct;
        }
        return NAMES.get(name);
    }

    private static final String[] RAINBOW = {"빨강", "주황", "노랑", "초록", "청록", "파랑", "보라", "분홍"};

    /** The i-th folder's color for "!폴더색 알록달록": rainbow order, repeating. */
    public static int rainbow(int i) {
        return NAMES.get(RAINBOW[Math.floorMod(i, RAINBOW.length)]);
    }

    public static String[] names() {
        return NAMES.keySet().toArray(new String[0]);
    }

    public static int nameColor(String name) {
        Integer c = NAMES.get(name);
        return c == null ? DEFAULT : c;
    }
}
