package com.oshare.airdrop;

import android.app.PendingIntent;
import android.content.Intent;
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

    private static final String TAG = "OPAirDrop";

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
            if (!Notices.ready(this)) {
                // 磁贴是个 Service，弹不出权限框。缺任何一条，刚打开的这个接收端都是哑的：
                // 没有通知，「有人要发东西给你」一个字也送不出去；没有蓝牙广播，对端连这台
                // 机器在不在都不知道——两种缺口在磁贴上长得一模一样（都是「开着但没反应」）。
                //
                // 所以把人送到主界面去：那里会把缺的那一条摆出来，并把框弹出去。这一下是他
                // 自己点的，startActivityAndCollapse 不受后台启动限制。
                askForPermissions();
            }
        }
        render();
    }

    /**
     * 把主界面拉起来，让它在可见的状态下把缺的几条权限问一次。
     *
     * <p>带上 {@link MainActivity#EXTRA_PERMISSIONS}：这是区分「他自己打开应用看看」和「他
     * 刚点了开、明确要能被发现」的唯一凭据。前者只弹从没问过的框，后者连「系统已经不再弹」的
     * 那几条也可以直接落到设置页——他已经在等文件了，这时候唯一有用的动作就是把开关勾上。
     */
    private void askForPermissions() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_PERMISSIONS, true);
        try {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException refused) {
            // 个别 ROM 在这一步会拒。磁贴自己已经切到「开」，而主界面的胶囊会写明缺什么，
            // 所以这里只留一行日志，不再弹第二条通知去解释一条发不出去的通知。
            Log.w(TAG, "磁贴拉不起主界面：" + refused);
        }
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
        // 用应用自己的图标（蓝色圆点 + 两道环），取的是**裁剪过视口**的那一份
        // （{@code tile_airdrop.xml}：同一组路径，viewport 收到 48 并让图形顶格画）。
        // 颜色不用管：磁贴本来就按状态给图标上 tint（开=亮、关=暗），drawable 里那个蓝色渐变
        // 在这一格会被刷成纯白，只在别处（比如以后直接当图标用）才看得见。
        //
        // 尺寸这件事到此为止了：系统把图标 setBounds 到固定的 24dp 槽位，不读 drawable 的
        // 固有尺寸，所以「再放大一点」只能靠 drawable 里把图形画得更满。上限与实测数字见
        // tile_airdrop.xml 的文件头（结论：当前已占槽位 96%，最多到 1.04 倍，再多就裁边）。
        tile.setIcon(Icon.createWithResource(this, R.drawable.tile_airdrop));
        tile.updateTile();
    }
}
