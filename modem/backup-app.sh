#!/system/bin/sh
# Called manually in a temporary root ADB session. Contains private SMS data.
set -e
PATH=/system/bin:/system/xbin
export PATH
umask 077
[ "$(busybox id -u)" = 0 ] || exit 1
[ -d /data/data/local.modem.sms ] || exit 1
mkdir -p /data/local/sms-only
chmod 700 /data/local/sms-only
archive=/data/local/sms-only/sms-app-$(date +%s)-$$.tar
# Stop the app while copying SQLite and its journals as one consistent directory.
# Android's system inbox remains separate and is imported when the service starts.
trap 'am startservice -n local.modem.sms/.SmsService >/dev/null 2>&1' EXIT
am force-stop local.modem.sms
busybox tar -cpf "$archive" -C /data/data local.modem.sms
chmod 600 "$archive"
am startservice -n local.modem.sms/.SmsService >/dev/null
trap - EXIT
echo "SMS_BACKUP_OK=$archive"
