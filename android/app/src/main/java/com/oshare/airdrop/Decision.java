package com.oshare.airdrop;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次邀请的答案，以及「这个答案是怎么来的」。
 *
 * <p>为什么值得单独一个类型：这五个值（{@code accepted / answered / answeredFrom /
 * cancelled} 加那个 latch）原来是 {@link ReceiveHost.Attempt} 里五个平级字段，任何两处
 * 读它们的代码都必须自己记得它们之间的关系——{@code accepted=false} 既可能是「人拒绝了」
 * 也可能是「一直没人理」，{@code cancelled=true} 时 {@code accepted} 仍然可能为真，而
 * 2026-11-22 到 23 的两处 bug（取消被报成失败、超时被报成断网）都是某一处忘了这条关系。
 *
 * <p>所以这里把关系写进唯一的地方，读的人只问语义，不再自己拼：
 *
 * <ul>
 *   <li>{@link #accepted()} —— 人说了收；</li>
 *   <li>{@link #declined()} —— 人明确说了不收（不是超时）；</li>
 *   <li>{@link #cancelled()} —— 人按过【取消接收】（可能发生在接受之后）；</li>
 *   <li>{@link #answered()} —— 有没有人回答过，不论答的是什么。</li>
 * </ul>
 *
 * <p>三个都从同一次 {@link #answer} 或 {@link #cancel} 派生，不再各自维护一份。
 */
final class Decision {

    /** 落下来表示「有人答了」；超时那条路不会 countDown，所以它同时是「等到了 / 等空了」。 */
    private final CountDownLatch settled = new CountDownLatch(1);

    private volatile boolean accepted;
    private volatile boolean answered;
    private volatile String from;
    private volatile boolean cancelled;

    /** 人说收，或者说不收。两个表面（通知、卡片）最后都落到这里。 */
    void answer(boolean yes, String by) {
        accepted = yes;
        answered = true;
        from = by;
        settled.countDown();
    }

    /**
     * 等人回答，最多 {@code timeoutMs}。
     *
     * @return 人说了收；超时、被打断、说了不收，都是 {@code false}
     */
    boolean await(long timeoutMs) {
        try {
            settled.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // 接收端正在被关掉。把中断还回去，让上层知道它该退出。
            Thread.currentThread().interrupt();
            return false;
        }
        return accepted;
    }

    /**
     * 按秒等一拍，用来给倒计时当节拍器。
     *
     * @return 真表示已经有人答了（该退出倒计时）；假表示又过了一秒
     */
    boolean tick() {
        try {
            return settled.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** 人按了【取消接收】。它不改 {@link #accepted}：人说过收，这一点不会因为取消而变。 */
    void cancel() {
        cancelled = true;
    }

    boolean accepted() {
        return accepted;
    }

    boolean answered() {
        return answered;
    }

    String from() {
        return from;
    }

    boolean cancelled() {
        return cancelled;
    }

    /** 人明确说了不收。与「一直没人回答」是两件事，屏幕上也是两句话。 */
    boolean declined() {
        return answered && !accepted;
    }

    /**
     * 邀请那一刻的判决，只有三种可能：{@code accepted} / {@code refused} / {@code unanswered}。
     *
     * <p>和 {@link #ending()} 的差别在「取消」：邀请阶段人按了取消，语义上仍然是拒绝，
     * 而收尾阶段的取消是另一件事（人收了一半不要了）。两个词各归各位，不再靠调用方
     * 在日志里做字符串替换。
     */
    String verdict() {
        return accepted ? "accepted" : answered ? "refused" : "unanswered";
    }

    /** 一句话说清这次收尾是什么，给日志用。 */
    String ending() {
        return cancelled ? "cancelled" : accepted ? "accepted" : answered ? "refused" : "unanswered";
    }
}
