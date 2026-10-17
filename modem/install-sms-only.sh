#!/system/bin/sh
# Only for the exact firmware documented in docs/01-hardware.md.
# Does not erase application data, flash firmware or reboot the modem.
set -e
PATH=/system/bin:/system/xbin
export PATH
umask 077
die() { echo "SMS_ONLY_ERROR: $*" >&2; exit 1; }
[ "$(busybox id -u)" = 0 ] || die 'root required'
[ "$(getprop ro.build.fingerprint)" = 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys' ] || die 'unverified firmware'
[ "$(getprop sys.boot_completed)" = 1 ] || die 'wait for Android boot to complete'
payload=/data/local/tmp/sms-only-install
boot=/system/etc/init.qcom.post_boot.sh
[ -f "$boot" ] || die 'qcom post-boot script missing'
hook_present=false
if busybox grep -Eq '^[^#]*/system/etc/modem-usb-lan\.sh' "$boot"; then
    hook_present=true
elif busybox grep -Eq '^[[:space:]]*exit([[:space:];]|$)' "$boot"; then
    die 'post-boot has an exit; review the boot hook manually before installation'
fi
for pkg in local.modem.sms local.modem.wifioff com.mo.sdk2230 com.wowi.nanowebyl com.android.systemui; do
    pm path "$pkg" | busybox grep -q '^package:' || die "required package missing: $pkg"
done
for tool in busybox iptables ip6tables settings svc app_process; do
    command -v "$tool" >/dev/null || die "tool missing: $tool"
done
for file in modem-usb-lan.sh sms-only-policy.sh; do
    sh -n "$payload/$file" || die "shell syntax: $file"
done
[ -s "$payload/sms-only-control.jar" ] || die 'control JAR missing'

# Keep the first installation backup and every subsequent pre-change snapshot.
backup=/data/local/sms-only/backups/$(date +%s)-$$
mkdir -p "$backup/system" "$backup/state"
chmod 700 /data/local/sms-only /data/local/sms-only/backups "$backup"
for name in init.qcom.post_boot.sh modem-usb-lan.sh sms-only-policy.sh sms-only-control.jar; do
    if [ -f "/system/etc/$name" ]; then
        cp -p "/system/etc/$name" "$backup/system/$name"
    else
        touch "$backup/system/$name.absent"
    fi
done
for key in mobile_data data_roaming wifi_scan_always_enabled tether_supported wifi_on; do
    settings get global "$key" > "$backup/state/global.$key"
done
getprop persist.ufi.nosignal.reboot > "$backup/state/persist.ufi.nosignal.reboot"
getprop sys.usb.tethering > "$backup/state/sys.usb.tethering"
pm list packages -d > "$backup/state/disabled-packages.txt"
for pkg in com.mo.sdk2230 com.wowi.nanowebyl com.android.systemui; do
    dumpsys package "$pkg" > "$backup/state/$pkg.txt"
done
prefs=/data/data/com.wowi.nanowebyl/shared_prefs/config.xml
if [ -f "$prefs" ]; then cp -p "$prefs" "$backup/state/vendor-config.xml"; else touch "$backup/state/vendor-config.xml.absent"; fi
echo "SMS_ONLY_BACKUP=$backup"

mount -o remount,rw /system
trap 'mount -o remount,ro /system' EXIT
for name in modem-usb-lan.sh sms-only-policy.sh sms-only-control.jar; do
    cp "$payload/$name" "/system/etc/$name"
    chown 0:0 "/system/etc/$name"
    chmod 644 "/system/etc/$name"
done
# Append to the existing vendor script; never replace it with another device's dump.
if [ "$hook_present" = false ]; then
    printf '\n# Restore local USB LAN for SMS only.\n/system/bin/busybox setsid /system/bin/sh /system/etc/modem-usb-lan.sh </dev/null >/dev/null 2>&1 &\n' >> "$boot"
fi
sh -n "$boot"
setprop persist.ufi.nosignal.reboot no
for target in com.mo.sdk2230 com.wowi.nanowebyl/com.wowi.nanoweb.StickService com.android.systemui/com.android.systemui.ElinkAutoTestService; do
    result="$(pm disable "$target" 2>&1)"
    echo "$result"
    echo "$result" | busybox grep -qi 'new state: disabled' || die "could not disable $target; backup=$backup"
done
am force-stop com.mo.sdk2230
am stopservice -n com.wowi.nanowebyl/com.wowi.nanoweb.StickService
am stopservice -n com.android.systemui/com.android.systemui.ElinkAutoTestService
# Never force-stop or disable the entire SystemUI, phone, RIL, netd or thermal service.
. /system/etc/sms-only-policy.sh
sms_firewall
sms_boot_policy
busybox tail -n 1 /data/local/sms-only/control.log | busybox grep -Fxq SMS_ONLY_CONTROL_OK || die "network controller failed; backup=$backup"
[ "$(settings get global mobile_data)" = 0 ] || die 'mobile data is still enabled'
[ "$(getprop persist.ufi.nosignal.reboot)" = no ] || die 'reboot watchdog is still enabled'
sync
mount -o remount,ro /system
trap - EXIT
echo "SMS_ONLY_INSTALLED backup=$backup"
