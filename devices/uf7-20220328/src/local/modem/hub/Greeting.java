package local.modem.hub;
import android.content.Context;
import android.media.AudioManager;
import java.io.*;
import org.json.JSONObject;

/** Mono PCM greeting and the UF7's dedicated modem uplink mixer route. */
final class Greeting {
 private final Context context;private final File file,callFile;private volatile Process process;private volatile int generation;
 interface Done{void finished(String error);}
 Greeting(Context c){context=c;file=new File(c.getFilesDir(),"greeting.wav");callFile=new File(c.getFilesDir(),"greeting-call.wav");}
 synchronized File file(){return file.isFile()?file:null;}
 synchronized JSONObject info()throws Exception{boolean ready=file.isFile()&&callFile.isFile();return new JSONObject().put("ready",ready).put("updatedAt",ready?file.lastModified():0).put("durationMs",ready?(file.length()-44)*1000/32000:0).put("url",ready?"/api/greeting/audio":JSONObject.NULL).put("answerDelaySeconds",5).put("messageSeconds",20);}
 synchronized void save(byte[] data)throws Exception{
  if(data.length<6444||data.length>1920044||!ascii(data,0,4).equals("RIFF")||!ascii(data,8,8).equals("WAVEfmt ")||i32(data,4)!=data.length-8||i32(data,16)!=16||i16(data,20)!=1||i16(data,22)!=1||i32(data,24)!=16000||i16(data,34)!=16||!ascii(data,36,4).equals("data")||i32(data,40)!=data.length-44||(data.length-44)%2!=0)throw new IllegalArgumentException("Нужен WAV PCM: моно, 16 кГц, 16 бит, от 0,2 до 60 секунд");
  File temporary=new File(file.getPath()+".new"),derived=new File(callFile.getPath()+".new");
  try{
   FileOutputStream out=new FileOutputStream(temporary);try{out.write(data);out.getFD().sync();}finally{out.close();}
   out=new FileOutputStream(derived);try{
    byte[] header=data.clone();int samples=(data.length-44)/2;put32(header,4,36+samples*6);put32(header,24,48000);put32(header,28,96000);put32(header,40,samples*6);out.write(header,0,44);
    byte[] block=new byte[12288];int p=0;for(int i=0;i<samples;i++){short a=(short)i16(data,44+i*2),b=(short)i16(data,44+Math.min(i+1,samples-1)*2);for(int j=0;j<3;j++){int value=a+(b-a)*j/3;block[p++]=(byte)value;block[p++]=(byte)(value>>8);if(p==block.length){out.write(block);p=0;}}}if(p>0)out.write(block,0,p);out.getFD().sync();
   }finally{out.close();}
   if(!derived.renameTo(callFile)||!temporary.renameTo(file))throw new IOException("Не удалось сохранить приветствие");Events.changed("greeting");
  }finally{temporary.delete();derived.delete();}
 }
 void play(final Done done){final int token=++generation;
  new Thread(new Runnable(){public void run(){String error="";AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);boolean enabled=false;
   try{File f;synchronized(Greeting.this){f=callFile.isFile()?callFile:null;}if(f==null){done.finished("");return;}
    if(token!=generation)return;am.setParameters("incall_music_enabled=true");enabled=true;command(new String[]{"/system/bin/tinymix","Incall_Music Audio Mixer MultiMedia2","1"},5000,token);
    command(new String[]{"/system/bin/tinyplay",f.getAbsolutePath(),"-D","0","-d","1"},70000,token);
   }catch(Exception e){if(token==generation)error="Приветствие: "+DeviceControl.detail(e);}
   finally{if(enabled){try{command(new String[]{"/system/bin/tinymix","Incall_Music Audio Mixer MultiMedia2","0"},3000,-1);}catch(Exception ignored){}am.setParameters("incall_music_enabled=false");}}
   if(token==generation)done.finished(error);
  }},"hub-greeting").start();
 }
 private void command(String[] args,long timeout,int token)throws Exception{
  if(token>=0&&token!=generation)throw new InterruptedIOException();final Process p=Runtime.getRuntime().exec(args);process=p;final ByteArrayOutputStream errors=new ByteArrayOutputStream();
  Thread drain=new Thread(new Runnable(){public void run(){try{InputStream in=p.getErrorStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)if(errors.size()<4096)errors.write(b,0,Math.min(n,4096-errors.size()));}catch(Exception ignored){}}},"greeting-stderr");drain.start();
  Thread stdout=new Thread(new Runnable(){public void run(){try{InputStream in=p.getInputStream();byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(Exception ignored){}}},"greeting-stdout");stdout.start();
  long end=android.os.SystemClock.elapsedRealtime()+timeout;try{while(true){try{int code=p.exitValue();drain.join(500);if(code!=0)throw new IOException("Аудиоканал недоступен ("+code+"): "+errors.toString("UTF-8"));return;}catch(IllegalThreadStateException active){}if(token>=0&&token!=generation)throw new InterruptedIOException();if(android.os.SystemClock.elapsedRealtime()>end)throw new IOException("Превышено время воспроизведения");Thread.sleep(30);}}finally{p.destroy();if(process==p)process=null;}
 }
 void stop(){generation++;Process p=process;if(p!=null)p.destroy();}
 private static String ascii(byte[] b,int p,int n)throws Exception{return new String(b,p,n,"US-ASCII");}
 private static int i16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
 private static int i32(byte[] b,int p){return i16(b,p)|(i16(b,p+2)<<16);}
 private static void put32(byte[] b,int p,int v){for(int i=0;i<4;i++)b[p+i]=(byte)(v>>(i*8));}
}
