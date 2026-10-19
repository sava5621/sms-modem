package local.modem.hub;
import android.app.*;
import android.content.*;
import android.os.*;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class HubService extends Service {
 private volatile boolean running;
 private static volatile HubService active;
 static HubService current()throws IOException{HubService h=active;if(h==null||!h.running)throw new IOException("Service is starting");return h;}
 static void pulseFromPage(){final HubService a=active;if(a!=null)a.main.post(new Runnable(){public void run(){if(!a.running)return;try{a.control.led(true);int duration=a.control.ledPulseMillis();if(duration==0)return;if(a.ledWake==null){a.ledWake=((PowerManager)a.getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ModemHub:LED");a.ledWake.setReferenceCounted(false);}a.ledWake.acquire(duration+1000);a.main.removeCallbacks(a.ledOff);a.main.postDelayed(a.ledOff,duration);}catch(Exception e){a.control.error="LED: "+DeviceControl.detail(e);}}});}
 final Handler main=new Handler();
 DeviceControl control;
 private PowerManager.WakeLock ledWake;
 Calls calls;
 WaveRecordings wave;
 Greeting greeting;
 private NetworkStatus network;
 private ThreadPoolExecutor pool;
 private ScheduledExecutorService scheduler;
 private final List<ServerSocket> sockets=Collections.synchronizedList(new ArrayList<ServerSocket>());
 public IBinder onBind(Intent i){return null;}
 public int onStartCommand(Intent i,int flags,int id){if(!getSharedPreferences("hub",0).getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}return START_STICKY;}
 public void onCreate(){
  super.onCreate();if(!getSharedPreferences("hub",0).getBoolean("enabled",false)){stopSelf();return;}
  control=new DeviceControl(this);greeting=new Greeting(this);wave=new WaveRecordings(this);calls=new Calls(this,greeting);calls.start();network=new NetworkStatus(this);network.start();running=true;active=this;startService(new Intent(this,LegacySmsService.class));
  Notification notification=new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Modem Hub").setContentText("СМС и входящие звонки").build();startForeground(91,notification);
  pool=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(8));
  scheduler=Executors.newSingleThreadScheduledExecutor();scheduler.scheduleWithFixedDelay(new Runnable(){public void run(){if(running){control.networkRegistered=network.registered();control.configure();try{Events.broadcast(new JSONObject().put("type","state").put("status",publicStatus()).toString());}catch(Exception ignored){}}}},0,5,TimeUnit.SECONDS);
  new Thread(new Runnable(){public void run(){SmsStore.get(HubService.this).importSystem();serve(DeviceControl.IP);}},"modem-http").start();
  new Thread(new Runnable(){public void run(){serve("127.0.0.1");}},"modem-recovery-http").start();
 }
 private void serve(String ip){
  while(running){ServerSocket server=null;try{server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName(ip),8088),8);sockets.add(server);Log.i("ModemHub","HTTP ready: "+ip+":8088");
   while(running){final Socket socket=server.accept();try{pool.execute(new Runnable(){public void run(){handle(socket);}});}catch(RuntimeException e){socket.close();}}
  }catch(Exception e){if(running)Log.w("ModemHub","Waiting for "+ip+": "+e.getClass().getSimpleName());}finally{if(server!=null){sockets.remove(server);try{server.close();}catch(Exception ignored){}}}
  if(running)try{Thread.sleep(3000);}catch(InterruptedException e){return;}
  }
 }
 private void handle(Socket socket){WebEndpoint.handle(this,socket,true);}
 final Runnable ledOff=new Runnable(){public void run(){try{control.ledUntil=0;control.led(false);}catch(Exception e){control.error="LED: "+DeviceControl.detail(e);}finally{if(ledWake!=null&&ledWake.isHeld())ledWake.release();}}};
 private String phoneNumber(TelephonyManager phone){if(phone.getSimState()!=TelephonyManager.SIM_STATE_READY)return "";try{String value=phone.getLine1Number();return value==null?"":value.trim();}catch(SecurityException e){return "";}}
 JSONObject publicStatus()throws Exception{JSONObject j=new JSONObject();TelephonyManager p=(TelephonyManager)getSystemService(TELEPHONY_SERVICE);return j.put("simState",p.getSimState()).put("operator",p.getNetworkOperatorName()).put("phoneNumber",phoneNumber(p)).put("network",network.snapshot()).put("counts",Archive.counts(this,calls)).put("storage",storage()).put("callState",calls.callState()).put("greeting",greeting.info()).put("revision",Events.revision()).put("error",control.error).put("version","1.8-DT");}
 JSONObject status()throws Exception {
  JSONObject j=new JSONObject();TelephonyManager p=(TelephonyManager)getSystemService(TELEPHONY_SERVICE);
  j.put("simState",p.getSimState());j.put("operator",p.getNetworkOperatorName());j.put("phoneNumber",phoneNumber(p));j.put("network",network.snapshot());j.put("ip",DeviceControl.IP);j.put("autoAnswer",calls.autoAnswer());j.put("callState",p.getCallState());j.put("error",control.error);j.put("callError",calls.error);j.put("freeBytes",getFilesDir().getUsableSpace());j.put("uptimeMs",SystemClock.elapsedRealtime());j.put("android",Build.VERSION.RELEASE);j.put("board",Build.BOARD);j.put("version","1.8-DT");j.put("counts",Archive.counts(this,calls)).put("storage",storage()).put("callState",calls.callState()).put("greeting",greeting.info()).put("revision",Events.revision());
  try{j.put("device",control.snapshot());}catch(Exception e){j.put("deviceError",DeviceControl.detail(e));}return j;
 }
 JSONObject storage()throws Exception{StatFs fs=new StatFs(getFilesDir().getAbsolutePath());long total=fs.getTotalBytes(),free=fs.getAvailableBytes();return new JSONObject().put("totalBytes",total).put("freeBytes",free).put("usedBytes",total-free);}
 JSONObject clearArchive()throws Exception{
  java.util.concurrent.FutureTask<JSONObject> task=new java.util.concurrent.FutureTask<JSONObject>(new java.util.concurrent.Callable<JSONObject>(){public JSONObject call()throws Exception{
   if(calls.callState()!=TelephonyManager.CALL_STATE_IDLE)throw new UiApi.Conflict("Дождитесь завершения звонка");
   wave.clearCache();int removedCalls=calls.clearArchive(),sms=SmsStore.get(HubService.this).clearArchive();Events.changed("clear");return new JSONObject().put("deletedSms",sms).put("deletedCalls",removedCalls).put("storage",storage());
  }});main.post(task);try{return task.get(15,TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
 }
 static int number(Uri uri,String key,int def,int max){String v=uri.getQueryParameter(key);if(v==null)return def;int n=Integer.parseInt(v);if(n<0||n>max||key.equals("limit")&&n==0)throw new IllegalArgumentException();return n;}
 static byte[] bytes(String value)throws Exception{return value.getBytes("UTF-8");}
 static void json(Socket socket,JSONObject value,boolean head)throws Exception{reply(socket,200,"application/json; charset=utf-8",bytes(value.toString()),head,"");}
 static void fail(Socket s,int code){try{reply(s,code,"application/json",bytes(code==400?"{\"error\":\"Invalid request\"}":"{\"error\":\"Service error\"}"),false,"");}catch(Exception ignored){}}
 static void header(OutputStream out,int code,String type,long size,String extra)throws Exception{
  String reason=code==200?"OK":code==400?"Bad Request":code==401?"Unauthorized":code==403?"Forbidden":code==404?"Not Found":code==405?"Method Not Allowed":"Internal Server Error";
  String h="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+size+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n"+extra+"\r\n";
  out.write(h.getBytes("ISO-8859-1"));
 }
 static void reply(Socket socket,int code,String type,byte[] body,boolean head,String extra)throws Exception{OutputStream out=socket.getOutputStream();header(out,code,type,body.length,extra);if(!head)out.write(body);out.flush();}
 public void onDestroy(){if(active==this)active=null;stopService(new Intent(this,LegacySmsService.class));running=false;main.removeCallbacksAndMessages(null);if(scheduler!=null)scheduler.shutdownNow();synchronized(sockets){for(ServerSocket s:sockets)try{s.close();}catch(Exception ignored){}}
  Events.closeAll();if(wave!=null)wave.close();if(pool!=null)pool.shutdownNow();if(network!=null)network.stop();if(calls!=null)calls.closeService();if(control!=null)try{control.ledUntil=0;control.led(false);}catch(Exception ignored){}if(ledWake!=null&&ledWake.isHeld())ledWake.release();stopForeground(true);super.onDestroy();}
}
