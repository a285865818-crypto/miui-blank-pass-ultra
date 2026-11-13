package io.github.sweetpotato_digua.mbpu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配置存储。
 * <p>
 * 真相源是 LSPosed 的 RemotePreferences（daemon 侧存储），hook 进程通过 framework 读取同一份数据。
 * 本地 SharedPreferences 只做镜像：LSPosed 未连接时界面仍能显示上次配置，恢复连接后自动补写。
 * 离线修改与删除用 dirty 键集记录，避免重连时旧 remote 值复活。
 */
public final class Store {

    private static final String LOCAL_NAME = "permnull";
    private static final String KEY_PENDING_STOP = "pending_stop";
    private static final String KEY_SHOW_SYSTEM = "show_system_apps";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_SORT_ORDER = "sort_order";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_PREDICTIVE_BACK = "predictive_back";
    private static final String KEY_ADVANCED_MATERIAL = "advanced_material";
    private static final String KEY_EXPORT_TREE = "export_tree_uri";
    private static final String KEY_SEARCH_HISTORY = "search_history";
    private static final String KEY_HELP_DISABLED = "help_disabled";
    private static final String KEY_CHANGED_AT = "changed_at_";
    private static final int MAX_SEARCH_HISTORY = 10;
    private static final String KEY_DIRTY_CONFIG = "dirty_config";

    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MBPU-config-sync");
        thread.setDaemon(true);
        return thread;
    });

    private final Object lock = new Object();
    private final SharedPreferences local;
    private volatile SharedPreferences remote;
    private boolean syncScheduled;
    private boolean syncRequested;

    public Store(Context ctx) {
        local = ctx.getApplicationContext()
                .getSharedPreferences(LOCAL_NAME, Context.MODE_PRIVATE);
    }

    /** 挂上 LSPosed 的 RemotePreferences，并在单线程中合并离线变更。 */
    public void setRemote(SharedPreferences prefs) {
        boolean changed;
        synchronized (lock) {
            changed = remote != prefs;
            remote = prefs;
        }
        if (prefs != null && changed) scheduleSync(prefs);
    }

    /**
     * 合并不覆盖远端已有键的旧格式配置，并优先同步 dirty 新增、修改和删除。
     * commit() 在专用线程执行；未收到 daemon 确认时保留 dirty，禁止静默丢配置。
     */
    private void scheduleSync(SharedPreferences prefs) {
        synchronized (lock) {
            if (remote != prefs) return;
            syncRequested = true;
            if (syncScheduled) return;
            syncScheduled = true;
        }
        try {
            SYNC_EXECUTOR.execute(() -> {
                try {
                    syncRemote(prefs);
                } finally {
                    // 关键：先消费掉本次请求，再决定是否还有新请求要跑。
                    // 旧写法只在有下一轮时才清 syncRequested，导致每轮结束都又把它
                    // 置回 true，同步任务永久自调度、不停打 LSPosed daemon IPC。
                    SharedPreferences next;
                    synchronized (lock) {
                        syncScheduled = false;
                        syncRequested = false;
                        next = remote;
                    }
                    if (next != null) {
                        synchronized (lock) {
                            if (!syncRequested) next = null;
                        }
                    }
                    if (next != null) scheduleSync(next);
                }
            });
        } catch (Throwable rejected) {
            synchronized (lock) {
                syncScheduled = false;
            }
        }
    }

    private void syncRemote(SharedPreferences prefs) {
        Map<String, ?> localValues;
        Set<String> dirty;
        try {
            synchronized (lock) {
                if (remote != prefs) return;
                localValues = local.getAll();
                dirty = dirtyConfigsLocked();
            }
            Map<String, ?> remoteValues = prefs.getAll();
            SharedPreferences.Editor editor = prefs.edit();
            Set<String> synced = new HashSet<>();
            boolean changed = false;

            for (Map.Entry<String, ?> entry : localValues.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (!isConfigKey(key) || !(value instanceof String) || dirty.contains(key)) continue;
                if (remoteValues.containsKey(key)) continue;
                editor.putString(key, (String) value);
                changed = true;
            }
            for (String key : dirty) {
                Object value = localValues.get(key);
                if (value instanceof String) editor.putString(key, (String) value);
                else editor.remove(key);
                changed = true;
            }
            if (!changed) return;
            if (!editor.commit()) return;

            synchronized (lock) {
                // 服务重连会把 remote 换成新对象。只有「提交的仍是当前 remote」
                // 才能清 dirty，否则旧 remote 提交成功后，新 remote 上的旧值会把
                // 本地新值覆盖回去（键已经不是 dirty 了，不会被重放）。
                if (remote != prefs) return;
                Set<String> currentDirty = dirtyConfigsLocked();
                for (String key : dirty) {
                    if (!currentDirty.contains(key)) continue;
                    Object before = localValues.get(key);
                    Object now = local.getAll().get(key);
                    if (Objects.equals(before, now)) synced.add(key);
                }
                if (!synced.isEmpty()) {
                    currentDirty.removeAll(synced);
                    local.edit().putStringSet(KEY_DIRTY_CONFIG, currentDirty).apply();
                }
            }
        } catch (Throwable ignored) {
            // 保留 dirty；下次服务绑定或配置修改时重试。
        }
    }

    private static boolean isConfigKey(String key) {
        return key != null && key.startsWith(Config.KEY_PREFIX);
    }

    private Set<String> dirtyConfigsLocked() {
        return stringSet(KEY_DIRTY_CONFIG);
    }

    private Set<String> stringSet(String key) {
        try {
            Set<String> stored = local.getStringSet(key, null);
            return stored == null ? new HashSet<>() : new HashSet<>(stored);
        } catch (Throwable ignored) {
            return new HashSet<>();
        }
    }

    private String string(String key, String fallback) {
        try {
            String value = local.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean bool(String key, boolean fallback) {
        try {
            return local.getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 读取某个应用的类别掩码；未同步的本地值优先于旧 remote 快照。 */
    public int get(String pkg) {
        String key = Config.KEY_PREFIX + pkg;
        String value;
        synchronized (lock) {
            Map<String, ?> localValues = local.getAll();
            if (dirtyConfigsLocked().contains(key)) {
                Object localValue = localValues.get(key);
                value = localValue instanceof String ? (String) localValue : null;
            } else {
                SharedPreferences r = remote;
                value = null;
                if (r != null) {
                    try {
                        Object remoteValue = r.getAll().get(key);
                        value = remoteValue instanceof String ? (String) remoteValue : null;
                    } catch (Throwable ignored) {
                    }
                }
                if (value == null) {
                    Object localValue = localValues.get(key);
                    value = localValue instanceof String ? (String) localValue : null;
                }
            }
        }
        return Config.parse(value);
    }

    /** 一次快照解析所有掩码，避免列表刷新时重复复制 local/remote 全量配置。 */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new LinkedHashMap<>();
        synchronized (lock) {
            Map<String, Object> merged = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, ?> entry : local.getAll().entrySet()) {
                    if (isConfigKey(entry.getKey()) && entry.getValue() instanceof String) {
                        merged.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Throwable ignored) {
            }
            SharedPreferences r = remote;
            if (r != null) {
                try {
                    for (Map.Entry<String, ?> entry : r.getAll().entrySet()) {
                        if (isConfigKey(entry.getKey()) && entry.getValue() instanceof String) {
                            merged.put(entry.getKey(), entry.getValue());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Set<String> dirty = dirtyConfigsLocked();
            for (String key : dirty) {
                Object value = local.getAll().get(key);
                if (value instanceof String) merged.put(key, value);
                else merged.remove(key);
            }
            for (Map.Entry<String, Object> entry : merged.entrySet()) {
                String pkg = entry.getKey().substring(Config.KEY_PREFIX.length());
                if (!pkg.isEmpty()) out.put(pkg, Config.parse((String) entry.getValue()));
            }
        }
        return out;
    }

    /** 已配置的应用包名集合。 */
    public Set<String> packages() {
        return new HashSet<>(snapshot().keySet());
    }

    /** 保存配置；mask 为 0 时删除该项。离线变更会在重连后重放。 */
    public void put(String pkg, int mask) {
        if (!validPackage(pkg)) {
            throw new IllegalArgumentException("invalid package name");
        }
        String key = Config.KEY_PREFIX + pkg;
        String value = mask == 0 ? null : Config.serialize(mask);
        SharedPreferences r;
        synchronized (lock) {
            Set<String> dirty = dirtyConfigsLocked();
            dirty.add(key);
            SharedPreferences.Editor editor = local.edit();
            if (value == null) editor.remove(key);
            else editor.putString(key, value);
            editor.putStringSet(KEY_DIRTY_CONFIG, dirty);
            editor.putLong(KEY_CHANGED_AT + pkg, System.currentTimeMillis());
            editor.apply();
            r = remote;
        }
        if (r != null) scheduleSync(r);
    }

    private static boolean validPackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9._]+");
    }

    /** 重启后需要卸载 hook 的包名；同一 Store 实例内串行化读改写。 */
    public void markPendingStop(String pkg) {
        // 模块自己永远不会被注入 hook，把它记进待卸载毫无意义，
        // 而且重启后也消不掉这个标记，会一直挂在弹窗里。
        if (Config.MODULE_PKG.equals(pkg)) return;
        synchronized (lock) {
            Set<String> pending = pendingStops();
            if (pending.add(pkg)) {
                local.edit().putStringSet(KEY_PENDING_STOP, pending).apply();
            }
        }
    }

    public void clearPendingStop(Set<String> pkgs) {
        synchronized (lock) {
            Set<String> pending = pendingStops();
            if (pending.removeAll(pkgs)) {
                local.edit().putStringSet(KEY_PENDING_STOP, pending).apply();
            }
        }
    }

    public Set<String> pendingStops() {
        Set<String> all = stringSet(KEY_PENDING_STOP);
        // 读的时候再过滤一次：旧版本可能已经把模块自己写进去了，
        // 这里顺手清掉，避免历史脏数据一直出现在重启弹窗里。
        if (all.remove(Config.MODULE_PKG)) {
            local.edit().putStringSet(KEY_PENDING_STOP, all).apply();
        }
        return all;
    }

    public boolean showSystemApps() {
        return bool(KEY_SHOW_SYSTEM, false);
    }

    public void setShowSystemApps(boolean show) {
        local.edit().putBoolean(KEY_SHOW_SYSTEM, show).apply();
    }

    public String sortMode() {
        return string(KEY_SORT_MODE, "INSTALL_TIME");
    }

    public void setSortMode(String mode) {
        local.edit().putString(KEY_SORT_MODE, mode).apply();
    }

    public String sortOrder() {
        return string(KEY_SORT_ORDER, "DESC");
    }

    public void setSortOrder(String order) {
        local.edit().putString(KEY_SORT_ORDER, order).apply();
    }

    public String darkMode() {
        return string(KEY_DARK_MODE, "system");
    }

    public void setDarkMode(String mode) {
        local.edit().putString(KEY_DARK_MODE, mode).apply();
    }

    public String language() {
        String code = string(KEY_LANGUAGE, Languages.SYSTEM);
        return Languages.isSupported(code) ? code : Languages.SYSTEM;
    }

    /** 只持久化语言，不触发重建；界面通过 CompositionLocal 即时切换。 */
    public void saveLanguage(String code) {
        local.edit().putString(KEY_LANGUAGE,
                Languages.isSupported(code) ? code : Languages.SYSTEM).apply();
    }

    /** 某应用最近一次配置变更时间（毫秒），0 表示无记录。 */
    public long changedAt(String pkg) {
        try {
            return local.getLong(KEY_CHANGED_AT + pkg, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 兼容旧调用：持久化并清应用名缓存。 */
    public void setLanguage(String code) {
        saveLanguage(code);
        AppLabels.INSTANCE.clear();
    }

    /** 是否已选择"不再提示"使用说明。 */
    public boolean helpDisabled() {
        return bool(KEY_HELP_DISABLED, false);
    }

    public void setHelpDisabled(boolean disabled) {
        local.edit().putBoolean(KEY_HELP_DISABLED, disabled).apply();
    }

    /** 搜索历史，最新在前。用换行分隔保存，StringSet 无序会丢顺序。 */
    public java.util.List<String> searchHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = string(KEY_SEARCH_HISTORY, "");
        if (raw.isEmpty()) return out;
        boolean dirty = false;
        for (String value : raw.split("\n")) {
            String trimmed = value.trim();
            // 旧版本逐字记录时留下过 "1"、"?" 这类半截输入，读取时顺手清掉。
            if (trimmed.length() < 2) {
                dirty = true;
                continue;
            }
            if (!out.contains(trimmed)) {
                out.add(trimmed);
                if (out.size() >= MAX_SEARCH_HISTORY) break;
            }
        }
        if (dirty) {
            local.edit().putString(KEY_SEARCH_HISTORY, String.join("\n", out)).apply();
        }
        return out;
    }

    /** 记录一次搜索；去重、最新优先、最多 10 条。 */
    public void addSearchHistory(String keyword) {
        String value = keyword == null ? "" : keyword.trim().replace('\n', ' ');
        if (value.isEmpty()) return;
        synchronized (lock) {
            java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
            values.add(value);
            values.addAll(searchHistory());
            java.util.List<String> capped = new java.util.ArrayList<>(values);
            if (capped.size() > MAX_SEARCH_HISTORY) {
                capped = capped.subList(0, MAX_SEARCH_HISTORY);
            }
            local.edit().putString(KEY_SEARCH_HISTORY, String.join("\n", capped)).apply();
        }
    }

    /** 删除单条历史。 */
    public void removeSearchHistory(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        if (value.isEmpty()) return;
        synchronized (lock) {
            java.util.List<String> values = searchHistory();
            if (values.remove(value)) {
                local.edit().putString(KEY_SEARCH_HISTORY, String.join("\n", values)).apply();
            }
        }
    }

    public void clearSearchHistory() {
        local.edit().remove(KEY_SEARCH_HISTORY).apply();
    }

    /** 自定义日志导出目录（SAF tree URI），空串表示使用系统下载目录。 */
    public String exportTreeUri() {
        return string(KEY_EXPORT_TREE, "");
    }

    public void setExportTreeUri(String uri) {
        local.edit().putString(KEY_EXPORT_TREE, uri == null ? "" : uri).apply();
    }

    public boolean advancedMaterial() {
        return bool(KEY_ADVANCED_MATERIAL, false);
    }

    public void setAdvancedMaterial(boolean enabled) {
        local.edit().putBoolean(KEY_ADVANCED_MATERIAL, enabled).apply();
    }

    public boolean predictiveBack() {
        return bool(KEY_PREDICTIVE_BACK, true);
    }

    public void setPredictiveBack(boolean enabled) {
        local.edit().putBoolean(KEY_PREDICTIVE_BACK, enabled).apply();
    }

    public static String languageOf(Context ctx) {
        try {
            String code = ctx.getSharedPreferences(LOCAL_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LANGUAGE, Languages.SYSTEM);
            return Languages.isSupported(code) ? code : Languages.SYSTEM;
        } catch (Throwable t) {
            return Languages.SYSTEM;
        }
    }
}
