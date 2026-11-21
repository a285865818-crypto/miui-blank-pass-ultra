package io.github.sweetpotato_digua.mbpu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private static final String KEY_PENDING_RECONCILE = "pending_reconcile";
    // 远端换代新增字段；原有 cfg:* 键保留，旧 Hook 进程仍可读取。
    private static final String KEY_REMOTE_GENERATION = "meta:config_generation";
    private static final String KEY_REMOTE_GENERATION_PREFIX = "meta:config_generation:";

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
                // R5：进入任务就先消费本次请求。原来在 finally 里才读 syncRequested，
                // 读到的是本次 scheduleSync 自己置的 true → needAgain 恒真 → 无间隔
                // 永久自调度。现在只有「syncRemote 执行期间新到达的请求」才会触发重跑。
                synchronized (lock) {
                    syncRequested = false;
                }
                try {
                    syncRemote(prefs);
                } finally {
                    // 关键：原子地「读走」本次请求，再决定是否还有新请求要跑。
                    // 同步执行期间 scheduleSync 置起的 syncRequested 只能在这里被消费，
                    // 绝不能无条件清零——那会把同步期间到达的修改一起吞掉（开关改了不生效）。
                    boolean needAgain;
                    SharedPreferences next;
                    synchronized (lock) {
                        syncScheduled = false;
                        needAgain = syncRequested;
                        syncRequested = false;
                        next = remote;
                    }
                    if (next != null && needAgain) {
                        scheduleSync(next);
                    }
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
            // 上次 replaceConfigs 时 remote 不可读，需补删 remote-only 的 cfg 键。
            boolean reconcile;
            synchronized (lock) {
                reconcile = local.getBoolean(KEY_PENDING_RECONCILE, false);
            }
            if (reconcile) {
                try {
                    Map<String, ?> remoteAll = prefs.getAll();
                    synchronized (lock) {
                        Set<String> localKeys = new HashSet<>();
                        for (String key : local.getAll().keySet()) {
                            if (isConfigKey(key)) localKeys.add(key);
                        }
                        Set<String> dirtyNow = dirtyConfigsLocked();
                        for (String key : remoteAll.keySet()) {
                            if (isConfigKey(key) && !localKeys.contains(key)) {
                                dirtyNow.add(key); // tombstone：本地没有但 remote 有 → 删
                            }
                        }
                        local.edit().putStringSet(KEY_DIRTY_CONFIG, dirtyNow)
                                .remove(KEY_PENDING_RECONCILE).apply();
                    }
                } catch (Throwable ignored) {
                    // 仍然不可读，保留标记下次再试。
                }
            }
            synchronized (lock) {
                // 锁内只做本地读与取引用；prefs.getAll() 是跨进程 IPC，禁止持 lock 调用。
                // N6 修复：remote 已换代时不能把本次请求静默丢掉。清掉「同步进行中」
                // 标志并重新置起 syncRequested，让 finally 对当前 remote 再调度一次；
                // 否则本次配置修改永远不会写到新 remote，目标应用继续读旧值。
                if (remote != prefs) {
                    syncRequested = true;
                    syncScheduled = false;
                    return;
                }
                localValues = local.getAll();
                dirty = dirtyConfigsLocked();
            }
            Map<String, Object> remoteValues = readRemoteConfigs(prefs);
            Map<String, Object> nextValues = new LinkedHashMap<>(remoteValues);
            SharedPreferences.Editor editor = prefs.edit();
            Set<String> synced = new HashSet<>();
            boolean changed = false;

            for (Map.Entry<String, ?> entry : localValues.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (!isConfigKey(key) || !(value instanceof String) || dirty.contains(key)) continue;
                if (remoteValues.containsKey(key)) continue;
                editor.putString(key, (String) value);
                nextValues.put(key, value);
                changed = true;
            }
            for (String key : dirty) {
                Object value = localValues.get(key);
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                    nextValues.put(key, value);
                } else {
                    editor.remove(key);
                    nextValues.remove(key);
                }
                changed = true;
            }
            if (!changed) return;

            // 先独立提交完整新代际，再原子切换代际并发布旧协议键。
            // 这样新读取方不会看到半份数据，旧 Hook 也只会在最后一次提交后看到新配置。
            String generation = UUID.randomUUID().toString();
            String prefix = KEY_REMOTE_GENERATION_PREFIX + generation + ":";
            SharedPreferences.Editor staging = prefs.edit();
            for (Map.Entry<String, Object> entry : nextValues.entrySet()) {
                staging.putString(prefix + entry.getKey(), (String) entry.getValue());
            }
            if (!staging.commit()) return;
            editor.putString(KEY_REMOTE_GENERATION, generation);
            if (!editor.commit()) return;

            // 代际已切换，旧暂存数据可清理；清理失败不影响正确性，下次同步再清。
            try {
                SharedPreferences.Editor cleanup = prefs.edit();
                for (String key : prefs.getAll().keySet()) {
                    if (key.startsWith(KEY_REMOTE_GENERATION_PREFIX) && !key.startsWith(prefix)) {
                        cleanup.remove(key);
                    }
                }
                cleanup.apply();
            } catch (RuntimeException ignored) {
            }

            synchronized (lock) {
                // 服务重连会把 remote 换成新对象。只有「提交的仍是当前 remote」
                // 才能清 dirty，否则旧 remote 提交成功后，新 remote 上的旧值会把
                // 本地新值覆盖回去（键已经不是 dirty 了，不会被重放）。
                if (remote != prefs) return;
                Set<String> currentDirty = dirtyConfigsLocked();
                // local.getAll() 一次取快照，不在循环里反复全量读。
                Map<String, ?> localNow = local.getAll();
                for (String key : dirty) {
                    if (!currentDirty.contains(key)) continue;
                    Object before = localValues.get(key);
                    Object now = localNow.get(key);
                    if (Objects.equals(before, now)) synced.add(key);
                }
                if (!synced.isEmpty()) {
                    currentDirty.removeAll(synced);
                    local.edit().putStringSet(KEY_DIRTY_CONFIG, currentDirty).apply();
                }
            }
        } catch (RuntimeException ignored) {
            // 保留 dirty；下次服务绑定或配置修改时重试。
        }
    }

    private static boolean isConfigKey(String key) {
        return key != null && key.startsWith(Config.KEY_PREFIX);
    }

    /**
     * 读取完整远端配置。代际在读取期间变化时只重读一次，避免持续换代导致死循环。
     * 没有代际字段时按旧格式读取，兼容升级前已经存在的数据。
     */
    private static Map<String, Object> readRemoteConfigs(SharedPreferences prefs) {
        Map<String, ?> values = prefs.getAll();
        for (int attempt = 0; attempt < 2; attempt++) {
            Object generation = values.get(KEY_REMOTE_GENERATION);
            if (generation == null) return legacyRemoteConfigs(values);
            String prefix = KEY_REMOTE_GENERATION_PREFIX + generation + ":";
            Map<String, Object> configs = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(prefix) && entry.getValue() instanceof String) {
                    configs.put(key.substring(prefix.length()), entry.getValue());
                }
            }
            Map<String, ?> verified = prefs.getAll();
            if (Objects.equals(generation, verified.get(KEY_REMOTE_GENERATION))) return configs;
            values = verified;
        }
        // 两次都碰到换代则退回同一次原子提交发布的旧协议键，不拼接两个代际。
        return legacyRemoteConfigs(values);
    }

    private static Map<String, Object> legacyRemoteConfigs(Map<String, ?> values) {
        Map<String, Object> configs = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (isConfigKey(entry.getKey()) && entry.getValue() instanceof String) {
                configs.put(entry.getKey(), entry.getValue());
            }
        }
        return configs;
    }

    private Set<String> dirtyConfigsLocked() {
        return stringSet(KEY_DIRTY_CONFIG);
    }

    private Set<String> stringSet(String key) {
        try {
            Set<String> stored = local.getStringSet(key, null);
            return stored == null ? new HashSet<>() : new HashSet<>(stored);
        } catch (RuntimeException ignored) {
            return new HashSet<>();
        }
    }

    private String string(String key, String fallback) {
        try {
            String value = local.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean bool(String key, boolean fallback) {
        try {
            return local.getBoolean(key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** 读取某个应用的类别掩码；未同步的本地值优先于旧 remote 快照。 */
    public int get(String pkg) {
        String key = Config.KEY_PREFIX + pkg;
        String value;
        Map<String, ?> localValues;
        boolean dirty;
        synchronized (lock) {
            localValues = local.getAll();
            dirty = dirtyConfigsLocked().contains(key);
        }
        if (dirty) {
            Object localValue = localValues.get(key);
            value = localValue instanceof String ? (String) localValue : null;
        } else {
            value = null;
            // remote.getAll() 是跨进程 IPC，禁止持 lock 调用；出锁后只读。
            SharedPreferences r = remote;
            if (r != null) {
                try {
                    Object remoteValue = readRemoteConfigs(r).get(key);
                    value = remoteValue instanceof String ? (String) remoteValue : null;
                } catch (Throwable ignored) {
                }
            }
            if (value == null) {
                Object localValue = localValues.get(key);
                value = localValue instanceof String ? (String) localValue : null;
            }
        }
        return Config.parse(value);
    }

    /** 一次快照解析所有掩码，避免列表刷新时重复复制 local/remote 全量配置。 */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, ?> localValues = new LinkedHashMap<>();
        Set<String> dirty;
        // 锁内只做本地读与取引用；remote.getAll() 是跨进程 IPC，禁止持 lock 调用。
        synchronized (lock) {
            try {
                localValues = local.getAll();
            } catch (Throwable ignored) {
            }
            dirty = dirtyConfigsLocked();
        }
        for (Map.Entry<String, ?> entry : localValues.entrySet()) {
            if (isConfigKey(entry.getKey()) && entry.getValue() instanceof String) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        SharedPreferences r = remote;
        if (r != null) {
            try {
                for (Map.Entry<String, Object> entry : readRemoteConfigs(r).entrySet()) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        for (String key : dirty) {
            Object value = localValues.get(key);
            if (value instanceof String) merged.put(key, value);
            else merged.remove(key);
        }
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            String pkg = entry.getKey().substring(Config.KEY_PREFIX.length());
            if (!pkg.isEmpty()) out.put(pkg, Config.parse((String) entry.getValue()));
        }
        return out;
    }

    /** 已配置的应用包名集合。 */
    public Set<String> packages() {
        return new HashSet<>(snapshot().keySet());
    }

    /**
     * 用完整备份替换所有每应用配置。
     * 输入先整体校验，再在一次 SharedPreferences 事务中提交，避免逐应用导入留下半份状态。
     * 本地与远端旧键都纳入 dirty tombstone，确保同步失败或重连后删除不会丢失。
     */
    public void replaceConfigs(Map<String, Integer> configs) {
        if (configs == null) throw new IllegalArgumentException("null config snapshot");
        Set<String> newKeys = new HashSet<>();
        for (Map.Entry<String, Integer> entry : configs.entrySet()) {
            String pkg = entry.getKey();
            Integer mask = entry.getValue();
            if (!isValidPackage(pkg) || mask == null || mask <= 0 || Config.count(mask) == 0) {
                throw new IllegalArgumentException("invalid config snapshot");
            }
            newKeys.add(Config.KEY_PREFIX + pkg);
        }

        Map<String, ?> before;
        SharedPreferences r;
        synchronized (lock) {
            before = local.getAll();
            r = remote;
        }
        Set<String> oldKeys = new HashSet<>();
        for (String key : before.keySet()) {
            if (isConfigKey(key)) oldKeys.add(key);
        }
        // RemotePreferences.getAll() 是 Binder IPC，必须在 Store 全局锁外执行。
        boolean remoteOk = true;
        if (r != null) {
            try {
                for (String key : r.getAll().keySet()) {
                    if (isConfigKey(key)) oldKeys.add(key);
                }
            } catch (Throwable ignored) {
                // 远端暂不可读：标记需要全量对账，下次 syncRemote 补删 remote-only 键。
                remoteOk = false;
            }
        } else {
            remoteOk = false;
        }

        synchronized (lock) {
            // 最终写锁内重新枚举 local 键：锁外 IPC 期间可能有并发 put() 新增的键。
            for (String key : local.getAll().keySet()) {
                if (isConfigKey(key)) oldKeys.add(key);
            }
            Set<String> dirty = dirtyConfigsLocked();
            SharedPreferences.Editor editor = local.edit();
            long now = System.currentTimeMillis();
            for (String key : oldKeys) {
                if (newKeys.contains(key)) continue;
                editor.remove(key);
                editor.remove(KEY_CHANGED_AT + key.substring(Config.KEY_PREFIX.length()));
            }
            for (Map.Entry<String, Integer> entry : configs.entrySet()) {
                String pkg = entry.getKey();
                editor.putString(Config.KEY_PREFIX + pkg, Config.serialize(entry.getValue()));
                editor.putLong(KEY_CHANGED_AT + pkg, now);
            }
            // 不删除旧 dirty：已删除键和 remote-only 键都必须作为 tombstone 同步到 daemon。
            dirty.addAll(oldKeys);
            dirty.addAll(newKeys);
            editor.putStringSet(KEY_DIRTY_CONFIG, dirty);
            if (!remoteOk) editor.putBoolean(KEY_PENDING_RECONCILE, true);
            if (!editor.commit()) throw new IllegalStateException("config commit failed");
        }
        if (r != null) scheduleSync(r);
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
            if (value == null) {
                editor.remove(key);
                // 删除配置时连变更时间一并清掉，该键不留永不过期的残值。
                editor.remove(KEY_CHANGED_AT + pkg);
            } else {
                editor.putString(key, value);
                editor.putLong(KEY_CHANGED_AT + pkg, System.currentTimeMillis());
            }
            editor.putStringSet(KEY_DIRTY_CONFIG, dirty);
            editor.apply();
            r = remote;
        }
        if (r != null) scheduleSync(r);
    }

    /** 配置备份恢复用：按现有校验规则规范化包名，不放宽存储键格式。 */
    public static boolean isValidPackage(String pkg) {
        return validPackage(pkg) && pkg.length() <= 255 && !Config.MODULE_PKG.equals(pkg);
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
        if (pkgs == null || pkgs.isEmpty()) return;
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
