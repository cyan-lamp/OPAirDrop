#!/system/bin/sh
# Samples the tun counters and the live TCP socket together, because a throughput
# number on its own cannot say why it is small. The socket line carries cwnd, rtt
# and the retransmit count, which is what separates "the radio is slow" from "the
# window never opened".
#
# Runs as root: a plain shell uid cannot see sockets owned by the app.
#
# Output is one line per sample, pipe separated:
#   S <uptime> |A <tun rx> <tun tx> |W <mon rx> <mon tx> |T <socket detail>
# uptime carries two decimals, which is all the resolution a half-second sample
# needs and avoids the arithmetic of a separate millisecond clock.

LIMIT=${LIMIT:-700}
GAP=${GAP:-0.7}

i=0
while [ "$i" -lt "$LIMIT" ]; do
    read -r up _ < /proc/uptime
    a=$(grep awdltun0 /proc/net/dev | tr -s ' ' ' ' | cut -d' ' -f2,10)
    w=$(grep wonder0 /proc/net/dev | tr -s ' ' ' ' | cut -d' ' -f2,10)
    t=$(ss -tin 2>/dev/null | grep -A1 'fe80' | sed 's/^ */ /' | tr '\n' ' ' \
        | sed 's/  */ /g' | cut -c1-240)
    echo "S $up |A $a |W $w |T $t"
    sleep "$GAP"
    i=$((i + 1))
done
