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
 *   <li>{@link LivenessState#goneFloor}：端口回绝之后的删除地板要跟着实测节奏放大——iPhone
 *       的应答器会成段休眠 6–8 秒，「在旁边、在宣告、却暂时不接 8770」是真实状态。常数 3 秒
 *       会把它删进墓里（用户报的「AirDrop 开着，设备却消失了」）。</li>
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
    public void a_gone_peer_waits_the_constant_floor_before_samples() {
        Peers.Peer peer = peer("a", "fe80::1");
        peer.gone = true;
        peer.seenAt = 1_000;
        peer.heardFrom = 1_000;
        // 还没有节奏样本：退回常数地板，不能更短。
        assertEquals(LivenessState.QUIET_BEFORE_GONE_MS, LivenessState.goneFloor(peer));
        assertEquals(LivenessState.Step.KEEP, LivenessState.decide(peer, 1_000 + 2_999, false));
        assertEquals(LivenessState.Step.DROP_GONE, LivenessState.decide(peer, 1_000 + 3_000, false));
    }

    @Test
    public void a_sleepy_peer_gets_a_floor_that_follows_its_measured_rhythm() {
        Peers.Peer peer = peer("a", "fe80::1");
        peer.gone = true;
        peer.seenAt = 0;
        peer.heardFrom = 0;
        // 量到的最坏间隔 4 秒（iPhone 应答器成段休眠的实测形状）。
        peer.gaps = LivenessState.CONNECTABLE_GAPS;
        peer.gapMax = 4_000;
        long floor = LivenessState.goneFloor(peer);
        assertEquals(2 * 4_000 + LivenessState.CONNECTABLE_SLACK_MS, floor);
        // 3 秒时不能删：它只是睡着了。这正是「AirDrop 开着设备却消失」的那一格。
        assertEquals(LivenessState.Step.KEEP, LivenessState.decide(peer, 3_000, false));
        assertEquals(LivenessState.Step.KEEP, LivenessState.decide(peer, floor - 1, false));
        assertEquals(LivenessState.Step.DROP_GONE, LivenessState.decide(peer, floor, false));
    }

    @Test
    public void a_peer_that_is_not_gone_is_never_dropped_by_the_gone_floor() {
        Peers.Peer peer = peer("a", "fe80::1");
        peer.seenAt = 0;
        peer.heardFrom = 0;
        peer.gaps = LivenessState.CONNECTABLE_GAPS;
        peer.gapMax = 4_000;
        // 没被端口证死：走的是 timeout()，20 秒起。4 秒以内必须留着。
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
