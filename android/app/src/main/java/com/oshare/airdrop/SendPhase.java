package com.oshare.airdrop;

/**
 * 一次发送的生命周期（评审拆分路线：取代 {@code sending}/{@code cancelling} 两个布尔）。
 *
 * <p>两个平级布尔的问题是它们的关系全靠读的人自己记：「cancelling=true 而 sending=false」
 * 是不是合法状态？「人取消之后 sending 还是 true 吗？」——2026-11-22/23 两处取消相关的
 * bug 都是某一处忘了它们之间的约定。枚举把关系写进唯一的地方：一次发送从 {@link #IDLE}
 * 出发，经 {@link #SENDING}（收尾判定读它）、到 {@link #CANCELLING}（人按了停，传输线程
 * 在下一拍看见它收手），最后回 {@link #IDLE}。
 *
 * <p>转移只发生在浏览/发送线程与主线程约定的几个点上：send() 的入口、cancelSend()、
 * 以及两次收尾（成功与失败共用 {@code settle}）。{@code sendSession} 的 UUID 不在这里——
 * 那是这一次发送的数据（卡片取消用它认「是不是这一次」），不是状态。
 */
enum SendPhase {

    /** 没有传输在跑。卡上不该有东西，发送页可以重新挑设备。 */
    IDLE,

    /** 传输在跑，人没按过停。open() 的取消检查与 sendLanes 的记账都读得到这个阶段的安静。 */
    SENDING,

    /** 人按了停。传输线程在下一个取消检查点收手；收尾按「已取消」说话，不按失败。 */
    CANCELLING,
}
