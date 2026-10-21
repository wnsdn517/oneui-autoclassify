package com.auto.classify.hook;

import android.content.ComponentName;
import android.os.UserHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * Talks to One UI Home's apps screen through its own model and events, so every change is
 * applied live and persisted by the launcher itself. Obfuscated members are located by type,
 * not by name.
 */
final class LauncherBridge {

    private static final String APP_ITEM = "com.honeyspace.sdk.source.entity.AppItem";
    private static final String FOLDER_ITEM = "com.honeyspace.sdk.source.entity.FolderItem";
    private static final String ICON_ITEM = "com.honeyspace.sdk.source.entity.IconItem";
    private static final String COMPONENT_KEY = "com.honeyspace.sdk.source.entity.ComponentKey";
    private static final String ADD_FOLDER_ITEM = "com.honeyspace.sdk.source.entity.AddFolderItemEventData";
    private static final String CREATE_FOLDER = "com.honeyspace.sdk.source.entity.CreateFolderData";
    private static final String SHARED_DATA = "com.honeyspace.sdk.HoneySharedData";
    private static final String SHARED_DATA_KT = "com.honeyspace.sdk.HoneySharedDataKt";
    private static final String PREFERENCE_DATA_SOURCE = "com.honeyspace.sdk.source.PreferenceDataSource";

    private final ClassLoader cl;
    private final Object vm;
    private final Object dataSource;
    private final Class<?> appCls;
    private final Class<?> folderCls;
    private final Class<?> iconCls;
    private Field itemsField;

    LauncherBridge(ClassLoader cl, Object applistVm, Object honeyDataSource) {
        this.cl = cl;
        this.vm = applistVm;
        this.dataSource = honeyDataSource;
        this.appCls = XposedHelpers.findClass(APP_ITEM, cl);
        this.folderCls = XposedHelpers.findClass(FOLDER_ITEM, cl);
        this.iconCls = XposedHelpers.findClass(ICON_ITEM, cl);
    }

