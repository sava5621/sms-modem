package local.modem.hub;
import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Derived browser audio cache. Original recordings are never changed. */
final class WaveRecordings {
 private final Context context;
 private final File directory;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<String,Job> jobs=new HashMap<String,Job>();
 private volatile int generation;
 private static final long MAX_PCM=64L*1024*1024;
 private static final class Job {volatile String state="preparing",error="";volatile int progress;}
 WaveRecordings(Context c){context=c;directory=new File(c.getCacheDir(),"recording-wav");}
 private String signature(File f){return f.length()+":"+f.lastModified();}
 private File output(String name){return new File(directory,name.replace(".3gp",".wav"));}
 private File stamp(String name){return new File(directory,name.replace(".3gp",".meta"));}
 synchronized File readyFile(String name,File source){
  File wav=output(name);try{if(wav.isFile()&&wav.length()>44&&signature(source).equals(DeviceControl.readAll(stamp(name)))){wav.setLastModified(System.currentTimeMillis());return wav;}}catch(Exception ignored){}return null;
 }
 synchronized JSONObject status(String name,File source)throws Exception {
  JSONObject j=new JSONObject();File ready=readyFile(name,source);
  if(ready!=null)return j.put("state","ready").put("progress",100).put("url","/recordings/"+ready.getName()).put("bytes",ready.length());
  Job job=jobs.get(name);if(job==null)return j.put("state","not_prepared").put("progress",0);
  return j.put("state",job.state).put("progress",job.progress).put("error",job.error);
 }
 synchronized JSONObject request(final String name,final File source,boolean retry)throws Exception {
  if(readyFile(name,source)!=null)return status(name,source);
  Job old=jobs.get(name);if(old!=null&&(old.state.equals("preparing")||old.state.equals("error")&&!retry))return status(name,source);
  for(Iterator<Map.Entry<String,Job>> it=jobs.entrySet().iterator();it.hasNext();)if(!it.next().getValue().state.equals("preparing"))it.remove();
  if(jobs.size()>=8)throw new IOException("Подготовка других записей ещё не закончена");
  final Job job=new Job();jobs.put(name,job);
  final int token=generation;worker.submit(new Runnable(){public void run(){decode(name,source,job,token);}});
  return status(name,source);
 }
 private void trimCache()throws Exception {
  if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать кэш аудио");
  File[] files=directory.listFiles(new FilenameFilter(){public boolean accept(File d,String n){return n.matches("call-[1-9][0-9]{0,15}\\.wav");}});
  if(files==null)return;
  Arrays.sort(files,new Comparator<File>(){public int compare(File a,File b){return a.lastModified()<b.lastModified()?-1:a.lastModified()==b.lastModified()?0:1;}});
  long total=0;for(File f:files)total+=f.length();
  for(File f:files){if(total<=128L*1024*1024&&directory.getUsableSpace()>=MAX_PCM)break;long size=f.length();if(f.delete()){total-=size;new File(directory,f.getName().replace(".wav",".meta")).delete();}}
  if(directory.getUsableSpace()<MAX_PCM)throw new IOException("Недостаточно места для подготовки аудио");
 }
 private void decode(String name,File source,Job job,int token){
  MediaExtractor extractor=null;MediaCodec codec=null;RandomAccessFile out=null;PowerManager.WakeLock wake=null;
  File partial=new File(directory,name.replace(".3gp",".part"));
  try{
   trimCache();wake=((PowerManager)context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ModemHub:AudioDecode");wake.acquire(10*60*1000L);
   String originalSignature=signature(source);
   extractor=new MediaExtractor();extractor.setDataSource(source.getAbsolutePath());
   MediaFormat format=null;int track=-1;
   for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;format=f;break;}}
   if(track<0)throw new IOException("В записи нет аудиодорожки");
   extractor.selectTrack(track);int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
   long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
   codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
   ByteBuffer[] inputs=codec.getInputBuffers(),outputs=codec.getOutputBuffers();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   out=new RandomAccessFile(partial,"rw");out.setLength(0);out.write(new byte[44]);
   boolean inputEnd=false,outputEnd=false;long written=0,lastProgress=SystemClock.elapsedRealtime();byte[] chunk=new byte[32768];
   while(!outputEnd){
    if(Thread.currentThread().isInterrupted()||token!=generation)throw new InterruptedIOException("Подготовка прервана");
    if(!inputEnd){int i=codec.dequeueInputBuffer(10000);if(i>=0){ByteBuffer b=inputs[i];b.clear();int n=extractor.readSampleData(b,0);if(n<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}else{codec.queueInputBuffer(i,0,n,extractor.getSampleTime(),0);extractor.advance();}}}
    int i=codec.dequeueOutputBuffer(info,10000);
    if(i==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)outputs=codec.getOutputBuffers();
    else if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}
    else if(i>=0){
     if(info.size>0){if(written+info.size>MAX_PCM)throw new IOException("Аудио превышает допустимый размер");ByteBuffer b=outputs[i].duplicate();b.position(info.offset);b.limit(info.offset+info.size);while(b.hasRemaining()){int n=Math.min(chunk.length,b.remaining());b.get(chunk,0,n);out.write(chunk,0,n);written+=n;}lastProgress=SystemClock.elapsedRealtime();if(duration>0)job.progress=(int)Math.min(99,Math.max(0,info.presentationTimeUs*100/duration));}
     outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(i,false);
    }
    if(SystemClock.elapsedRealtime()-lastProgress>30000)throw new IOException("Декодер аудио не отвечает");
   }
   if(written==0||rate<8000||rate>48000||channels<1||channels>2)throw new IOException("Неподдерживаемая аудиодорожка");
   if(!originalSignature.equals(signature(source)))throw new IOException("Запись изменилась во время подготовки");
   out.seek(0);out.writeBytes("RIFF");le32(out,(int)written+36);out.writeBytes("WAVEfmt ");le32(out,16);le16(out,1);le16(out,channels);le32(out,rate);le32(out,rate*channels*2);le16(out,channels*2);le16(out,16);out.writeBytes("data");le32(out,(int)written);out.getFD().sync();out.close();out=null;
   synchronized(this){if(token!=generation)throw new InterruptedIOException();if(!partial.renameTo(output(name)))throw new IOException("Не удалось сохранить подготовленное аудио");
   FileOutputStream meta=new FileOutputStream(stamp(name));try{meta.write(originalSignature.getBytes("UTF-8"));meta.getFD().sync();}finally{meta.close();}
   job.progress=100;job.state="ready";}
  }catch(Exception e){job.error=e instanceof IOException?e.getMessage():"Не удалось подготовить аудио: "+e.getClass().getSimpleName();job.state="error";}
  finally{if(out!=null)try{out.close();}catch(Exception ignored){}if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}if(extractor!=null)extractor.release();if(wake!=null&&wake.isHeld())wake.release();partial.delete();}
 }
 private static void le16(RandomAccessFile f,int n)throws Exception{f.write(n&255);f.write((n>>8)&255);}
 private static void le32(RandomAccessFile f,int n)throws Exception{le16(f,n);le16(f,n>>16);}
 synchronized void clearCache()throws Exception{generation++;jobs.clear();File[] files=directory.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&f.getName().matches("call-[1-9][0-9]{0,15}\\.(wav|meta|part)")&&!f.delete())throw new IOException("Не удалось очистить аудиокэш");}
 void close(){generation++;worker.shutdownNow();}
}
