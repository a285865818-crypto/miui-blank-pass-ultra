# ===== libxposed 入口（必须保留，否则模块加载不了）=====
# LSPosed 通过反射实例化模块入口类，类名与构造器不能被混淆。
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class com.sweetpotato.MBPU.PermNullModule { *; }

# service 层的 Provider 由系统按清单实例化。
-keep class io.github.libxposed.service.** { *; }

# ===== 混淆强度 =====
# 类成员统一重命名（默认已开启）。这里额外压缩包名层级，
# 让逆向时看到的包结构失去语义（com.sweetpotato.MBPU.xxx -> a.b.c）。
-repackageclasses ''
-allowaccessmodification

# 去掉调试元数据：行号表、源文件名是逆向定位的主要线索。
# 保留 InnerClasses/EnclosingMethod，否则 Compose/内部类会出问题。
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-renamesourcefileattribute SourceFile
-dontwarn io.github.libxposed.**

# Kotlin/Compose 元数据可以删（运行时不依赖它做反射）。
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.**

# 关闭 R8 对 libxposed 的优化警告
-dontnote io.github.libxposed.**

# ===== native 安全层 =====
# JNI 方法名必须保持原名，否则 native 链接失败。
-keep class com.sweetpotato.MBPU.Security {
    private static native <methods>;
    public static void verify(android.content.Context);
}
-keepclasseswithmembers class com.sweetpotato.MBPU.Security {
    native <methods>;
}
