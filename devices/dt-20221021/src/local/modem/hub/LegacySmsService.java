package local.modem.hub;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class LegacySmsService extends Service {
    private volatile boolean running;
    private final java.util.List<ServerSocket> sockets=java.util.Collections.synchronizedList(new java.util.ArrayList<ServerSocket>());
    private Thread compatThread;
    private Thread acceptThread;
    private ThreadPoolExecutor pool;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onCreate() {
        super.onCreate();
        pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(12));
        running = true;
        acceptThread = new Thread(new Runnable() { public void run() { serve(80); } }, "sms-http");
        acceptThread.start();
        compatThread=new Thread(new Runnable(){public void run(){serve(8000);}},"sms-compatible-http");compatThread.start();
    }
    private void serve(int port) {
        SmsStore.get(this).importSystem();
        while (running) {
            ServerSocket listener=null;
            try {
                listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(InetAddress.getByName("192.168.101.1"), port), 16);
                sockets.add(listener);Log.i("SmsInbox", "HTTP listener ready on LAN port "+port);
                while (running) {
                    final Socket client = listener.accept();
                    try { pool.execute(new Runnable() { public void run() { handle(client); } }); }
                    catch (RuntimeException busy) { client.close(); }
                }
            } catch (Exception ex) {
                if(port==80 && (String.valueOf(ex.getMessage()).contains("EACCES") || String.valueOf(ex.getMessage()).contains("Permission denied"))){Log.i("SmsInbox","Port 80 uses the firmware redirect to port 8000");return;}
                if (running) Log.w("SmsInbox", "Waiting for LAN port "+port+": " + ex.toString());
            } finally { try { if (listener != null){sockets.remove(listener);listener.close();} } catch (Exception ignored) {} }
            if (running) try { Thread.sleep(3000); } catch (InterruptedException ex) { break; }
        }
    }
    private void handle(Socket client){WebEndpoint.handle(this,client,false);}
    static void reply(Socket client, int status, String mime, byte[] body, boolean head) throws Exception {
        String reason = status == 409 ? "Conflict" : status == 503 ? "Service Unavailable" : status == 202 ? "Accepted" : status == 401 ? "Unauthorized" : status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 400 ? "Bad Request" : status == 415 ? "Unsupported Media Type" : status == 429 ? "Too Many Requests" : "Internal Server Error";
        String header = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + mime + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://192.168.101.1 ws://192.168.101.1:8000 ws://192.168.101.1:8088 ws://localhost:18081 ws://127.0.0.1:18081; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n";
        OutputStream output = client.getOutputStream(); output.write(header.getBytes("ISO-8859-1")); if (!head) output.write(body); output.flush();
    }
    @Override public void onDestroy() {
        running = false;
        synchronized(sockets){for(ServerSocket socket:sockets)try{socket.close();}catch(Exception ignored){}}
        if(compatThread!=null)compatThread.interrupt();if (acceptThread != null) acceptThread.interrupt(); if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }
}
