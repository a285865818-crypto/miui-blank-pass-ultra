package io.github.sweetpotato_digua.mbpu;

import android.content.SharedPreferences;
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

    /**
     * 已装 hook 的包名（M15/L19：之前按 (pkg, loader 身份) 去重，若两阶段回调给不同 loader
     * 会重复安装；现只按包名去重，loader 只用来定位 hook 目标。
     * 用 Set<String> 避免持有目标 ClassLoader 引用，防止其及其加载的类无法回收）。
     */
    private final Set<String> installed = ConcurrentHashMap.newKeySet();

    /**
     * 正在等待签名校验结论的包名 → 该包最近一次回调给出的 ClassLoader。
     * M14：按包名去重但保存最新 cl（put 覆盖旧值），重试线程取最新 cl，避免吞掉
     * onPackageReady 后到的新 ClassLoader。
     */
    private final ConcurrentHashMap<String, ClassLoader> sigRetryPending = new ConcurrentHashMap<>();

    /** 当前进程名；onModuleLoaded 时保存，install 时用作 HOOKSTAT 标识。 */
    private volatile String currentProcessName = "?";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            currentProcessName = param.getProcessName();
            log(Log.INFO, TAG, "loaded in " + currentProcessName);
        } catch (Throwable ignored) {
            // L20：取进程名失败时不要留 "?"，install 里会用 pkg 兜底。
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // L17：minSdk=29(Q)，原 SDK_INT >= Q 分支恒真，删掉只留 try/catch 取 cl。
        ClassLoader cl = null;
        try {
            cl = param.getDefaultClassLoader();
        } catch (Throwable ignored) {
        }
        install("onPackageLoaded", param.getPackageName(), cl);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        install("onPackageReady", param.getPackageName(), param.getClassLoader());
    }

    /**
     * R8/R9：返回值三态，让重试循环能区分「成功/永久放弃」与「暂时失败要重试」。
     * true = 已安装或明确无需安装（未启用、签名不符、自身包名、cl 为 null）；
     * false = 暂时失败（签名读不出、配置读不到），调用方应安排重试。
     * 回调入口忽略返回值（由内部自调度），重试线程依据返回值决定是否继续。
     */
    private boolean install(String phase, String pkg, ClassLoader cl) {
        // L18：自身包名不硬编码，跟 applicationId 保持一致（包名迁移时不用再改这里）。
        if (BuildConfig.APPLICATION_ID.equals(pkg)) return true;
        if (cl == null) {
            safeLog(Log.WARN, phase + " " + pkg + " classLoader=null");
            return true;
        }
        // L20：currentProcessName 取不到时用 pkg 兜底，HOOKSTAT 标识不至于是 "?"。
        String processName = currentProcessName;
        if ("?".equals(processName)) processName = pkg;

        // 装 hook 之前先确认承载本模块代码的 APK 没被换签名重打包。
        // 不匹配就静默跳过：重打包产物能装能开界面，但对任何应用都不生效。
        Boolean sigOk = ModuleIntegrity.isTrusted(this);
        if (sigOk == null) {
            // 进程刚起步时 Application 还没创建，取不到 PackageManager。
            // 不判负也不判正，交给后台重试——那个阶段一定拿得到。
            scheduleSigRetry(phase, pkg, cl);
            return false;
        }
        if (!sigOk) {
            safeLog(Log.ERROR, "module signature mismatch, refuse to hook " + pkg);
            return true;
        }

        int mask;
        SharedPreferences prefs = null;
        try {
            prefs = getRemotePreferences(Config.GROUP);
        } catch (Throwable t) {
            safeLog(Log.WARN, "getRemotePreferences failed for " + pkg + ": " + t + ", will retry");
            scheduleSigRetry(phase, pkg, cl);
            return false;
        }
        // R8：prefs==null 是「RemotePreferences 尚未连接/不可用」，不是「未启用」。
        // 旧代码此时 spec=null → mask=0 → 永久跳过且日志误报 not enabled。
        if (prefs == null) {
            safeLog(Log.WARN, "remote prefs unavailable for " + pkg + ", will retry");
            scheduleSigRetry(phase, pkg, cl);
            return false;
        }
        try {
            String spec = prefs.getString(Config.KEY_PREFIX + pkg, null);
            // 只读当前包，避免为诊断日志复制所有配置。
            mask = Config.parse(spec);
        } catch (Throwable t) {
            // H11：区分「配置读不到」和「未启用」——读不到（remote prefs 不可用、回调早于
            // attach、类型异常）走延后重试，不能像 mask==0 那样永久放弃该包。
            safeLog(Log.WARN, "read config failed for " + pkg + ": " + t + ", will retry");
            scheduleSigRetry(phase, pkg, cl);
            return false;
        }

        // H11：mask==0 = 该包未启用（配置存在但为空/0），与上面的「读不到」明确区分。
        if (mask == 0) {
            safeLog(Log.INFO, "mask==0 for " + pkg + " (not enabled)");
            return true;
        }
        // M15/L19：只按包名去重，loader 仅用于定位 hook 目标，不进去重键。
        if (!installed.add(pkg)) {
            safeLog(Log.INFO, phase + " " + pkg + " already installed, skip");
            return true;
        }

        Hooks hooks = null;
        try {
            hooks = new Hooks(this, pkg, mask, cl);
            hooks.install();
            // 统计通过 module.log 输出结构化标记，由诊断页从 LSPosed 日志解析。
            // 目标进程以自身 UID 运行，无法写入模块 app 的私有目录，因此文件方案不可行。
            // 用实际进程名（pkg 或 pkg:remote）而不是包名：
            // 包名会导致主/子进程写同一个 HOOKSTAT 键互相覆盖，
            // 子进程统计永远丢失，诊断页读不到子进程数据。
            reportStatus(processName, hooks.okCount(), hooks.okCount() + hooks.failCount());
            safeLog(Log.INFO, pkg + " active, mask=" + Config.serialize(mask) + " via " + phase);
        } catch (Throwable t) {
            // M13：安装失败时的去重标记取舍——
            // - 能拿到 Hooks 实例且一个 hook 都没装上（okCount==0）：移除标记，允许下次回调重试；
            // - 异常发生在 Hooks 构造阶段（hooks 仍为 null），无法得知装没装上：
            //   保守保留标记不重试，避免「已装一半 + 下次重复安装」的双倍 hook。
            if (hooks != null && hooks.okCount() == 0) {
                installed.remove(pkg);
            }
            safeLog(Log.ERROR, "install failed for " + pkg + ": " + t);
        }
        return true;
    }

    /**
     * 签名校验拿不到结论时的后台重试（H11 复用：配置读取失败时也走这里延后重试）。
     *
     * <p>LSPosed 只在 onPackageLoaded / onPackageReady 两个时机回调，而这两个
     * 时机都可能早于 Application 创建，此时取不到 PackageManager。实测有的应用
     * 只有前两次回调，靠后续回调兜底并不可靠，所以这里主动起一个短命线程，
     * 每隔 400ms 重试一次，最多 5 次（约 2 秒）后放弃。
     *
     * <p>放弃时只记日志、不装 hook：宁可少装，也不能给重打包产物放行。
     * M14：同一进程内按包名去重但保存最新 ClassLoader（put 覆盖旧值），
     * 重试线程取最新 cl，避免吞掉 onPackageReady 后到的新 ClassLoader。
     */
    private void scheduleSigRetry(String phase, String pkg, ClassLoader cl) {
        ClassLoader prev = sigRetryPending.putIfAbsent(pkg, cl);
        if (prev != null) {
            // 已有重试线程在跑：只把 cl 刷成最新的，不重复起线程。
            sigRetryPending.put(pkg, cl);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // M14：每次重试都取最新的 cl（可能已被后续回调刷新）。
                    ClassLoader latest = sigRetryPending.get(pkg);
                    if (latest == null) latest = cl;
                    // R9：直接看 install 返回值。旧代码靠 scheduleSigRetry 再调度，
                    // 但 pending 里已有本 pkg → 只刷 cl 不起新线程，本线程 return 后
                    // finally 删 pending，配置读取失败再也不会重试。
                    // 现在 install 内部虽仍会调 scheduleSigRetry（只刷 cl 无副作用），
                    // 是否继续完全由这里的返回值驱动：false=暂时失败，继续下一轮。
                    boolean done = install(phase + "/retry" + i, pkg, latest);
                    if (!done) {
                        continue;
                    }
                    return;
                }
                safeLog(Log.WARN, "signature check unresolved for " + pkg + ", hook skipped");
            } finally {
                sigRetryPending.remove(pkg);
            }
        }, "MBPU-sigretry");
        t.setDaemon(true);
        t.start();
    }

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
