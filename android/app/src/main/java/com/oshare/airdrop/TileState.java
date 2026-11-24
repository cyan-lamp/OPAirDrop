package com.oshare.airdrop;

/**
 * 磁贴的三态判定（评审 P1-24：从 {@code AirdropTile} 里切出来的纯函数层）。
 *
 * <p>零 Android 依赖（Tile 的状态常量用文档值写成字面量），真值表钉在
 * {@code AirdropTileTest} 里：想收而服务没起来、在听而链路不在，都是**可点的灰**
 * ——STATE_UNAVAILABLE 会让 {@code Tile.click()} 直接 return，把「点一下重试」写在
 * 一格点不动的灰块上是旧版的真 bug。
 */
final class TileState {

    private TileState() {
    }

    /** 文档值：{@code Tile.STATE_INACTIVE = 1}、{@code Tile.STATE_ACTIVE = 2}。 */
    static final int INACTIVE = 1;
    static final int ACTIVE = 2;

    /**
     * 三态判定：想收而服务没起来、在听而链路不在，都回可点的灰；只有「在听且链路在」
     * 才亮绿灯。
     */
    static int stateOf(boolean wanted, boolean live, boolean linked) {
        if (wanted && !live) {
            return INACTIVE;
        }
        if (live && !linked) {
            return INACTIVE;
        }
        return live ? ACTIVE : INACTIVE;
    }

    /** 与 {@link #stateOf} 配对的副标题；绿灯返回 {@code null}（系统用默认文案）。 */
    static String subtitleOf(boolean wanted, boolean live, boolean linked, boolean settling,
            String trouble) {
        if (wanted && !live) {
            // 刚点过「开」的两秒里，服务还可能在那次异步绑定的路上，这一段不该被说成失败。
            return settling ? "正在启动…" : "监听没起来，点一下重试";
        }
        if (live && !linked) {
            return trouble == null || trouble.isEmpty() ? "链路未建立，点这里建链" : trouble;
        }
        return null;
    }
}
