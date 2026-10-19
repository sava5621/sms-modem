package local.modem.hub;
import android.net.Uri;
import java.io.*;
import java.net.Socket;
import java.util.Iterator;
import org.json.JSONObject;

/** The same management permissions on the SMS page and the control panel. */
final class UiApi {
 static final class Conflict extends Exception{Conflict(String message){super(message);}}
 static boolean handle(Socket socket,String method,Uri uri,String auth,String range,String action,String contentType,String transfer,int length,InputStream input,boolean fullStatus)throws Exception {
  String path=uri.getPath();
  if(!path.startsWith("/api/")&&!path.startsWith("/recordings/"))return false;
  boolean head=method.equals("HEAD"),read=method.equals("GET")||head;
  HubService hub=HubService.current();
  if(path.equals("/api/greeting")&&method.equals("POST")){
   if(!action.equals("save-greeting")||!contentType.split(";",2)[0].trim().equals("audio/wav")||length<44||length>1920044)throw new IllegalArgumentException();
   if(hub.calls.callState()!=0)throw new Conflict("Дождитесь завершения звонка");byte[] data=new byte[length];int p=0;while(p<data.length){int n=input.read(data,p,data.length-p);if(n<0)throw new EOFException();p+=n;}hub.greeting.save(data);json(socket,200,hub.greeting.info(),false);return true;
  }
  if(path.equals("/api/archive/clear")&&method.equals("POST")){
   JSONObject b=body(action,"clear-archive",contentType,transfer,length,input);if(b.length()!=2||!"archive".equals(b.optString("scope"))||!"clear-all".equals(b.optString("confirm")))throw new IllegalArgumentException();json(socket,200,hub.clearArchive(),false);return true;
  }
  if((path.equals("/api/sms/read")||path.equals("/api/sms/delete"))&&method.equals("POST")){
   boolean mark=path.endsWith("/read");JSONObject b=body(action,mark?"mark-read":"delete-sms",contentType,transfer,length,input);if(b.length()!=1)throw new IllegalArgumentException();org.json.JSONArray ids=b.getJSONArray("ids");if(ids.length()<1||ids.length()>500)throw new IllegalArgumentException();java.util.ArrayList<Long> list=new java.util.ArrayList<Long>();for(int i=0;i<ids.length();i++){Object v=ids.get(i);if(!(v instanceof Integer)&&!(v instanceof Long)||((Number)v).longValue()<=0)throw new IllegalArgumentException();list.add(((Number)v).longValue());}int n=mark?SmsStore.get(hub).markRead(list):SmsStore.get(hub).delete(list);json(socket,200,new JSONObject().put(mark?"marked":"deleted",n),false);return true;
  }
  if(path.equals("/api/calls/listened")&&method.equals("POST")){
   JSONObject b=body(action,"mark-listened",contentType,transfer,length,input);Object id=b.get("id");if(b.length()!=1||!(id instanceof Integer)&&!(id instanceof Long)||((Number)id).longValue()<=0)throw new IllegalArgumentException();json(socket,200,new JSONObject().put("marked",hub.calls.markListened(((Number)id).longValue())),false);return true;
  }
  if(path.equals("/api/settings")&&method.equals("POST")){
   JSONObject body=body(action,"configure",contentType,transfer,length,input);
   if(body.length()==1&&body.has("autoAnswer")&&body.get("autoAnswer") instanceof Boolean)hub.calls.setAutoAnswer(body.getBoolean("autoAnswer"));
   else throw new IllegalArgumentException("Invalid settings");
   json(socket,200,settings(hub),false);return true;
  }
  if(path.equals("/api/recordings/prepare")&&method.equals("POST")){
   JSONObject body=body(action,"prepare-recording",contentType,transfer,length,input);
   for(Iterator<String> it=body.keys();it.hasNext();){String k=it.next();if(!k.equals("file")&&!k.equals("retry"))throw new IllegalArgumentException();}
   if(!(body.get("file") instanceof String)||body.has("retry")&&!(body.get("retry") instanceof Boolean))throw new IllegalArgumentException();
   String name=body.getString("file");File source=hub.calls.recording(name);if(source==null){json(socket,404,new JSONObject().put("error","Запись не найдена"),false);return true;}
   JSONObject state=hub.wave.request(name,source,body.optBoolean("retry",false));json(socket,state.getString("state").equals("preparing")?202:200,state,false);return true;
  }
  if(!read){json(socket,405,new JSONObject().put("error","Method not allowed"),false);return true;}
  if(length>0||!transfer.isEmpty())throw new IllegalArgumentException();
  if(path.equals("/api/status"))json(socket,200,fullStatus?hub.status():hub.publicStatus(),head);
  else if(path.equals("/api/settings"))json(socket,200,settings(hub),head);
  else if(path.equals("/api/sms"))json(socket,200,SmsStore.get(hub).listFiltered(HubService.number(uri,"offset",0,1000000),HubService.number(uri,"limit",40,200),uri.getQueryParameter("contact"),search(uri),unread(uri)),head);
  else if(path.equals("/api/calls"))json(socket,200,hub.calls.listFiltered(HubService.number(uri,"offset",0,1000000),HubService.number(uri,"limit",40,200),uri.getQueryParameter("contact"),search(uri),unread(uri)),head);
  else if(path.equals("/api/senders"))json(socket,200,Archive.contacts(hub,HubService.number(uri,"offset",0,1000000),HubService.number(uri,"limit",40,200),search(uri),unread(uri)),head);
  else if(path.equals("/api/timeline")){String key=uri.getQueryParameter("contact");if(key==null||key.length()>200)throw new IllegalArgumentException();json(socket,200,Archive.timeline(hub,key,HubService.number(uri,"offset",0,10000),HubService.number(uri,"limit",40,200),search(uri),unread(uri)),head);}
  else if(path.equals("/api/greeting"))json(socket,200,hub.greeting.info(),head);
  else if(path.equals("/api/greeting/audio")){File f=hub.greeting.file();if(f==null)json(socket,404,new JSONObject().put("error","Приветствие ещё не записано"),head);else file(socket,f,"audio/wav",true,range,head);}
  else if(path.equals("/api/recordings/status")){
   String name=uri.getQueryParameter("file");File source=name==null?null:hub.calls.recording(name);
   json(socket,source==null?404:200,source==null?new JSONObject().put("error","Запись не найдена"):hub.wave.status(name,source),head);
  }else if(path.startsWith("/recordings/")){
   String name=path.substring(12);boolean wav=name.matches("call-[1-9][0-9]{0,15}\\.wav");String original=wav?name.replace(".wav",".3gp"):name;
   File source=hub.calls.recording(original),file=source==null?null:wav?hub.wave.readyFile(original,source):source;
   if(file==null)json(socket,404,new JSONObject().put("error","Запись не найдена или ещё не подготовлена"),head);
   else file(socket,file,wav?"audio/wav":"audio/3gpp",wav,range,head);
  }else json(socket,404,new JSONObject().put("error","Not found"),head);
  return true;
 }
 private static String search(Uri u){String s=u.getQueryParameter("q");if(s!=null&&s.length()>200)throw new IllegalArgumentException();return s==null?"":s;}
 private static boolean unread(Uri u){String s=u.getQueryParameter("filter");if(s!=null&&!s.equals("all")&&!s.equals("unread"))throw new IllegalArgumentException();return "unread".equals(s);}
 private static JSONObject settings(HubService h)throws Exception{return h.greeting.info().put("autoAnswer",h.calls.autoAnswer());}
 private static JSONObject body(String action,String expected,String type,String transfer,int length,InputStream in)throws Exception{
  if(!expected.equals(action)||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json")||!transfer.isEmpty()||length<1||length>16384)throw new IllegalArgumentException();
  byte[] data=new byte[length];int offset=0;while(offset<length){int n=in.read(data,offset,length-offset);if(n<0)throw new IllegalArgumentException();offset+=n;}return new JSONObject(new String(data,"UTF-8"));
 }
 private static void json(Socket s,int code,JSONObject j,boolean head)throws Exception{LegacySmsService.reply(s,code,"application/json; charset=utf-8",j.toString().getBytes("UTF-8"),head);}
 private static void file(Socket s,File f,String mime,boolean inline,String range,boolean head)throws Exception{
  long size=f.length(),start=0,end=size-1;boolean partial=false;
  if(!range.isEmpty()){
   try{if(!range.startsWith("bytes=")||range.indexOf(',')>=0)throw new Exception();String[] pair=range.substring(6).split("-",-1);if(pair.length!=2)throw new Exception();if(pair[0].isEmpty()){long suffix=Long.parseLong(pair[1]);if(suffix<=0)throw new Exception();start=Math.max(0,size-suffix);}else{start=Long.parseLong(pair[0]);if(!pair[1].isEmpty())end=Math.min(size-1,Long.parseLong(pair[1]));}if(start<0||start>=size||end<start)throw new Exception();partial=true;}
   catch(Exception invalid){OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+size+"\r\nContent-Length: 0\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes("US-ASCII"));out.flush();return;}
  }
  long length=end-start+1;String header="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+length+"\r\nAccept-Ranges: bytes\r\nContent-Disposition: "+(inline?"inline":"attachment")+"; filename=\""+f.getName()+"\"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+size+"\r\n":"")+"\r\n";
  OutputStream out=s.getOutputStream();out.write(header.getBytes("US-ASCII"));
  if(!head){RandomAccessFile in=new RandomAccessFile(f,"r");try{in.seek(start);byte[] chunk=new byte[32768];long remaining=length;while(remaining>0){int n=in.read(chunk,0,(int)Math.min(chunk.length,remaining));if(n<0)throw new EOFException();out.write(chunk,0,n);remaining-=n;}}finally{in.close();}}
  out.flush();
 }
}
