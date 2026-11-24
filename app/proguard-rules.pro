# Sonicraft 混淆规则（U8q：release 深度混淆启用）

# ---- JNI 符号绑定（按名称反射/动态链接，混淆即 UnsatisfiedLinkError）----
# sonicraft-dsp.so 以 Java_com_jiangjie_Sonicraft_core_dsp_* 符号链接 Java 层
-keep class com.jiangjie.Sonicraft.core.dsp.** { *; }
# native 崩溃捕获入口（nativeInstall 符号绑定）
-keep class com.jiangjie.Sonicraft.CrashCapture { *; }

# ---- 深度混淆强化 ----
# 优化 5 轮 + 允许调整访问级别（internal/private 合并提升，减小体积并提高混淆强度）
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively
# 全部混淆类重排到根包，抹掉原包结构语义
-repackageclasses ''
# 混淆名用无意义字符组合（app/dictionaries，防 a/b/c 可猜序列）
-obfuscationdictionary dictionaries/obfuscation-dict.txt
-classobfuscationdictionary dictionaries/class-dict.txt
# 注：R8 不支持 -packagedictionary（ProGuard 7 专属），包名打乱由 -repackageclasses 覆盖

# 保留行号辅助崩溃定位（类名仍混淆；配合 mapping.txt 反解，文件在 app/build/outputs/mapping/release/）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 特权通道（跨进程按「类名/接口描述符」寻址，混淆即失联）----
# 2026-11-05 修复 release 闪退：`-repackageclasses ''` + 深度混淆会把下面这些
# 改名/挪包，而它们的名字是**以字符串形式跨进程传递**的，改名即找不到：
#
# 1) Stellar 在我们 App 之外的进程里实例化 UserService，靠的是类名字符串
#    （UserServiceArgs.Builder(LoopbackProbeUserService::class.java)），
#    改名 → Stellar 侧 ClassNotFoundException → 打开 App 闪退。
-keep class com.jiangjie.Sonicraft.privilege.** { *; }

# 2) Stellar API 的 Parcelable（BinderContainer）：类名会被写进 Parcel，
#    收发两端必须同名。混错会报
#    `BadParcelableException: ClassNotFoundException when unmarshalling: com.stellar.api.BinderContainer`
-keep class com.stellar.** { *; }
-keep interface com.stellar.** { *; }

# 3) AIDL 生成类的 descriptor 用「接口全名」，改名后服务端拒绝匹配
-keep interface com.jiangjie.Sonicraft.privilege.** { *; }

# 5) Shizuku 后端靠**反射**调 `Shizuku.newProcess`（API 13.1.5 起该方法转 private），
#    方法名一旦被 R8 重命名，getDeclaredMethod 直接抛 NoSuchMethodException
#    → Shevery/Shizuku 后端起不来。用户只装 Shevery 时会导致整个 App 不可用。
-keep class dev.rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# 4) Parcelable 的 CREATOR 字段名必须保留，否则反序列化取不到
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
