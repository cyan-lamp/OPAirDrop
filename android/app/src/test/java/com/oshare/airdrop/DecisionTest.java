package com.oshare.airdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 邀请三态的关系（P1-24）：{@code accepted=false} 既可能是「人拒绝了」也可能是「没人理」，
 * 2026-11-22/23 的两处 bug 都是某处忘了这条关系——这里把 {@link Decision} 的每个语义
 * 钉住，谁动关系谁先改这里。
 */
public class DecisionTest {

    @Test
    public void accept_sets_accepted_and_answered() {
        Decision d = new Decision();
        d.answer(true, "user");
        assertTrue(d.accepted());
        assertTrue(d.answered());
        assertEquals("user", d.from());
        assertFalse(d.declined());
        assertEquals("accepted", d.verdict());
        assertEquals("accepted", d.ending());
    }

    @Test
    public void decline_is_declined_not_unanswered() {
        Decision d = new Decision();
        d.answer(false, "user");
        assertFalse(d.accepted());
        assertTrue(d.answered());
        assertTrue(d.declined());
        assertEquals("refused", d.verdict());
        assertEquals("refused", d.ending());
    }

    @Test
    public void no_answer_is_unanswered_not_declined() {
        Decision d = new Decision();
        assertEquals(-1, d.from() == null ? -1 : 0); // from 未答时是 null
        assertNull(d.from());
        assertFalse(d.answered());
        assertFalse(d.declined());
        assertEquals("unanswered", d.verdict());
        assertEquals("unanswered", d.ending());
    }

    @Test
    public void cancel_after_accept_keeps_accepted() {
        Decision d = new Decision();
        d.answer(true, "user");
        d.cancel();
        // 人说过收，这一点不因取消而变；收尾的说法才换成 cancelled。
        assertTrue(d.accepted());
        assertEquals("accepted", d.verdict());
        assertEquals("cancelled", d.ending());
    }

    @Test
    public void cancel_before_answer_is_still_unanswered_verdict() {
        Decision d = new Decision();
        d.cancel();
        assertFalse(d.answered());
        assertEquals("unanswered", d.verdict());
        assertEquals("cancelled", d.ending());
    }

    @Test
    public void await_times_out_as_false() {
        Decision d = new Decision();
        long started = System.nanoTime();
        assertFalse(d.await(120));
        assertTrue("超时耗尽而不是立刻返回", System.nanoTime() - started >= 100_000_000L);
    }

    @Test
    public void await_returns_the_answer_when_settled() throws Exception {
        final Decision d = new Decision();
        Thread other = new Thread(() -> d.answer(false, "peer"));
        other.start();
        Thread.sleep(50);
        assertFalse(d.await(5_000));
        assertTrue(d.answered());
        other.join();
    }
}
