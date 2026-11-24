package com.oshare.airdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 磁贴三态的真值表（评审 P1-24，纯函数在 {@link TileState}）。
 *
 * <p>判定本身只有四行，但它管的是「点不点得动」：STATE_UNAVAILABLE 会让 {@code Tile.click()}
 * 直接 return（旧版把「点一下重试」写在一格点不动的灰块上），所以哪一支都必须是可点的
 * INACTIVE 或 ACTIVE——这个约束的真值表钉在这里。
 */
public class AirdropTileTest {

    private static final int INACTIVE = TileState.INACTIVE;
    private static final int ACTIVE = TileState.ACTIVE;

    @Test
    public void wanted_but_service_down_is_inactive_and_clickable() {
        assertEquals(INACTIVE, TileState.stateOf(true, false, true));
        assertEquals("监听没起来，点一下重试",
                TileState.subtitleOf(true, false, true, false, ""));
    }

    @Test
    public void settling_window_says_starting_not_failure() {
        assertEquals("正在启动…", TileState.subtitleOf(true, false, true, true, ""));
    }

    @Test
    public void listening_without_link_stays_inactive_and_clickable() {
        assertEquals(INACTIVE, TileState.stateOf(true, true, false));
        assertEquals("链路未建立，点这里建链",
                TileState.subtitleOf(true, true, false, false, ""));
        // StackKeeper 有一句现成的麻烦话时优先用它。
        assertEquals("没有网卡 awdltun0",
                TileState.subtitleOf(true, true, false, false, "没有网卡 awdltun0"));
    }

    @Test
    public void listening_and_linked_is_the_green_light() {
        assertEquals(ACTIVE, TileState.stateOf(true, true, true));
        assertNull(TileState.subtitleOf(true, true, true, false, ""));
    }

    @Test
    public void not_wanted_is_off_even_when_stale_link_reported() {
        // 用户根本没开：无论链路报告说什么，这一格都是暗的。
        assertEquals(INACTIVE, TileState.stateOf(false, false, false));
        assertNull(TileState.subtitleOf(false, false, false, false, ""));
    }
}
