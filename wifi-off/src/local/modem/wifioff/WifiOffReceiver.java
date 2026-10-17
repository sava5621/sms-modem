package local.modem.wifioff;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

/** Event-driven policy for this modem's Android 4.4 firmware. No polling or resident service. */
public final class WifiOffReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { disable(context); }
    static void disable(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            int state = wifi.getWifiState();
            if (state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING) {
                wifi.setWifiEnabled(false);
                Log.i("ModemWifiOff", "Wi-Fi client disabled");
            }
            int ap = ((Integer) WifiManager.class.getMethod("getWifiApState").invoke(wifi)).intValue();
            if (ap == 12 || ap == 13) {
                WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class).invoke(wifi, null, false);
                Log.i("ModemWifiOff", "Wi-Fi access point disabled");
            }
        } catch (Exception ex) { Log.e("ModemWifiOff", "Could not disable Wi-Fi", ex); }
    }
}
