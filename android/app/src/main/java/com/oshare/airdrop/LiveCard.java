package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 我们自己的那张云：一条系统绘制的实时通知（Android 16 起的 Live Updates）。
 *
 * <p># 为什么不抄互传的卡片
 *
 * <p>互传那张卡是它自己的窗口，画在它的进程里。要用它，就得先让互传活着、把它叫醒、再钩住
 * 它画卡的那一层——这条链上有三处会坏（进程在不在、binder 通不通、钩子对不对得上这一版
 * ROM），而且**没有一处会告诉我们它到底画出来了没有**。2026-11-16 13:14 就是这样：`attached`
 * 为真、事件也送到了互传进程，屏幕上什么都没有，超时后按时过期。
 *
 * <p>Android 16 把这件事收进了系统：一条「进度型」的常驻通知 + {@code setRequestPromotedOngoing}，
 * 系统就把它提升为实时通知，ColorOS 把它画在流体云那个位置。画的人是系统，所以我们不需要知道
 * 它是怎么画的，也不需要它在不在——通知发出去就是发出去了。
 *
 * <p># 它和普通通知的关系
 *
 * <p>同一个通知 id、同一个渠道。系统愿意提升，它出现在状态栏那颗胶囊里；不愿意（用户关了、
 * 这台机器不支持），它仍然是一条带着进度的常驻通知，人照样点得到。**所以这条路没有失败态**：
 * 最差也等于今天兜底的那条通知。
 *
 * <p># 什么时候会拿不到
 *
 * <p>{@link NotificationManager#canPostPromotedNotifications()} 为假。那是系统层面的开关
 * （用户可以在设置里关掉某个应用的实时通知），调用方据此决定要不要换一套观感，而不是猜。
 *
 * <p># 展开是怎么发生的
 *
 * <p>两件事在这里被分开，过去它们被当成了一件：
 *
 * <p><b>抬头</b>（状态栏上那一下 banner）由渠道重要性和 {@code setOnlyAlertOnce} 决定，
 * {@link #announce} 用 {@code setOnlyAlertOnce(false)} 投一次，系统就抬。这部分在应用侧就能做。
 *
 * <p><b>摊开</b>（胶囊长成那张带进度条和按钮的大卡）是系统的事，应用侧没有这个开关。
 * 2026-11-18 之后这里不再试图去按它：原先那条路是发广播让 Xposed 模块进 SystemUI 调
 * 插件 ViewModel 的 onExpand，看着能让卡片摊开，代价是**通知中心里那张卡不见了、点空白
 * 区域收不回去**——因为它只在「展开」这一帧被摆好了姿态，系统其余的状态迁移没有跟着走。
 * 一个能摊开但收不回去、还在通知中心缺席的卡，比一颗需要人点一下的胶囊更糟。
 *
 * <p>现在唯一会影响摊开的，是包内那个**可选的** LSPosed 模块（{@code FluidModule}）。它
 * 不调 onExpand，只把系统算出来的 {@code remindType} 从 0 抬到 11，剩下交给系统自己的状态
 * 机去摆——所以通知中心里那张卡还在、点空白也收得回去。启用它，卡片在桌面态就展开、本应用
 * 在前台也照弹；不启用，这一页的代码一个字都不用变，只是停在胶囊上，而且本应用在前台时
 * 系统会把那张卡整张丢掉。
 *
 * <p>所以这里只投一张**规矩的**实时通知：{@code ProgressStyle} +
 * {@code setRequestPromotedOngoing} + {@code setShortCriticalText}，全部是
 * {@code android.jar} 里的公开 API。系统愿意提升就在状态栏那颗胶囊里，愿意展开它自己会展开；
 * 用户点一下也能展开。**不再往 extras 里塞任何 OPPO 私有键**（见下面 markFluid 的历史）：
 * 那些键不仅会被 SystemUI 重新计算后覆盖，其中 {@code remindType=13}（强提醒·始终）和
 * 非空的 {@code fullScreenIntent} 还会把它钉在「始终提醒」的姿态上——这正是「点空白
 * 区域不收缩」的直接原因。
 */
final class LiveCard {

    private static final String TAG = "OPAirDrop";

    /**
     * 一张卡一条渠道，重要性高。
     *
     * <p>名字带版本号（{@code _2}）不是洁癖：渠道一旦被建出来，重要性就**改不动**了——同一个
     * id 再调一次 {@code createNotificationChannel} 只会更新名字和描述，重要性取原来那个。
     * 这台机器上 {@code live} 那条是低重要性建出来的（用来验证「胶囊不展开是不是因为重要性
     * 太低」那一轮），沿用下去就永远抬不起头，所以换一条新的，并把旧的删掉。
     */
    private static final String CHANNEL = "live_card_2";

    /**
     * 邀请卡专用的一条渠道：**这条会响、会震**。
     *
     * <p>为什么不能把 {@link #CHANNEL} 改响：那张卡每秒要刷五次进度，同一条渠道要是会响，
     * 就成了一只每秒叫一声的门铃。而「有人要发东西给你」这个时刻恰好相反——它只发生一次，
     * 且值得把人从别处叫回来。两件事对声音的需求是相反的，所以分成两条渠道，而不是加开关。
     *
     * <p>渠道的声音和振动一旦建出来就改不动（和重要性一样），所以这条也带版本位；将来要换
     * 提示音就再加一条，别原地改。
     */
    private static final String ALERT_CHANNEL = "live_card_alert_1";

    /** 历史渠道：留着只会在应用的通知设置里堆一排不再有人发的东西。 */
    private static final String[] LEGACY_CHANNELS = {
            "live", "live_loud", "live_banner", "opairdrop_send"};

    /** 上面那几条只扫一次。这个类每秒要投五次通知，不该每次附送四次 binder 往返。 */
    private static boolean swept;
    /**
     * 上一次投出去那张卡的「按钮行长什么样」，见 {@link #post} 里那一段。
     *
     * <p>{@code null} 是「还没投过」——第一张卡无论如何都要撤一次（那时本来也没东西可撤，
     * 代价是一次空 binder）。
     */
    private static String lastShape;

    /** 两次响铃之间至少隔这么久，免得同一件事被重投时连叫两声。 */
    private static final long CHIME_GAP = 1500L;

    /**
     * 振动节奏，按 {@code R.raw.chime_airdrop} 那四下敲击的时刻排的。
     *
     * <p>实测音效起振在 12 / 130 / 245 / 369ms（间隔 118/115/124）。所以这里用前导 12ms
     * 空档对上第一下，之后每下震 45ms、按「下一个起振点 − 上一段结束」精确补空档，
     * 四个起点一毫秒不差地压在四次敲击上。
     *
     * <p>为什么每下只写 45ms 而不是音效那样的 ~100ms：ColorOS 收到**带振幅**的 waveform
     * 会把它拆成它自己的预烘焙脉冲（dumpsys 里是 {@code OplusPrebakedSegment}，
     * {@code mDuration=45}），无论你请求多长都只给 45ms，但「停」的段原样保留。第一版写的
     * 62ms 就吃了这个亏：四段各被砍 17ms，到第四下已经提前 51ms，听起来跟音效脱拍。
     * 按 45ms 写，两种设备都对——ColorOS 上原样执行，原生 Android 上（不压缩）节奏点也一致，
     * 只是震感短一点。
     *
     * <p>振幅递减跟着音效的 -4.2 / -4.8 / -6.4 / -11.8dB 走，最后一下压到 120：
     * 那一下本来就是个余响，震太实反而像四下等重的机械提示音。
     */
    private static final long[] CHIME_VIBRATE_TIMINGS = {12, 45, 73, 45, 70, 45, 79, 45};
    private static final int[] CHIME_VIBRATE_AMPLITUDES = {0, 255, 0, 235, 0, 200, 0, 120};

    /** 上一次响铃的时刻（{@code uptimeMillis}）。0 表示还没响过。 */
    private static long sLastChime;

    /** 正在播的那个播放器。留着它是为了下一条来时能把上一声掐掉，别两路声音叠着响。 */
    private static MediaPlayer sChimePlayer;

    /*
     * 曾经在这里的三个探针开关已经删掉了：priorityProbe（PRIORITY_HIGH）、
     * categoryProbe（CATEGORY_CALL）、loudChannelProbe（换一条会响的渠道）。
     *
     * 它们试的是同一件事的三个变量：能不能让系统自己把这张卡当成「值得打断人」的提醒
     * ——OplusLiveAlertNotificationsRepository.addLiveAlert 的 remindType 一直是 0，而 0 的
     * 唯一入口是 makeUnLoggableAndUnFluidConditionDecision 判了「别打断」。前两个对应 AOSP
     * isImportanceHighEnough 的第二条分支；第三个是赌 ranking importance 本身——渠道声明的是
     * IMPORTANCE_HIGH，但 setSound(null) + enableVibration(false) 让系统把它压到 3。
     *
     * **三个都没能让 remindType 从 0 动起来。** 结论留在这里是为了别再试第四遍：这条路上没有
     * 应用侧能扳的开关，声音只能自己发（见 chime）。
     */

    /** 一张卡一个 id：同时只有一个传输在走，第二个会覆盖第一个，这正是我们要的。 */
    private static final int ID = 0x0B00;

    /** 卡上【完成】按下去的那条广播；一个 id 一处，免得两次传输抢同一个 PendingIntent。 */
    private static final int DONE_REQUEST = 0x0B04;

    /**
     * 曾经在这里的那四个常量已经删掉了：{@code ACTION_EXPAND}（请模块摊开卡片）、
     * {@code KEY_OPTIONS}（{@code oplusLiveAlertOptions}）、{@code REMIND_STRONG_ALWAYS}（13）、
     * {@code HOST_STATUS_BAR}（8）。
     *
     * <p>它们合起来做的事是「让这张通知看起来像系统自己的强提醒卡」。代价在 2026-11-18 显形：
     * 通知中心里找不到这张卡、点空白区域收不回去。这两个症状不是三个独立的 bug，是同一次
     * 越权的三个面——通知被摆成了系统卡的姿态，却没有系统卡的完整状态机。
     *
     * <p>只留公开 API：{@code ProgressStyle}、{@code setRequestPromotedOngoing}、
     * {@code setShortCriticalText}。系统要提升就提升，不提升它至少是一条正常的进度通知。
     */
    private LiveCard() {}

    /** 这张机器此刻允许我们发实时通知没有。 */
    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        return manager.canPostPromotedNotifications();
    }

    /**
     * 画一张在走的卡，**不打扰人**。
     *
     * @param percent 0–100；负数表示「不知道进度」，这时卡片画成不确定态而不是停在 0%
     */
    static void update(Context context, String title, String shortText, int percent) {
        post(context, title, shortText, percent, false, false, null, null, null);
    }

    /**
     * 正在接收的那张卡，额外挂一颗【取消接收】。
     *
     * <p># 为什么进度卡该有这颗按钮
     *
     * <p>点了「接收」之后，人唯一能做的就是把手机放下等它传完。一个几百兆的视频传到一半、
     * 发现传错了设备，或者对面那台手机就在手上要先说句话——那时卡片上没有任何出口，人只能
     * 去杀进程或者拔网络。
     *
     * <p># 为什么它只在进度中出现，不在邀请中出现
     *
     * <p>邀请那张卡上已经有【拒绝】了。同一个意思摆两颗按钮，人会以为它们不一样。这颗按钮
     * 出现的那一刻，正是「拒绝」这个答案已经不可能再给的时候——人已经说了收。
     *
     * <p># 按下去之后发生什么
     *
     * <p>不是「把已收到的删掉」，是关掉那条连接（{@link ReceiveHost.Attempt#cancel}）。读字节
     * 的线程从 {@code read} 里出来，走的是和对端走掉完全一样的一条路：卡片撤掉、页面上留一句
     * 「已取消」。对端看到的是一次 abort，不是一次完成——这与它自己中途取消时发给我们的是
     * 同一种信号（见 Plan.md 里 /Upload 的 RST 实测）。
     *
     * @param cancel 按【取消接收】时的广播；由 {@link ReceiveHost} 带着 session 造出来
     */
    static void updateWithCancel(Context context, String title, String shortText, int percent,
                                 PendingIntent cancel) {
        updateWithStop(context, title, shortText, percent, "取消接收", cancel);
    }

    /**
     * 进度卡上那颗「这件事还没结束，要不要停」的按钮，名字由调用方给。
     *
     * <p># 为什么名字是参数
     *
     * <p>同一颗按钮在两个方向上说的不是一件事：接收时停的是「我不收了」，发送时停的是
     * 「我发出去的这份不算了」。写成两个方法名只会让 {@code post} 的那两个重载各挂一次，
     * 而这颗按钮的位置、时机、按下去之后的收尾三者完全相同——不同的只有一个词。
     *
     * <p>写错这个词的代价是实打实的：在两台手机中间看这张卡的人，靠这一行判断「我现在停的
     * 是收还是发」。收发同时进行（一边收一边给第三台发）时，这是唯一能分辨的地方。
     *
     * @param label 按钮上那个词，例如「取消接收」「取消发送」
     * @param stop  按下去要做的事；{@code null} 时这颗按钮不挂
     */
    static void updateWithStop(Context context, String title, String shortText, int percent,
                               String label, PendingIntent stop) {
        post(context, title, shortText, percent, false, false, null, null, null, label, stop);
    }

    /**
     * 同一张卡，但**把它抬起来一次**（系统的抬头，也就是展开后那张大卡）。
     *
     * <p>用在四个时机上：接到邀请、开始发送、发送结束、接收完成。这四处都是「此刻有事发生，
     * 看一眼」，中间的进度不该跟着一起响——所以它是独立的一个入口，而不是 {@link #update}
     * 的一个布尔参数：调用点写的是意图，不是标志位。
     */
    static void announce(Context context, String title, String shortText, int percent) {
        post(context, title, shortText, percent, true, false, null, null, null);
    }

    /** 收尾：进度拉满，短句换成结果。 */
    static void finish(Context context, String title, String shortText) {
        post(context, title, shortText, 100, true, true, null, null, null);
    }

    /**
     * 收完的那张卡：进度拉满，挂【打开文件】【完成】，**不自己消失**。
     *
     * <p>为什么不让它自动关：传输结束不是这件事的结束，人还要去打开那份文件。一张自己消失的
     * 完成提示，等于把「东西在哪」这个问题留给了人去翻相册。留到他按「完成」为止，才是把最后
     * 一步交还在他手上。
     *
     * @param open 打开那份文件的 intent；没有可打开的时传 {@code null}，那就只挂「完成」
     */
    static void finishWithActions(Context context, String title, String shortText,
                                  PendingIntent open) {
        post(context, title, shortText, 100, true, true, open, "打开文件", null);
    }

    /**
     * 收到的是链接时的那张卡：和 {@link #finishWithActions} 只差一个词。
     *
     * <p>为什么值得为这一个词多一个入口：【打开文件】按在那个位置上，人期待的是相册或文件
     * 管理起来。一次纯链接分享没有文件，那串地址就是收到的全部东西——按钮写错一个词，
     * 人按下去之前会以为点开的是自己刚收下的那份文件。
     *
     * @param open 打开那条链接的 intent
     */
    static void finishWithLink(Context context, String title, String shortText, PendingIntent open) {
        post(context, title, shortText, 100, true, true, open, "打开链接", null);
    }

    /**
     * 把一张**已经投过的**终态卡按原样再摆一遍，不抬头。
     *
     * <p>只有一个调用者：{@link SendHost} 发现那颗【取消发送】按在了一趟已经结束的发送上。
     * 那时屏上那张卡是旧的（按钮还是进行中那一行），重投一次才能把它换成终态的样子。
     *
     * <p>为什么不是再走一遍 {@link #finishWithActions}：那个入口会抬头。人为了一件早就结束的
     * 事点了一下，得到的回应不该是通知栏里又弹一次「发送完成」——那会读成「刚才那次又成功了
     * 一遍」。
     *
     * @param retry 失败卡上那颗【重试】；成功卡传 {@code null}
     */
    static void restate(Context context, String title, String shortText, int percent,
                        PendingIntent retry) {
        post(context, title, shortText, percent, false, true, null, null, retry);
    }

    /**
     * 失败的那张卡：停在断开那一刻的百分比上，挂【重试】【取消】，**不自己消失**。
     *
     * <p># 为什么不像以前那样八秒后自己下去
     *
     * <p>失败是唯一一种「人不做点什么，这件事就永远没完成」的状态。一条自己消失的失败提示，
     * 在通知栏里和「什么都没发生过」长得一模一样——2026-11-17 那次「流体云通知自己关了」就是
     * 这么被读成「它自己拒的」。留着它，人至少知道自己手上还有一个可以按的按钮。
     *
     * <p># 为什么进度停在断开那一刻
     *
     * <p>拉满的轨道在胶囊上就是「收完了」这个意思，而把失败画成成功是这里最不能犯的错。
     * 收到 4% 断的，轨道就停在 4%，人自己看得出断在中途。{@code percent} 为负（一个字节都还没
     * 落地）时画 0% 而不是不确定态：那个转圈的动画说的是「正在动」，这条连接已经不会再动了。
     *
     * @param retry 按【重试】时去哪儿；{@code null} 表示这件事没法重来（比如接收侧），
     *              那就只挂【完成】
     */
    static void failedWithActions(Context context, String title, String shortText, int percent,
                                  PendingIntent retry) {
        post(context, title, shortText, Math.max(0, percent), true, true, null, null, retry);
    }

    /**
     * 一张「要不要收」的卡：同一个胶囊位置，展开后带两个按钮。
     *
     * @param accept 按「接受」时发的广播；调用方负责把它接到自己的决定上
     * @param refuse 同上，按「拒绝」
     */
    static void invite(Context context, String title, String shortText,
                       PendingIntent accept, PendingIntent refuse) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        channel(manager);
        alertChannel(manager);
        Notification.Builder builder = new Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                .setShortCriticalText(shortText)
                // 邀请阶段**不画进度条**：这时没有任何字节在走，一条轨道只会让人以为传输已经开始。
                // 轨道从「点了接收」那一刻起才该出现（{@link #update} 会给这张卡换上 ProgressStyle）。
                .setOngoing(true)
                // 邀请必须抬头：它问的是一个需要人在几秒内回答的问题。
                .setOnlyAlertOnce(false)
                .setShowWhen(false)
                .setRequestPromotedOngoing(true)
                // 先「拒绝」后「接收」：加进来的顺序就是左右顺序，而主导动作该在右边——
                // 这是拿手机的那只手够得最舒服的一侧，也是「接受」与「取消」惯常的排布。
                .addAction(new Notification.Action.Builder(null, "拒绝", refuse).build())
                .addAction(new Notification.Action.Builder(null, "接收", accept).build());
        Notification card = builder.build();
        immersive(card);
        manager.notify(ID, card);
        // 邀请卡不走 {@link #post}（它不发进度条、走的是另一条会响的渠道），所以形状得在这里
        // 自己记一笔：否则下一张进度卡的形状与上一次传输留下的那个恰好相同时，系统会把
        // 「拒绝/接收」这两个答案留在屏上。
        lastShape = "invite";
        // 响了才算「通知到了」。系统那条路对提升过的通知不出声（见 alertChannel 的注释），
        // 所以这一声得我们自己发。放在 notify 之后：卡先在原位站好，声音再跟上，
        // 人抬头时看到的就是「已经在那儿的那张卡」，而不是「先响一声、卡再慢慢浮出来」。
        chime(context);
        Log.i(TAG, "live invite: " + title + " · " + shortText);
    }

    /**
     * 请流体云把这张卡摊开成沉浸态——「展开」，不用钩子、不用私有 JSON。
     *
     * <p># 键从哪来的
     *
     * <p>SystemUI 的 {@code OplusLiveAlertNotificationsRepository#entryToLiveAlert} 在把一条通知
     * 转成流体云卡时，会自己拼一份 {@code OplusLiveAlertNotificationOptions}，其中四个键
     * <b>直接读通知 extras 的顶层</b>，不是读我们塞的那串私有 JSON：
     *
     * <pre>
     * linkedHashMap.put("liveAlertService", bundle.getString("liveAlertService", ""));
     * linkedHashMap.put("immersiveCardType", Integer.valueOf(bundle.getInt("immersiveCardType", -1)));
     * linkedHashMap.put("requestLockScreenImmersive", Integer.valueOf(bundle.getInt("requestLockScreenImmersive", -1)));
     * linkedHashMap.put("ignoreEntry", Integer.valueOf(bundle.getInt("ignoreEntry", 0)));
     * </pre>
     *
     * <p>所以这是一个**应用侧可写的入口**：写普通 extra 就行，系统自己会把它搬进插件。
     *
     * <p># 为什么是 2
     *
     * <p>插件里两处都在读同一个键，两处都认 2：
     *
     * <ul>
     *   <li>{@code livealert/notification/origin/b#b()} 拼「这张卡支持哪些尺寸」：
     *       来源是通知（{@code ENTRY_NOTIFICATION}）时给 {@code VIEW_SIZE_SM} + {@code VIEW_SIZE_LG}，
     *       再判 {@code immersiveCardType == 2} 就追加 {@code VIEW_SIZE_IMMERSIVE}。
     *       不支持这个尺寸的卡，想展开也没有那一面可画。</li>
     *   <li>{@code seedling/notification/j} 选「这一屏画哪个尺寸」：
     *       {@code if (n.y() == 2) { format = n.E(VIEW_SIZE_IMMERSIVE); ... }}，
     *       而 {@code n.y()} 就是取 map 里的 {@code immersiveCardType}，取不到给 0。</li>
     * </ul>
     *
     * <p>两个条件合起来才是「摊开」。只写第二处会拿到一个不存在的面，只写第一处则永远不选它。
     *
     * <p># 为什么不是 {@code open.oppomobile.com} 上的公开文档
     *
     * <p>公开 API（{@code android.jar} API 37）里没有展开——{@code ProgressStyle}、
     * {@code setRequestPromotedOngoing}、{@code setShortCriticalText} 之外一个都没有。
     * 这个键也没写进文档，但它是**系统自己读的契约**，不是我们要去改的别人的状态：
     * 传进去的是一个值，怎么画由系统决定，画不画得出来也不影响通知本身的合法性。
     * 这和之前那套「改 SystemUI 里插件的 ViewModel」是两件事。
     */
    private static void immersive(Notification card) {
        if (card.extras == null) {
            card.extras = new Bundle();
        }
        card.extras.putInt("immersiveCardType", 2);
    }

    /** 撤掉。传 {@code null} 之外什么都不需要。 */
    static void drop(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(ID);
        }
    }

    /**
     * 这里原来有一个 {@code expand(Context)}，往 {@code com.android.systemui} 发一条广播，
     * 让 Xposed 模块进 SystemUI 调流体云插件的 onExpand。
     *
     * <p>**这条广播已经撤了**：卡片确实会摊开一下，但**通知中心里那张卡同时消失了，点空白
     * 区域也收不回去**，每次都需要用户去通知中心手动划掉。一个摊开了却收不回去的卡，不如
     * 一颗点一下就开的胶囊。绕开系统自己的状态机是它的全部病根。
     *
     * <p>但 {@code FluidModule} 与 LSPosed 声明**没有**跟着走：2026-11-19 换了一套实现又装
     * 回来，不再调 {@code onExpand}，只把系统算出来的 {@code remindType} 从 0 抬到 11
     * （{@code REMIND_TYPE_FORCED}），剩下的 {@code CAPSULE → CARD} 迁移交给系统自己走完——
     * 所以通知中心里那张卡还在、点空白也收得回去。详见 {@link FluidModule}。模块是可选的：
     * 不启用就停在胶囊上，这一页的代码一个字都不用变。
     *
     * <p>这一侧仍然只有公开 API：{@code ProgressStyle} + {@code setRequestPromotedOngoing}
     * 决定它是不是实时通知，展开与收起由用户点击和系统策略决定。应用侧没有、也不该有
     * 这个开关——{@code android.jar} 里根本没有类似方法（{@code Notification.Builder} 上只有
     * {@code setRequestPromotedOngoing} 和 {@code setShortCriticalText}）。
     */

    /** 卡上【完成】按下去的那条广播。两处（发送完成、接收完成）指向同一个动作。 */
    private static PendingIntent done(Context context) {
        return PendingIntent.getBroadcast(context, DONE_REQUEST,
                new Intent(context, CardDismiss.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 这里原来有一个 {@code markFluid(Context, Notification)}，往通知 extras 里塞
     * {@code oplusLiveAlertOptions}（{@code remindType=13}、{@code showHostMap=[8]}），
     * 并把 {@code fullScreenIntent} 指向【完成】那条广播。
     *
     * <p># 它为什么被删
     *
     * <p>那套写法的目标是把通知摆成「系统自己的强提醒卡」的姿态，好让它在桌面态就摊开。
     * {@code dumpsys} 证明 JSON 确实进了 extras，可卡片的行为从此不再由系统统一管理：
     *
     * <ul>
     *   <li>{@code remindType=13} 就是「强提醒 · 始终」——一个「始终提醒」的姿态，自然
     *       不会因为用户点了空白区域而收敛。这是「点空白不收缩」的直接原因。</li>
     *   <li>非空 {@code fullScreenIntent} 会让这条通知在系统眼里带「全屏意图」的语义
     *       （{@code entryToReminderType} 只看它是否非空就返回 13），通知中心对它的归类
     *       也随之改变——这是「通知中心里不显示流体云」的原因。</li>
     *   <li>更要紧的是：{@code entryToLiveAlert} 最后会用 {@code entryToReminderType}
     *       算出来的值**整个重写**这个 key，所以我们写进去的数在系统眼里本来就不是权威值。
     *       一个既不被采纳、又会误导系统状态机的字段，留着只有坏处。</li>
     * </ul>
     *
     * <p>现在这条路完全交给公开 API。如果系统不提升它，它就是一条带进度的常驻通知——
     * 这是可以接受的降级，而不是需要靠私有键去撬的失败态。
     */

    /**
     * 卡片本身的唯一出口。
     *
     * @param alert     这一次要不要抬头。抬头由渠道重要性 + {@code setOnlyAlertOnce} 共同决定，
     *                  两者缺一不可：低重要性渠道上把 onlyAlertOnce 设成 false 也不抬。
     * @param terminal  这张卡是不是「这件事已经结束」。终态必须挂一颗收尾按钮——传输结束不是这件事的
     *                  结束，人还要按一下才算收尾；而进度中的卡一颗按钮都不该有。
     * @param open      【打开文件】/【打开链接】的 intent，没有就不挂这颗按钮
     * @param openLabel {@code open} 那颗按钮写什么。收的是文件就是「打开文件」，收的是一条链接
     *                  就是「打开链接」——同一个位置，两种东西，说错一个词人就会按错期待
     * @param retry     【重试】的 intent，没有就不挂这颗按钮
     */
    private static void post(Context context, String title, String shortText, int percent,
                             boolean alert, boolean terminal, PendingIntent open, String openLabel,
                             PendingIntent retry) {
        post(context, title, shortText, percent, alert, terminal, open, openLabel, retry,
                null, null);
    }

    /**
     * 同上，多一颗「进行中才能按」的按钮。
     *
     * <p>它是重载而不是把 {@code stop} 加进上面那个签名：上面那六处调用点里没有一处需要这颗
     * 按钮，为了它让它们各写一遍 {@code null}，读代码的人会以为那六个 {@code null} 各自有
     * 含义。
     *
     * @param stopLabel 那颗按钮写什么；{@code stop} 为 {@code null} 时不看
     * @param stop      进行中的那颗按钮，没有就不挂
     */
    private static void post(Context context, String title, String shortText, int percent,
                             boolean alert, boolean terminal, PendingIntent open, String openLabel,
                             PendingIntent retry, String stopLabel, PendingIntent stop) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        channel(manager);

        Notification.ProgressStyle style = new Notification.ProgressStyle()
                // 形状跟着进度走：不设这个，系统画的是一段静态装饰而不是一条在走的轨道。
                .setStyledByProgress(true)
                .setProgressIndeterminate(percent < 0);
        if (percent >= 0) {
            style.setProgress(Math.min(100, percent));
        }

        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                // 胶囊里那一行字。系统只在有它的时候才把这张卡提升到状态栏那个位置。
                .setShortCriticalText(shortText)
                .setStyle(style)
                .setOngoing(true)
                // 这里就是「抬头」的开关：true 是安静地换一行字，false 是把卡抬起来。
                .setOnlyAlertOnce(!alert)
                .setShowWhen(false)
                .setRequestPromotedOngoing(true);
        // 顺序就是左右顺序：【打开文件】/【打开链接】/【重试】说的是「接下来做什么」，排在
        // 左边；【完成】是「这件事到此为止」，永远在最右。前两颗不会同时出现，所以最多两颗。
        if (open != null) {
            builder.addAction(new Notification.Action.Builder(null,
                    openLabel == null ? "打开文件" : openLabel, open).build());
        }
        if (retry != null) {
            builder.addAction(new Notification.Action.Builder(null, "重试", retry).build());
        }
        // 进行中那颗（【取消接收】/【取消发送】，名字由调用方给）。它和上面两颗不会同时出现：
        // 一颗说的是「这件事还没结束，要不要停」，上面两颗说的是「这件事已经结束了，接下来做
        // 什么」。同一张卡上同时问这两个问题，读的人只会以为这是一次已经失败的传输。
        if (stop != null) {
            builder.addAction(new Notification.Action.Builder(null,
                    stopLabel == null ? "取消接收" : stopLabel, stop).build());
        }
        // 终态必须有这颗收尾按钮——哪怕没有别的按钮。没有它，一张停在 3% 的失败卡就只能等系统
        // 回收，而人拿不到「我已经知道了」这个动作。
        //
        // 它叫什么取决于旁边站着谁，而不是取决于这里只有一处调用点：
        // · 旁边是【重试】时叫【取消】。「要不要再来一次」是一对答案，是 / 否；写成【完成】
        //   等于替人把这件事盖了章，可人当下只是不想重试，并没有承认什么已经完成。
        // · 旁边是【打开文件】/【打开链接】、或者旁边空着时叫【完成】。那两处问的是
        //   「现在打开吗」，答一句「完了」才顺；而【取消】在那儿会让人以为要把收到的文件退回去。
        if (terminal) {
            String label = retry != null ? "取消" : "完成";
            builder.addAction(new Notification.Action.Builder(null, label, done(context)).build());
        }
        Notification card = builder.build();
        // 按钮行和上一张不一样时，**先撤再投**，而不是把内容改掉。
        //
        // 2026-11-19 23:22:32.943 已经投出「发送完成」，65 秒后仍有人在按那颗【取消发送】：
        // 5 秒内 6 次广播全部落在已经结束的那一趟上（`no send is listening for 2f391a32…`），
        // 而应用侧那一分钟一条卡片日志都没有——屏上那张是系统留着的前一帧，按钮还是旧的。
        // 只改内容做不到这件事：进度卡挂【取消发送】、终态卡挂【完成】/【重试】，同一条通知
        // 记录上换按钮行，系统有可能只换了字、没换那一行按得到的东西。
        //
        // 判据是「形状」而不是「是不是终态」：邀请卡（拒绝/接收）→ 进度卡（取消接收）也是
        // 同一种变化——那是人在对面手机上点了「接收」的那一刻，两个答案换成一个出口。
        String shape = (open != null ? "open" : "")
                + (retry != null ? "retry" : "")
                + (stop != null ? "stop" : "")
                + (terminal ? "|end" : "|live");
        if (!shape.equals(lastShape)) {
            manager.cancel(ID);
            lastShape = shape;
        }
        manager.notify(ID, card);
        Log.i(TAG, "live card: " + title + " · " + shortText + " · " + percent + "%"
                + (alert ? " (heads-up)" : ""));
    }

    /** 这条渠道，第一次用到时建；顺手把历史上那几条删掉。 */
    private static void channel(NotificationManager manager) {
        if (!swept) {
            swept = true;
            for (String legacy : LEGACY_CHANNELS) {
                if (manager.getNotificationChannel(legacy) != null) {
                    manager.deleteNotificationChannel(legacy);
                }
            }
        }
        if (manager.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "传输进度", NotificationManager.IMPORTANCE_HIGH);
        // 高重要性是为了抬头，不是为了让手机响：这四件事要人看见，不该吓人一跳。
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * 邀请那条渠道：高重要性只是为了抬头，声音和振动**故意声明为空**——提醒由我们自己在
     * {@link #chime} 里播。
     *
     * <p># 为什么不是让它自己响
     *
     * <p>2026-11-19 01:46 和 01:47 各试了一次，渠道配得完全正确
     * （{@code mImportance=4}、{@code mSound=content://settings/system/notification_sound}、
     * {@code mVibrationPattern=[0,220,180,220]}），可 {@code dumpsys audio} 的播放器事件流里
     * 一个 {@code USAGE_NOTIFICATION} 的新播放器都没有出现——**一声没响**。
     *
     * <p>原因在提升那一步：{@code NotificationLARuleManager#onLiveNotificationPost} 对我们这条
     * 带 {@code PROMOTED_ONGOING} 的通知返回 {@code result=7}，把它交给了流体云。流体云卡片
     * 本身就是那个「提醒」，所以系统那一路的 alerting 被吞掉了。这不是渠道没声明对，是
     * 「被提升过的通知不再走普通提醒」。
     *
     * <p>所以这里把声音交回给我们自己：{@code manager.notify} 之后紧接着播一次。好处是
     * **不依赖那张卡有没有展开成功**——通知发出去，声音就一定会响，而不是「卡摊开了才有声」。
     *
     * <p># 为什么要留一条（静音的）渠道
     *
     * <p>不留的话通知会被丢进系统默认渠道，那条的形状不受我们控制，而且以后想调重要性就晚了
     * （渠道建出来之后重要性改不动）。留着它，等于留了一个以后能改的把手。
     */
    private static void alertChannel(NotificationManager manager) {
        NotificationChannel existing = manager.getNotificationChannel(ALERT_CHANNEL);
        if (existing != null) {
            // 老版本把声音和振动声明在渠道上（01:46 之前装的那一版）。渠道建出来之后重要性改不动，
            // 但声音和振动是能改的，所以这里只把这两项收回自己手里，不重建渠道——
            // 重建会把用户对这条渠道做过的手动设置一起抹掉。
            if (existing.getSound() != null || existing.shouldVibrate()) {
                existing.setSound(null, null);
                existing.enableVibration(false);
                manager.createNotificationChannel(existing);
                Log.i(TAG, "旧的响铃渠道已改成静音，提醒交回本地播放");
            }
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL, "收到文件", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * 响一声 + 震四下。这是「有人要发东西给你」这件事**唯一**的提醒来源。
     *
     * <p>振动节奏不是随手定的，是照着音效排的：{@link #CHIME_VIBRATE_TIMINGS} 里每个「震」
     * 的起点正好压在音效的每次敲击上，连力度递减都跟着走。所以听到的和摸到的是同一件事，
     * 而不是「有声音、另外还有一组无关的嗡嗡」。
     *
     * <p>声音用自带的 {@code R.raw.chime_airdrop}（由一段「隔空投送铃声」转成 PCM，
     * 见 {@code make_chime.py}），不是系统默认通知音。换来的是：节奏固定、能和振动对点。
     * 代价是不跟着用户的铃声走——所以音量仍然走通知音量，静音模式、振动模式、勿扰的语义
     * 一个都不少，只换音色，不换礼仪。
     *
     * <p>为什么不用渠道挂一个自定义铃声：{@code NotificationChannel#setSound} 只认
     * {@code content://} / {@code file://}，{@code android.resource://} 交给系统会静默失效，
     * 得先把 raw 里的音频导出成文件再喂 URI，绕这一圈没有收益。
     *
     * <p>三道闸，任何一道关着就安静——因为「我不想被打扰」这个意愿比「我要提醒你」优先：
     *
     * <ul>
     *   <li>应用的通知被关了（{@code areNotificationsEnabled()} 为假）；</li>
     *   <li>这条渠道被单独关掉（{@code IMPORTANCE_NONE}）；</li>
     *   <li>勿扰开着（中断过滤器不是 {@code ALL}）——此时只留振动，不出声。</li>
     * </ul>
     *
     * <p>另外静音模式（{@code RINGER_MODE_SILENT}）连振动也不给：那是用户把手按住不放的表达。
     * 振动模式（{@code RINGER_MODE_VIBRATE}）则只振不响，正是它字面的意思。
     */
    private static void chime(Context context) {
        try {
            // 同一件事可能会被投两次（比如探针先 cancel 再延迟 700ms 重发、或者上层重试）。
            // 一秒半之内只认第一次：这段时间里的第二次投递说的还是同一句话。
            long now = SystemClock.uptimeMillis();
            if (now - sLastChime < CHIME_GAP) {
                Log.i(TAG, "chime 跳过：距上次响铃只有 " + (now - sLastChime) + "ms");
                return;
            }
            sLastChime = now;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                if (!manager.areNotificationsEnabled()) {
                    Log.i(TAG, "chime 跳过：应用通知被关了");
                    return;
                }
                NotificationChannel channel = manager.getNotificationChannel(ALERT_CHANNEL);
                if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    Log.i(TAG, "chime 跳过：这条渠道被关了");
                    return;
                }
            }
            AudioManager audio = context.getSystemService(AudioManager.class);
            int ringer = audio == null ? AudioManager.RINGER_MODE_NORMAL : audio.getRingerMode();
            boolean quiet = manager != null
                    && manager.getCurrentInterruptionFilter()
                    != NotificationManager.INTERRUPTION_FILTER_ALL;
            if (ringer == AudioManager.RINGER_MODE_NORMAL && !quiet) {
                playChimeSound(context);
            }
            if (ringer != AudioManager.RINGER_MODE_SILENT) {
                vibrate(context, CHIME_VIBRATE_TIMINGS, CHIME_VIBRATE_AMPLITUDES);
            }
            Log.i(TAG, "chime: ringer=" + ringer + (quiet ? " (勿扰，只振动)" : ""));
        } catch (Throwable t) {
            // 提醒失败不该连累通知本身：卡已经发出去了，人还是能在胶囊上看到它。
            Log.w(TAG, "chime 没响成：" + t);
        }
    }

    /**
     * 播一遍那段四下敲击。
     *
     * <p>用 {@link MediaPlayer} 而不是 {@code Ringtone}：raw 资源没有 URI，{@code Ringtone}
     * 拿不到它。{@code MediaPlayer.create} 会同步准备好，直接 {@code start} 就行。
     *
     * <p>静态留着上一次的实例是必须的：上一条还没播完又来一条时，{@code release()} 掉旧的
     * 再建新的，否则会有两路音频叠着响，听起来像回声。而 {@code create} 的实例不会自己回收，
     * 不主动放就会一直占着一个解码器。
     *
     * <p>{@code USAGE_NOTIFICATION} + {@code CONTENT_TYPE_SONIFICATION}：这两个决定了它走
     * 通知音量、被静音模式掐掉、在勿扰里走「允许通知」这条规则——和系统通知音同等待遇。
     * 换音色不该顺手换掉这些。
     *
     * <p>音源是 44.1kHz 单声道 PCM。之所以先转成 wav 再进 raw，是因为原文件 151KB 里
     * 有 141KB 是 ID3（含一张 36KB 封面），真正的音频只占 10KB；而且原音峰值只有 -16dBFS，
     * 太轻，{@code setVolume} 又只能往下调，所以放大在转码时一次性做掉（+14.8dB，峰值 0.85）。
     */
    private static void playChimeSound(Context context) {
        try {
            stopChimeSound();
            // 不能图省事用 MediaPlayer.create()：它在返回之前就已经 prepare 了，
            // 之后调 setAudioAttributes 会被底层忽略，实测 attributes 落成 USAGE_UNKNOWN，
            // 声音于是走 STREAM_MUSIC——媒体音量、静音模式管不着它，等于把上面那三道闸全废了。
            // 所以老老实实「先设属性、再给数据源、最后 prepare」。
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(context,
                    Uri.parse("android.resource://" + context.getPackageName()
                            + "/" + R.raw.chime_airdrop));
            player.setOnCompletionListener(mp -> {
                mp.release();
                if (sChimePlayer == mp) {
                    sChimePlayer = null;
                }
            });
            player.prepare();
            sChimePlayer = player;
            player.start();
        } catch (Throwable t) {
            Log.w(TAG, "chime 播放失败：" + t);
        }
    }

    /** 掐掉上一次还没播完的那一声，没有就什么都不做。 */
    private static void stopChimeSound() {
        MediaPlayer old = sChimePlayer;
        sChimePlayer = null;
        if (old == null) {
            return;
        }
        try {
            if (old.isPlaying()) {
                old.stop();
            }
        } catch (Throwable ignored) {
            // 已经 release 过或者状态不对，都无所谓——反正下一个还要重建。
        }
        try {
            old.release();
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    /** 按给定的波形振一次，{@code -1} 表示不重复，每段都用满振幅。 */
    private static void vibrate(Context context, long[] pattern) {
        int[] amplitudes = new int[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            amplitudes[i] = (i % 2 == 1) ? 255 : 0;
        }
        vibrate(context, pattern, amplitudes);
    }

    /**
     * 按给定的波形 + 每段振幅振一次。
     *
     * <p>{@code amplitudes} 与 {@code timings} 一一对应，0 表示「这段停」。
     * 有了它，重音才能跟着音效走：只是「四下一样重」的话，听起来仍像机械提示音。
     */
    private static void vibrate(Context context, long[] pattern, int[] amplitudes) {
        Vibrator vibrator = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            if (manager != null) {
                vibrator = manager.getDefaultVibrator();
            }
        }
        if (vibrator == null) {
            vibrator = context.getSystemService(Vibrator.class);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        VibrationEffect effect;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            effect = VibrationEffect.createWaveform(pattern, amplitudes, -1);
        } else {
            effect = VibrationEffect.createWaveform(pattern, -1);
        }
        // 必须写明是「通知」类振动。不写的话系统按 USAGE_TOUCH 记（实测 dumpsys 里就是 TOUCH），
        // 而触摸反馈走的是另一套开关：勿扰不管它、系统设置里的「通知振动」也不管它。
        // 我们这是在替一条通知发声，就该让这条通知的振动设置说了算。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION));
        } else {
            vibrator.vibrate(effect);
        }
    }
}
