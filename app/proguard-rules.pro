# ===== libxposed 入口（必须保留，否则模块加载不了）=====
# LSPosed 通过反射实例化模块入口类，类名与构造器不能被混淆。
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class io.github.sweetpotato_digua.mbpu.PermNullModule { *; }

# service 层的 Provider 由系统按清单实例化。
-keep class io.github.libxposed.service.** { *; }

# ===== 混淆强度（最大） =====
# 多轮优化（默认 1 轮，5 轮效果更好但构建更慢）
-optimizationpasses 5
# 激进接口合并与重载：相同签名不同类的方法名强制重名，加大逆向难度
-mergeinterfacesaggressively
-overloadaggressively
# 移除所有参数名
# （R8 默认已移除，这里显式声明确保无遗漏）

# 类成员统一重命名（默认已开启）。这里额外压缩包名层级，
# 让逆向时看到的包结构失去语义（io.github.sweetpotato_digua.mbpu.xxx -> a.b.c）。
-repackageclasses ''
-allowaccessmodification

# ===== 剥离所有日志（release 零日志泄露）=====
# 用 -assumenosideeffects 告知 R8 所有 Log.x() 无副作用，优化后完全移除。
# 注意：必须在 -optimizationpasses 之后、保持规则之前才有效。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ===== 移除源文件属性（不只是改名，彻底删） =====
-renamesourcefileattribute ''

# 去掉调试元数据：行号表、源文件名是逆向定位的主要线索。
# 保留 InnerClasses/EnclosingMethod，否则 Compose/内部类会出问题。
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
# 移除行号表（比保留但改名更彻底）
-renamesourcefileattribute SourceFile
# 等等，保留行号表方便你自己调试崩溃，要删就取消注释：
# -keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn io.github.libxposed.**

# Kotlin/Compose 元数据可以删（运行时不依赖它做反射）。
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**

# 关闭 R8 对 libxposed 的优化警告
-dontnote io.github.libxposed.**

# ===== native 安全层 =====
# JNI 方法名必须保持原名，否则 native 链接失败。
# 所有 native 方法（包括新增的完整性/反转储/时序检测）都必须保留。
-keep class io.github.sweetpotato_digua.mbpu.Security {
    private static native <methods>;
    public static void verify(android.content.Context);
}
-keepclasseswithmembers class io.github.sweetpotato_digua.mbpu.Security {
    native <methods>;
}
# StrEnc 解密方法必须保留（运行时解密字符串）
-keep class io.github.sweetpotato_digua.mbpu.StrEnc {
    public static String dec(byte[]);
    public static byte[] enc(java.lang.String);
}

# ===== 字符串加密（Java 层敏感常量） =====
# StrEnc 类中的解密方法不能被移除或重命名
-keep class io.github.sweetpotato_digua.mbpu.StrEnc {
    public static String dec(...);
    private static byte[] key;
}
