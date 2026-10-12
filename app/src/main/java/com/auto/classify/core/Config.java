package com.auto.classify.core;

/** Keys shared by the settings screen and the launcher-side hook. */
public final class Config {
    public static final String MODULE_PKG = "com.auto.classify";
    public static final String LAUNCHER_PKG = "com.sec.android.app.launcher";
    public static final String PREFS = "config";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_RULES = "rules";
    public static final String KEY_CREATE_FOLDERS = "create_folders";
    public static final String KEY_MIN_APPS = "min_apps";
    /** "category" (rules + app category) or "color" (icon hue). */
    public static final String KEY_MODE = "mode";
    public static final String MODE_CATEGORY = "category";
    public static final String MODE_COLOR = "color";
    /** Only the user's rules move apps; no automatic categories. */
    public static final String MODE_RULES = "rules";
    /** Folders the AI chose from the user's instruction (KEY_AI_MAP); rules still win. */
    public static final String MODE_AI = "ai";
    /** AI assignment, JSON {pkg: folder}; Classifier.LOOSE = outside folders. */
    public static final String KEY_AI_MAP = "ai_map";
    /** Ask the AI where a newly installed app goes (AI mode). */
    public static final String KEY_AI_INSTALL = "ai_install";
    /** Seconds between automatic AI requests, and how many per day. */
    public static final String KEY_AI_COOLDOWN = "ai_cooldown";
    public static final String KEY_AI_DAILY = "ai_daily";
    /** The AI's one-line summary of its last organization. */
    public static final String KEY_AI_NOTE = "ai_note";
    /** Pages the AI chose, JSON {folder: page}; Classifier.LOOSE = loose apps. */
    public static final String KEY_AI_PAGES = "ai_pages";
    /** Folder order the AI chose, JSON array of folder names; folders not named follow in their current order. */
    public static final String KEY_AI_ORDER = "ai_order";
    /** Packages never touched, one per line. */
    public static final String KEY_EXCLUDED = "excluded";
    /** When true, apps the user moved out of an auto folder are left alone afterwards. */
    public static final String KEY_SKIP_MOVED = "skip_moved";
    /** Take apps out of automatic folders that no longer fit the current settings. */
    public static final String KEY_CLEAN_STALE = "clean_stale";
    /** What happens to apps no rule or category claims: keep | free | folder | page. */
    public static final String KEY_UNCLASSIFIED = "unclassified";
    public static final String UNCLASSIFIED_KEEP = "keep";
    /** Out of folders, onto pages no rule reserves (e.g. page 1 when rules use page 2). */
    public static final String UNCLASSIFIED_FREE = "free";
    public static final String UNCLASSIFIED_FOLDER = "folder";
    public static final String UNCLASSIFIED_PAGE = "page";
    public static final String KEY_UNCLASSIFIED_FOLDER = "unclassified_folder";
    public static final String KEY_UNCLASSIFIED_PAGE = "unclassified_page";
    /** Fix new apps' page placement when the screen turns off (restarts Home unseen). */
    public static final String KEY_SCREEN_OFF_TIDY = "screen_off_tidy";
    /** Detailed categories (소셜 and 소통 apart, …) instead of the broad default groups. */
    public static final String KEY_DETAILED = "detailed_categories";
    /** Category evidence to use, comma-separated SmartClassifier.SIG_* ids. */
    public static final String KEY_SIGNALS = "signals";
    public static final String DEFAULT_SIGNALS = "roles,play,model,declared";
    /** Minimum combined confidence (percent) before an app is moved. */
    public static final String KEY_THRESHOLD = "threshold";
    public static final int DEFAULT_THRESHOLD = 80;
    /** "기본이름=내이름" lines renaming the default category folders. */
    public static final String KEY_FOLDER_NAMES = "folder_names";
    /** ColorEngine.Method name. */
    public static final String KEY_COLOR_METHOD = "color_method";
    /** Sample point for the POSITION method, percent of icon width/height. */
    public static final String KEY_COLOR_X = "color_x";
    public static final String KEY_COLOR_Y = "color_y";
    /** Number of groups for the SIMILARITY method. */
    public static final String KEY_COLOR_GROUPS = "color_groups";
    /** Last SIMILARITY clustering (JSON), so single installs join the nearest group. */
    public static final String KEY_COLOR_CENTERS = "color_centers";

    /** Classifier provider in the module app, called by the launcher. */
    public static final String CLASSIFIER_AUTHORITY = "com.auto.classify.classifier";

    /** AI rule builder: "offline" (built-in parser) or "online" (the user's API key). */
    public static final String KEY_AI_ENGINE = "ai_engine";
    public static final String KEY_AI_REQUEST = "ai_request";

    /** Comma-separated feature ids the user chose to turn off after a failed self-check. */
    public static final String KEY_DISABLED = "disabled_features";
    /** Signature of the last diagnosis the user dismissed, so it isn't shown again. */
    public static final String KEY_ACKED_HEALTH = "acked_health";

    /** One UI Home versions this module was verified on; others get a warning. */
    public static final String[] TESTED_LAUNCHER_VERSIONS = {"17.0.07.13"};

    /** Broadcast to the launcher. Extras: cmd = reload | reclassify | folders | status. */
    public static final String ACTION_COMMAND = "com.auto.classify.COMMAND";
    public static final String PERMISSION_COMMAND = "com.auto.classify.permission.COMMAND";

    /** Launcher -> module app: result of a command ("message" extra, user facing). */
    public static final String ACTION_RESULT = "com.auto.classify.RESULT";
    /** Launcher -> module app: organize preview, JSON {moves:[{label,pkg,from,to}],created,emptied}. */
    public static final String ACTION_PLAN = "com.auto.classify.PLAN";
    /** Launcher -> module app: backups, JSON [{name, summary:{folders:[{title,apps}],loose}}]. */
    public static final String ACTION_BACKUPS = "com.auto.classify.BACKUPS";
    /** Launcher -> module app: self-diagnosis, JSON {launcherVersion, attached, checks:[...]}. */
    public static final String ACTION_HEALTH = "com.auto.classify.HEALTH";
    /** Launcher -> module app: current apps-screen layout JSON (for the AI prompt). */
    public static final String ACTION_LAYOUT = "com.auto.classify.LAYOUT";
    /** Launcher -> module app: log text for export. */
    public static final String ACTION_LOGS = "com.auto.classify.LOGS";
    public static final String EXTRA_MESSAGE = "message";
    /** Module app -> launcher: optional argument of a command (e.g. backup name). */
    public static final String EXTRA_ARG = "arg";

    public static final String DEFAULT_RULES =
            "# 형식: 폴더이름|토큰,토큰,...  (=라벨 정확히 일치, pkg:패키지 접두사, 폴더이름 - 는 제외)\n"
                    + "# 위에서 먼저 맞는 줄이 우선, 규칙이 없으면 앱이 선언한 카테고리로 분류\n"
                    + "-|com.samsung.knox.securefolder\n"
                    + "Google|pkg:com.google.\n"
                    + "AI|chatgpt,claude,gemini,perplexity,copilot\n";

    private Config() {
    }
}
