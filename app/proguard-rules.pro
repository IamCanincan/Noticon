# LibXposed 102：保留模块入口类与资源清单
-dontwarn io.github.libxposed.annotation.**
# 保留入口类的原始类名（不要 allowobfuscation，否则 java_init.list 会被改成混淆名）
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
