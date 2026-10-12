package com.auto.classify;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import com.auto.classify.core.Config;
import com.auto.classify.core.ModuleStatus;
import com.auto.classify.hook.Diag;
import com.auto.classify.hook.Engine;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point. The engine starts with the launcher's Application so the settings app can
 * always get a diagnosis, even if the apps-screen hooks fail on this launcher version.
 */
public class ModuleMain implements IXposedHookLoadPackage {

    static final String TAG = "AutoClassify";

    /** Apps screen view models: paged (default) and vertical list layouts. */
    private static final String[] APPLIST_VMS = {
            "com.honeyspace.ui.honeypots.applist.viewmodel.ApplistViewModel",
            "com.honeyspace.ui.honeypots.verticalapplist.viewmodel.VerticalApplistViewModel",
    };
    private static final String HONEY_DATA_SOURCE = "com.honeyspace.sdk.database.HoneyDataSource";

    private static Engine engine;
    private static boolean dataSourceHooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (Config.MODULE_PKG.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(ModuleStatus.class, "isHooked", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 상태 표시 연결 실패: " + t);
            }
            return;
        }
        if (!Config.LAUNCHER_PKG.equals(lpparam.packageName)
                || !lpparam.packageName.equals(lpparam.processName)) return;
        ClassLoader cl = lpparam.classLoader;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (engine != null) return;
                try {
                    Context context = ((Application) param.thisObject).getApplicationContext();
                    engine = new Engine(context, cl, ModuleMain::loadPrefs);
                    registerCommandReceiver(context);
                    context.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            engine.onScreenOff();
                        }
                    }, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
                    registerPackageCallback(context);
                    log("시작됨");
                } catch (Throwable t) {
                    Diag.check("engine", Diag.F_CORE, false, Diag.FATAL, "엔진 시작 실패: " + t);
                    log("시작 실패: " + Log.getStackTraceString(t));
                }
            }
        });

        checkSdk(cl);
        hookViewModels(cl);
        loadPrefs(); // sets SingleFolders.enabled before the apps screen loads
        List<String> single = com.auto.classify.hook.SingleFolders.install(cl);
        if (single.isEmpty()) Diag.ok("hook.single", Diag.F_ORGANIZE);
        else Diag.check("hook.single", Diag.F_ORGANIZE, false, Diag.WARN,
                "앱 1개짜리 폴더를 유지할 수 없어요 (홈이 다시 시작될 때 풀림): " + String.join(" / ", single));
    }

    private static void hookViewModels(ClassLoader cl) {
        Class<?> dataSourceIface = XposedHelpers.findClassIfExists(HONEY_DATA_SOURCE, cl);
        List<String> hooked = new ArrayList<>();
        for (String name : APPLIST_VMS) {
            Class<?> vm = XposedHelpers.findClassIfExists(name, cl);
            if (vm == null || dataSourceIface == null) continue;
            try {
                if (XposedBridge.hookAllConstructors(vm, new VmHook(dataSourceIface)).isEmpty()) continue;
                hooked.add(vm.getSimpleName());
            } catch (Throwable t) {
                log("연결 실패 " + name + ": " + t);
            }
        }
        if (hooked.isEmpty()) {
            Diag.check("hook.vm", Diag.F_CORE, false, Diag.FATAL,
                    "앱스 화면 ViewModel을 찾지 못했습니다 (지원하지 않는 One UI 홈 버전)");
        } else {
            Diag.ok("hook.vm", Diag.F_CORE);
            log("앱스 화면 연결 준비: " + hooked);
        }
    }

    /** Verifies the SDK types the live path relies on exist with the expected shapes. */
    private static void checkSdk(ClassLoader cl) {
        String p = "com.honeyspace.sdk.source.entity.";
        try {
            Class<?> folder = XposedHelpers.findClass(p + "FolderItem", cl);
            XposedHelpers.findConstructorExact(p + "ComponentKey", cl, ComponentName.class, UserHandle.class);
            XposedHelpers.findConstructorExact(p + "AddFolderItemEventData", cl,
                    List.class, int.class, folder, boolean.class, boolean.class);
            XposedHelpers.findMethodExact("com.honeyspace.sdk.HoneySharedDataKt", cl, "getEvent",
                    XposedHelpers.findClass("com.honeyspace.sdk.HoneySharedData", cl), String.class);
            Diag.ok("sdk.add", Diag.F_LIVE);
        } catch (Throwable t) {
            Diag.check("sdk.add", Diag.F_LIVE, false, Diag.ERROR, "폴더 추가 API 없음: " + t.getMessage());
        }
        try {
            XposedHelpers.findConstructorExact(p + "CreateFolderData", cl, List.class, int.class, List.class);
            Diag.ok("sdk.create", Diag.F_LIVE);
        } catch (Throwable t) {
            Diag.check("sdk.create", Diag.F_LIVE, false, Diag.ERROR, "폴더 생성 API 없음: " + t.getMessage());
        }
        try {
            XposedHelpers.findMethodExact(p + "FolderItem", cl, "getFolderEvent");
            XposedHelpers.findMethodExact(p + "FolderItem$FolderEvent", cl, "getUpdateLabel");
            Diag.ok("sdk.rename", Diag.F_LIVE);
        } catch (Throwable t) {
            Diag.check("sdk.rename", Diag.F_LIVE, false, Diag.WARN,
                    "폴더 이름 갱신 API 없음, DB 방식으로 대체 (열린 폴더 제목이 늦게 반영될 수 있음)");
        }
    }

    private static final class VmHook extends XC_MethodHook {
        private final Class<?> dataSourceIface;

        VmHook(Class<?> dataSourceIface) {
            this.dataSourceIface = dataSourceIface;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (engine == null) return;
                Object dataSource = null;
                for (Object arg : param.args) {
                    if (dataSourceIface.isInstance(arg)) {
                        dataSource = arg;
                        break;
                    }
                }
                if (dataSource == null) {
                    Diag.check("vm.args", Diag.F_LIVE, false, Diag.ERROR, "ViewModel 인자 구조가 다릅니다");
                    return;
                }
                engine.attach(param.thisObject, dataSource);
                if (!dataSourceHooked) {
                    dataSourceHooked = true;
                    hookDataSource(dataSource.getClass());
                }
                Diag.ok("vm.args", Diag.F_LIVE);
                log("앱스 화면에 연결됨");
            } catch (Throwable t) {
                Diag.failed(Diag.F_LIVE, "attach", t);
            }
        }
    }

    /** Observes item writes so the tracker can tell user moves from ours. */
    private static void hookDataSource(Class<?> impl) {
        XC_MethodHook observer = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (engine != null && param.args.length == 1 && param.args[0] != null) {
                    engine.onItemWritten(param.args[0]);
                }
            }
        };
        XposedBridge.hookAllMethods(impl, "updateItem", observer);
        XposedBridge.hookAllMethods(impl, "insertItem", observer);
    }

    private static void registerPackageCallback(Context context) {
        LauncherApps la = context.getSystemService(LauncherApps.class);
        la.registerCallback(new LauncherApps.Callback() {
            @Override
            public void onPackageAdded(String pkg, UserHandle user) {
                if (Process.myUserHandle().equals(user)) engine.onPackageAdded(pkg);
            }

            @Override
            public void onPackageRemoved(String pkg, UserHandle user) {
                if (Process.myUserHandle().equals(user)) engine.onPackageRemoved(pkg);
            }

            @Override
            public void onPackageChanged(String pkg, UserHandle user) {
            }

            @Override
            public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) {
            }

            @Override
            public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) {
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private static void registerCommandReceiver(Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String cmd = intent.getStringExtra("cmd");
                // Routine requests from the settings screen would only bury what matters.
                if (!java.util.Arrays.asList("health", "backups", "layout", "reload", "logs", "plan").contains(cmd)) log("요청: " + cmd);
                engine.command(cmd == null ? "" : cmd, intent.getStringExtra(Config.EXTRA_ARG));
            }
        }, new IntentFilter(Config.ACTION_COMMAND), Config.PERMISSION_COMMAND,
                new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
    }

    private static Engine.Settings loadPrefs() {
        XSharedPreferences p = new XSharedPreferences(Config.MODULE_PKG, Config.PREFS);
        p.reload();
        Engine.Settings s = new Engine.Settings();
        s.enabled = p.getBoolean(Config.KEY_ENABLED, true);
        s.createFolders = p.getBoolean(Config.KEY_CREATE_FOLDERS, true);
        s.minApps = p.getInt(Config.KEY_MIN_APPS, 2);
        s.skipMoved = p.getBoolean(Config.KEY_SKIP_MOVED, false);
        s.cleanStale = p.getBoolean(Config.KEY_CLEAN_STALE, true);
        s.unclassified = p.getString(Config.KEY_UNCLASSIFIED, Config.UNCLASSIFIED_FREE);
        s.unclassifiedFolder = p.getString(Config.KEY_UNCLASSIFIED_FOLDER, "기타");
        s.unclassifiedPage = p.getInt(Config.KEY_UNCLASSIFIED_PAGE, 1);
        s.screenOffTidy = p.getBoolean(Config.KEY_SCREEN_OFF_TIDY, true);
        // AI mode makes the folders it was asked for, single-app ones included; so does a minimum of 1.
        com.auto.classify.hook.SingleFolders.enabled = s.minApps <= 1
                || Config.MODE_AI.equals(p.getString(Config.KEY_MODE, Config.MODE_CATEGORY));
        for (String f : p.getString(Config.KEY_DISABLED, "").split(",")) {
            if (!f.trim().isEmpty()) s.disabled.add(f.trim());
        }
        return s;
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
        Diag.log(msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
