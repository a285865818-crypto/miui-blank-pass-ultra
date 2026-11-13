package com.sweetpotato.MBPU;

import android.app.Application;
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

    /** 每个进程只算一次；null 表示还没校验过。 */
    private static volatile Boolean trusted;

    private ModuleIntegrity() {
    }

    /**
     * 当前进程加载的模块是否可信。同一进程内重复调用只做一次实际校验，
     * 后续直接读缓存，不产生额外开销。
     */
    static boolean isTrusted(XposedInterface xposed) {
        Boolean cached = trusted;
        if (cached != null) {
            return cached;
        }
        synchronized (ModuleIntegrity.class) {
            if (trusted == null) {
                trusted = check(xposed);
            }
            return trusted;
        }
    }

    private static boolean check(XposedInterface xposed) {
        String expected = BuildConfig.EXPECTED_SIG_SHA256;
        if (expected == null) {
            expected = "";
        }
        // 未配置期望值：源码自行编译的产物，无从比对，一律放行。
        if (expected.isEmpty()) {
            return true;
        }

        String actual = computeModuleSigHash(xposed);
        // 配了期望值却读不到签名，说明 APK 结构异常或被改动，按不可信处理。
        if (actual.isEmpty()) {
            return false;
        }
        return expected.equalsIgnoreCase(actual);
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
            return "";
        }
    }

    /**
     * 目标进程里没有可直接引用的 Context，通过 ActivityThread 取当前
     * Application 再拿 PackageManager。取不到返回 null，由调用方按不可信处理。
     */
    private static PackageManager currentPackageManager() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof Application) {
                return ((Application) app).getPackageManager();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
