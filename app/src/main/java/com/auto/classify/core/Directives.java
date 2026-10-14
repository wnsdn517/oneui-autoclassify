package com.auto.classify.core;

/**
 * Layout directives: rule lines starting with '!'. They change how the apps screen is
 * arranged after apps are filed, rather than where a particular app goes.
 *
 *   !폴더안정렬 이름|색상|설치일|그대로    order of apps inside every folder
 *   !앱정렬 이름|색상|설치일|그대로        order of apps outside folders
 *   !폴더정렬 이름|앱수|그대로             order of folders among themselves
 *   !배치 폴더먼저|앱먼저|그대로           folders before loose apps, or the reverse
 *   !최소앱 N                              apps needed before a new folder is made
 *   !폴더색 아이콘|카테고리|알록달록|기본|그대로  color folders by their apps' icons, by category, each differently, or reset
 */
public final class Directives {

    public static final String KEEP = "그대로";
    public static final String NAME = "이름";
    public static final String COLOR = "색상";
    public static final String INSTALLED = "설치일";
    public static final String SIZE = "앱수";
    public static final String FOLDERS_FIRST = "폴더먼저";
    public static final String ICON = "아이콘";
    public static final String CATEGORY = "카테고리";
    public static final String RESET = "기본";
    /** Every folder a different color, in rainbow order. */
    public static final String RAINBOW = "알록달록";
    public static final String APPS_FIRST = "앱먼저";

    public static final String D_INSIDE = "폴더안정렬";
    public static final String D_LOOSE = "앱정렬";
    public static final String D_FOLDERS = "폴더정렬";
    public static final String D_PLACEMENT = "배치";
    public static final String D_MIN_APPS = "최소앱";
    public static final String D_FOLDER_COLOR = "폴더색";

    public String insideSort = KEEP;
    public String looseSort = KEEP;
    public String folderSort = KEEP;
    public String placement = KEEP;
    /** -1 = use the setting. */
    public int minApps = -1;
    public String folderColor = KEEP;

    public boolean needsColor() {
        return COLOR.equals(insideSort) || COLOR.equals(looseSort) || COLOR.equals(folderSort);
    }

    public boolean needsInstallTime() {
        return INSTALLED.equals(insideSort) || INSTALLED.equals(looseSort);
    }

    public static Directives parse(String rulesText) {
        Directives d = new Directives();
        if (rulesText == null) return d;
        for (String raw : rulesText.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("!")) continue;
            String[] kv = line.substring(1).trim().split("\\s+", 2);
            if (kv.length < 2) continue;
            String v = kv[1].trim();
            switch (kv[0]) {
                case D_INSIDE:
                    d.insideSort = v;
                    break;
                case D_LOOSE:
                    d.looseSort = v;
                    break;
                case D_FOLDERS:
                    d.folderSort = v;
                    break;
                case D_PLACEMENT:
                    d.placement = v;
                    break;
                case D_FOLDER_COLOR:
                    d.folderColor = v;
                    break;
                case D_MIN_APPS:
                    try {
                        d.minApps = Math.max(1, Integer.parseInt(v));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                default:
            }
        }
        return d;
    }

    /** @return an error for a malformed directive line, or null. */
    public static String validateLine(String line) {
        String[] kv = line.substring(1).trim().split("\\s+", 2);
        String key = kv[0];
        String v = kv.length > 1 ? kv[1].trim() : "";
        switch (key) {
            case D_INSIDE:
            case D_LOOSE:
                return oneOf(key, v, NAME, COLOR, INSTALLED, KEEP);
            case D_FOLDERS:
                return oneOf(key, v, NAME, COLOR, SIZE, KEEP);
            case D_PLACEMENT:
                return oneOf(key, v, FOLDERS_FIRST, APPS_FIRST, KEEP);
            case D_MIN_APPS:
                return v.matches("\\d+") && Integer.parseInt(v) >= 1 ? null : "!최소앱 뒤에는 1 이상의 숫자가 와야 합니다";
            case D_FOLDER_COLOR:
                return oneOf(key, v, ICON, CATEGORY, RAINBOW, RESET, KEEP);
            default:
                return "알 수 없는 지시문 '!" + key + "' (가능: !폴더안정렬, !앱정렬, !폴더정렬, !배치, !최소앱, !폴더색)";
        }
    }

    private static String oneOf(String key, String v, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return null;
        return "!" + key + " 값은 " + String.join(" / ", allowed) + " 중 하나여야 합니다";
    }

    /** Serialized form passed from the classifier to the launcher. */
    public String[] toArray() {
        return new String[]{insideSort, looseSort, folderSort, placement, String.valueOf(minApps), folderColor};
    }

    public static Directives fromArray(String[] a) {
        Directives d = new Directives();
        if (a == null || a.length < 5) return d;
        d.insideSort = a[0];
        d.looseSort = a[1];
        d.folderSort = a[2];
        d.placement = a[3];
        try {
            d.minApps = Integer.parseInt(a[4]);
        } catch (NumberFormatException ignored) {
        }
        if (a.length > 5) d.folderColor = a[5];
        return d;
    }
}
