#!/system/bin/sh
set -eu
PATH=/system/bin:/system/xbin
export PATH
[ "$(busybox id -u)" = 0 ] || exit 10
[ "$(getprop ro.build.fingerprint)" = 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys' ] || exit 11
[ ! -e /data/data/local.modem.hub/databases/inbox.db ] || { echo 'Target database already exists; refusing overwrite'; exit 12; }
[ -f /data/data/local.modem.sms/databases/inbox.db ] || exit 13
stage=/data/local/tmp/dt-ui-migration
backup=/data/local/sms-only/backups/dt-ui-$(date +%s)-$$
mkdir -p "$backup/system"
chmod 700 "$backup"
cp /system/etc/modem-usb-lan.sh "$backup/system/modem-usb-lan.sh"
cp /system/etc/sms-only-policy.sh "$backup/system/sms-only-policy.sh"
cp /system/etc/init.qcom.post_boot.sh "$backup/system/init.qcom.post_boot.sh"
iptables -S > "$backup/iptables-before.txt"
ip6tables -S > "$backup/ip6tables-before.txt"
getprop persist.adb.tcp.port > "$backup/adb-tcp-before.txt"
pm list packages -d > "$backup/disabled-packages.txt"
for key in mobile_data data_roaming wifi_on wifi_scan_always_enabled tether_supported; do settings get global "$key" > "$backup/$key.txt"; done
tinymix > "$backup/mixer.txt" 2>&1 || true
am force-stop local.modem.sms
busybox tar -cpf "$backup/sms-app.tar" -C /data/data local.modem.sms
sqlite3 /data/data/local.modem.sms/databases/inbox.db ".backup $backup/inbox.db"
sqlite3 "$backup/inbox.db" 'SELECT count(*) FROM messages;' > "$backup/sms-count.txt"
mkdir -p /data/data/local.modem.hub/databases
cp "$backup/inbox.db" /data/data/local.modem.hub/databases/inbox.db
chown 1000:1000 /data/data/local.modem.hub/databases /data/data/local.modem.hub/databases/inbox.db
chmod 700 /data/data/local.modem.hub/databases
chmod 600 /data/data/local.modem.hub/databases/inbox.db
restorecon /data/data/local.modem.hub/databases /data/data/local.modem.hub/databases/inbox.db
mount -o remount,rw /system
trap 'mount -o remount,ro /system' EXIT
cp "$stage/modem-usb-lan.sh" /system/etc/modem-usb-lan.sh
cp "$stage/sms-only-policy.sh" /system/etc/sms-only-policy.sh
chown 0:0 /system/etc/modem-usb-lan.sh /system/etc/sms-only-policy.sh
chmod 644 /system/etc/modem-usb-lan.sh /system/etc/sms-only-policy.sh
mount -o remount,ro /system
pidfile=/data/local/modem-usb-lan.pid
if [ -r "$pidfile" ]; then
 read old_pid old_boot < "$pidfile"
 read boot < /proc/sys/kernel/random/boot_id
 case "$old_pid" in ''|*[!0-9]*) echo 'Invalid helper PID'; exit 14;; esac
 if [ "$old_boot" = "$boot" ] && [ -r "/proc/$old_pid/cmdline" ] && busybox grep -q 'modem-usb-lan.sh' "/proc/$old_pid/cmdline"; then kill "$old_pid"; fi
fi
# Wait for the previous helper's TERM trap to remove its PID file. Starting
# earlier can make the new helper mistake the old process for a live owner.
if [ -n "${old_pid:-}" ]; then
 for delay in 1 2 3 4 5 6 7 8 9 10; do
  [ -r "/proc/$old_pid/cmdline" ] && busybox grep -q 'modem-usb-lan.sh' "/proc/$old_pid/cmdline" || break
  sleep 1
 done
 if [ -r "/proc/$old_pid/cmdline" ] && busybox grep -q 'modem-usb-lan.sh' "/proc/$old_pid/cmdline"; then echo 'Previous helper did not stop'; exit 15; fi
fi
ifconfig rndis0 192.168.101.1 netmask 255.255.255.0 up
for fw in iptables ip6tables; do
 for chain in SMS_ONLY_IN SMS_ONLY_OUT SMS_ONLY_FWD; do "$fw" -F "$chain"; done
done
busybox setsid sh /system/etc/modem-usb-lan.sh </dev/null > "$backup/helper-start.txt" 2>&1 &
setprop persist.adb.tcp.port -1
setprop service.adb.tcp.port -1
pm disable local.modem.sms
echo "DT_MIGRATION_COMPLETE backup=$backup"
cat "$backup/sms-count.txt"
