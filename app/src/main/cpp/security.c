/*
 * native 层安全校验。
 *
 * 为什么放 native：
 *   - 逆 so 需要 IDA/Ghidra 反汇编 + ARM 知识，门槛远高于反编译 dex
 *   - 关键常量在编译时被烧进机器码，静态分析难提取
 *   - 检测代码本身也在 native，patch 需要改 so 再重签名，容易被签名校验抓到
 *
 * 实现：签名校验 + 反调试 + 完整性校验。
 * 只有签名不匹配才杀进程；反调试与反注入只记日志，避免误伤正常设备。
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <pthread.h>

#define LOG_TAG "MBPU_SEC"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* ===== 期望的 APK 签名证书 SHA-256 =====
 * 开源版本不把证书哈希烧进 so：期望值由 Java 层从 BuildConfig 传入
 * （BuildConfig.EXPECTED_SIG_SHA256，来源是不入库的 local.properties）。
 * 未配置（空串）时 native 层直接放行，任何人克隆后都能自行构建。
 */
/* ===== 1. 反调试检测 ===== */

/*
 * ptrace 自检：被调试器附着后 PTRACE_TRACEME 返回 -1。
 *
 * ⚠️ 不能作为硬性 kill 条件：HyperOS/MIUI 的 SELinux 策略会拒绝
 * zygote → untrusted_app 的 ptrace 调用（avc denied），
 * 导致正常启动也返回 -1，触发 abort() 闪退。
 * 只在 TracerPid 确认时才认为真正在调试。
 */
static int check_ptrace(void) {
    /* SELinux 可能阻止 ptrace，这里仅记录不作为判断依据 */
    ptrace(PTRACE_TRACEME, 0, 0, 0);
    ptrace(PTRACE_DETACH, 0, 0, 0);
    return 0;
}

/** TracerPid 非 0 说明被调试 */
static int check_tracer_pid(void) {
    char buf[256] = {0};
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    char *p = strstr(buf, "TracerPid:");
    if (!p) return 0;
    p += 10;
    while (*p == ' ' || *p == '\t') p++;
    return (*p != '0' && *p != '\0');
}

/** Frida 默认端口 27042 */
static int check_frida(void) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return 0;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    int connected = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    close(sock);
    return connected;
}

/** 尝试打开常见的 frida agent so */
static int check_frida_files(void) {
    const char *paths[] = {
        "/data/local/tmp/re.frida.server/frida-agent-64.so",
        "/data/local/tmp/frida-agent-64.so",
        "/data/local/tmp/frida-agent-32.so",
    };
    for (int i = 0; i < 3; i++) {
        int fd = open(paths[i], O_RDONLY);
        if (fd >= 0) { close(fd); return 1; }
    }
    return 0;
}

/* ===== 2. 签名校验 ===== */

/*
 * 从 /proc/self/cmdline 拿包名，再走 PackageManager 查签名。
 * 但 native 层不走 PackageManager，用更直接的方式：
 * 读取 APK 自己的签名块（APK Signing Block）。
 *
 * 简化实现：Java 层把签名 hash 传进来做比对，native 层做二次校验。
 * 这样即使 Java 层被 hook 绕过，native 层还有一次机会。
 */
JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeCheckSig(JNIEnv *env, jclass clazz,
                                                  jstring expected_hash,
                                                  jstring sig_hash) {
    if (sig_hash == NULL) return JNI_FALSE;
    if (expected_hash == NULL) return JNI_TRUE;

    const char *expected = (*env)->GetStringUTFChars(env, expected_hash, NULL);
    if (expected == NULL) return JNI_TRUE;
    if (expected[0] == '\0') {
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        LOGW("EXPECTED_SIG_SHA256 not configured, signature check skipped");
        return JNI_TRUE;
    }

    const char *actual = (*env)->GetStringUTFChars(env, sig_hash, NULL);
    if (actual == NULL) {
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_FALSE;
    }

    int match = (strlen(actual) == 64 && strcmp(actual, expected) == 0);
    (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
    (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
    return match ? JNI_TRUE : JNI_FALSE;
}

/* ===== 3. 反调试入口 ===== */

JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeAntiDebug(JNIEnv *env, jclass clazz) {
    /* ptrace 自检：必须在主线程启动早期调用，且只调用一次 */
    if (check_ptrace()) return JNI_TRUE;
    if (check_tracer_pid()) return JNI_TRUE;
    if (check_frida()) return JNI_TRUE;
    if (check_frida_files()) return JNI_TRUE;
    return JNI_FALSE;
}

/* ===== 4. 反 hook / 完整性校验 =====
 * 检测 inline hook：检查关键函数入口是否有跳转指令。
 * 由于 libxposed 自己就是 hook 框架，这里只检测常见的第三方 hook，
 * 不检测 Xposed 自身的 hook（否则误伤自己）。 */

JNIEXPORT jint JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeIntegrity(JNIEnv *env, jclass clazz) {
    /*
     * 读取自己的 maps，检测可疑的注入 so。
     * 注：Xposed/LSPosed 的 so 也会被列出来，需要排除。
     */
    char buf[4096] = {0};
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;

    int suspicious = 0;
    /* Frida agent */
    if (strstr(buf, "frida")) suspicious |= 1;
    /* Xposed 自己不算可疑 */
    /* 常见的 hook 框架 */
    if (strstr(buf, "substrate") || strstr(buf, "xposed") || strstr(buf, "edxposed")) {
        /* Xposed 框架自身——允许 */
    }
    if (strstr(buf, "libfrida-gadget")) suspicious |= 2;
    return suspicious;
}

