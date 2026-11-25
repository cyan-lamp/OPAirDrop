# Release 混淆规则（交付版开 R8）
#
# 只保住「名字本身就是契约」的东西：Rust 按类名+方法名找它们，
# 改名不是优化而是把跨语言调用拆掉。其余代码没有按名字反射的依赖，交给 R8 裁剪。

# JNI 符号是 Java_com_oshare_airdrop_<class>_<method>：类名、方法名、参数类型都不能改。
-keep class com.oshare.airdrop.Native { *; }

# Rust 在 bleAdvertise 里 FindClass("com/oshare/airdrop/BleRadio") 并按名字取
# start(Context,int,byte[],int,int,boolean,int) / stop()。
-keep class com.oshare.airdrop.BleRadio {
    public static void start(...);
    public static void stop();
}

# Rust 拿 Java 传下来的对象取类后按名字找方法（Decide.accept / Landing.landed /
# Watcher.say 等）；实现类的方法名必须留着。
-keep class com.oshare.airdrop.Decision { *; }
-keep class com.oshare.airdrop.Meter { *; }

# 栈还原要用的类型信息；行号会被 R8 重写，不强制保留。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
