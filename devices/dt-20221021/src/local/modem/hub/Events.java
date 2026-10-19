package local.modem.hub;
import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import android.util.Base64;
import org.json.JSONObject;

/** Bounded, server-push WebSocket channel. Mutations use the HTTP API. */
final class Events {
 private static final Set<Client> clients=new HashSet<Client>();
 private static final AtomicLong revision=new AtomicLong(System.currentTimeMillis());
 static long revision(){return revision.get();}
 static void changed(String topic){try{broadcast(new JSONObject().put("type","changed").put("topic",topic).put("revision",revision.incrementAndGet()).toString());}catch(Exception ignored){}}
 static void broadcast(String value){Client[] copy;synchronized(clients){copy=clients.toArray(new Client[clients.size()]);}for(Client c:copy)c.offer(1,value.getBytes(java.nio.charset.Charset.forName("UTF-8")));}
 static boolean upgrade(Socket socket,HttpRequest r)throws Exception{
  String key=r.get("sec-websocket-key"),connection=r.get("connection").toLowerCase(Locale.US);
  if(!r.method.equals("GET")||r.length!=0||!r.get("upgrade").equalsIgnoreCase("websocket")||!Arrays.asList(connection.split("\\s*,\\s*")).contains("upgrade")||!r.get("sec-websocket-version").equals("13")||r.get("origin").isEmpty()||key.length()>64)throw new IllegalArgumentException();
  try{if(Base64.decode(key,Base64.DEFAULT).length!=16)throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException();}
  final Client c=new Client(socket);synchronized(clients){if(clients.size()>=8){LegacySmsService.reply(socket,503,"text/plain",new byte[0],false);return false;}clients.add(c);}
  try{String accept=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("US-ASCII")),Base64.NO_WRAP);socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes("US-ASCII"));socket.getOutputStream().flush();socket.setSoTimeout(45000);c.start();c.offer(1,new JSONObject().put("type","connected").put("revision",revision()).toString().getBytes("UTF-8"));return true;}catch(Exception e){c.close();throw e;}
 }
 static void closeAll(){Client[] copy;synchronized(clients){copy=clients.toArray(new Client[clients.size()]);}for(Client c:copy)c.close();}
 private static final class Packet{final int op;final byte[] bytes;Packet(int o,byte[] b){op=o;bytes=b;}}
 private static final class Client {
  final Socket socket;final ArrayBlockingQueue<Packet> queue=new ArrayBlockingQueue<Packet>(16);volatile boolean open=true;
  Client(Socket s){socket=s;}
  void offer(int op,byte[] bytes){if(open&&!queue.offer(new Packet(op,bytes)))close();}
  void start(){new Thread(new Runnable(){public void run(){writeLoop();}},"hub-ws-write").start();new Thread(new Runnable(){public void run(){readLoop();}},"hub-ws-read").start();}
  void writeLoop(){try{OutputStream out=socket.getOutputStream();while(open){Packet p=queue.poll(15,TimeUnit.SECONDS);if(p==null)p=new Packet(9,new byte[0]);int n=p.bytes.length;out.write(128|p.op);if(n<126)out.write(n);else{out.write(126);out.write(n>>8);out.write(n&255);}out.write(p.bytes);out.flush();if(p.op==8)break;}}catch(Exception ignored){}finally{close();}}
  void readLoop(){try{InputStream in=socket.getInputStream();while(open){int a=in.read(),b=in.read();if(a<0||b<0)break;int op=a&15,n=b&127;if((a&128)==0||(a&112)!=0||(b&128)==0||n==127)break;if(n==126)n=(one(in)<<8)|one(in);if(n>1024||(op>=8&&n>125))break;byte[] mask=new byte[4],data=new byte[n];read(in,mask);read(in,data);for(int i=0;i<n;i++)data[i]^=mask[i%4];if(op==8){offer(8,data);return;}if(op==9)offer(10,data);else if(op==1)offer(1,"{\"type\":\"pong\"}".getBytes("UTF-8"));else if(op!=10)break;}}catch(Exception ignored){}finally{close();}}
  int one(InputStream i)throws IOException{int b=i.read();if(b<0)throw new EOFException();return b;}
  void read(InputStream in,byte[] b)throws IOException{int p=0;while(p<b.length){int n=in.read(b,p,b.length-p);if(n<0)throw new EOFException();p+=n;}}
  void close(){if(!open)return;open=false;synchronized(clients){clients.remove(this);}try{socket.close();}catch(Exception ignored){}}
 }
}
