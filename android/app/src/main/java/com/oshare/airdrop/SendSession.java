package com.oshare.airdrop;

/**
 * 发送这件事此刻在不在。
 *
 * <p># 为什么要一个独立的类
 *
 * <p>传输界面要在多个地方回答同一个问题：「现在有没有人等着看传输」。自己进程里的答案来自
 * {@link ReceiveService} 与发送页，但那个答案带着资源 id、Receiver 和 SharedPreferences，
 * 不适合塞进别的进程。所以这里只留两个布尔，谁都能读，读到的是各自进程里的事实。
 *
 * <p># 两个布尔不是一件事
 *
 * <p>{@code open} 说的是「页面在人眼前」：人随时可能点一台设备，云要提前在那儿。
 * {@code busy} 说的是「字节正在走」：页面可以被切走、甚至被系统回收，云不能跟着消失。
 * 合成一个布尔会让「发送中把应用划掉」变成云也断了，那正是最难解释的一种断法。
 */
final class SendSession {

    private static volatile boolean open;
    private static volatile boolean busy;

    private SendSession() {}

    /** 发送页到了人眼前。 */
    static void opened() {
        open = true;
    }

    /** 发送页离开了。传输还没完的时候不能连带把它也清掉。 */
    static void closed() {
        open = false;
    }

    /** 一个传输开始了。 */
    static void began() {
        busy = true;
    }

    /** 一个传输结束了，无论成败。 */
    static void ended() {
        busy = false;
    }

    static boolean active() {
        return open || busy;
    }

    static boolean busy() {
        return busy;
    }
}
