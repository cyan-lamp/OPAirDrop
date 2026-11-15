package com.oshare.airdrop;

import java.util.Locale;

/**
 * 把 AirDrop 的 ShareTarget 类型翻成互传自己的设备类型。
 *
 * <p># 为什么需要一张表
 *
 * <p>两边说的不是同一套数：AirDrop 的 {@code ShareTarget} 只有手机、平板、电脑三档，而互传
 * 画设备图标时分的四类（{@code oshare_icon_apple}、{@code _pad}、{@code _macbook}、
 * {@code _imac}）里，电脑那两档只能靠型号名区分——AirDrop 的 {@code /Discover} 会带
 * {@code ModelName}，那就是这里唯一能用上的证据。
 *
 * <p># 为什么只剩设备类型
 *
 * <p>这份类是从发送侧的实现里剪出来的，只留下接收路径真正读到的一段：接收弹窗与流体云卡片
 * 要画的是「谁在发」，那是设备类型；发送侧另外还负责状态文案与点击去抖，接收路径一个都用不到，
 * 留着就是一份没人读的代码配上一条没人验证的分支。
 */
public final class AirDropPresentation {

    public enum Kind {
        PHONE(1, "oshare_icon_apple"),
        TABLET(2, "oshare_icon_apple_pad"),
        LAPTOP(3, "oshare_icon_apple_macbook"),
        DESKTOP(4, "oshare_icon_apple_imac"),
        UNKNOWN(0, "oshare_icon_default");
        public final int oshareType;
        public final String drawable;
        Kind(int oshareType, String drawable) { this.oshareType = oshareType; this.drawable = drawable; }
    }

    private AirDropPresentation() {}

    public static Kind kind(int shareTargetType, String model, String name) {
        if (shareTargetType == 1) return Kind.PHONE;
        if (shareTargetType == 2) return Kind.TABLET;
        String hint = ((model == null ? "" : model) + " " + (name == null ? "" : name))
                .toLowerCase(Locale.ROOT);
        if (shareTargetType == 3 || shareTargetType == 0) {
            if (hint.contains("imac") || hint.contains("mac mini") || hint.contains("macmini")
                    || hint.contains("mac studio") || hint.contains("macstudio")
                    || hint.contains("mac pro") || hint.contains("macpro")) return Kind.DESKTOP;
            if (shareTargetType == 3 || hint.contains("macbook")) return Kind.LAPTOP;
        }
        if (shareTargetType == 0) {
            if (hint.contains("ipad")) return Kind.TABLET;
            if (hint.contains("iphone")) return Kind.PHONE;
        }
        return Kind.UNKNOWN;
    }

    /**
     * 已经认出来的类型优先于这一次猜出来的未知。
     *
     * <p>同一个对端在发现阶段和连接阶段能被读到的信息不一样：{@code /Discover} 带型号名，
     * 而建立会话之后有些字段就空了。一个「上次是 MacBook、这次说不清」的对端，画 MacBook 比
     * 画一个问号更接近事实。
     */
    public static Kind preferKnown(Kind previous, Kind candidate) {
        return candidate == Kind.UNKNOWN && previous != null ? previous : candidate;
    }
}
