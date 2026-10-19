package local.modem.hub;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.Socket;
import java.util.Locale;

final class WebEndpoint {
 static void handle(Context context,Socket socket,boolean fullStatus){boolean upgraded=false;
  try{
   socket.setSoTimeout(10000);String peer=socket.getInetAddress().getHostAddress();
   if(!peer.equals("127.0.0.1")&&!peer.startsWith("192.168.100.")){reply(socket,403,"text/plain","LAN access only".getBytes("UTF-8"),false);return;}
   HttpRequest r=new HttpRequest(socket.getInputStream());String host=r.get("host").toLowerCase(Locale.US);boolean head=r.method.equals("HEAD");
   byte[] raw=new byte[r.length];int p=0;while(p<raw.length){int n=socket.getInputStream().read(raw,p,raw.length-p);if(n<0)throw new EOFException();p+=n;}
   if(!HttpRequest.validHost(host)||!HttpRequest.validOrigin(r.get("origin"),host)){reply(socket,403,"text/plain","Invalid host or origin".getBytes("UTF-8"),head);return;}
   Uri uri=Uri.parse(r.target);String path=uri.getPath();
   if(path.equals("/ws")){upgraded=Events.upgrade(socket,r);return;}
   if(UiApi.handle(socket,r.method,uri,"",r.get("range"),r.get("x-modem-action"),r.get("content-type"),r.get("transfer-encoding"),r.length,new ByteArrayInputStream(raw),fullStatus))return;
   if(!r.method.equals("GET")&&!head){reply(socket,405,"text/plain",new byte[0],head);return;}
   if(r.length!=0)throw new IllegalArgumentException();
   if(path.equals("/")||path.equals("/index.html")){if(!head)HubService.pulseFromPage();path="/sms.html";}
   if(path.equals("/sms.html")||path.equals("/ui.css")||path.equals("/ui.js")){
    InputStream in=context.getAssets().open(path.substring(1));ByteArrayOutputStream bytes=new ByteArrayOutputStream();try{byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}finally{in.close();}
    reply(socket,200,path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8",bytes.toByteArray(),head);
   }else reply(socket,404,"text/plain",new byte[0],head);
  }catch(IllegalArgumentException e){failure(socket,400,"Некорректный запрос");}catch(org.json.JSONException e){failure(socket,400,"Некорректный JSON");}catch(UiApi.Conflict e){failure(socket,409,e.getMessage());}catch(Exception e){android.util.Log.w("ModemHub","HTTP request failed",e);failure(socket,500,"Не удалось выполнить запрос");}
  finally{if(!upgraded)try{socket.close();}catch(Exception ignored){}}
 }
 private static void failure(Socket s,int code,String message){try{reply(s,code,"application/json; charset=utf-8",new org.json.JSONObject().put("error",message).toString().getBytes("UTF-8"),false);}catch(Exception ignored){}}
 private static void reply(Socket s,int code,String mime,byte[] body,boolean head)throws Exception{LegacySmsService.reply(s,code,mime,body,head);}
}
