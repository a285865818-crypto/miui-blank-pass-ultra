/*
 * obf.h —— C 项字符串 XOR 混淆的运行时解密层。
 *
 * C 预处理器无法对字符串字面量逐字符展开（没有 map/反射），
 * 因此采用「char 数组逐字节 XOR 0x5A」路线：
 *   - 密文表由 gen_obf.py 离线生成（obf_strings.h），.rodata 只存密文；
 *   - 运行时 obf_dec() 解密到调用者提供的栈缓冲，用完 obf_wipe() 清零；
 *   - 明文只存在于 gen_obf.py 与源码注释，不进二进制。
 *
 * 所有 x* 包装宏在调用处以 sizeof(enc) 取密文长度（编译期常量），
 * enc 必须是数组名（不能是指针），保证 sizeof 语义正确。
 */
#ifndef MBPU_OBF_H
#define MBPU_OBF_H

#include <stddef.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <android/log.h>

#define OBF_KEY ((unsigned char)0x5A)

/* 解密 n 字节到 dst（dst 至少 n+1），末尾补 0。
 *
 * volatile 读 + 编译器屏障是本方案的命门：
 * 若用普通 const 读，-O2/LTO 会把整个解密循环常量折叠，
 * 直接把「解密后的明文」当字符串常量放回 .rodata（实测踩坑）。
 * volatile 强制真实读内存，屏障阻断后续常量传播，明文只存在于栈上。 */
static inline void obf_dec(char *dst, const volatile unsigned char *enc, size_t n) {
    for (size_t i = 0; i < n; i++)
        dst[i] = (char)(enc[i] ^ OBF_KEY);
    dst[n] = '\0';
    __asm__ __volatile__("" : : "r"(dst) : "memory");
}

/* 用完清零；volatile 防止编译器把这个「无用写」优化掉。 */
static inline void obf_wipe(volatile void *p, size_t n) {
    volatile unsigned char *q = (volatile unsigned char *)p;
    while (n--)
        *q++ = 0;
}

/* strstr：needle 为密文数组。命中返回 1。 */
static inline int xstrstr_n(const char *hay, const unsigned char *enc, size_t n) {
    char nd[96];
    int hit;
    if (n >= sizeof(nd)) return 0;
    obf_dec(nd, enc, n);
    hit = (hay != NULL) && (strstr(hay, nd) != NULL);
    obf_wipe(nd, sizeof(nd));
    return hit;
}
#define xstrstr(hay, enc) xstrstr_n((hay), (enc), sizeof(enc))

/* 精确相等比较：a 为明文 C 串，enc 为密文数组。 */
static inline int xstreq_n(const char *a, const unsigned char *enc, size_t n) {
    char b[32];
    int r;
    if (n >= sizeof(b)) return 0;
    obf_dec(b, enc, n);
    r = (a != NULL) && (strcmp(a, b) == 0);
    obf_wipe(b, sizeof(b));
    return r;
}
#define xstreq(a, enc) xstreq_n((a), (enc), sizeof(enc))

/* open：path 为密文数组。 */
static inline int xopen_n(const unsigned char *enc, size_t n, int flags) {
    char p[128];
    int fd;
    if (n >= sizeof(p)) return -1;
    obf_dec(p, enc, n);
    fd = open(p, flags);
    obf_wipe(p, sizeof(p));
    return fd;
}
#define xopen(enc, flags) xopen_n((enc), sizeof(enc), (flags))

/* access：返回 0 表示存在。 */
static inline int xaccess_n(const unsigned char *enc, size_t n) {
    char p[128];
    int r;
    if (n >= sizeof(p)) return -1;
    obf_dec(p, enc, n);
    r = access(p, R_OK);
    obf_wipe(p, sizeof(p));
    return r;
}
#define xaccess(enc) xaccess_n((enc), sizeof(enc))

/* opendir：path 为密文数组。 */
static inline DIR *xopendir_n(const unsigned char *enc, size_t n) {
    char p[128];
    DIR *d;
    if (n >= sizeof(p)) return NULL;
    obf_dec(p, enc, n);
    d = opendir(p);
    obf_wipe(p, sizeof(p));
    return d;
}
#define xopendir(enc) xopendir_n((enc), sizeof(enc))

/*
 * XLOG：日志文本密文化。fmt 为密文数组，运行时解密到栈再送 logcat，
 * 打完立即清零。__VA_ARGS__ 直接透传给格式串（## 允许零参数）。
 * fmt 是运行时缓冲，编译器无法静态检查格式，属预期行为。
 */
#define XLOG(prio, enc, ...)                                                \
    do {                                                                    \
        char _xf[256];                                                      \
        obf_dec(_xf, (enc), sizeof(enc));                                   \
        _Pragma("clang diagnostic push")                                     \
        _Pragma("clang diagnostic ignored \"-Wformat-security\"")            \
        __android_log_print((prio), "MBPU_SEC", _xf, ##__VA_ARGS__);        \
        _Pragma("clang diagnostic pop")                                      \
        obf_wipe(_xf, sizeof(_xf));                                         \
    } while (0)

#endif /* MBPU_OBF_H */
