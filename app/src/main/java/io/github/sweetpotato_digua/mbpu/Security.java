package io.github.sweetpotato_digua.mbpu;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 模块界面进程的安全校验层。
 *
 * native 层提供三项能力：
 *   1. 反调试（ptrace 自检、TracerPid、Frida 端口/文件、maps 注入检测）
 *   2. 签名校验（APK 签名证书 SHA-256 与构建时注入的期望值比对，防止重打包）
 *   3. 反注入（检测可疑 so）
 *
 * 三者的处置力度刻意不同：<b>只有签名校验会终止进程</b>。
 * 反调试与反注入仅写日志——HyperOS 的 SELinux 会误伤 ptrace 检测，
 * root 设备上 KernelSU / Magisk 也可能触发误判，直接杀进程会让正常用户闪退。
 *
 * 另见 {@link ModuleIntegrity}：目标应用进程里没有本类覆盖，
 * 那边单独做一次签名校验，不匹配则跳过安装 hook。
 */
public final class Security {

    static {
        System.loadLibrary("mbpu_security");
    }

    private Security() {
    }

    /* ===== native 方法 ===== */

    /** 反调试检测：发现调试器/Frida 返回 true */
    private static native boolean nativeAntiDebug();

    /** 签名校验：期望值 + 实际签名 hash；期望值为空表示未配置，直接放行 */
    private static native boolean nativeCheckSig(String expectedHash, String sigHash);

    /** 反注入检测：返回可疑标记位 */
    private static native int nativeIntegrity();

    /* ===== Java 层签名 hash 计算 ===== */

    /**
     * 计算当前 APK 签名证书的 SHA-256（小写十六进制）。
     * 不匹配时返回空字符串。
     */
    private static String computeSigHash(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            int flags = PackageManager.GET_SIGNING_CERTIFICATES;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                flags = PackageManager.GET_SIGNATURES;
            }
            android.content.pm.PackageInfo info = pm.getPackageInfo(pkg, flags);

            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sigs = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                Signature[] legacy = info.signatures;
                sigs = legacy;
            }

            if (sigs == null || sigs.length == 0) return "";
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

    /* ===== 对外入口 ===== */

    /**
     * 在 App.onCreate() 最早处调用。
     * 签名不符会结束进程；反调试与反注入只记录日志。
     *
     * 注意：只在模块自身的 UI 进程调用（Application.onCreate 只在模块进程触发），
     * 目标进程中模块代码是被 hook 注入的，不会走这里——那条路径由
     * {@link ModuleIntegrity} 负责。
     */
    public static void verify(Context context) {
        if (context == null) return;

        // 1. 反调试：只记录，不杀进程。
        //    HyperOS 的 SELinux 会误伤 ptrace 检测（avc denied），
        //    root 设备上 KernelSU/Magisk 也可能触发误判。
        //    杀进程会导致正常用户闪退。
        if (nativeAntiDebug()) {
            android.util.Log.w("MBPU_SEC", "anti-debug detected (logged only)");
        }

        // 2. 签名校验：这是唯一会 abort 的检查（防重打包）。
        //    期望值来自 BuildConfig.EXPECTED_SIG_SHA256（local.properties 注入，不入库）。
        //    没配置就跳过——开源版本让任何人克隆后都能直接构建运行。
        String expected = BuildConfig.EXPECTED_SIG_SHA256;
        if (expected == null) expected = "";
        if (expected.isEmpty()) {
            android.util.Log.w("MBPU_SEC", "EXPECTED_SIG_SHA256 not configured, signature check skipped");
        } else {
            String sigHash = computeSigHash(context);
            if (sigHash.isEmpty() || !nativeCheckSig(expected, sigHash)) {
                Process.killProcess(Process.myPid());
                System.exit(1);
                return;
            }
        }

        // 3. 反注入检测：只记录
        int suspicious = nativeIntegrity();
        if (suspicious != 0) {
            android.util.Log.w("MBPU_SEC", "suspicious so detected: " + suspicious);
        }
    }
}
