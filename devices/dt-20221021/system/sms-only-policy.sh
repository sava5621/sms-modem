#!/system/bin/sh
# Sourced by modem-usb-lan.sh. Local SMS HTTP and USB ADB remain available.
sms_logdir=/data/local/sms-only

# Android's iptables 1.4.11 has no -w. Retry its transient resource error
# when netd updates rules concurrently; a missing -C rule (exit 1) stays fast.
sms_xtables() {
    sms_binary="$1"
    shift
    sms_attempt=0
    while :; do
        if "$sms_binary" "$@"; then return 0; else sms_rc=$?; fi
        [ "$sms_rc" -eq 4 ] || return "$sms_rc"
        sms_attempt=$((sms_attempt + 1))
        [ "$sms_attempt" -lt 5 ] || return "$sms_rc"
        sleep 1
    done
}
iptables() { sms_xtables /system/bin/iptables "$@"; }
ip6tables() { sms_xtables /system/bin/ip6tables "$@"; }

sms_firewall_family() {
    firewall="$1"
    if ! "$firewall" -C SMS_ONLY_IN -j DROP >/dev/null 2>&1; then
        "$firewall" -N SMS_ONLY_IN 2>/dev/null || true
        "$firewall" -F SMS_ONLY_IN || return 1
        "$firewall" -A SMS_ONLY_IN -i lo -j ACCEPT || return 1
        "$firewall" -A SMS_ONLY_IN -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT || return 1
        if [ "$firewall" = iptables ]; then
            "$firewall" -A SMS_ONLY_IN -s 192.168.100.0/24 -d 192.168.100.2 -p tcp -m multiport --dports 80,8000,8088 -j ACCEPT || return 1
            "$firewall" -A SMS_ONLY_IN -s 192.168.100.0/24 -p icmp --icmp-type echo-request -j ACCEPT || return 1
        fi
        "$firewall" -A SMS_ONLY_IN -j DROP || return 1
    fi
    if ! "$firewall" -C SMS_ONLY_OUT -j DROP >/dev/null 2>&1; then
        "$firewall" -N SMS_ONLY_OUT 2>/dev/null || true
        "$firewall" -F SMS_ONLY_OUT || return 1
        "$firewall" -A SMS_ONLY_OUT -o lo -j ACCEPT || return 1
        if [ "$firewall" = iptables ]; then
            "$firewall" -A SMS_ONLY_OUT -d 192.168.100.0/24 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT || return 1
        fi
        "$firewall" -A SMS_ONLY_OUT -j DROP || return 1
    fi
    if ! "$firewall" -C SMS_ONLY_FWD -j DROP >/dev/null 2>&1; then
        "$firewall" -N SMS_ONLY_FWD 2>/dev/null || true
        "$firewall" -A SMS_ONLY_FWD -j DROP || return 1
    fi
    "$firewall" -C INPUT -j SMS_ONLY_IN >/dev/null 2>&1 || "$firewall" -I INPUT 1 -j SMS_ONLY_IN
    "$firewall" -C OUTPUT -j SMS_ONLY_OUT >/dev/null 2>&1 || "$firewall" -I OUTPUT 1 -j SMS_ONLY_OUT
    "$firewall" -C FORWARD -j SMS_ONLY_FWD >/dev/null 2>&1 || "$firewall" -I FORWARD 1 -j SMS_ONLY_FWD
}

sms_no_forwarding() {
    forwarding="$(cat /proc/sys/net/ipv4/ip_forward)"
    [ "$forwarding" = 0 ] || echo 0 > /proc/sys/net/ipv4/ip_forward
    if [ -e /proc/sys/net/ipv6/conf/all/forwarding ]; then
        forwarding="$(cat /proc/sys/net/ipv6/conf/all/forwarding)"
        [ "$forwarding" = 0 ] || echo 0 > /proc/sys/net/ipv6/conf/all/forwarding
    fi
}

sms_firewall() {
    sms_firewall_family iptables || return 1
    sms_firewall_family ip6tables || return 1
    sms_http_redirect || return 1
    sms_no_forwarding
}

