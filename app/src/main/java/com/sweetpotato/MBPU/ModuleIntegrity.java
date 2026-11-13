package com.sweetpotato.MBPU;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.lang.reflect.Method;
import java.security.MessageDigest;

import io.github.libxposed.api.XposedInterface;

/**
 * 目标进程内的模块自身完整性校验。
 *
 * <p>hook 逻辑运行在目标应用进程里，那份代码是被注入的，不会经过
 * {@link App#onCreate()}，所以 {@link Security#verify} 覆盖不到这里。
 * 一旦有人把 {@link Hooks} 拿走、换自己的签名重打包，目标进程里的 hook
 * 仍然会照常安装并工作。
 *
 * <p>本类补上这一环：在真正安装 hook 之前，核对承载模块代码的那份 APK
 * 的签名证书，与构建时注入的期望值不一致就跳过安装。重打包产物因此变成
 * 空壳——能装、能打开界面，但不会对任何目标应用生效。
 *
 * <p>失败处理刻意选择「静默跳过」而不是崩溃：模块没有资格让用户的应用
 * 闪退。攻击者最多得到一个不工作的包，普通用户不会看到任何异常。
 */
final class ModuleIntegrity {

    /** 每个进程只算一次：null 表示还没得出确定结论，会继续重试。 */
    private static volatile Boolean trusted;

    private ModuleIntegrity() {
    }

    /**
     * 当前进程加载的模块是否可信。
     *
     * @return {@code TRUE} 通过，{@code FALSE} 确认不匹配，
     *         {@code null} 本轮还拿不到签名（通常是进程启动太早，
     *         Application 尚未创建），调用方应当推迟而不是放行。
     */
    static Boolean isTrusted(XposedInterface xposed) {
        Boolean cached = trusted;
        if (cached != null) {
            return cached;
        }
        synchronized (ModuleIntegrity.class) {
            if (trusted == null) {
                Boolean result = check(xposed);
                // 只有拿到确定结论才缓存，避免一次过早的探测把结果钉死。
                if (result != null) {
                    trusted = result;
                }
                return result;
            }
            return trusted;
        }
    }

    private static Boolean check(XposedInterface xposed) {
        String expected = BuildConfig.EXPECTED_SIG_SHA256;
        if (expected == null) {
            expected = "";
        }
        // 未配置期望值：源码自行编译的产物，无从比对，一律放行。
        if (expected.isEmpty()) {
            return Boolean.TRUE;
        }

        String actual = computeModuleSigHash(xposed);
        if (actual.isEmpty()) {
            // 拿不到签名不代表不可信，只是这次太早。返回 null 让调用方稍后重试。
            android.util.Log.w("MBPU_SEC", "module sig not readable yet, defer");
            return null;
        }
        boolean ok = expected.equalsIgnoreCase(actual);
        android.util.Log.i("MBPU_SEC", "module sig verified=" + ok
                + " prefix=" + actual.substring(0, Math.min(8, actual.length())));
        return ok;
    }

    /**
     * 计算模块 APK 的签名证书 SHA-256（小写十六进制）。失败返回空串。
     *
     * <p>路径取自 libxposed 的 {@code getModuleApplicationInfo()}，它指向
     * 框架实际加载的那份 APK，比从 PackageManager 按包名反查更贴近事实。
     */
    private static String computeModuleSigHash(XposedInterface xposed) {
        try {
            ApplicationInfo info = xposed.getModuleApplicationInfo();
            if (info == null || info.sourceDir == null) {
                android.util.Log.w("MBPU_SEC", "diag: appInfo=" + info);
                return "";
            }

            java.io.File apk = new java.io.File(info.sourceDir);
            if (!apk.exists() || !apk.canRead()) {
                android.util.Log.w("MBPU_SEC", "diag: apk not readable: " + info.sourceDir);
                return "";
            }

            PackageManager pm = currentPackageManager();
            if (pm == null) {
                return "";
            }

            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo pi = pm.getPackageArchiveInfo(info.sourceDir, flags);
            if (pi == null) {
                android.util.Log.w("MBPU_SEC", "diag: pkgArchiveInfo=null");
                return "";
            }

            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sigs = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                Signature[] legacy = pi.signatures;
                sigs = legacy;
            }
            if (sigs == null || sigs.length == 0) {
                android.util.Log.w("MBPU_SEC", "diag: sigs=null");
                return "";
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            android.util.Log.w("MBPU_SEC", "diag: throw " + t);
            return "";
        }
    }

    /**
     * 取当前进程的 PackageManager。失败返回 null。
     *
     * <p>踩过的坑，别再走回头路：
     * <ul>
     *   <li>{@code ActivityThread.getPackageManager()} 返回的是
     *       {@code IPackageManager}（Binder 代理），不是
     *       {@code android.content.PackageManager}，反射拿到也没法用。</li>
     *   <li>{@code currentApplication()} 在 {@code onPackageLoaded} 阶段
     *       还是 null，要到 {@code onPackageReady} 才有值。</li>
     * </ul>
     * 所以这里按可用性依次尝试，取不到就返回 null，由调用方当作
     * 「尚未确定」处理，而不是直接判定不可信。
     */
    private static PackageManager currentPackageManager() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getPackageManager();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object app = Class.forName("android.app.AppGlobals")
                    .getDeclaredMethod("getInitialApplication")
                    .invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getPackageManager();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