    /**
     * Moves a top-level item next to another, live, through the apps screen's own
     * "move item" path (ApplistViewModel.X(itemId, targetId, before) in One UI Home 17: start
     * drag, drop at the target's page/slot, end drag; the launcher saves it). The method is
     * found by shape; with no single match nothing is called.
     * @return false when this launcher has no such method
     */
    boolean moveNextTo(int itemId, int anchorId, boolean before) throws Exception {
        Method found = null;
        for (Method m : vm.getClass().getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 3 && p[0] == int.class && p[1] == int.class
                    && p[2] == boolean.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                if (found != null) return false; // ambiguous on this version
                found = m;
            }
        }
        if (found == null) return false;
        found.setAccessible(true);
        found.invoke(vm, itemId, anchorId, before);
        return true;
    }

    /**
     * The apps-screen grid {cols, rows} the launcher is using now, read from the view model's
     * PreferenceDataSource (getApplistCellX/Y, the same values the screen lays out with).
     * @return null when it can't be read
     */
    int[] applistGrid() {
        Class<?> prefsCls = XposedHelpers.findClassIfExists(PREFERENCE_DATA_SOURCE, cl);
        if (prefsCls == null) return null;
        for (Field f : vm.getClass().getDeclaredFields()) {
            if (!prefsCls.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object prefs = f.get(vm);
                if (prefs == null) continue;
                Object x = XposedHelpers.callMethod(XposedHelpers.callMethod(prefs, "getApplistCellX"), "getValue");
                Object y = XposedHelpers.callMethod(XposedHelpers.callMethod(prefs, "getApplistCellY"), "getValue");
                if (x instanceof Integer && y instanceof Integer && (int) x > 0 && (int) y > 0) {
                    return new int[]{(int) x, (int) y};
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * The folder grid {cols, rows} the launcher opens folders with (Folder.Grid, e.g. 3x4 or 4x4),
     * read from the same PreferenceDataSource as applistGrid. null when it can't be read.
     */
    int[] folderGrid() {
        Class<?> prefsCls = XposedHelpers.findClassIfExists(PREFERENCE_DATA_SOURCE, cl);
        if (prefsCls == null) return null;
        for (Field f : vm.getClass().getDeclaredFields()) {
            if (!prefsCls.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object prefs = f.get(vm);
                if (prefs == null) continue;
                Object p = XposedHelpers.callMethod(XposedHelpers.callMethod(prefs, "getFolderGrid"), "getValue");
                if (p == null) continue;
                int x = XposedHelpers.getIntField(p, "x"), y = XposedHelpers.getIntField(p, "y");
                if (x > 0 && y > 0) return new int[]{x, y};
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Top-level entries of the apps screen (apps and folders, not folder children). */
    List<Object> topLevelItems() {
        Collection<?> wrappers = wrappers();
        List<Object> out = new ArrayList<>();
        if (wrappers == null) return out;
        for (Object w : wrappers.toArray()) {
            Object item = unwrap(w);
            if (item != null) out.add(item);
        }
        return out;
    }

    boolean isApp(Object item) {
        return appCls.isInstance(item);
    }

    boolean isFolder(Object item) {
        return folderCls.isInstance(item);
    }

    int id(Object item) {
        return (int) XposedHelpers.callMethod(item, "getId");
    }

    String label(Object item) {
        Object live = XposedHelpers.callMethod(item, "getLabel");
        Object v = live == null ? null : XposedHelpers.callMethod(live, "getValue");
        return v == null ? "" : v.toString();
    }

    /** ComponentName of an AppItem, or null. */
    ComponentName component(Object appItem) {
        Object key = XposedHelpers.callMethod(appItem, "getComponent");
        return key == null ? null : (ComponentName) XposedHelpers.callMethod(key, "getComponentName");
    }

    Object findFolderByLabel(String name) {
        for (Object item : topLevelItems()) {
            if (isFolder(item) && label(item).trim().equals(name)) return item;
        }
        return null;
    }

    Object findFolderById(int folderId) {
        for (Object item : topLevelItems()) {
            if (isFolder(item) && id(item) == folderId) return item;
        }
        return null;
    }

    /** Title stored in the launcher DB for an item, or null. */
    String dbTitle(int itemId) {
        Object data = XposedHelpers.callMethod(dataSource, "getHoneyData", itemId);
        return data == null ? null : (String) XposedHelpers.callMethod(data, "getTitle");
    }

    /** Sort key of a top-level item's place on the apps screen (page, then slot); MAX when unknown. */
    long position(Object item) {
        try {
            Object data = XposedHelpers.callMethod(dataSource, "getHoneyData", id(item));
            if (data == null) return Long.MAX_VALUE;
            Object page = XposedHelpers.callMethod(dataSource, "getHoneyGroupData",
                    (int) XposedHelpers.callMethod(data, "getContainerId"));
            int pageRank = page == null ? Integer.MAX_VALUE / 2 : (int) XposedHelpers.callMethod(page, "getRank");
            return ((long) pageRank << 32) | ((int) XposedHelpers.callMethod(data, "getRank") & 0xFFFFFFFFL);
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    Object findTopLevelApp(String pkg) {
        for (Object item : topLevelItems()) {
            if (!isApp(item)) continue;
            ComponentName cn = component(item);
            if (cn != null && pkg.equals(cn.getPackageName())) return item;
        }
        return null;
    }

    List<Integer> folderIds() {
        List<Integer> ids = new ArrayList<>();
        for (Object item : topLevelItems()) if (isFolder(item)) ids.add(id(item));
        return ids;
    }

    /** Moves apps into an existing folder through the launcher's "AddFolderItem" event. */
    boolean addToFolder(Object folder, List<ComponentName> components, UserHandle user) throws Exception {
        Constructor<?> keyCtor = XposedHelpers.findConstructorExact(COMPONENT_KEY, cl,
                ComponentName.class, UserHandle.class);
        List<Object> keys = new ArrayList<>();
        for (ComponentName cn : components) keys.add(keyCtor.newInstance(cn, user));
        Constructor<?> ctor = XposedHelpers.findConstructorExact(ADD_FOLDER_ITEM, cl,
                List.class, int.class, folderCls, boolean.class, boolean.class);
        return emit("AddFolderItem", ctor.newInstance(keys, id(folder), folder, false, true));
    }

    /**
     * Creates a new apps-screen folder from top-level AppItems ("CreateApplistFolder").
     * The launcher puts the folder where the LAST item of the list was.
     */
    boolean createFolder(List<Object> appItems) throws Exception {
        Constructor<?> ctor = XposedHelpers.findConstructorExact(CREATE_FOLDER, cl,
                List.class, int.class, List.class);
        return emit("CreateApplistFolder", ctor.newInstance(appItems, -1, Collections.emptyList()));
    }

    /**
     * Renames a folder. The open-folder title lives in the folder's own view model, which only
     * listens to FolderItem.FolderEvent.updateLabel (the path the title editor uses); that
     * handler also saves the DB row. The direct DB/label write covers folders whose view model
     * isn't subscribed yet.
     */
    void renameFolder(Object folder, String name) {
        Object folderEvent = XposedHelpers.callMethod(folder, "getFolderEvent");
        if (folderEvent != null) emitTo(XposedHelpers.callMethod(folderEvent, "getUpdateLabel"), name);
        Object data = XposedHelpers.callMethod(dataSource, "getHoneyData", id(folder));
        if (data != null && !name.equals(XposedHelpers.callMethod(data, "getTitle"))) {
            XposedHelpers.callMethod(data, "setTitle", name);
            XposedHelpers.callMethod(dataSource, "updateItem", data);
        }
        Object live = XposedHelpers.callMethod(folder, "getLabel");
        if (live != null) XposedHelpers.callMethod(live, "postValue", name);
    }

    private boolean emit(String event, Object payload) {
        Object flow = XposedHelpers.callStaticMethod(XposedHelpers.findClass(SHARED_DATA_KT, cl),
                "getEvent", sharedData(), event);
        return flow != null && emitTo(flow, payload);
    }

    private boolean emitTo(Object flow, Object payload) {
        if (flow == null) return false;
        if ((boolean) XposedHelpers.callMethod(flow, "tryEmit", payload)) return true;
        XposedHelpers.callMethod(flow, "emit", payload, continuation());
        return true;
    }

    private Object sharedData() {
        Class<?> cls = XposedHelpers.findClass(SHARED_DATA, cl);
        for (Field f : vm.getClass().getDeclaredFields()) {
            if (cls.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try {
                    return f.get(vm);
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        throw new IllegalStateException("HoneySharedData not found");
    }

    /** The view model's list of item wrappers: the collection whose entries unwrap to IconItems. */
    private Collection<?> wrappers() {
        try {
            if (itemsField != null) return (Collection<?>) itemsField.get(vm);
            for (Field f : vm.getClass().getDeclaredFields()) {
                if (!Collection.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object value = f.get(vm);
                if (!(value instanceof Collection) || ((Collection<?>) value).isEmpty()) continue;
                boolean hasFolder = false;
                for (Object w : ((Collection<?>) value).toArray()) {
                    if (folderCls.isInstance(unwrap(w))) {
                        hasFolder = true;
                        break;
                    }
                }
                if (hasFolder) {
                    itemsField = f;
                    return (Collection<?>) value;
                }
            }
        } catch (IllegalAccessException ignored) {
        }
        return null;
    }

    private Object unwrap(Object wrapper) {
        if (wrapper == null) return null;
        for (Method m : wrapper.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || !iconCls.isAssignableFrom(m.getReturnType())) continue;
            try {
                return m.invoke(wrapper);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object continuation() {
        Class<?> contCls = XposedHelpers.findClass("kotlin.coroutines.Continuation", cl);
        Object ctx = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("kotlin.coroutines.EmptyCoroutineContext", cl), "INSTANCE");
        return Proxy.newProxyInstance(cl, new Class<?>[]{contCls}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getContext":
                    return ctx;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "AutoClassifyContinuation";
                default:
                    return null;
            }
        });
    }
}
