package local.modem.sms;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class SmsService extends Service {
    private static final String PUBLIC_HOST = "sms-manager.nightlife-server.ru";
    private volatile boolean running;
    private volatile ServerSocket listener;
    private Thread acceptThread;
    private ThreadPoolExecutor pool;
    private byte[] page;
    private final DeleteGuard deleteGuard = new DeleteGuard();
    private final WebSocketHub sockets=new WebSocketHub();
    private final AtomicLong revision=new AtomicLong();
    private Handler events;
    private NetworkMonitor network;
    private final Runnable sendMessages=new Runnable(){public void run(){
        try { if(running)sockets.broadcast(new JSONObject().put("type","messages").put("revision",revision.get()).toString()); }
        catch(Exception ex) { Log.w("SmsInbox","Unable to publish message event"); }
    }};
    private final Runnable messagesChanged=new Runnable(){public void run(){
        revision.incrementAndGet();
        if(events!=null&&running){events.removeCallbacks(sendMessages);events.postDelayed(sendMessages,100);}
    }};
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onCreate() {
        super.onCreate();
        try {
            InputStream asset = getAssets().open("index.html");
            try { page = readAll(asset); } finally { asset.close(); }
        } catch (Exception ex) { Log.e("SmsInbox", "Page unavailable", ex); stopSelf(); return; }
        pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(12));
        running = true;
        events=new Handler();
        SmsStore.get(this).setChangeListener(messagesChanged);
        network=new NetworkMonitor(this,new Runnable(){public void run(){
            try { if(running)sockets.broadcast(stateMessage("network").toString()); }
            catch(Exception ex) { Log.w("SmsNetwork","Unable to publish radio event"); }
        }});
        network.start();
        acceptThread = new Thread(new Runnable() { public void run() { serve(); } }, "sms-http");
        acceptThread.start();
    }
    private void serve() {
        SmsStore.get(this).importSystem();
        while (running) {
            try {
                listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(InetAddress.getByName("192.168.100.1"), 8088), 16);
                Log.i("SmsInbox", "HTTP listener ready on LAN port 8088");
                while (running) {
                    final Socket client = listener.accept();
                    try { pool.execute(new Runnable() { public void run() { handle(client); } }); }
                    catch (RuntimeException busy) { client.close(); }
                }
            } catch (Exception ex) {
                if (running) Log.w("SmsInbox", "Waiting for LAN interface: " + ex.getClass().getSimpleName());
            } finally { try { if (listener != null) listener.close(); } catch (Exception ignored) {} }
            if (running) try { Thread.sleep(3000); } catch (InterruptedException ex) { break; }
        }
    }
    private void handle(Socket client) {
        boolean upgraded=false;
        try {
            client.setSoTimeout(5000);
            String peer = client.getInetAddress().getHostAddress();
            if (!peer.startsWith("192.168.100.") && !peer.equals("127.0.0.1")) { reply(client, 403, "text/plain", bytes("LAN access only"), false); return; }
            String header = readHeader(client.getInputStream());
            String[] lines = header.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length != 3) { reply(client, 400, "text/plain", bytes("Bad request"), false); return; }
            String host = "", origin = "", contentType="", action="", transfer="",upgrade="",connection="",socketKey="",socketVersion="";
            boolean hostSeen=false,originSeen=false,keySeen=false,versionSeen=false;
            int contentLength=-1;
            for (int i=1; i<lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) continue;
                String key = lines[i].substring(0, colon).toLowerCase(Locale.US), value = lines[i].substring(colon+1).trim();
                if (key.equals("host")) { if(hostSeen)throw new IllegalArgumentException("Duplicate host"); hostSeen=true; host=value.toLowerCase(Locale.US); }
                if (key.equals("origin")) { if(originSeen)throw new IllegalArgumentException("Duplicate origin");originSeen=true;origin=value; }
                if (key.equals("upgrade")) upgrade=value;
                if (key.equals("connection")) connection+=","+value;
                if (key.equals("sec-websocket-key")) { if(keySeen)throw new IllegalArgumentException("Duplicate socket key");keySeen=true;socketKey=value; }
                if (key.equals("sec-websocket-version")) { if(versionSeen)throw new IllegalArgumentException("Duplicate socket version");versionSeen=true;socketVersion=value; }
                if (key.equals("content-type")) contentType = value;
                if (key.equals("x-sms-action")) action = value;
                if (key.equals("transfer-encoding")) transfer = value;
                if (key.equals("content-length")) {
                    if(contentLength!=-1)throw new IllegalArgumentException("Duplicate length");
                    contentLength=Integer.parseInt(value);
                }
            }
            if (!validHost(host)) {
                reply(client, 403, "text/plain", bytes("Invalid host"), false); return;
            }
            if (!validOrigin(origin,host)) { reply(client, 403, "text/plain", bytes("Invalid origin"), false); return; }
            boolean head = first[0].equals("HEAD");
            if (!first[1].startsWith("/") || first[1].startsWith("//")) { reply(client, 400, "text/plain", bytes("Invalid target"), head); return; }
            Uri target = Uri.parse(first[1]);
            if(target.getPath().equals("/ws")) {
                if(!first[0].equals("GET")){reply(client,405,"text/plain",bytes("Method not allowed"),false);return;}
                if(origin.isEmpty()){reply(client,403,"text/plain",bytes("Origin required"),false);return;}
                if(!socketVersion.equals("13")){reply(client,426,"text/plain",bytes("WebSocket version 13 required"),false);return;}
                if(!first[2].equals("HTTP/1.1")||!upgrade.equalsIgnoreCase("websocket")||!hasToken(connection,"upgrade")||!WebSocketProtocol.validKey(socketKey)||!transfer.isEmpty()||contentLength>0){
                    reply(client,400,"text/plain",bytes("Invalid WebSocket handshake"),false);return;
                }
                upgraded=sockets.accept(client,socketKey,new WebSocketHub.InitialMessage(){public String get() throws Exception{return stateMessage("ready").toString();}});
                if(!upgraded)reply(client,503,"text/plain",bytes("Too many live connections"),false);
                return;
            }
            if (first[0].equals("POST") && target.getPath().equals("/api/sms/delete")) {
                if(!action.equals("delete") || !contentType.split(";",2)[0].trim().equalsIgnoreCase("application/json")) { reply(client,415,"application/json",bytes("{\"error\":\"JSON request required\"}"),false);return; }
                if(!transfer.isEmpty() || contentLength<1 || contentLength>16384) { reply(client,400,"application/json",bytes("{\"error\":\"Invalid request size\"}"),false);return; }
                JSONObject request=new JSONObject(readBody(client.getInputStream(),contentLength));
                JSONArray values=request.getJSONArray("ids");
                if(values.length()<1 || values.length()>500)throw new IllegalArgumentException("Select 1 to 500 messages");
                LinkedHashSet<Long> ids=new LinkedHashSet<Long>();
                for(int i=0;i<values.length();i++) {
                    Object value=values.get(i);
                    if(!(value instanceof Integer) && !(value instanceof Long))throw new IllegalArgumentException("Invalid message ID");
                    long id=((Number)value).longValue(); if(id<=0)throw new IllegalArgumentException("Invalid message ID"); ids.add(id);
                }
                int verified=deleteGuard.check(peer,request.optString("code",""));
                if(verified!=0) {
                    JSONObject error=new JSONObject();error.put("error",verified<0?"wrong_code":"try_later");if(verified>0)error.put("retryAfter",verified);
                    reply(client,verified<0?403:429,"application/json; charset=utf-8",bytes(error.toString()),false);return;
                }
                JSONObject result=new JSONObject();result.put("deleted",SmsStore.get(this).delete(new ArrayList<Long>(ids)));
                reply(client,200,"application/json; charset=utf-8",bytes(result.toString()),false);return;
            }
            if (!first[0].equals("GET") && !head) { reply(client,405,"text/plain",bytes("Method not allowed"),false);return; }
            if (target.getPath().equals("/") || target.getPath().equals("/index.html")) reply(client, 200, "text/html; charset=utf-8", page, head);
            else if (target.getPath().equals("/api/sms")) {
                int offset = number(target.getQueryParameter("offset"), 0, 0, 1000000);
                int limit = number(target.getQueryParameter("limit"), 100, 1, 200);
                reply(client, 200, "application/json; charset=utf-8", bytes(SmsStore.get(this).list(offset, limit, target.getQueryParameter("sender")).toString()), head);
            } else if(target.getPath().equals("/api/senders")) {
                int offset=number(target.getQueryParameter("offset"),0,0,1000000), limit=number(target.getQueryParameter("limit"),100,1,200);
                reply(client,200,"application/json; charset=utf-8",bytes(SmsStore.get(this).senders(offset,limit).toString()),head);
            } else if(target.getPath().equals("/api/status")) {
                reply(client,200,"application/json; charset=utf-8",bytes(stateMessage("status").toString()),head);
            } else reply(client, 404, "text/plain", bytes("Not found"), head);
        } catch (IllegalArgumentException ex) {
            try { reply(client,400,"application/json",bytes("{\"error\":\"Invalid request\"}"),false); } catch(Exception ignored){}
        } catch (org.json.JSONException ex) {
            try { reply(client,400,"application/json",bytes("{\"error\":\"Invalid JSON\"}"),false); } catch(Exception ignored){}
        } catch (Exception ex) {
            try { reply(client, 500, "application/json", bytes("{\"error\":\"Messages unavailable\"}"), false); } catch (Exception ignored) {}
            Log.w("SmsInbox", "Request failed: " + ex.getClass().getSimpleName());
        } finally { if(!upgraded)try { client.close(); } catch (Exception ignored) {} }
    }
    private JSONObject stateMessage(String type) throws Exception {
        return new JSONObject().put("type",type).put("revision",revision.get()).put("network",network.snapshot());
    }
    private static boolean hasToken(String value,String token) {
        for(String part:value.split(","))if(part.trim().equalsIgnoreCase(token))return true;
        return false;
    }
    private static int number(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); } catch (Exception ex) { return fallback; }
    }
    private static boolean validHost(String host) {
        return host.equals("192.168.100.1") || host.equals("192.168.100.1:80") || host.equals("192.168.100.1:8088") || host.equals(PUBLIC_HOST) || host.equals(PUBLIC_HOST + ":443");
    }
    private static boolean validOrigin(String origin,String host) {
        if(origin.isEmpty())return true;
        if(host.equals(PUBLIC_HOST) || host.equals(PUBLIC_HOST + ":443"))return origin.equals("https://" + PUBLIC_HOST);
        return origin.equals("http://" + host);
    }
    private static String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int tail = 0;
        while (buffer.size() < 8192) {
            int value = input.read(); if (value < 0) throw new java.io.IOException("Incomplete request");
            buffer.write(value); tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) return buffer.toString("ISO-8859-1");
        }
        throw new java.io.IOException("Header limit");
    }
    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int n;
        while ((n = input.read(chunk)) != -1) output.write(chunk, 0, n);
        return output.toByteArray();
    }
    private static String readBody(InputStream input,int length) throws Exception {
        byte[] body=new byte[length];int offset=0;
        while(offset<length){int n=input.read(body,offset,length-offset);if(n<0)throw new IllegalArgumentException("Incomplete body");offset+=n;}
        return new String(body,"UTF-8");
    }
    private static byte[] bytes(String value) throws Exception { return value.getBytes("UTF-8"); }
    private static void reply(Socket client, int status, String mime, byte[] body, boolean head) throws Exception {
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 400 ? "Bad Request" : status == 415 ? "Unsupported Media Type" : status == 426 ? "Upgrade Required" : status == 429 ? "Too Many Requests" : status == 503 ? "Service Unavailable" : "Internal Server Error";
        String header = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + mime + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n"+(status==426?"Sec-WebSocket-Version: 13\r\n":"")+"Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://192.168.100.1 ws://192.168.100.1:8088 wss://"+PUBLIC_HOST+"; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n";
        OutputStream output = client.getOutputStream(); output.write(header.getBytes("ISO-8859-1")); if (!head) output.write(body); output.flush();
    }
    @Override public void onDestroy() {
        running = false;
        SmsStore.get(this).clearChangeListener(messagesChanged);
        if(events!=null)events.removeCallbacksAndMessages(null);
        if(network!=null)network.stop();
        sockets.shutdown();
        try { if (listener != null) listener.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt(); if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }
}
