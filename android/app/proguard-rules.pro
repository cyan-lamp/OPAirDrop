# Release 混淆规则（交付版开 R8）
#
# 只保住「名字本身就是契约」的东西：Rust 按类名+方法名找它们，
# 改名不是优化而是把跨语言调用拆掉。其余代码没有按名字反射的依赖，交给 R8 裁剪。
#
# 2026-11-25 事故：最初给 BleRadio 写的是 `public static void start(...)`，
# R8 的 (...) 不是「任意参数」而是「空参数表」——真正的 start(Context,int,
# byte[],int,int,boolean,int) 被裁，运行时 NoSuchMethodError + JNI pending
# 异常下继续调用 → SIGABRT。对 JNI 按名字调用的类和接口，一律整类 keep。

# JNI 符号是 Java_com_oshare_airdrop_<class>_<method>：类名、方法名、签名都不能改。
# Native 的嵌套接口（Progress/Link/Decision/Landing）是 Rust GetMethodID 的目标，
# 必须显式 keep——`{ *; }` 不自动覆盖嵌套类。
-keep class com.oshare.airdrop.Native { *; }
-keep class com.oshare.airdrop.Native$** { *; }

# Rust FindClass("com/oshare/airdrop/BleRadio") + GetStaticMethodID(start/stop)。
-keep class com.oshare.airdrop.BleRadio { *; }

# 回调实现类：Rust 从 Java 传下来的对象取 GetObjectClass，再按名字找
# report(String)V / name()V / accept(String)Z / landed(JJ)V / connect()Socket。
# keep 接口不够——实现类的方法也要保住原名。
-keep class com.oshare.airdrop.Meter { *; }
-keep class com.oshare.airdrop.Decision { *; }
-keep class com.oshare.airdrop.DecisionReceiver { *; }

# LSPosed 从 META-INF/xposed/java_init.list 按字符串加载 FluidModule：
# APK 内没有任何代码引用它，R8 判成未使用整个裁掉（usage.txt 里那行就是证据），
# 结果模块 class not found、流体云卡不再自动展开。外部按名字加载的类必须 keep。
# 2026-11-25：release 开混淆后 hook 失效，真因就是这个。
-keep class com.oshare.airdrop.FluidModule { *; }

# 栈还原要用的类型信息；行号会被 R8 重写，不强制保留。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# JNI 侧按名字构造的异常：R8 会把没人静态引用的类裁掉，
# 然后「对方拒绝」就变成 ClassNotFoundException，界面上显示成「发送失败」。
# （2026-11-27 真机：iPhone 点拒绝，本机显示「发送失败」而不是「对方拒绝」。）
-keep class com.oshare.airdrop.RefusedException { *; }
-keep class com.oshare.airdrop.PeerMismatch { *; }
-keep class com.oshare.airdrop.Report { *; }
