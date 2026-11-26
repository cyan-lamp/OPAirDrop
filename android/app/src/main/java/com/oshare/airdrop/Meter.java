package com.oshare.airdrop;

import android.util.Log;

/**
 * 一次接收的进度：把 native 报上来的字节换算成人读得懂的一句话，并投到那张卡上。
 *
 * <p>从 {@code Listener} 里搬出来的——外层 2000 行、混着连接生命周期与决定逻辑，而这一块
 * 只依赖三样：{@code ReceiveHost} 那张卡、{@code Listener.bytes} 的换算、一个日志 TAG。
 * 它自己还带着 {@code Stall}（线静默时的取证）与 {@code stackOf}，两者都只服务它。
 */
final class Meter implements Native.Landing {

    /** 最新的成员进度（已落盘个数 / 发送端报的总数，0 = 没报）。读线程写、卡上读。 */
    private volatile int membersDone;
    private volatile int membersTotal;

    private static final String TAG = "OPAirDrop";

        /** 两次投递之间的最小间隔。 */
        private static final long INTERVAL_MS = 200;

        /**
         * 这一次接收的落点：流体云那张卡（{@link ReceiveHost}）。
         *
         * <p>只有它一处。这里原来还写着「本应用的通知和卡各一份」，那条通知已经撤掉
         * （2026-11-19）：同一条传输在两个地方各画一遍，人看到的只会是两个不一样的数。
         */
        private final ReceiveHost.Attempt attempt;

        /**
         * 单格信箱：读线程放，投递线程取。见类注释。
         *
         * <p>读线程只在这一把锁里停几微秒——投递线程画卡时是**在锁外**的（它取完数字就
         * 出去），所以 UI 慢多久都不会变成读线程的等待。
         */
        private final Object slot = new Object();

        /** 有新的数字没画过。由 {@link #slot} 守护。 */
        private boolean pending;

        /** 已经收工，投递线程该退出了。由 {@link #slot} 守护。 */
        private boolean closed;

        /** 对端声明的总数；0 表示它什么都没说。 */
        private volatile long total;

        /** 投出去几条、一共花了多少毫秒、最坏一条多少毫秒。只给日志。 */
        private long posts;
        private long postMillis;
        private long worstMillis;

        /** 上面那笔账报过一次没有。由 {@link #slot} 守护。 */
        private boolean tallied;

        /**
         * 最后一次报上来的已落地字节数。
         *
         * <p>只为了分辨一件事：这条连接断的时候，**有没有一个字节真的走过**。零字节断开是
         * 「对面还没开始/本机还没来得及开始」，走了几兆才断开是「传到一半被停下」——这两种
         * 在人那边要做的事完全不同，而异常本身只有一个 errno。
         *
         * <p>写在节流判断之前而不是之后：被节流掉的那几次恰恰是最后几次，漏记的话，一个在
         * 两百毫秒内断掉的传输会被读成「一个字节都没走」。
         */
        private volatile long done;

        /**
         * 最后一次有字节落地的时刻（{@link System#nanoTime}）；0 表示一个字节都没走过。
         *
         * <p>存在的理由只有一个：连接断掉的那一刻，{@code Socket closed} 这句话分不出「对面
         * 传到一半收手」和「对面早就停了、几秒后才断」。差的就是这一段时间——断之前线安静了
         * 五十毫秒是中止，安静了四秒是**停住**，而停住要找的是别的东西（见 {@code serve} 的
         * catch 里那一行）。
         */
        private volatile long lastAt;

        /** 画卡的那条线程。一条连接一条，随 {@link #close} 退出。 */
        private final Thread poster;

        Meter(ReceiveHost.Attempt attempt) {
            this.attempt = attempt;
            this.poster = new Thread(this::post, "opairdrop-post");
            this.poster.setDaemon(true);
            this.poster.start();
        }

        @Override
        public void landed(long done, long total) {
            this.done = done;
            this.total = total;
            // 记在投递之前：被节流掉的那几次恰恰是最后几次，漏记的话，一个在两百毫秒内断掉
            // 的传输会被读成「一个字节都没走过」。
            this.lastAt = System.nanoTime();
            synchronized (slot) {
                pending = true;
                slot.notifyAll();
            }
        }

