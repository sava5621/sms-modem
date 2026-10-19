#!/system/bin/sh
set -eu
PATH=/system/bin:/system/xbin
export PATH
[ "$(busybox id -u)" = 0 ] || exit 10
[ "$(getprop ro.build.fingerprint)" = 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys' ] || exit 11
[ -f /data/data/local.modem.hub/databases/inbox.db ] || exit 12
[ "$(getprop persist.adb.tcp.port)" = -1 ] || exit 13
stage=/data/local/tmp/dt-network-update
. "$stage/sms-only-policy.sh"
backup=/data/local/sms-only/backups/dt-network-$(date +%s)-$$
mkdir -p "$backup/system"
chmod 700 "$backup"
for name in modem-usb-lan.sh sms-only-policy.sh init.qcom.post_boot.sh; do cp "/system/etc/$name" "$backup/system/$name"; done
iptables -S > "$backup/iptables-before.txt"
iptables -t nat -S > "$backup/nat-before.txt"
ip6tables -S > "$backup/ip6tables-before.txt"
settings get global mobile_data > "$backup/mobile-data-before.txt"
am force-stop local.modem.hub
busybox tar -cpf "$backup/hub-app.tar" -C /data/data local.modem.hub
sqlite3 /data/data/local.modem.hub/databases/inbox.db 'SELECT count(*) FROM messages;' > "$backup/sms-count.txt"
mount -o remount,rw /system
trap 'mount -o remount,ro /system' EXIT
for name in modem-usb-lan.sh sms-only-policy.sh; do
 cp "$stage/$name" "/system/etc/$name"
 chown 0:0 "/system/etc/$name"
 chmod 644 "/system/etc/$name"
done
mount -o remount,ro /system
if [ -r /data/local/modem-usb-lan.pid ]; then
 read old_pid old_boot < /data/local/modem-usb-lan.pid
 read boot < /proc/sys/kernel/random/boot_id
 case "$old_pid" in ''|*[!0-9]*) echo 'Invalid helper PID'; exit 14;; esac
 if [ "$old_boot" = "$boot" ] && [ -r "/proc/$old_pid/cmdline" ] && busybox grep -q 'modem-usb-lan.sh' "/proc/$old_pid/cmdline"; then
  kill "$old_pid"
  for delay in 1 2 3 4 5 6 7 8 9 10; do
   [ -r "/proc/$old_pid/cmdline" ] && busybox grep -q 'modem-usb-lan.sh' "/proc/$old_pid/cmdline" || break
   sleep 1
  done
  if [ -r "/proc/$old_pid/cmdline" ] && busybox grep -q 'modem-usb-lan.sh' "/proc/$old_pid/cmdline"; then echo 'Old helper did not stop'; exit 15; fi
 fi
fi
for fw in iptables ip6tables; do
 for chain in SMS_ONLY_IN SMS_ONLY_OUT SMS_ONLY_FWD; do "$fw" -F "$chain"; done
done
# Recreate only our own filter rules and HTTP redirect, leaving netd untouched.
. /system/etc/sms-only-policy.sh
sms_firewall
ifconfig rndis0 192.168.100.2 netmask 255.255.255.0 up
busybox setsid sh /system/etc/modem-usb-lan.sh </dev/null > "$backup/helper-start.txt" 2>&1 &
echo "DT_NETWORK_COMPLETE backup=$backup"
cat "$backup/sms-count.txt"
