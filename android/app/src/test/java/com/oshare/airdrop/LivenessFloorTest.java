package com.oshare.airdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 「谁该消失」与「谁该上一格」这两条判据的边界（2026-11-27 加入）。
 *
 * <p>两条都是纯函数，所以能在桌面 JVM 上钉住，不需要真机：
 *
 * <ul>
 *   <li>{@link LivenessState#goneFloor}：端口回绝**不是**删除依据，只压制敲门。端口回答的是
 *       「现在能不能发」，mDNS 沉默才回答「在不在」——2026-11-27 01:33 的真机上，一台就在
 *       手边的 iPhone 两次 ECONNREFUSED、mDNS 只安静 3.5 秒就被删了（用户报的第 2 条）。</li>
 *   <li>{@link Peers#dedupe}：一台设备在表里可能有两条记录（键是每分钟轮换的 mDNS 实例名），
 *       界面上就是两格同名设备（用户报的「先看见两台、随即变回一台」）。出口按地址去重。</li>
 *   <li>{@link SendActivity#listed}：屏幕上出现什么。没有真名也没有型号的对端（冷启动
 *       {@code recall} 造的那条）不上屏——它唯一能显示的名字是「附近设备」。</li>
 * </ul>
 *
 * <p>凡是调整这些阈值或身份规则的改动，先改这里。
 */
public class LivenessFloorTest {

    private static Peers.Peer peer(String name, String address) {
        Peers.Peer peer = new Peers.Peer(name);
        peer.address = address;
        peer.port = 8770;
        peer.host = name + ".local";
        return peer;
    }

    @Test
    public void a_gone_peer_stays_while_its_records_are_recent() {
        // 2026-11-27 01:33 的真机现场：这台 iPhone 就在手边，端口连着两次 ECONNREFUSED，
        // 而 mDNS 只安静了 3.5 秒。按「端口回绝 + 3 秒地板」它会当场从列表消失——
        // 那正是用户报的第 2 条（「AirDrop 开关没关，设备却没了」）。
        Peers.Peer peer = peer("a", "fe80::1");
        peer.gone = true;
        peer.seenAt = 1_000;
        peer.heardFrom = 1_000;
        peer.gaps = LivenessState.CONNECTABLE_GAPS;
        peer.gapMax = 1_296;
        // 删除地板 = mDNS 那条 20 秒的沉默线，不再是常数、也不跟着节奏缩短。
        assertEquals(20_000, LivenessState.goneFloor(peer));
        assertEquals("3.5 秒（真机现场）：还在",
                LivenessState.Step.KEEP, LivenessState.decide(peer, 1_000 + 3_511, false));
        assertEquals("19.9 秒：还在", LivenessState.Step.KEEP,
                LivenessState.decide(peer, 1_000 + 19_999, false));
        assertEquals("20 秒：才删", LivenessState.Step.DROP_GONE,
                LivenessState.decide(peer, 1_000 + 20_000, false));
    }

    @Test
    public void a_gone_peer_is_not_knocked_again() {
        // 端口给过答案之后再敲，只会往日志里刷「miss 3 of 2」「miss 4 of 2」。
        Peers.Peer peer = peer("a", "fe80::1");
        peer.gone = true;
        peer.seenAt = 0;
        peer.heardFrom = 0;
        assertEquals(LivenessState.Step.KEEP, LivenessState.decide(peer, 5_000, false));
    }

    @Test
    public void a_peer_that_is_not_gone_is_never_dropped_by_the_gone_floor() {
        Peers.Peer peer = peer("a", "fe80::1");
        peer.seenAt = 0;
        peer.heardFrom = 0;
        peer.gaps = LivenessState.CONNECTABLE_GAPS;
        peer.gapMax = 4_000;
        // 没被端口证死：走的是 timeout()，20 秒起。4 秒以内必须留着，而且可以敲门。
        assertEquals(LivenessState.Step.KNOCK, LivenessState.decide(peer, 4_000, false));
    }

    @Test
    public void two_instance_names_for_one_address_are_one_cell() {
        Peers.Peer older = peer("aaaa", "fe80::289d:95ff:fe33:f872%awdltun0");
        older.seenAt = 1_000;
        Peers.Peer newer = peer("bbbb", "fe80::289d:95ff:fe33:f872%awdltun0");
        newer.seenAt = 2_000;
        Peers.Peer other = peer("cccc", "fe80::1111:2222:3333:4444%awdltun0");
        other.seenAt = 1_500;

        List<Peers.Peer> all = new ArrayList<>();
        all.add(older);
        all.add(newer);
        all.add(other);
        List<Peers.Peer> kept = Peers.dedupe(all);

        assertEquals("两个实例名、同一个地址：只留一格", 2, kept.size());
        // 留下的是「听得更新」的那条：换名之后旧实例名已经过期。
        assertSame(newer, kept.get(0));
        assertSame(other, kept.get(1));
        // 顺序沿用入表顺序，列表不该因为去重而重排。
        assertEquals("bbbb", kept.get(0).name);
    }

    @Test
    public void unproven_peers_never_reach_the_list() {
        Peers.Peer suspect = peer("dddd", "fe80::1");
        suspect.unproven = true;
        List<Peers.Peer> kept = Peers.dedupe(List.of(suspect));
        assertTrue(kept.isEmpty());
    }

    @Test
    public void a_peer_without_a_model_is_not_identified() {
        Peers.Peer unknown = peer("eeee", "fe80::1");
        assertFalse("没有 TXT 型号：界面要等 /Discover 问回真名，不画「附近设备」",
                unknown.identified());
        unknown.model = "iPhone17,1";
        assertTrue(unknown.identified());
        unknown.model = "   ";
        assertFalse(unknown.identified());
    }

    @Test
    public void a_port_that_answers_counts_as_being_there() {
        // 2026-11-27 11:49 的现场：iPhone 的 8770 每分钟都接通，mDNS 却一声不吭——
        // 只信 mDNS 的话，20 秒后这台明明连得上的设备就被删了。
        Peers.Peer peer = peer("a", "fe80::1");
        long now = 30_000;
        peer.seenAt = 0;
        peer.heardFrom = 0;
        peer.portAt = now - 1_000;
        assertEquals(now - 1_000, LivenessState.lastHeard(peer));
        assertEquals(now - 20_000, LivenessState.lastHeard(peer) - 19_000);
    }

    @Test
    public void the_list_only_shows_devices_it_can_name() {
        long now = 100_000;

        // 连不上（地址或端口缺一条）：不上屏。
        Peers.Peer incomplete = new Peers.Peer("ffff");
        assertFalse("记录没凑齐就不是一台能发的设备",
                SendActivity.listed(incomplete, null, now));

        // 有真名：立刻上屏，不用等窗口。
        Peers.Peer named = peer("gggg", "fe80::2");
        named.readyAt = now;
        assertTrue(SendActivity.listed(named, "王小明's iPhone", now));

        // 没有真名、也没有型号（recall 从磁盘造的那条）：等到天荒地老也不上屏——
        // 它唯一能显示的名字是「附近设备」，用户明确要求不出现这种格子。
        Peers.Peer bare = peer("hhhh", "fe80::3");
        bare.readyAt = 0;
        assertFalse(SendActivity.listed(bare, null, now));

        // 有型号：先等名字窗口（1.5 秒），过了就按型号上屏（「iPhone」说得对）。
        Peers.Peer typed = peer("iiii", "fe80::4");
        typed.model = "iPhone17,1";
        typed.readyAt = now;
        assertFalse("窗口内还可能有真名，先等一下", SendActivity.listed(typed, null, now));
        assertFalse(SendActivity.listed(typed, null, now + 1_499));
        assertTrue(SendActivity.listed(typed, null, now + 1_500));
    }
}
