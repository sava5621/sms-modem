package local.modem.hub;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.io.*;
import java.net.*;
import org.json.*;

public final class DeviceControl {
 static final String IP="192.168.100.2";
 static final String VENDOR="com.wowi.nanowebyl";
 final Context c;
 volatile String error="";
 volatile long ledUntil;
 private boolean ledControllerStopped;
 volatile boolean networkRegistered;
 final DtPolicy policy;
 private static final ComponentName SYSTEM_UI=new ComponentName("com.android.systemui","com.android.systemui.SystemUIService");
 private static final ComponentName FACTORY_LED=new ComponentName("com.android.systemui","com.android.systemui.ElinkAutoTestService");
 DeviceControl(Context context){if(!Build.FINGERPRINT.equals("qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys"))throw new IllegalStateException("DT firmware mismatch");c=context;policy=new DtPolicy(context);}
 static Object service(String name,String iface)throws Exception {
  Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
  return Class.forName(iface+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
 }
 static Object call(Object obj,String iface,String name,Class<?>[] types,Object... args)throws Exception {
  return Class.forName(iface).getMethod(name,types).invoke(obj,args);
 }
 static final String NM="android.os.INetworkManagementService";
 static String property(String name)throws Exception {return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,name);}
 static String read(String path){try{BufferedReader r=new BufferedReader(new FileReader(path));try{return r.readLine();}finally{r.close();}}catch(Exception e){return "unknown";}}
 static void write(String path,String value)throws Exception {FileOutputStream f=new FileOutputStream(path);try{f.write(value.getBytes("US-ASCII"));}finally{f.close();}}
 JSONObject snapshot()throws Exception {
  JSONObject j=new JSONObject();
  j.put("configurationEnabled",c.getSharedPreferences("hub",0).getBoolean("configurationEnabled",false));j.put("lanInterface",lanInterface());j.put("uid",android.os.Process.myUid());j.put("ip",IP);j.put("vendorState",c.getPackageManager().getApplicationEnabledSetting(VENDOR));
  for(String k:new String[]{"mobile_data","wifi_on","wifi_scan_always_enabled","bluetooth_on"})j.put(k,Settings.Global.getString(c.getContentResolver(),k));
  Object nm=service("network_management",NM);
  j.put("usbConfig",property("sys.usb.config"));j.put("usbDefault",property("persist.sys.usb.config"));
  j.put("rndis",NetworkInterface.getByName("rndis0")==null?"absent":String.valueOf(call(nm,NM,"getInterfaceConfig",new Class[]{String.class},"rndis0")));
  j.put("forwarding",call(nm,NM,"getIpForwardingEnabled",new Class[]{}));
  WifiManager w=(WifiManager)c.getSystemService(Context.WIFI_SERVICE);
  j.put("wifiApState",WifiManager.class.getMethod("getWifiApState").invoke(w));
  j.put("systemUiServiceState",c.getPackageManager().getComponentEnabledSetting(SYSTEM_UI));
  j.put("factoryLedServiceState",c.getPackageManager().getComponentEnabledSetting(FACTORY_LED));
  j.put("green",read("/sys/class/leds/4G_L2/brightness"));
  j.put("red",read("/sys/class/leds/4G_S/brightness"));
  j.put("blue",read("/sys/class/leds/WIFI_S/brightness"));
  j.put("capturePermission",c.checkCallingOrSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT"));
  j.put("modifyPhonePermission",c.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE"));
  j.put("ipv6Disabled",read("/proc/sys/net/ipv6/conf/rndis0/disable_ipv6"));
  j.put("policy",policy.snapshot());
  return j;
 }
 synchronized void backup()throws Exception {
  File f=new File(c.getFilesDir(),"before.json");
  if(!f.exists()){byte[] data=snapshot().toString(2).getBytes("UTF-8");File temp=new File(c.getFilesDir(),"before.json.tmp");FileOutputStream out=new FileOutputStream(temp);try{out.write(data);out.getFD().sync();}finally{out.close();}if(!temp.renameTo(f))throw new IOException("Cannot commit settings backup");}
 }
 static String lanInterface(){return new File("/sys/class/net/rndis0/brport/bridge").exists()&&NetworkInterfaceExists("bridge1")?"bridge1":"rndis0";}
 static boolean NetworkInterfaceExists(String name){try{return NetworkInterface.getByName(name)!=null;}catch(Exception e){return false;}}
 synchronized void configure() {
  StringBuilder errors=new StringBuilder();
  try {backup();}catch(Exception e){error="Не удалось сохранить настройки: "+detail(e);return;}
  if(!c.getSharedPreferences("hub",0).getBoolean("configurationEnabled",false))return;
  try {
   PackageManager pm=c.getPackageManager();
   ComponentName home=new ComponentName(c,HomeActivity.class);
   if(pm.getComponentEnabledSetting(home)==PackageManager.COMPONENT_ENABLED_STATE_DISABLED)pm.setComponentEnabledSetting(home,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP);
   if(pm.getApplicationEnabledSetting(VENDOR)!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    pm.setApplicationEnabledSetting(VENDOR,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,0);
   android.app.ActivityManager am=(android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
   for(android.app.ActivityManager.RunningAppProcessInfo process:am.getRunningAppProcesses())if(VENDOR.equals(process.processName)){am.getClass().getMethod("forceStopPackage",String.class).invoke(am,VENDOR);break;}
  }catch(Exception e){errors.append("Штатная панель: ").append(detail(e)).append("; ");}
  try {
   WifiManager w=(WifiManager)c.getSystemService(Context.WIFI_SERVICE);
   if(w.getWifiState()!=WifiManager.WIFI_STATE_DISABLED && w.getWifiState()!=WifiManager.WIFI_STATE_DISABLING)w.setWifiEnabled(false);
   int state=((Integer)WifiManager.class.getMethod("getWifiApState").invoke(w)).intValue();
   if(state==12||state==13)WifiManager.class.getMethod("setWifiApEnabled",WifiConfiguration.class,boolean.class).invoke(w,null,false);
   if(Settings.Global.getInt(c.getContentResolver(),"wifi_scan_always_enabled",-1)!=0)Settings.Global.putInt(c.getContentResolver(),"wifi_scan_always_enabled",0);
   // DT registers and receives SMS with data disabled. Preserve its existing
   // SMS-only policy; do not copy the UF7 mobile-data workaround.
   if(Settings.Global.getInt(c.getContentResolver(),"mobile_data",0)!=0){
    Object cm=c.getSystemService(Context.CONNECTIVITY_SERVICE);
    cm.getClass().getMethod("setMobileDataEnabled",boolean.class).invoke(cm,false);
    Settings.Global.putInt(c.getContentResolver(),"mobile_data",0);
   }
  }catch(Exception e){errors.append("Радиосеть: ").append(detail(e)).append("; ");}
  try {
   if(NetworkInterface.getByName("rndis0")==null){Object usb=c.getSystemService(Context.USB_SERVICE);usb.getClass().getMethod("setCurrentFunction",String.class,boolean.class).invoke(usb,"rndis,adb",true);error="Ожидание USB RNDIS";return;}
   Object nm=service("network_management",NM);
   if((Boolean)call(nm,NM,"isTetheringStarted",new Class[]{}))call(nm,NM,"stopTethering",new Class[]{});
   if((Boolean)call(nm,NM,"getIpForwardingEnabled",new Class[]{}))call(nm,NM,"setIpForwardingEnabled",new Class[]{boolean.class},false);
   String lan=lanInterface();
   call(nm,NM,"setInterfaceUp",new Class[]{String.class},"rndis0");
   for(String iface:new String[]{"rndis0",lan})if(!"1".equals(read("/proc/sys/net/ipv6/conf/"+iface+"/disable_ipv6")))call(nm,NM,"disableIpv6",new Class[]{String.class},iface);
   NetworkInterface ni=NetworkInterface.getByName(lan);boolean ready=false;
   if(ni!=null&&ni.isUp())for(java.util.Enumeration<InetAddress> e=ni.getInetAddresses();e.hasMoreElements();)if(IP.equals(e.nextElement().getHostAddress()))ready=true;
   if(!ready){
    Object cfg=call(nm,NM,"getInterfaceConfig",new Class[]{String.class},lan);
    Class<?> link=Class.forName("android.net.LinkAddress");
    Object address=link.getConstructor(InetAddress.class,int.class).newInstance(InetAddress.getByName(IP),24);
    cfg.getClass().getMethod("setLinkAddress",link).invoke(cfg,address);cfg.getClass().getMethod("setInterfaceUp").invoke(cfg);cfg.getClass().getMethod("clearFlag",String.class).invoke(cfg,"running");
    call(nm,NM,"setInterfaceConfig",new Class[]{String.class,cfg.getClass()},lan,cfg);
   }
  }catch(Exception e){errors.append("USB-сеть: ").append(detail(e)).append("; ");}
  try {policy.configure();}catch(Exception e){errors.append("Изоляция: ").append(detail(e)).append("; ");}
  try {led(false);}catch(Exception e){errors.append("LED: ").append(detail(e)).append("; ");}
  error=errors.toString();
  if(!error.isEmpty())Log.w("ModemHub",error);
 }
 synchronized JSONObject ledSettings()throws Exception{return new JSONObject().put("ledMode",ledMode()).put("ledPulseSeconds",ledPulseSeconds());}
 String ledMode(){return c.getSharedPreferences("hub",0).getString("ledMode","page");}
 int ledPulseSeconds(){return c.getSharedPreferences("hub",0).getInt("ledPulseSeconds",5);}
 int ledPulseMillis(){return "page".equals(ledMode())?ledPulseSeconds()*1000:0;}
 synchronized void setLedSettings(String mode,int seconds)throws Exception{
  if(!mode.equals("off")&&!mode.equals("on")&&!mode.equals("page")||seconds<1||seconds>60)throw new IllegalArgumentException("Invalid LED settings");
  if(!c.getSharedPreferences("hub",0).edit().putString("ledMode",mode).putInt("ledPulseSeconds",seconds).commit())throw new IOException("Не удалось сохранить настройку");
  ledUntil=0;led(false);
 }
 synchronized void led(boolean pulse)throws Exception {
  if(!c.getSharedPreferences("hub",0).getBoolean("configurationEnabled",false))return;
  takeLedControl();
  String mode=ledMode();if(pulse&&mode.equals("page"))ledUntil=SystemClock.elapsedRealtime()+ledPulseSeconds()*1000L;
  android.telephony.TelephonyManager phone=(android.telephony.TelephonyManager)c.getSystemService(Context.TELEPHONY_SERVICE);
  boolean fault=phone.getSimState()!=android.telephony.TelephonyManager.SIM_STATE_READY||!networkRegistered;
  ledWrite("4G_S","0");ledWrite("WIFI_S",fault?"255":"0");
  ledWrite("4G_L2",!fault&&(mode.equals("on")||mode.equals("page")&&SystemClock.elapsedRealtime()<ledUntil)?"255":"0");
  // 4G_L3 is off on this board and has a read-only node for the system UID.
  // SIM_EN/SIM_SEL/ESIM* and FTM_ST are not indicators and are never written.
  if(!"0".equals(read("/sys/class/leds/4G_L3/brightness")))throw new IOException("4G_L3 is controlled by firmware");
 }
 private void takeLedControl()throws Exception{
  if(ledControllerStopped)return;
  // UF7 SystemUI's NetworkController.E4GLedThread writes 4G_L2 once a
  // second. Disable its screen-only service before terminating that process;
  // forceStopPackage alone leaves this persistent system process running.
  // ElinkReportIccid also flashes the same LEDs from the factory test service.
  // Telephony and the radio are separate packages and remain enabled.
  PackageManager pm=c.getPackageManager();android.content.SharedPreferences p=c.getSharedPreferences("hub",0);
  if(!p.contains("systemUiServiceBefore")&&!p.edit().putInt("systemUiServiceBefore",pm.getComponentEnabledSetting(SYSTEM_UI)).commit())throw new IOException("Cannot back up LED controller state");
  if(!p.contains("factoryLedServiceBefore")&&!p.edit().putInt("factoryLedServiceBefore",pm.getComponentEnabledSetting(FACTORY_LED)).commit())throw new IOException("Cannot back up factory LED controller state");
  pm.setComponentEnabledSetting(SYSTEM_UI,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
  pm.setComponentEnabledSetting(FACTORY_LED,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
  c.stopService(new Intent().setComponent(SYSTEM_UI));
  c.stopService(new Intent().setComponent(FACTORY_LED));
  android.app.ActivityManager am=(android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
  java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes=am.getRunningAppProcesses();
  if(processes!=null)for(android.app.ActivityManager.RunningAppProcessInfo process:processes)if("com.android.systemui".equals(process.processName)&&process.uid==android.os.Process.myUid())android.os.Process.killProcess(process.pid);
  ledControllerStopped=true;
 }
 static void ledWrite(String name,String value)throws Exception{String path="/sys/class/leds/"+name+"/brightness";if(!value.equals(read(path)))write(path,value);}
 void restore()throws Exception {
  policy.restore();
  JSONObject j=new JSONObject(readAll(new File(c.getFilesDir(),"before.json")));
  c.getSharedPreferences("hub",0).edit().putBoolean("configurationEnabled",false).commit();
  android.content.SharedPreferences p=c.getSharedPreferences("hub",0);
  if(p.contains("factoryLedServiceBefore")){
   int state=p.getInt("factoryLedServiceBefore",0);c.getPackageManager().setComponentEnabledSetting(FACTORY_LED,state,PackageManager.DONT_KILL_APP);
   if(state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT||state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED)c.startService(new Intent().setComponent(FACTORY_LED));
   p.edit().remove("factoryLedServiceBefore").commit();
  }
  if(p.contains("systemUiServiceBefore")){
   int state=p.getInt("systemUiServiceBefore",0);c.getPackageManager().setComponentEnabledSetting(SYSTEM_UI,state,PackageManager.DONT_KILL_APP);
   if(state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT||state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED)c.startService(new Intent().setComponent(SYSTEM_UI));
   p.edit().remove("systemUiServiceBefore").commit();ledControllerStopped=false;
  }
  Object nm=service("network_management",NM);
  if(NetworkInterface.getByName("rndis0")!=null){call(nm,NM,"setInterfaceDown",new Class[]{String.class},"rndis0");call(nm,NM,"clearInterfaceAddresses",new Class[]{String.class},"rndis0");call(nm,NM,"enableIpv6",new Class[]{String.class},"rndis0");}
  Object cm=c.getSystemService(Context.CONNECTIVITY_SERVICE);
  cm.getClass().getMethod("setMobileDataEnabled",boolean.class).invoke(cm,!"0".equals(j.optString("mobile_data","1")));
  String scan=j.optString("wifi_scan_always_enabled","");
  if(scan.isEmpty())c.getContentResolver().delete(android.net.Uri.parse("content://settings/global"),"name=?",new String[]{"wifi_scan_always_enabled"});
  else Settings.Global.putString(c.getContentResolver(),"wifi_scan_always_enabled",scan);
  c.getPackageManager().setApplicationEnabledSetting(VENDOR,j.optInt("vendorState",0),0);
  c.getPackageManager().setComponentEnabledSetting(new ComponentName(c,HomeActivity.class),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
  WifiManager w=(WifiManager)c.getSystemService(Context.WIFI_SERVICE);
  w.setWifiEnabled(!"0".equals(j.optString("wifi_on","0")));
  if(j.optInt("wifiApState")==13)WifiManager.class.getMethod("setWifiApEnabled",WifiConfiguration.class,boolean.class).invoke(w,null,true);
  ledUntil=0;led(false);
  if(!j.optString("usbDefault","").isEmpty()){Object usb=c.getSystemService(Context.USB_SERVICE);usb.getClass().getMethod("setCurrentFunction",String.class,boolean.class).invoke(usb,j.getString("usbDefault"),true);}
 }
 static String readAll(File f)throws Exception {FileInputStream in=new FileInputStream(f);try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}finally{in.close();}}
 static String detail(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getClass().getSimpleName()+": "+e.getMessage();}
}