        /**
         * 投递线程：有新数字就画一张，画完歇够 {@link #INTERVAL_MS}。
         *
         * <p>歇的那一段用「截止时刻」而不是 {@code wait(200)}：{@link #landed} 每来一块就
         * {@code notifyAll} 一次，按间隔睡会被提前叫醒，节流等于没有。提前醒只重算剩下的时间。
         */
        private void post() {
            while (true) {
                synchronized (slot) {
                    while (!pending && !closed) {
                        try {
                            slot.wait();
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                    pending = false;
                }
                // 锁外画卡：UI 慢多久都只慢这一条线程，读线程不受影响。
                long before = System.nanoTime();
                attempt.progress(done, total, membersDone, membersTotal);
                long took = (System.nanoTime() - before) / 1_000_000L;
                // 这三个数只有这一条线程写，读的是 close()，靠 join 建立先后。
                posts++;
                postMillis += took;
                if (took > worstMillis) {
                    worstMillis = took;
                }
                long next = System.nanoTime() + INTERVAL_MS * 1_000_000L;
                synchronized (slot) {
                    while (!closed) {
                        long left = (next - System.nanoTime()) / 1_000_000L;
                        if (left <= 0) {
                            break;
                        }
                        try {
                            slot.wait(left);
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                }
            }
        }

        /**
         * 收工：等投递线程退出，返回之后不会再有进度上卡。
         *
         * <p>幂等，而且会从三条路进来（收完、断开、{@code finally} 兜底）——只有第一次做事。
         * 它阻塞到投递线程真的退出为止，这是「收尾那句话不会被迟到的进度盖掉」的唯一保证。
         */
        void close() {
            synchronized (slot) {
                closed = true;
                slot.notifyAll();
            }
            poster.interrupt();
            try {
                poster.join(2_000);
            } catch (InterruptedException interrupted) {
                // 正在关掉接收端。把中断还回去，让上层知道它该退出。
                Thread.currentThread().interrupt();
            }
            boolean say = false;
            synchronized (slot) {
                if (!tallied && posts > 0) {
                    tallied = true;
                    say = true;
                }
            }
            if (say) {
                // 这笔账回答的是「读线程是不是在等 UI」：总时间占传输时长一大半、最坏一次
                // 几百毫秒，那就是。数字不该出现在卡上，只该出现在这里。
                Log.i(TAG, "progress posts: " + posts + " in " + postMillis
                        + " ms, worst " + worstMillis + " ms");
            }
        }

        /** 有没有字节真的落地过。见 {@link #done}。 */
        long bytes() {
            return done;
        }

        /** 线安静了多久，毫秒；一个字节都还没走过时是 -1。见 {@link #lastAt}。 */
        long silentMillis() {
            long since = lastAt;
            return since == 0 ? -1 : (System.nanoTime() - since) / 1_000_000L;
        }

        /** 同一件事，说成日志里的半句话。 */
        String quiet() {
            long silent = silentMillis();
            return silent < 0 ? "no byte ever landed" : "last byte " + silent + " ms before the break";
        }

        /**
         * 成员进度：已经落盘几个、发送端报了一共几个。
         *
         * <p>上一版这里**漏了实现**——{@code Native.Landing} 上的 {@code members} 有默认空
         * 方法，所以编译、运行、JNI 查找都不报错，只是两个字段永远是 0，卡片上那句
         * 「已收 2/5 个」永远不出现（2026-11-26 用户实测）。接口给默认实现是为了不逼老的
         * 落地者改，代价就是漏实现时没有任何一处会红——所以这里必须显式写出来。
         *
         * <p>写在 {@code landed} 之外：成员完成是个独立事件（一个文件收全了），它带着
         * 上一块已经报过的字节数一起投，节流由 {@link #post} 管。
         */
        @Override
        public void members(int done, int total) {
            membersDone = done;
            membersTotal = total;
            synchronized (slot) {
                pending = true;
                slot.notifyAll();
            }
        }

        /** 有结果了。 */
        void finished(String text) {
            // 收完那句话由流体云的卡说（带【打开文件】【完成】）。这里不再另发一条普通通知，
            // 否则一次接收会在通知栏和胶囊里各留一条同样的话，而其中一条点不动。
        }
    }
