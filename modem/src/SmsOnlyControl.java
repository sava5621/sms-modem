import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Looper;

/** One-shot root command using this firmware's own network-management APIs. */
public final class SmsOnlyControl {
    public static void main(String[] args) {
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Object result = connectivity.getClass().getMethod("setUsbTethering", boolean.class).invoke(connectivity, false);
            System.out.println("USB tethering disable result=" + result);
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            System.out.println("Wi-Fi client disable result=" + wifi.setWifiEnabled(false));
            Object ap = wifi.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class).invoke(wifi, null, false);
            System.out.println("Wi-Fi hotspot disable result=" + ap);
            Context panel = context.createPackageContext("com.wowi.nanowebyl", Context.CONTEXT_IGNORE_SECURITY);
            boolean saved = panel.getSharedPreferences("config", Context.MODE_PRIVATE)
                .edit().putBoolean("testConnect", false).commit();
            if (!saved) throw new IllegalStateException("Could not disable vendor Internet watchdog");
            System.out.println("Vendor testConnect=false saved");
            System.out.println("SMS_ONLY_CONTROL_OK");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
