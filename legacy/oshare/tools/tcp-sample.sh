#!/system/bin/sh
# Samples the upload socket while a transfer runs, so "the bar stopped" can be split
# into: the kernel stopped handing frames to the tun, the tun stopped carrying them,
# or the receiver stopped opening its window.
#
# Counters come from /proc/net/dev rather than /sys/class/net/awdltun0/statistics,
# which does not exist on this kernel's tun at all -- the read fails with ENOENT and an
# empty tx= in every sample made the tun look idle for the whole run.
#
# Columns on the awdltun0 line: rx_bytes rx_pkts rx_errs rx_drop ... tx_bytes tx_pkts
# tx_errs tx_drop. The drop pair is the one that matters: it is where a frame the
# driver refused would show up.
LOG=/data/local/tmp/tcp-sample.log
: > "$LOG"
END=$(( $(date +%s) + ${1:-180} ))
while [ "$(date +%s)" -lt "$END" ]; do
  echo "=== $(date +%s%3N) $(awk '$1 == "awdltun0:" { print "rx=" $2 "/" $3 " tx=" $10 "/" $11 " err=" $12 " drop=" $13 }' /proc/net/dev)" >> "$LOG"
  ss -tin state established 2>/dev/null | grep -A1 ":8770" >> "$LOG"
  grep "^Tcp:" /proc/net/snmp >> "$LOG"
  sleep 0.2
done
