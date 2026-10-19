package local.modem.hub;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.media.MediaRecorder;
import android.os.*;
import android.telephony.*;
import android.util.Log;
import java.io.*;
import org.json.*;

public final class Calls extends SQLiteOpenHelper {
 private final Context c;
 private final Handler handler=new Handler();
 private final TelephonyManager phone;
 private final Greeting greeting;
 private MediaRecorder recorder;
 private File recording;
 private long callId,startedElapsed;
 private boolean incoming;
 private int state=TelephonyManager.CALL_STATE_IDLE;
 volatile String error="";
 private PowerManager.WakeLock wake;
 Calls(Context context,Greeting greeting){super(context,"calls.db",null,2);c=context;this.greeting=greeting;phone=(TelephonyManager)c.getSystemService(Context.TELEPHONY_SERVICE);getWritableDatabase().execSQL("UPDATE calls SET status='interrupted', error='Сервис был перезапущен до завершения звонка' WHERE status IN ('ringing','answered','recording','greeting')");}
 public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE calls (_id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, started INTEGER, answered INTEGER, ended INTEGER, duration INTEGER DEFAULT 0, status TEXT, file TEXT, error TEXT, listened INTEGER NOT NULL DEFAULT 0, contact_key TEXT NOT NULL DEFAULT '', search_text TEXT NOT NULL DEFAULT '', greeting_ended INTEGER)");}
 public void onUpgrade(SQLiteDatabase db,int a,int b){if(a<2){db.execSQL("ALTER TABLE calls ADD COLUMN listened INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE calls ADD COLUMN contact_key TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE calls ADD COLUMN search_text TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE calls ADD COLUMN greeting_ended INTEGER");Cursor q=db.query("calls",new String[]{"_id","number"},null,null,null,null,null);try{while(q.moveToNext()){ContentValues v=new ContentValues();v.put("contact_key",ContactKey.of(q.getString(1)));v.put("search_text",ContactKey.search(q.getString(1)));db.update("calls",v,"_id=?",new String[]{q.getString(0)});}}finally{q.close();}}}
 void start(){phone.listen(listener,PhoneStateListener.LISTEN_CALL_STATE);}
 void closeService(){phone.listen(listener,PhoneStateListener.LISTEN_NONE);handler.removeCallbacksAndMessages(null);finishCall("service_stopped");close();}
 boolean autoAnswer(){return c.getSharedPreferences("hub",0).getBoolean("autoAnswer",true);}
 void setAutoAnswer(boolean value){c.getSharedPreferences("hub",0).edit().putBoolean("autoAnswer",value).commit();}
 final PhoneStateListener listener=new PhoneStateListener(){public void onCallStateChanged(int next,String number){onState(next,number);}};
 private void onState(int next,String number){
  int prior=state;state=next;
  try{
   if(next==TelephonyManager.CALL_STATE_RINGING && prior!=next && callId==0){
    incoming=true;ContentValues row=new ContentValues();row.put("number",number==null?"":number);row.put("started",System.currentTimeMillis());row.put("status","ringing");row.put("contact_key",ContactKey.of(number));row.put("search_text",ContactKey.search(number));callId=getWritableDatabase().insertOrThrow("calls",null,row);
    Events.changed("calls");
    if(autoAnswer()){wake=((PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ModemHub:Answer");wake.acquire(100000);handler.postDelayed(answer,5000);}
   }else if(next==TelephonyManager.CALL_STATE_OFFHOOK && prior!=next && incoming){
    handler.removeCallbacks(answer);startedElapsed=SystemClock.elapsedRealtime();update("status","answered");ContentValues v=new ContentValues();v.put("answered",System.currentTimeMillis());getWritableDatabase().update("calls",v,"_id=?",new String[]{""+callId});
    final long id=callId;update("status","greeting");
    greeting.play(new Greeting.Done(){public void finished(final String greetingError){handler.post(new Runnable(){public void run(){if(callId!=id||state!=TelephonyManager.CALL_STATE_OFFHOOK)return;if(!greetingError.isEmpty()){error=greetingError;update("error",error);}ContentValues v=new ContentValues();v.put("greeting_ended",System.currentTimeMillis());getWritableDatabase().update("calls",v,"_id=?",new String[]{""+callId});startRecording();handler.postDelayed(hangup,20000);Events.changed("calls");}});}});
   }else if(next==TelephonyManager.CALL_STATE_IDLE && prior!=next){handler.removeCallbacks(answer);handler.removeCallbacks(hangup);finishCall(startedElapsed>0?"completed":"missed");}
  }catch(Exception e){error=DeviceControl.detail(e);Log.e("ModemHub","Call handler failed",e);}
 }
 final Runnable answer=new Runnable(){public void run(){
  if(state!=TelephonyManager.CALL_STATE_RINGING||!autoAnswer()||!incoming)return;
  try{Object p=DeviceControl.service("phone","com.android.internal.telephony.ITelephony");DeviceControl.call(p,"com.android.internal.telephony.ITelephony","answerRingingCall",new Class[]{});}
  catch(Exception e){error="Автоответ: "+DeviceControl.detail(e);update("error",error);update("status","answer_failed");}
 }};
 private void startRecording(){
  try{
   File dir=new File(c.getFilesDir(),"recordings");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create recording directory");
   if(dir.getUsableSpace()<128L*1024*1024)throw new IOException("Менее 128 МиБ свободного места");
   recording=new File(dir,"call-"+callId+".3gp");
   recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);recorder.setAudioSamplingRate(8000);recorder.setAudioEncodingBitRate(12200);recorder.setOutputFile(recording.getAbsolutePath());recorder.setMaxDuration(60*60*1000);recorder.setMaxFileSize(64L*1024*1024);
   recorder.setOnInfoListener(new MediaRecorder.OnInfoListener(){public void onInfo(MediaRecorder r,int what,int extra){if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED){error="Запись остановлена по лимиту (60 мин / 64 МиБ)";update("error",error);stopRecording();}}});
   recorder.setOnErrorListener(new MediaRecorder.OnErrorListener(){public void onError(MediaRecorder r,int what,int extra){error="Ошибка записи "+what+"/"+extra;update("error",error);stopRecording();}});
   recorder.prepare();recorder.start();update("status","recording");
   if(wake!=null&&wake.isHeld())wake.release();wake=((PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ModemHub:Call");wake.acquire(90000);
  }catch(Exception e){error="Аудиозапись не началась: "+DeviceControl.detail(e);update("error",error);update("status","recording_failed");if(recorder!=null){try{recorder.release();}catch(Exception ignored){}recorder=null;}if(recording!=null)recording.delete();recording=null;}
 }
 private void update(String name,String value){if(callId==0)return;ContentValues v=new ContentValues();v.put(name,value);getWritableDatabase().update("calls",v,"_id=?",new String[]{""+callId});Events.changed("calls");}
 private void stopRecording(){
  if(recorder!=null){try{recorder.stop();if(recording!=null && recording.length()>0)update("file",recording.getName());}catch(Exception e){error="Не удалось завершить запись: "+DeviceControl.detail(e);update("error",error);if(recording!=null)recording.delete();}finally{recorder.release();recorder=null;recording=null;}}
  if(wake!=null){if(wake.isHeld())wake.release();wake=null;}
 }
 private void finishCall(String status){greeting.stop();handler.removeCallbacks(hangup);if(callId==0)return;stopRecording();ContentValues v=new ContentValues();v.put("status",status);v.put("ended",System.currentTimeMillis());v.put("duration",startedElapsed==0?0:(SystemClock.elapsedRealtime()-startedElapsed)/1000);getWritableDatabase().update("calls",v,"_id=?",new String[]{""+callId});callId=0;startedElapsed=0;incoming=false;Events.changed("calls");}
 JSONObject list(int offset,int limit)throws Exception {return listFiltered(offset,limit,null,"",false);}
 JSONObject listFiltered(int offset,int limit,String contact,String search,boolean unheard)throws Exception {
  String where="search_text GLOB ?"+(contact==null?"":" AND contact_key=?")+(unheard?" AND file IS NOT NULL AND listened=0":"");String[] args=contact==null?new String[]{ContactKey.pattern(search)}:new String[]{ContactKey.pattern(search),contact};
  JSONArray rows=new JSONArray();Cursor q=getReadableDatabase().query("calls",null,where,args,null,null,"started DESC,_id DESC",offset+","+limit);
  try{while(q.moveToNext()){JSONObject j=new JSONObject();for(String key:new String[]{"_id","started","answered","ended","duration","greeting_ended"})j.put(key,q.getLong(q.getColumnIndexOrThrow(key)));for(String key:new String[]{"number","status","file","error"})j.put(key,q.getString(q.getColumnIndexOrThrow(key)));j.put("listened",q.getInt(q.getColumnIndexOrThrow("listened"))!=0);j.put("contactKey",q.getString(q.getColumnIndexOrThrow("contact_key")));j.put("kind","call");rows.put(j);}}finally{q.close();}
  JSONObject out=new JSONObject();out.put("total",Archive.count(getReadableDatabase(),"SELECT COUNT(*) FROM calls WHERE "+where,args));out.put("hasMore",offset+rows.length()<out.getInt("total"));out.put("calls",rows);out.put("autoAnswer",autoAnswer());out.put("state",state);out.put("error",error);return out;
 }
 int callState(){return phone.getCallState();}
 final Runnable hangup=new Runnable(){public void run(){if(state!=TelephonyManager.CALL_STATE_OFFHOOK||!incoming)return;try{Object p=DeviceControl.service("phone","com.android.internal.telephony.ITelephony");boolean ended=(Boolean)DeviceControl.call(p,"com.android.internal.telephony.ITelephony","endCall",new Class[]{});if(!ended)throw new IOException("Телефония не завершила вызов");}catch(Exception e){error="Завершение звонка: "+DeviceControl.detail(e);update("error",error);}}};
 synchronized int markListened(long id){ContentValues v=new ContentValues();v.put("listened",1);int n=getWritableDatabase().update("calls",v,"_id=? AND file IS NOT NULL AND listened=0",new String[]{""+id});if(n>0)Events.changed("listened");return n;}
 synchronized JSONArray contactStats(String search)throws Exception{Cursor q=getReadableDatabase().rawQuery("SELECT contact_key,MAX(number),COUNT(*),SUM(CASE WHEN file IS NOT NULL AND listened=0 THEN 1 ELSE 0 END),MAX(started),MAX(CASE WHEN search_text GLOB ? THEN 1 ELSE 0 END) FROM calls GROUP BY contact_key",new String[]{ContactKey.pattern(search)});JSONArray a=new JSONArray();try{while(q.moveToNext())a.put(new JSONObject().put("contactKey",q.getString(0)).put("sender",q.getString(1)).put("callCount",q.getInt(2)).put("unheardCount",q.getInt(3)).put("receivedAt",q.getLong(4)).put("matches",q.getInt(5)!=0));}finally{q.close();}return a;}
 synchronized int clearArchive()throws Exception{if(callState()!=TelephonyManager.CALL_STATE_IDLE)throw new UiApi.Conflict("Дождитесь завершения звонка");File dir=new File(c.getFilesDir(),"recordings");File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&f.getName().matches("call-[1-9][0-9]{0,15}\\.3gp")&&!f.delete())throw new IOException("Не удалось удалить запись "+f.getName());return getWritableDatabase().delete("calls",null,null);}
 File recording(String name){if(!name.matches("call-[1-9][0-9]{0,15}\\.3gp"))return null;Cursor q=getReadableDatabase().query("calls",new String[]{"file"},"file=?",new String[]{name},null,null,null);try{if(!q.moveToFirst())return null;}finally{q.close();}File f=new File(new File(c.getFilesDir(),"recordings"),name);return f.isFile()?f:null;}
}
