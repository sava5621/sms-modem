package local.modem.hub;
import android.app.Instrumentation;import android.os.Bundle;import android.content.*;import android.database.*;import android.database.sqlite.*;import android.database.DatabaseErrorHandler;import java.io.*;import java.util.*;import java.util.zip.*;import org.json.*;
public final class UpgradeTest extends Instrumentation{
 JSONArray checks=new JSONArray();File root;SmsStore sms;Calls calls;WaveRecordings waves;
 public void onCreate(Bundle b){super.onCreate(b);start();}
 void check(boolean ok,String name)throws Exception{if(!ok)throw new Exception(name);checks.put(name);}
 static void copy(InputStream in,OutputStream out)throws Exception{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
 static void rm(File f){if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)rm(x);}f.delete();}
 class Isolated extends ContextWrapper{
  Isolated(Context b){super(b);}public Context getApplicationContext(){return this;}public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}public File getCacheDir(){File f=new File(root,"cache");f.mkdirs();return f;}
  public File getDatabasePath(String n){File d=new File(root,"databases");d.mkdirs();return new File(d,n);}public SQLiteDatabase openOrCreateDatabase(String n,int m,SQLiteDatabase.CursorFactory f){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(n),f);}public SQLiteDatabase openOrCreateDatabase(String n,int m,SQLiteDatabase.CursorFactory f,DatabaseErrorHandler e){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(n).getPath(),f,e);}
  public SharedPreferences getSharedPreferences(String n,int m){return getBaseContext().getSharedPreferences("qa-v17-"+n,m);}
 }
 static byte[] tone(){int n=16000;byte[] b=new byte[44+n*2];String[] s={"RIFF","WAVEfmt ","data"};int[] p={0,8,36};for(int k=0;k<s.length;k++)for(int j=0;j<s[k].length();j++)b[p[k]+j]=(byte)s[k].charAt(j);int[] pos={4,16,20,22,24,28,32,34,40};int[] val={36+n*2,16,1,1,16000,32000,2,16,n*2};for(int k=0;k<pos.length;k++)for(int j=0;j<(pos[k]==20||pos[k]==22||pos[k]==32||pos[k]==34?2:4);j++)b[pos[k]+j]=(byte)(val[k]>>(j*8));return b;}
 public void onStart(){Bundle result=new Bundle();try{
  if(android.os.Looper.myLooper()==null)android.os.Looper.prepare();Context target=getTargetContext();root=new File(target.getCacheDir(),"qa-v17-"+System.currentTimeMillis());root.mkdirs();
  ZipInputStream zip=new ZipInputStream(new FileInputStream(new File("/storage/emulated/0/Android/data/local.modem.hub/files/before-v17.zip")));try{ZipEntry e;while((e=zip.getNextEntry())!=null){if(!e.getName().startsWith("databases/")&&!e.getName().startsWith("files/"))continue;File f=new File(root,e.getName());if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new Exception("Zip path");f.getParentFile().mkdirs();FileOutputStream out=new FileOutputStream(f);try{copy(zip,out);}finally{out.close();}}}finally{zip.close();}
  Isolated c=new Isolated(target);java.lang.reflect.Constructor<SmsStore> constructor=SmsStore.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);sms=constructor.newInstance(c);Greeting greeting=new Greeting(c);calls=new Calls(c,greeting);
  JSONObject old=sms.listFiltered(0,40,null,"",false);check(old.getInt("total")==2,"SMS schema migration preserves original two messages");check(calls.list(0,40).getInt("total")==1,"Call schema migration preserves original recording");
  JSONObject first=old.getJSONArray("messages").getJSONObject(0);sms.add("8 (995) 189-98-40","АбВ 100% [test]? literal*",900001,900002,"qa");
  check(sms.listFiltered(0,40,null,"аБв",false).getInt("total")==1,"Cyrillic case-insensitive search");check(sms.listFiltered(0,40,null,"[test]?",false).getInt("total")==1,"Search escapes glob metacharacters");check(sms.listFiltered(0,40,null,"literal?",false).getInt("total")==0,"Question mark is a literal search character");
  check(ContactKey.of("8 (995) 189-98-40").equals(ContactKey.of("+7 995 1899840")),"Phone formatting and Russian prefix normalize to one sender");
  java.util.ArrayList<Long> ids=new java.util.ArrayList<Long>();ids.add(first.getLong("id"));check(sms.markRead(ids)==1,"Mark message as read");check(sms.listFiltered(0,40,null,"",true).getInt("total")==2,"Unread SMS filter excludes read message");
  check(calls.markListened(1)==1,"Mark call recording as listened");check(calls.listFiltered(0,40,null,"",true).getInt("total")==0,"Unheard call filter excludes listened recording");
  sms.close();sms=constructor.newInstance(c);check(sms.listFiltered(0,40,null,"",true).getInt("total")==2,"Read markers survive database reopen");
  greeting.save(tone());check(greeting.info().getBoolean("ready")&&greeting.info().getLong("durationMs")==1000,"Greeting validates and persists mono PCM");
  waves=new WaveRecordings(c);File source=calls.recording("call-1.3gp");waves.request("call-1.3gp",source,true);waves.clearCache();Thread.sleep(800);check(waves.readyFile("call-1.3gp",source)==null,"Clearing audio cache cancels a queued decode");
  if(calls.callState()!=0)throw new Exception("A real call is active; isolated clear test needs idle telephony");
  check(calls.clearArchive()==1,"Clear call archive removes isolated call row");check(!source.exists(),"Clear archive removes isolated audio file");check(sms.clearArchive()==3,"Clear SMS archive removes all isolated messages");sms.add(first.getString("sender"),first.getString("body"),first.getLong("sentAt"),first.getLong("receivedAt"),"system");check(sms.list(0,40).getInt("total")==0,"Cleared system SMS cannot reappear through import");check(greeting.file()!=null,"Clear archive preserves greeting");
  Events.changed("qa-verification");result.putString("checks",checks.toString());finish(-1,result);
 }catch(Exception e){result.putString("checks",checks.toString());result.putString("error",android.util.Log.getStackTraceString(e));finish(0,result);}finally{if(waves!=null)waves.close();if(sms!=null)sms.close();if(calls!=null)calls.close();if(root!=null&&root.getName().startsWith("qa-v17-"))rm(root);}}
}
