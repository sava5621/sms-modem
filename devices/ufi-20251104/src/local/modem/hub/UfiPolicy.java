package local.modem.hub;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Policy for the inspected 2025 UFI firmware, using its existing system UID, not root. */
public final class UfiPolicy {
    public static final String FINGERPRINT="qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.richal.20251104:user/test-keys";
    private static final String NM="android.os.INetworkManagementService",IP="192.168.101.1",VENDOR="com.youdo.server";
    private final Context context;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private volatile String state="{}";
    private String lastError="";
    private boolean first=true;
    private long firewallAt;
    public UfiPolicy(Context context) {
        if(!Build.FINGERPRINT.equals(FINGERPRINT)||android.os.Process.myUid()!=1000)throw new IllegalStateException("UFI firmware/system UID mismatch");
        this.context=context.getApplicationContext();
    }
    private static Object service() throws Exception {
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"network_management");
        return Class.forName(NM+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
    private static Object call(Object object,String name,Class<?>[] types,Object... values) throws Exception {
        return Class.forName(NM).getMethod(name,types).invoke(object,values);
    }
    private void setting(String key,int value) {
        if(Settings.Global.getInt(context.getContentResolver(),key,-1)!=value&&!Settings.Global.putInt(context.getContentResolver(),key,value))throw new IllegalStateException("Unable to set "+key);
    }
    public void start() { worker.scheduleWithFixedDelay(new Runnable(){public void run(){configure();}},0,10,TimeUnit.SECONDS); }
    public void stop() { worker.shutdownNow(); } // Kernel firewall and persistent off settings remain in force.
    public JSONObject snapshot() throws Exception { return new JSONObject(state); }
    private void configure() {
        JSONObject next=new JSONObject();StringBuilder errors=new StringBuilder();
        Object cm=context.getSystemService(Context.CONNECTIVITY_SERVICE),nm=null;
        try {
            // Never enable mobile data as a workaround for a registration failure.
            if((Boolean)cm.getClass().getMethod("getMobileDataEnabled").invoke(cm))cm.getClass().getMethod("setMobileDataEnabled",boolean.class).invoke(cm,false);
            setting("mobile_data",0);setting("data_roaming",0);setting("wifi_scan_always_enabled",0);setting("tether_supported",0);
        } catch(Exception ex) { error(errors,"data",ex); }
        try {
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            int wifiState=wifi.getWifiState();
            if(wifiState==WifiManager.WIFI_STATE_ENABLED||wifiState==WifiManager.WIFI_STATE_ENABLING)wifi.setWifiEnabled(false);
            int ap=((Integer)wifi.getClass().getMethod("getWifiApState").invoke(wifi)).intValue();
            if(ap==12||ap==13)wifi.getClass().getMethod("setWifiApEnabled",WifiConfiguration.class,boolean.class).invoke(wifi,null,false);
        } catch(Exception ex) { error(errors,"wifi",ex); }
        try {
            // This dedicated vendor router panel was already disabled by the previous setup.
            PackageManager pm=context.getPackageManager();
            if(pm.getApplicationEnabledSetting(VENDOR)!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED)pm.setApplicationEnabledSetting(VENDOR,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,0);
            nm=service();
            boolean tethering=(Boolean)call(nm,"isTetheringStarted",new Class[]{});
            if(first||tethering)cm.getClass().getMethod("setUsbTethering",boolean.class).invoke(cm,false);
            if(tethering)call(nm,"stopTethering",new Class[]{});
            if((Boolean)call(nm,"getIpForwardingEnabled",new Class[]{}))call(nm,"setIpForwardingEnabled",new Class[]{boolean.class},false);
            // KitKat netd: default DROP input / REJECT output and forwarding, IPv4 and IPv6.
            // Only local loopback and the USB interface are allowed; never allow rmnet or any UID.
            long now=SystemClock.elapsedRealtime();
            // netd may restart while NetworkManagementService still caches enabled=true.
            // Rebuild just its three firewall chains periodically to restore the USB allow rules.
            if(first||now-firewallAt>=60000||!(Boolean)call(nm,"isFirewallEnabled",new Class[]{})) {
                call(nm,"setFirewallEnabled",new Class[]{boolean.class},true);
                call(nm,"setFirewallInterfaceRule",new Class[]{String.class,boolean.class},"lo",true);
                call(nm,"setFirewallInterfaceRule",new Class[]{String.class,boolean.class},"rndis0",true);
                firewallAt=now;
            }
            next.put("firewallEnabled",call(nm,"isFirewallEnabled",new Class[]{}));
            next.put("forwarding",call(nm,"getIpForwardingEnabled",new Class[]{}));
            next.put("tethering",call(nm,"isTetheringStarted",new Class[]{}));
            first=false;
        } catch(Exception ex) { first=true;error(errors,"firewall/tether",ex); }
        try {
            NetworkInterface usb=NetworkInterface.getByName("rndis0");
            if(usb==null) {
                Object manager=context.getSystemService(Context.USB_SERVICE);
                manager.getClass().getMethod("setCurrentFunction",String.class,boolean.class).invoke(manager,"rndis,adb",true);
                throw new IllegalStateException("Waiting for RNDIS");
            }
            if(nm==null)nm=service();
            boolean address=false;
            for(Enumeration<InetAddress> values=usb.getInetAddresses();values.hasMoreElements();)if(IP.equals(values.nextElement().getHostAddress()))address=true;
            if(!address||!usb.isUp()) {
                Object config=call(nm,"getInterfaceConfig",new Class[]{String.class},"rndis0");
                Class<?> link=Class.forName("android.net.LinkAddress");
                Object lan=link.getConstructor(InetAddress.class,int.class).newInstance(InetAddress.getByName(IP),24);
                config.getClass().getMethod("setLinkAddress",link).invoke(config,lan);
                config.getClass().getMethod("setInterfaceUp").invoke(config);
                config.getClass().getMethod("clearFlag",String.class).invoke(config,"running");
                call(nm,"setInterfaceConfig",new Class[]{String.class,config.getClass()},"rndis0",config);
            }
            call(nm,"disableIpv6",new Class[]{String.class},"rndis0");
        } catch(Exception ex) { error(errors,"USB LAN",ex); }
        try {
            next.put("mobileData",Settings.Global.getInt(context.getContentResolver(),"mobile_data",-1));
            next.put("dataRoaming",Settings.Global.getInt(context.getContentResolver(),"data_roaming",-1));
            next.put("wifi",Settings.Global.getInt(context.getContentResolver(),"wifi_on",-1));
            next.put("uid",android.os.Process.myUid());next.put("error",errors.toString());next.put("checkedAt",System.currentTimeMillis());
            state=next.toString();
        } catch(Exception ignored) { }
        String message=errors.toString();
        if(!message.equals(lastError)&&!message.isEmpty())Log.w("SmsOnlyUfi",message);
        lastError=message;
    }
    private static void error(StringBuilder target,String area,Exception ex) {
        Throwable cause=ex;while(cause.getCause()!=null)cause=cause.getCause();
        target.append(area).append(':').append(cause.getClass().getSimpleName()).append(';');
    }
}