# UID 1000 cannot bind a privileged TCP port on this firmware. Keep the
# listener on 8000 and redirect port 80 inside DT, including after reboot.
sms_http_redirect() {
    iptables -t nat -N SMS_DT_HTTP 2>/dev/null || true
    iptables -t nat -C SMS_DT_HTTP -p tcp --dport 80 -j REDIRECT --to-ports 8000 >/dev/null 2>&1 ||
        iptables -t nat -A SMS_DT_HTTP -p tcp --dport 80 -j REDIRECT --to-ports 8000 || return 1
    iptables -t nat -C PREROUTING -i rndis0 -d 192.168.100.2 -p tcp --dport 80 -j SMS_DT_HTTP >/dev/null 2>&1 ||
        iptables -t nat -I PREROUTING 1 -i rndis0 -d 192.168.100.2 -p tcp --dport 80 -j SMS_DT_HTTP
}

sms_disable_tethering() {
    if [ -f "$sms_logdir/control.log" ] && [ "$(busybox wc -c < "$sms_logdir/control.log")" -gt 65536 ]; then
        mv -f "$sms_logdir/control.log" "$sms_logdir/control.previous.log"
    fi
    CLASSPATH=/system/etc/sms-only-control.jar app_process /system/bin SmsOnlyControl >> "$sms_logdir/control.log" 2>&1
    # The one-shot root process must leave the panel's preferences owned by its UID.
    if [ -f /data/data/com.wowi.nanowebyl/shared_prefs/config.xml ]; then
        chown 1000:1000 /data/data/com.wowi.nanowebyl/shared_prefs /data/data/com.wowi.nanowebyl/shared_prefs/config.xml
        chmod 700 /data/data/com.wowi.nanowebyl/shared_prefs
        chmod 600 /data/data/com.wowi.nanowebyl/shared_prefs/config.xml
    fi
    setprop sys.usb.tethering false
    sms_no_forwarding
}

sms_boot_policy() {
    mkdir -p "$sms_logdir"
    chmod 700 "$sms_logdir"
    [ "$(getprop persist.ufi.nosignal.reboot)" = no ] || setprop persist.ufi.nosignal.reboot no
    [ "$(settings get global mobile_data)" = 0 ] || settings put global mobile_data 0
    svc data disable
    [ "$(settings get global data_roaming)" = 0 ] || settings put global data_roaming 0
    [ "$(settings get global wifi_scan_always_enabled)" = 0 ] || settings put global wifi_scan_always_enabled 0
    [ "$(settings get global tether_supported)" = 0 ] || settings put global tether_supported 0
    sms_disable_tethering
    sms_health BOOT
}

sms_health() {
    mkdir -p "$sms_logdir"
    if [ -f "$sms_logdir/health.log" ] && [ "$(busybox wc -c < "$sms_logdir/health.log")" -gt 65536 ]; then
        mv -f "$sms_logdir/health.log" "$sms_logdir/health.previous.log"
    fi
    read boot_id < /proc/sys/kernel/random/boot_id
    read uptime rest < /proc/uptime
    read temperature < /sys/class/thermal/thermal_zone0/temp
    echo "$(date +%s) event=$1 boot=$boot_id uptime=$uptime cpu_temp=$temperature sim=$(getprop gsm.sim.state)" >> "$sms_logdir/health.log"
}

sms_periodic_policy() {
    sms_firewall
    [ "$(getprop persist.ufi.nosignal.reboot)" = no ] || setprop persist.ufi.nosignal.reboot no
    if [ "$(settings get global mobile_data)" != 0 ]; then
        svc data disable
        settings put global mobile_data 0
        log -p w -t SmsOnlyPolicy 'Mobile data was enabled; disabled again'
    fi
    if [ "$(getprop sys.usb.tethering)" = true ]; then sms_disable_tethering; fi
    if [ "$(settings get global wifi_on)" != 0 ]; then svc wifi disable; fi
}
