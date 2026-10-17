package local.modem.sms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Long-lived sockets have their own bounded queues and never occupy HTTP workers. */
public final class WebSocketHub {
    public interface InitialMessage { String get() throws Exception; }
    public static final int MAX_CLIENTS=8;
    private final HashSet<Client> clients=new HashSet<Client>();
    private boolean stopped;
    public boolean accept(Socket socket,String key,InitialMessage initial) throws Exception {
        final Client client;
        synchronized(this) {
            if(stopped || clients.size()>=MAX_CLIENTS)return false;
            client=new Client(socket);
            // Queue the snapshot before publishing this client to broadcasters.
            client.enqueue(new WebSocketProtocol.Frame(1,initial.get().getBytes("UTF-8")));
            clients.add(client);
        }
        try {
            socket.setSoTimeout(45000); socket.setTcpNoDelay(true);
            String response="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+WebSocketProtocol.acceptKey(key)+"\r\n\r\n";
            socket.getOutputStream().write(response.getBytes("US-ASCII")); socket.getOutputStream().flush();
            client.start(); return true;
        } catch(Exception ex) { client.close(); throw ex; }
    }
    public void broadcast(String json) {
        try {
            WebSocketProtocol.Frame frame=new WebSocketProtocol.Frame(1,json.getBytes("UTF-8"));
            for(Client client:snapshot())client.enqueue(frame);
        } catch(java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private synchronized Client[] snapshot() { return clients.toArray(new Client[clients.size()]); }
    private synchronized void remove(Client client) { clients.remove(client); }
    public void shutdown() {
        synchronized(this) { stopped=true; }
        for(Client client:snapshot())client.close();
    }
    public synchronized int size() { return clients.size(); }

    private final class Client {
        final Socket socket;
        final ArrayBlockingQueue<WebSocketProtocol.Frame> queue=new ArrayBlockingQueue<WebSocketProtocol.Frame>(32);
        volatile boolean closed;
        Thread reader,writer;
        Client(Socket socket) { this.socket=socket; }
        void start() {
            writer=new Thread(new Runnable(){public void run(){writeLoop();}},"sms-ws-write");
            reader=new Thread(new Runnable(){public void run(){readLoop();}},"sms-ws-read");
            writer.setDaemon(true); reader.setDaemon(true); writer.start(); reader.start();
        }
        void enqueue(WebSocketProtocol.Frame frame) { if(!closed && !queue.offer(frame))close(); }
        void closeSoon(WebSocketProtocol.Frame frame) {
            queue.clear(); enqueue(frame);
            // Bound the closing handshake even if the peer stops reading.
            try { writer.join(1000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        void writeLoop() {
            try {
                OutputStream out=socket.getOutputStream();
                long nextPing=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!closed) {
                    long wait=Math.max(1,nextPing-System.nanoTime());
                    WebSocketProtocol.Frame frame=queue.poll(wait,TimeUnit.NANOSECONDS);
                    if(frame!=null) {
                        WebSocketProtocol.writeFrame(out,frame);
                        if(frame.opcode==8)break;
                    }
                    if(System.nanoTime()>=nextPing) {
                        WebSocketProtocol.writeFrame(out,new WebSocketProtocol.Frame(9,new byte[0]));
                        WebSocketProtocol.writeFrame(out,new WebSocketProtocol.Frame(1,"{\"type\":\"heartbeat\"}".getBytes("UTF-8")));
                        nextPing=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    }
                }
            } catch(Exception ignored) { } finally { close(); }
        }
        void readLoop() {
            try {
                InputStream in=socket.getInputStream();
                while(!closed) {
                    WebSocketProtocol.Frame frame=WebSocketProtocol.readClientFrame(in);
                    if(frame.opcode==8) { closeSoon(frame); return; }
                    if(frame.opcode==9)enqueue(new WebSocketProtocol.Frame(10,frame.payload));
                }
            } catch(WebSocketProtocol.ProtocolException invalid) {
                closeSoon(WebSocketProtocol.closeFrame(invalid.closeCode));
            } catch(IOException ignored) { } finally { close(); }
        }
        void close() {
            closed=true;
            try { socket.close(); } catch(IOException ignored) { }
            if(writer!=null)writer.interrupt();
            if(reader!=null)reader.interrupt();
            remove(this);
        }
    }
}
