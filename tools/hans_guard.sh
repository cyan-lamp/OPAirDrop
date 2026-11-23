#!/system/bin/sh
# OPAirDrop 的 hans 豁免守护
#
# 背景（2026-11-23 实测钉死）：
#   OPPO netd 把 uid 10409 写进 app_hans_limit_map 之后，
#   IPv6 filter 表 oplus_fw_OUTPUT / oplus_fw_INPUT 上挂的
#   skfilter_egress_hans / skfilter_ingress_hans 这两个 BPF 程序
#   就会 DROP 我们的 AWDL 组播包，nf_hook_slow 返回 -1，
#   内核把它翻译成 -EPERM，应用侧表现为 "sendto failed: EPERM"，
#   iPhone 因此发现不了本机，流体云迟迟不弹。
#
#   删掉 map 里 key=10409 这一个条目即可双向放行（egress/ingress 共用这张 map），
#   且 vending(10121) / gms(10339) 等其它条目的限制原样保留，副作用为零。
#
#   但 netd 会在熄屏等时机把它重写回来（2026-11-23 persist2.sh 实测），
#   所以需要这个看门狗：平时只做一次 lookup，出现才删，成本极低。

MAP=/sys/fs/bpf/map_oplus-netd_app_hans_limit_map
BPFTOOL=/system/bin/bpftool
KEY="0xa9 0x28 0x00 0x00"     # 10409 = com.oshare.airdrop
TAG=OPAirDropGuard
LOCK=/data/local/tmp/.opairdrop_hans_guard.pid

logi() { log -t $TAG -p i "$*" 2>/dev/null; }

# 单实例：手动跑过一份、开机又起一份的话只留先到的那个
if [ -f "$LOCK" ]; then
  old=$(cat "$LOCK" 2>/dev/null)
  if [ -n "$old" ] && [ "$old" != "$$" ] && kill -0 "$old" 2>/dev/null; then
    logi "已有守护在跑 pid=$old，本次退出"
    exit 0
  fi
fi
echo $$ > "$LOCK"

logi "守护启动 pid=$$"

# 开机早期 netd 还没建出 BPF 对象，先等它
n=0
while [ ! -e "$MAP" ]; do
  n=$((n + 1))
  [ $((n % 60)) -eq 0 ] && logi "等待 hans map 出现，已等 $((n / 2)) 秒"
  sleep 0.5
done
logi "hans map 就绪，开始看守"

deleted=0
last_log=0
fail=0

while true; do
  if "$BPFTOOL" map lookup pinned "$MAP" key $KEY >/dev/null 2>&1; then
    if "$BPFTOOL" map delete pinned "$MAP" key $KEY >/dev/null 2>&1; then
      deleted=$((deleted + 1))
      now=$(date +%s)
      # netd 若反复抢写，别把日志刷爆：10 秒最多一条
      if [ $((now - last_log)) -ge 10 ]; then
        logi "第 $deleted 次摘除 uid 10409（netd 又写回来了）"
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
