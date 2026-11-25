#!/system/bin/sh
# OPAirDrop 的 hans 豁免守护
#
# 背景（2026-11-23 实测钉死）：
#   OPPO netd 把本应用的 uid 写进 app_hans_limit_map 之后，
#   IPv6 filter 表 oplus_fw_OUTPUT / oplus_fw_INPUT 上挂的
#   skfilter_egress_hans / skfilter_ingress_hans 这两个 BPF 程序
#   就会 DROP 我们的 AWDL 组播包，nf_hook_slow 返回 -1，
#   内核把它翻译成 -EPERM，应用侧表现为 "sendto failed: EPERM"，
#   iPhone 因此发现不了本机，流体云迟迟不弹。
#
#   删掉 map 里本应用那一个条目即可双向放行（egress/ingress 共用这张 map），
#   且 vending(10121) / gms(10339) 等其它条目的限制原样保留，副作用为零。
#
#   但 netd 会在熄屏等时机把它重写回来（2026-11-23 persist2.sh 实测），
#   所以需要这个看门狗：平时只做一次 lookup，出现才删，成本极低。
#
#   2026-11-25 根因修正（P0）：key 原来写死 10409。**uid 在卸载重装后会漂移**
#   （2026-11-25 实测 10409 → 10448 → 10449），写死的 key 守护着空气，
#   新 uid 的限网条目出现时无人删除——这就是「后台限网反复回归」的根源。
#   现在每 10 秒从 /data/data/<pkg> 的属主动态取 uid，key 跟着 uid 走，
#   任何一次重装都不再需要改脚本。

MAP=/sys/fs/bpf/map_oplus-netd_app_hans_limit_map
BPFTOOL=/system/bin/bpftool
PKG=com.oshare.airdrop
TAG=OPAirDropGuard
LOCK=/data/local/tmp/.opairdrop_hans_guard.pid

logi() { log -t $TAG -p i "$*" 2>/dev/null; }

# uid -> map key。map 的 key 是 4 字节小端整数：10449 = 0x000028F1
# 在字节流里是 f1 28 00 00，bpftool 的 key 参数按「低字节在前」给。
# Android 的 uid 恒小于 65536（appid + 10000），高两字节恒为 0。
uid_to_key() {
  hex=$(printf '%08x' "$1" 2>/dev/null)
  low=${hex#??????}          # 最后两个字符 = 低字节，如 "f1"
  rest=${hex#????}           # 去掉前四个 = "28f1"
  high=${rest%??}            # 再去尾 = 次低字节 "28"
  echo "0x$low 0x$high 0x00 0x00"
}

# 动态取 uid：/data/data/<pkg> 的属主就是它。重装后目录跟着新 uid 走。
# 取不到（比如目录还没建）就返回空，主循环跳过这轮，别拿旧值硬删。
current_uid() {
  stat -c %u "/data/data/$PKG" 2>/dev/null
}

logi "守护启动 pid=$$（按 uid 动态看守 $PKG）"

# 单实例：手动跑过一份、开机又起一份的话只留先到的那个。
# 2026-11-24 实测的坑：pidfile 里的 pid 是旧的（守护死后残留），而 PID 会被复用
# ——当晚 3191 变成了 /vendor/bin/lowi-server，kill -0 照样成功，新实例以为
# 「已有守护在跑」直接退出。之后每次开机自启都这样退出，守护从此永久失效，
# netd 写回的 key 无人删除，EPERM 间歇性掐断 AWDL（发送速度不稳的真凶）。
# 所以「pid 活着」还不够，cmdline 必须还是本脚本才承认。
if [ -f "$LOCK" ]; then
  old=$(cat "$LOCK" 2>/dev/null)
  if [ -n "$old" ] && [ "$old" != "$$" ] && kill -0 "$old" 2>/dev/null; then
    case "$(tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null)" in
      *99-opairdrop-hans*)
        logi "已有守护在跑 pid=$old，本次退出"
        exit 0
        ;;
    esac
    logi "pidfile 里的 $old 已是别的进程（PID 复用），接管"
  fi
fi
echo $$ > "$LOCK"

# 开机早期 netd 还没建出 BPF 对象、data 目录也可能没解密，都等
n=0
while [ ! -e "$MAP" ] || ! [ -d "/data/data/$PKG" ]; do
  n=$((n + 1))
  [ $((n % 60)) -eq 0 ] && logi "等待 map/$PKG 就绪，已等 $((n / 2)) 秒"
  sleep 0.5
done

UID_NOW=$(current_uid)
[ -n "$UID_NOW" ] || UID_NOW=0
KEY=$(uid_to_key "$UID_NOW")
logi "hans map 就绪，看守 uid=$UID_NOW key=[$KEY]"

deleted=0
last_log=0
fail=0
# 每 20 轮（10 秒）刷新一次 uid；uid 变了立刻换 key 并留一条日志
tick=0

while true; do
  tick=$((tick + 1))
  if [ $((tick % 20)) -eq 0 ]; then
    fresh=$(current_uid)
    if [ -n "$fresh" ] && [ "$fresh" != "$UID_NOW" ]; then
      UID_NOW=$fresh
      KEY=$(uid_to_key "$UID_NOW")
      logi "uid 漂移 → $UID_NOW，改守 key=[$KEY]"
    fi
  fi
  if [ -n "$KEY" ] && "$BPFTOOL" map lookup pinned "$MAP" key $KEY >/dev/null 2>&1; then
    if "$BPFTOOL" map delete pinned "$MAP" key $KEY >/dev/null 2>&1; then
      deleted=$((deleted + 1))
      now=$(date +%s)
      # netd 若反复抢写，别把日志刷爆：10 秒最多一条
      if [ $((now - last_log)) -ge 10 ]; then
        logi "第 $deleted 次摘除 uid $UID_NOW（netd 又写回来了）"
        last_log=$now
      fi
      fail=0
    else
      fail=$((fail + 1))
      [ $fail -eq 5 ] && logi "删除失败 5 次，可能 SELinux 拦了 bpftool"
    fi
  fi
  sleep 0.5
done
