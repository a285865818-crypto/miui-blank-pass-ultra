package io.github.sweetpotato_digua.mbpu;

import android.app.Application;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLongArray;

import io.github.libxposed.api.XposedModule;

/**
 * 进程内拦截调用统计。记录的是被 hook 的 API 调用次数，不是权限操作的全局精确计量。
 *
 * <p>事件驱动限频：每个类别每 60 秒最多输出一条增量日志，格式
 * {@code BLOCKSTAT|进程名|类别ID|增量次数}。没有后台定时器——进程退出前未满一个窗口的
 * 尾部计数可能不输出，统计失败绝不影响实际拦截结果。
 *
 * <p>热路径（含 AudioRecord.read 这类高频回调）无锁：按类别 id 直索引 AtomicLongArray，
 * 未命中 flush 窗口时只有一次自增和一次读，不分配对象、不做字符串操作、不做反射。
 */
final class BlockStats {
    private static final long FLUSH_INTERVAL_MS = 60_000L;
    /** Config 类别 id 目前为 2..15，留足余量；id 即数组下标。 */
    private static final int SLOTS = 32;

    private static final AtomicLongArray PENDING = new AtomicLongArray(SLOTS);
    private static final AtomicLongArray LAST_FLUSH = new AtomicLongArray(SLOTS);

    static {
        // 进程首事件不应立刻刷一条只有 1 的日志，从类加载时刻起算第一个窗口。
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < SLOTS; i++) LAST_FLUSH.set(i, now);
    }

    /** configure() 在 hook 安装前调用，热路径只读这个已缓存句柄。 */
    private static volatile XposedModule module;
    private static volatile String processName;

    private BlockStats() {
    }

    static void configure(XposedModule hookedModule, String fallbackPackage) {
        module = hookedModule;
        if (processName == null) {
            processName = findProcessName(fallbackPackage);
        }
    }

    /** 静态记录入口：Hooks 实例只需传入 Config 类别 id。 */
    static void record(int categoryId) {
        if (categoryId <= 0 || categoryId >= SLOTS) return;
        PENDING.incrementAndGet(categoryId);

        long now = SystemClock.elapsedRealtime();
        long last = LAST_FLUSH.get(categoryId);
        if (now - last < FLUSH_INTERVAL_MS) return;
        // CAS 抢占本窗口的 flush 权：多线程同时到期只有一方输出，其余直接返回。
        if (!LAST_FLUSH.compareAndSet(categoryId, last, now)) return;

        long delta = PENDING.getAndSet(categoryId, 0);
        if (delta == 0L) return;

        // 字符串构造与 module.log 只发生在 flush，且不在任何锁内。
        XposedModule currentModule = module;
        boolean written = false;
        if (currentModule != null) {
            try {
                String name = processName;
                if (name == null) name = "unknown";
                currentModule.log(Log.INFO, "PermNull",
                        "BLOCKSTAT|" + name + "|" + categoryId + "|" + delta);
                written = true;
            } catch (Throwable ignored) {
            }
        }
        if (!written) {
            // 日志暂不可写时把增量退回，下一个窗口随新事件重试；不为重试而刷日志风暴。
            PENDING.addAndGet(categoryId, delta);
        }
    }

    private static String findProcessName(String fallbackPackage) {
        String name = null;
        // minSdk 29：Application.getProcessName() 是公开 API，返回完整的 pkg:sub 名，
        // 不必再反射 SystemProperties / ActivityThread（隐藏 API 限制下会退回主包名，
        // 把子进程统计错并进主进程）。
        try {
            name = Application.getProcessName();
        } catch (Throwable ignored) {
        }
        if (name == null || name.isEmpty()) name = fallbackPackage;
        if (name == null || name.isEmpty()) name = "unknown";
        // 防止异常进程名破坏单行统计格式。
        return name.replace('|', '_').replace('\n', '_').replace('\r', '_');
    }
}
