package com.oshare.airdrop;

/**
 * 像素换算的唯一入口（评审 4.2 曾有三份 {@code dp()}：SendActivity、MainActivity、Chrome）。
 *
 * <p>密度由调用方传进来而不是这里取系统值——SendActivity 声明了
 * {@code configChanges="density"}（改字体/显示大小不重建），它的密度必须用自己缓存的那份；
 * 评审 4.4 指出的「density 缓存」正是这份缓存的用途，不是 bug。
 */
final class Ui {

    private Ui() {
    }

    static int dp(float density, float value) {
        return Math.round(density * value);
    }
}
