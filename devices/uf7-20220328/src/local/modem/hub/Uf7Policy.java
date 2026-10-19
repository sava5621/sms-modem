package local.modem.hub;
import android.content.*;import android.os.*;import android.provider.Settings;import java.io.*;import java.net.*;import org.json.*;

/** UF7 keeps mobile data enabled for radio registration; netd blocks IP on every non-USB interface. */
final class Uf7Policy {
 private final Context context;private final File before;private long appliedAt,adbRestartAt;private int adbRestarts;
 private static final String[] GLOBAL={"data_roaming","tether_supported"};
 private static final String[] PROPS={"persist.ufi.nosignal.reboot","persist.adb.tcp.port","service.adb.tcp.port"};
 Uf7Policy(Context c){context=c;before=new File(c.getFilesDir(),"policy-before.json");}
 private static void property(String key,String value)throws Exception{if(!value.equals(DeviceControl.property(key)))Class.forName("android.os.SystemProperties").getMethod("set",String.class,String.class).invoke(null,key,value);if(!value.equals(DeviceControl.property(key)))throw new IOException("Property requires explicit USB initialization: "+key);}
 private void backup(Object nm)throws Exception{
  if(before.isFile())return;JSONObject globals=new JSONObject(),props=new JSONObject();
  for(String key:GLOBAL){String value=Settings.Global.getString(context.getContentResolver(),key);globals.put(key,value==null?JSONObject.NULL:value);}
  for(String key:PROPS)props.put(key,DeviceControl.property(key));
  JSONObject value=new JSONObject().put("globals",globals).put("properties",props).put("firewall",DeviceControl.call(nm,DeviceControl.NM,"isFirewallEnabled",new Class[]{}));
  File temporary=new File(before.getPath()+".new");FileOutputStream out=new FileOutputStream(temporary);try{out.write(value.toString(2).getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}if(!temporary.renameTo(before))throw new IOException("Cannot save network policy backup");
 }
 synchronized void configure()throws Exception{
  if(!context.getSharedPreferences("hub",0).getBoolean("securityEnabled",true))return;
  Object nm=DeviceControl.service("network_management",DeviceControl.NM);backup(nm);
  for(String key:GLOBAL)if(Settings.Global.getInt(context.getContentResolver(),key,-1)!=0&&!Settings.Global.putInt(context.getContentResolver(),key,0))throw new IOException("Cannot set "+key);
  int previousPort=10242;try{int p=Integer.parseInt(DeviceControl.property(PROPS[2]));if(p>0&&p<65536)previousPort=p;}catch(NumberFormatException ignored){}
  property(PROPS[0],"no");property(PROPS[1],"-1");property(PROPS[2],"-1");
  long now=SystemClock.elapsedRealtime();
  if(appliedAt==0||now-appliedAt>=60000||!(Boolean)DeviceControl.call(nm,DeviceControl.NM,"isFirewallEnabled",new Class[]{})){
   DeviceControl.call(nm,DeviceControl.NM,"setFirewallEnabled",new Class[]{boolean.class},true);
   for(String iface:new String[]{"lo","rndis0"})DeviceControl.call(nm,DeviceControl.NM,"setFirewallInterfaceRule",new Class[]{String.class,boolean.class},iface,true);
   if(!"rndis0".equals(DeviceControl.lanInterface()))DeviceControl.call(nm,DeviceControl.NM,"setFirewallInterfaceRule",new Class[]{String.class,boolean.class},DeviceControl.lanInterface(),true);
   appliedAt=now;
  }
  // UF7 init starts adbd listening even with persist.adb.tcp.port=-1. Restart
  // only adbd after clearing the runtime port; USB functions and RIL stay up.
  if((listening(10242)||previousPort!=10242&&listening(previousPort))&&(adbRestartAt==0||now-adbRestartAt>=15000)){
   Class.forName("android.os.SystemProperties").getMethod("set",String.class,String.class).invoke(null,"ctl.restart","adbd");adbRestartAt=now;adbRestarts++;
  }
 }
 private static boolean listening(int port){Socket socket=new Socket();try{socket.connect(new InetSocketAddress("127.0.0.1",port),200);return true;}catch(IOException e){return false;}finally{try{socket.close();}catch(IOException ignored){}}}
 synchronized JSONObject snapshot()throws Exception{
  Object nm=DeviceControl.service("network_management",DeviceControl.NM);
  return new JSONObject().put("enabled",context.getSharedPreferences("hub",0).getBoolean("securityEnabled",true)).put("firewallEnabled",DeviceControl.call(nm,DeviceControl.NM,"isFirewallEnabled",new Class[]{})).put("dataRoaming",Settings.Global.getInt(context.getContentResolver(),GLOBAL[0],-1)).put("tetherSupported",Settings.Global.getInt(context.getContentResolver(),GLOBAL[1],-1)).put("rebootWatchdog",DeviceControl.property(PROPS[0])).put("persistentAdbTcpPort",DeviceControl.property(PROPS[1])).put("adbTcpPort",DeviceControl.property(PROPS[2])).put("networkAdbListening",listening(10242)).put("adbRestarts",adbRestarts).put("mobileDataKeptForRegistration",true).put("lastAppliedElapsedMs",appliedAt);
 }
 synchronized void restore()throws Exception{
  if(!before.isFile())return;JSONObject b=new JSONObject(DeviceControl.readAll(before));
  if(!context.getSharedPreferences("hub",0).edit().putBoolean("securityEnabled",false).commit())throw new IOException("Cannot stop network policy");
  Object nm=DeviceControl.service("network_management",DeviceControl.NM);DeviceControl.call(nm,DeviceControl.NM,"setFirewallEnabled",new Class[]{boolean.class},b.getBoolean("firewall"));
  JSONObject globals=b.getJSONObject("globals"),props=b.getJSONObject("properties");
  for(String key:GLOBAL)if(globals.isNull(key))context.getContentResolver().delete(android.net.Uri.parse("content://settings/global"),"name=?",new String[]{key});else Settings.Global.putString(context.getContentResolver(),key,globals.getString(key));
  for(String key:PROPS)property(key,props.getString(key));
 }
}
