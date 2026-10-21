package com.auto.classify.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.auto.classify.core.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Shows the names and icons set in AutoClassify on the launcher's own items too. The launcher's
 * item class is found by its getters (getTitle/getComponent/getIcon with their return types), not by
 * a fixed obfuscation-prone name, so it works across One UI Home versions. Room write paths (which
 * persist the item) always get the real value, so no override is written into the launcher DB.
 */
final class LauncherNames {

    private static final long TTL_MS = 60_000;
    private static final String AUTHORITY = "content://" + Config.CLASSIFIER_AUTHORITY;

    private final Context context;
    private final ClassLoader cl;
    private final Object dataSource;
    private final Map<String, Boolean> roomClass = new ConcurrentHashMap<>();
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Map<String, String> names = Collections.emptyMap();
    private volatile Map<String, byte[]> icons = Collections.emptyMap();
    private volatile long loadedAt;

    LauncherNames(Context context, ClassLoader cl, Object honeyDataSource) {
        this.context = context;
        this.cl = cl;
        this.dataSource = honeyDataSource;
    }

    /** Hooks the item getters. Logs and does nothing when this launcher's item class doesn't match. */
    void install() {
        try {
            Class<?> item = findItemClass();
            if (item == null) {
                Diag.log("런처 이름/아이콘: 아이템 클래스를 찾지 못해 건너뜀");
                return;
            }
            Method title = getter(item, "getTitle", String.class);
            Method component = getter(item, "getComponent", String.class);
            Method icon = getter(item, "getIcon", Bitmap.class);
            deoptimize(title);
            deoptimize(icon);
            XposedBridge.hookMethod(title, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    refreshIfStale();
                    String pkg = pkgOf(component, param.thisObject);
                    if (pkg == null) return;
                    String name = names.get(pkg);
                    if (name != null && !isRoomWrite()) param.setResult(name);
                }
            });
            XposedBridge.hookMethod(icon, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    refreshIfStale();
                    String pkg = pkgOf(component, param.thisObject);
                    if (pkg == null) return;
                    byte[] png = icons.get(pkg);
                    if (png == null || isRoomWrite()) return;
                    Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bmp != null) param.setResult(bmp);
                }
            });
            hookPackageManager();
            hookTitleField(item, component);
            Diag.log("런처 이름/아이콘 훅 연결 (아이템 클래스 " + item.getName() + ")");
            refreshIfStale();
        } catch (Throwable t) {
            Diag.failed(Diag.F_CORE, "런처 이름/아이콘 훅", t);
        }
    }

    /**
     * Home and app icons and labels come from PackageManager (IconSource builds them from it), not from
     * ItemData, so the public framework getters are hooked too. Those signatures are the same on every
     * One UI version.
     */
    private void hookPackageManager() {
        Class<?> pm = context.getPackageManager().getClass();
        XposedBridge.hookAllMethods(pm, "getApplicationLabel", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                refreshIfStale();
                String name = names.get(packageOf(param.args.length == 1 ? param.args[0] : null));
                if (name != null) param.setResult(name);
            }
        });
        XC_MethodHook icon = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                refreshIfStale();
                byte[] png = icons.get(packageOf(param.args.length == 1 ? param.args[0] : null));
                if (png == null) return;
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp != null) param.setResult(new BitmapDrawable(context.getResources(), bmp));
            }
        };
        XposedBridge.hookAllMethods(pm, "getApplicationIcon", icon);
        XposedBridge.hookAllMethods(pm, "getActivityIcon", icon);
    }

    /**
     * The apps screen reads the title as a field of the entity (loadByPage builds the items without
     * calling getTitle), so the override is set on the field right after each item is constructed.
     */
    private void hookTitleField(Class<?> item, Method component) {
        Field field = null;
        StringBuilder strings = new StringBuilder();
        for (Class<?> c = item; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                strings.append(f.getName()).append(' ');
                if (field == null && f.getName().equals("title")) field = f;
            }
        }
        Diag.log("런처 아이템 문자열 필드: " + strings);
        if (field == null) {
            Diag.log("런처 이름: title 필드를 찾지 못해 아이템 생성 훅은 건너뜀");
            return;
        }
        field.setAccessible(true);
        Field titleField = field;
        XposedBridge.hookAllConstructors(item, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                refreshIfStale();
                String pkg = pkgOf(component, param.thisObject);
                if (pkg == null) return;
                String name = names.get(pkg);
                if (name != null) titleField.set(param.thisObject, name);
            }
        });
    }

    /** Package of a PackageManager argument: ApplicationInfo, package name, ComponentName or Intent. */
    private static String packageOf(Object arg) {
        if (arg instanceof ApplicationInfo) return ((ApplicationInfo) arg).packageName;
        if (arg instanceof String) return (String) arg;
        if (arg instanceof ComponentName) return ((ComponentName) arg).getPackageName();
        if (arg instanceof Intent && ((Intent) arg).getComponent() != null) {
            return ((Intent) arg).getComponent().getPackageName();
        }
        return null;
    }

    /**
     * The concrete item class: a public, non-static, one-int-parameter method on the data source
     * returns it, and it has the three getters with the expected return types.
     */
    private Class<?> findItemClass() {
        if (dataSource == null) return null;
        for (Method m : dataSource.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != int.class || Modifier.isStatic(m.getModifiers())) continue;
            Class<?> r = m.getReturnType();
            if (r.isInterface() || r.isPrimitive()) continue;
            if (getter(r, "getTitle", String.class) != null
                    && getter(r, "getComponent", String.class) != null
                    && getter(r, "getIcon", Bitmap.class) != null) {
                return r;
            }
        }
        return null;
    }

    /** A public getter with no parameters and the given return type, or null. */
    private static Method getter(Class<?> c, String name, Class<?> returns) {
        try {
            Method m = c.getMethod(name);
            return m.getReturnType() == returns ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Package name from the item's "pkg/class" component, or null for non-app items. */
    private static String pkgOf(Method component, Object item) {
        try {
            String c = (String) component.invoke(item);
            if (c == null) return null;
            int slash = c.indexOf('/');
            return slash > 0 ? c.substring(0, slash) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the current call comes from a Room write (an adapter that persists the item). */
    private boolean isRoomWrite() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String n = e.getClassName();
            if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("dalvik.")) continue;
            Boolean room = roomClass.get(n);
            if (room == null) {
                room = isRoomAdapter(n);
                roomClass.put(n, room);
            }
            if (room) return true;
        }
        return false;
    }

    private boolean isRoomAdapter(String className) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
                if (s.getName().startsWith("androidx.room.")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Reloads the overrides in the background when older than TTL_MS; never blocks the caller. */
    private void refreshIfStale() {
        if (System.currentTimeMillis() - loadedAt < TTL_MS || !loading.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                load();
            } catch (Throwable t) {
                Diag.failed(Diag.F_CORE, "런처 이름/아이콘 읽기", t);
            } finally {
                loadedAt = System.currentTimeMillis();
                loading.set(false);
            }
        }, "AutoClassify-names").start();
    }

    private void load() throws Exception {
        Bundle b = context.getContentResolver().call(Uri.parse(AUTHORITY), "app_overrides", null, null);
        if (b == null) return;
        Map<String, String> n = new HashMap<>();
        String[] pkgs = b.getStringArray("pkgs"), vals = b.getStringArray("names");
        if (pkgs != null && vals != null) {
            for (int i = 0; i < pkgs.length && i < vals.length; i++) n.put(pkgs[i], vals[i]);
        }
        Map<String, byte[]> ic = new HashMap<>();
        String[] iconPkgs = b.getStringArray("icons");
        if (iconPkgs != null) {
            for (String pkg : iconPkgs) {
                byte[] png = readIcon(pkg);
                if (png != null) ic.put(pkg, png);
            }
        }
        names = n;
        icons = ic;
    }

    private byte[] readIcon(String pkg) {
        try (ParcelFileDescriptor pfd = context.getContentResolver()
                .openFileDescriptor(Uri.parse(AUTHORITY + "/icon/" + pkg), "r")) {
            if (pfd == null) return null;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd.dup())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Same deoptimization the other hooks use: callers that inline the getter would skip the hook. */
    private static void deoptimize(Method m) {
        try {
            XposedBridge.class.getDeclaredMethod("deoptimizeMethod", java.lang.reflect.Member.class).invoke(null, m);
        } catch (Throwable ignored) {
        }
    }
}
