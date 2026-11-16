package com.oshare.airdrop;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 控制中心里那块【AirDrop】：开就是「在收」，关就是「不在收」。
 *
 * <p># 为什么要有它
 *
 * <p>在此之前，接收端什么时候算「在收」取决于别人的东西——互传的「所有人（10 分钟）」。那是一个
 * 十分钟后自己到期的计时器：时间一到，我们的接收端跟着安静地停，屏幕上没有任何说法。人在控制
 * 中心看得见互传的开关，却看不见我们的状态，于是「怎么又收不到了」这件事没有答案。
 *
 * <p>这块磁贴把那个决定权拿回来，只有两种状态，没有第三种：**开**，服务在跑、8770 在听、
 * mDNS 与 BLE 都在发；**关**，三样一起停。状态就是磁贴本身的颜色，不需要再解释。
 *
 * <p># 它必须能冷启动
 *
 * <p>系统在展开通知栏时绑定这个服务，所以进程被杀之后磁贴照样点得动。点了之后走的是
 * {@link ReceiveService#setManual} + {@link ReceiveService#apply}：写凭据、起前台服务。
 * 磁贴的点击会带来一个短暂的允许窗口，这正是「应用在后台也能开始接收」需要的那一下。
 */
public final class AirdropTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        render();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (ReceiveService.listening(this)) {
            ReceiveService.setManual(this, false);
            ReceiveService.apply(this);
        } else {
            // 就是一个开关：打开就一直收，关掉就停。没有十分钟的窗口，也没有第二处设置——
            // 一个开关只该有一个含义（见 2026-11-16 15:10 那条：别人的十分钟窗口正是"又收不到
            // 了"的来源）。
            ReceiveService.enable(this, 0);
        }
        render();
    }

    /** 磁贴的样子只反映一件事：现在收不收。 */
    private void render() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = ReceiveService.listening(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_airdrop));
        // 用应用自己的图标（蓝色圆点 + 两道环）。给的是**裁剪过视口**的那一份
        // （{@code tile_airdrop.xml}：同一组路径，视口从 108 收到 48），所以它在磁贴里占满
        // 那一格，和旁边系统图标同样大；颜色交给系统着色——磁贴本来就是单色语义的地方。
        tile.setIcon(Icon.createWithResource(this, R.drawable.tile_airdrop));
        tile.updateTile();
    }
}
