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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /* ===== 签名 hash 归一化（与 ModuleIntegrity 保持同一规则） ===== */

    /**
     * 归一化签名证书 SHA-256：去空白、去冒号、转小写。
     *
     * <p>keytool 输出是「大写 + 冒号分隔」的 96 字符，直接拿来比对必然失败，
     * 所以统一在 Java 侧归一化后才与 {@link #computeSigHash} 的产物比对，
     * native 层只做防御性的 {@code strncmp}。
     *
     * @return 64 位小写 hex；长度不符（含 {@code null}）返回 {@code ""}，视为未配置
     */
    static String normalizeHex(String s) {
        if (s == null) return "";
        String t = s.trim().replace(":", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        return t.length() == 64 ? t : "";
    }

    /* ===== Java 层签名 hash 计算 ===== */

    /**
     * 计算当前 APK 签名证书的 SHA-256（小写十六进制）。
     *
     * <p>三态返回：
     * <ul>
     *   <li>{@code null} = 读取失败（PM 异常、signingInfo 为 null、sigs 为空），
     *       调用方走降级路径（只记日志），不能据此杀进程；</li>
     *   <li>{@code ""} = 确认校验失败（签名与期望值不匹配）；</li>
     *   <li>64 位 hex = 计算成功。</li>
     * </ul>
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

            if (sigs == null || sigs.length == 0) return null;   // 读不到
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // 多签名 APK 下 signatures 数组顺序不保证，收集全部 hash 排序后取最小，结果才稳定。
            List<String> hashes = new ArrayList<>(sigs.length);
            for (Signature s : sigs) {
                hashes.add(sha256Hex(md, s.toByteArray()));
            }
            Collections.sort(hashes);
            return hashes.get(0);
        } catch (Throwable t) {
            android.util.Log.w("MBPU_SEC", "computeSigHash failed: " + t);
            return null;   // 读不到，不杀
        }
    }

    /** 证书 DER 的 SHA-256，输出 64 位小写 hex。 */
    private static String sha256Hex(MessageDigest md, byte[] der) {
        byte[] hash = md.digest(der);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
        //    期望值与实际值都过 normalizeHex，规则与 ModuleIntegrity、native 层一致（H10）。
        String expected = normalizeHex(BuildConfig.EXPECTED_SIG_SHA256);
        if (expected.isEmpty()) {
            android.util.Log.w("MBPU_SEC", "EXPECTED_SIG_SHA256 not configured or invalid, signature check skipped");
        } else {
            String sigHash = computeSigHash(context);
            if (sigHash == null) {
                // PM 抖动 / split 未就绪 / 签名读不到：降级为只记日志，不能当篡改处理（H9）。
                android.util.Log.w("MBPU_SEC", "sig hash unavailable, skip check (degraded)");
            } else {
                // 归一化后若为空说明 computeSigHash 产物异常；但 verify 的入口值
                // normalizeHex 已保证非空才进来，这里只可能是畸形值，按不匹配杀进程。
                sigHash = normalizeHex(sigHash);   // 已是小写 64 hex，双保险
                if (sigHash.isEmpty() || !nativeCheckSig(expected, sigHash)) {
                    android.util.Log.e("MBPU_SEC", "signature mismatch, killing process; got="
                            + (sigHash.isEmpty() ? "<malformed>" : sigHash.substring(0, 8)));
                    Process.killProcess(Process.myPid());
                    System.exit(1);
                }
            }
        }

        // 3. 反注入检测：只记录。
        //    nativeIntegrity 的 -1 表示检测本身失败（如 maps 读不开），
        //    不是「可疑」，只有 > 0 的位掩码才说明检出（L25）。
        int suspicious = nativeIntegrity();
        if (suspicious > 0) {
            android.util.Log.w("MBPU_SEC", "suspicious so detected: " + suspicious);
        }
    }
}
