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
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <pthread.h>
#include <errno.h>

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
 * ptrace 自检已移除（L22）：
 * PTRACE_TRACEME 会让父进程成为自己的 tracer，PTRACE_DETACH 用 pid=0
 * 又撤不掉，随后 check_tracer_pid 读到自己刚造的 tracer → 每次启动都误报。
 * 而且 HyperOS/MIUI 的 SELinux 会拒绝 zygote → untrusted_app 的 ptrace
 * 调用（avc denied），自检结果本身不可信。
 * TracerPid 由 check_tracer_pid 判断，这里恒返回 0，不作为判断依据。
 */
static int check_ptrace(void) {
    return 0;
}

/** TracerPid 非 0 说明被调试 */
static int check_tracer_pid(void) {
    char buf[256] = {0};
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n;
    // N19 修复：read 被信号打断（EINTR）时重试，不要误判为检测失败。
    do { n = read(fd, buf, sizeof(buf) - 1); } while (n < 0 && errno == EINTR);
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
    /* connect 加 1s 超时 + FD_CLOEXEC（L24）：无超时的 connect 会把主线程
     * 挂死在网络栈上，注入方甚至可以靠黑洞端口拖慢启动。
     * 超时或失败一律视为「未检测到 frida」。 */
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int fl = fcntl(sock, F_GETFD);
    if (fl >= 0) fcntl(sock, F_SETFD, fl | FD_CLOEXEC);
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
        /* 普通应用对 /data/local/tmp 里的文件必然 EACCES（L23）：
         * open 失败不代表文件不存在，更不代表可疑。先判可读性，
         * 不可读直接跳过，避免把「打不开」误判成「检出 frida」。 */
        if (access(paths[i], R_OK) != 0) continue;
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
 *
 * 归一化（去冒号、转小写）在 Java 层统一完成，此处只做防御性 strncmp。
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

    /* 归一化（去冒号、转小写）已在 Java 层完成，这里只做防御性比对（H10/M21）：
     * strncmp 限长 64，避免依赖 actual 的结尾；actual 必须恰好 64 位。
     * expected 长度不是 64 属于构建配置错误——LOGE 后放行，不杀进程
     * （杀进程惩罚的是用户，不是配置错误）。 */
    if (strlen(expected) != 64) {
        LOGE("EXPECTED_SIG_SHA256 malformed (len=%zu), expected 64 hex chars; skip check", strlen(expected));
        (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_TRUE;
    }

    int match = (strlen(actual) == 64 && strncmp(actual, expected, 64) == 0);
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
     *
     * 整个 maps 循环读到 EOF（M20）：实测 maps 有 500KB+，只读前 4096 字节
     * 覆盖率不到 1%，注入的 so 极可能在后半段，等于没检测。
     * 用 prev[16] 保存每块末尾 15 字节做跨块衔接，防止 "frida" 被块边界切断。
     *
     * 返回值：>0 = 检出可疑位掩码；-1 = 检测本身失败（不是「可疑」，
     * Java 侧只对 >0 告警，见 L25）；0 = 未检出。
     */
    int suspicious = 0;
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return -1;
    char buf[4096];
    char prev[16] = {0};   /* 跨块半词缓冲 */
    ssize_t n;
    // N19 修复：EINTR 时继续读，其他错误才中止。
    while (true) {
        n = read(fd, buf, sizeof(buf) - 1);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;

        buf[n] = '\0';
        char joined[sizeof(prev) + sizeof(buf)];
        size_t plen = strlen(prev);
        memcpy(joined, prev, plen);
        memcpy(joined + plen, buf, n + 1);
        if (strstr(joined, "frida")) suspicious |= 1;
        if (strstr(joined, "libfrida-gadget")) suspicious |= 2;
        /* 保留最后 15 字节作为下轮 prev */
        size_t keep = n >= 15 ? 15 : (size_t)n;
        memcpy(prev, buf + n - keep, keep);
        prev[keep] = '\0';
    }
    close(fd);
    if (n < 0) return -1;   /* 读失败，不当作「未检出」的证据 */
    return suspicious;
}

