package com.sweetpotato.MBPU;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * modern Xposed API 入口。
 * <p>
 * 同时实现 onPackageLoaded 与 onPackageReady：不同 LSPosed 版本/不同应用的加载路径下，
 * 这两个回调的触发时机并不一致（onPackageReady 依赖 createOrUpdateClassLoaderLocked 的
 * hook，模块加载晚于该步骤时会漏掉）。两个都挂上，先到先用，用 flag 防重复安装。
 */
public class PermNullModule extends XposedModule {

    private static final String TAG = "PermNull";

    /** 同进程可能加载多个包/实际 ClassLoader，按二者身份去重，不能只用进程级 Boolean。 */
    private final Set<HookTarget> installed = ConcurrentHashMap.newKeySet();

    private static final class HookTarget {
        final String pkg;
        final ClassLoader loader;

        HookTarget(String pkg, ClassLoader loader) {
            this.pkg = pkg;
            this.loader = loader;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HookTarget && pkg.equals(((HookTarget) other).pkg)
                    && loader == ((HookTarget) other).loader;
        }

        @Override
        public int hashCode() {
            return 31 * pkg.hashCode() + System.identityHashCode(loader);
        }
    }

    /** 当前进程名；onModuleLoaded 时保存，install 时用作 HOOKSTAT 标识。 */
    private volatile String currentProcessName = "?";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            currentProcessName = param.getProcessName();
            log(Log.INFO, TAG, "loaded in " + currentProcessName);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        ClassLoader cl = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                cl = param.getDefaultClassLoader();
            } catch (Throwable ignored) {
            }
        }
        install("onPackageLoaded", param.getPackageName(), cl);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        install("onPackageReady", param.getPackageName(), param.getClassLoader());
    }

    private void install(String phase, String pkg, ClassLoader cl) {
        if ("com.sweetpotato.MBPU".equals(pkg)) return;
        if (cl == null) {
            safeLog(Log.WARN, phase + " " + pkg + " classLoader=null");
            return;
        }

        // 装 hook 之前先确认承载本模块代码的 APK 没被换签名重打包。
        // 不匹配就静默跳过：重打包产物能装能开界面，但对任何应用都不生效。
        // 每个进程只算一次，后续走缓存，开销可忽略。
        if (!ModuleIntegrity.isTrusted(this)) {
            safeLog(Log.ERROR, "module signature mismatch, refuse to hook " + pkg);
            return;
        }

        int mask = 0;
        try {
            SharedPreferences prefs = getRemotePreferences(Config.GROUP);
            String spec = prefs == null ? null : prefs.getString(Config.KEY_PREFIX + pkg, null);
            // 只读当前包，避免为诊断日志复制所有配置。
            mask = Config.parse(spec);
        } catch (Throwable t) {
            safeLog(Log.WARN, "read config failed for " + pkg + ": " + t);
            return;
        }

        if (mask == 0) return;
        HookTarget target = new HookTarget(pkg, cl);
        if (!installed.add(target)) {
            safeLog(Log.INFO, phase + " " + pkg + " already installed, skip");
            return;
        }

        try {
            Hooks hooks = new Hooks(this, pkg, mask, cl);
            hooks.install();
            // 统计通过 module.log 输出结构化标记，由诊断页从 LSPosed 日志解析。
            // 目标进程以自身 UID 运行，无法写入模块 app 的私有目录，因此文件方案不可行。
            // 用实际进程名（pkg 或 pkg:remote）而不是包名：
            // 包名会导致主/子进程写同一个 HOOKSTAT 键互相覆盖，
            // 子进程统计永远丢失，诊断页读不到子进程数据。
            reportStatus(currentProcessName, hooks.okCount(), hooks.okCount() + hooks.failCount());
            safeLog(Log.INFO, pkg + " active, mask=" + Config.serialize(mask) + " via " + phase);
        } catch (Throwable t) {
            installed.remove(target);
            safeLog(Log.ERROR, "install failed for " + pkg + ": " + t);
        }
    }

    private String hookStatus(int total, int ok) {
        return total + "/" + ok;
    }

    /**
     * 输出可被诊断页解析的统计行：HOOKSTAT|进程名|成功数|总数。
     * 走 module.log，随 LSPosed 写入 modules_*.log，是当前唯一可靠的跨进程通道。
     */
    private void reportStatus(String processName, int ok, int total) {
        try {
            log(android.util.Log.INFO, TAG,
                    "HOOKSTAT|" + processName + "|" + ok + "|" + total);
        } catch (Throwable ignored) {
        }
        // 不再向 RemotePreferences 写统计：Config.GROUP 同时保存 cfg:* 配置，
        // 而本方法运行在目标应用进程里，目标进程一旦拿到模块/service 句柄就能
        // 以模块身份改配置或伪造统计。统计只走 module.log（由 LSPosed 写盘）。
    }

    private void safeLog(int priority, String msg) {
        try {
            log(priority, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
