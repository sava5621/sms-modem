#!/system/bin/sh
# Restore this modem's local USB LAN after boot or RNDIS recreation.
# Runs as root from qcom-post-boot. Does not enable Internet tethering.
PATH=/system/bin:/system/xbin
export PATH
umask 077
PIDFILE=/data/local/modem-usb-lan.pid
read current_boot < /proc/sys/kernel/random/boot_id
if [ -r "$PIDFILE" ]; then
    read previous previous_boot < "$PIDFILE"
    case "$previous" in
        ''|*[!0-9]*) ;;
        *)
            if [ "$previous_boot" = "$current_boot" ] && [ "$previous" != "$$" ] && [ -r "/proc/$previous/cmdline" ] &&
                busybox grep -q 'modem-usb-lan.sh' "/proc/$previous/cmdline"; then
                exit 0
            fi
            ;;
    esac
fi
echo "$$ $current_boot" > "$PIDFILE"
trap '' HUP
trap 'rm -f "$PIDFILE"; exit 0' INT TERM

restore_lan() {
    [ -d /sys/class/net/rndis0 ] || return
    current="$(ip -o -4 addr show dev rndis0 2>/dev/null)"
    if [ -n "$current" ]; then
        case "$current" in
            *' inet 192.168.101.1/24 '*) ;;
            *) return ;;
        esac
        read flags < /sys/class/net/rndis0/flags
        [ "$((flags & 1))" -eq 1 ] && return
    else
        # Leave the firmware's bridge alone while it owns the LAN address.
        ip -o -4 addr | busybox grep -q ' inet 192.168.101.1/' && return
    fi
    if ifconfig rndis0 192.168.101.1 netmask 255.255.255.0 up; then
        log -p i -t ModemUsbLan 'Restored rndis0 local LAN 192.168.101.1/24'
    fi
}

. /system/etc/sms-only-policy.sh
sms_firewall
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
sms_boot_policy
sms_tick=0
while :; do
    restore_lan
    sms_no_forwarding
    if [ "$((sms_tick % 6))" -eq 0 ]; then sms_periodic_policy; fi
    if [ "$((sms_tick % 60))" -eq 0 ]; then sms_health SAMPLE; fi
    sms_tick=$((sms_tick + 1))
    sleep 5
done
