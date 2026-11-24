package com.oshare.airdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 地址挑选的判据（P1-24 的第一组单测）。
 *
 * <p>这些判据全来自真机现场：选到 {@code 169.254.x.x} 就连不上，选到带 zone 的串
 * 连 {@code InetAddress} 都解析不了。四条 rank 的顺序写在 {@link Addresses} 的类注释里，
 * 这里逐条钉住，谁改顺序谁先改这里。
 */
public class AddressesTest {

    @Test
    public void bare_strips_zone_and_lowercases() {
        assertEquals("fe80::1", Addresses.bare("fe80::1%wlan0"));
        assertEquals("fe80::1", Addresses.bare("FE80::1"));
        assertEquals("fe80::1%awdltun0".substring(0, 7), Addresses.bare("fe80::1%awdltun0"));
    }

    @Test
    public void link_local_ipv6_wins() {
        assertEquals(3, Addresses.rank("fe80::704d:92ff:fe08:dd2"));
        assertEquals(2, Addresses.rank("2001:db8::1"));
        assertEquals(1, Addresses.rank("192.168.1.5"));
        assertEquals(0, Addresses.rank("169.254.33.10"));
    }

    @Test
    public void better_prefers_the_higher_rank() {
        assertTrue(Addresses.better("fe80::1", "192.168.1.5"));
        assertFalse(Addresses.better("169.254.1.1", "fe80::1"));
        // 同 rank 不换：换一个同层的地址没有任何收益，还可能引起闪烁。
        assertFalse(Addresses.better("fe80::2", "fe80::1"));
    }

    @Test
    public void empty_current_always_accepts() {
        assertTrue(Addresses.better("169.254.1.1", ""));
    }
}
