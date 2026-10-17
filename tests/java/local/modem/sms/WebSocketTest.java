package local.modem.sms;

import java.io.*;
import java.net.*;
import java.util.*;

/** Host-only protocol/resource regression checks; no SMS database or modem required. */
public final class WebSocketTest {
    private static final String KEY="dGhlIHNhbXBsZSBub25jZQ==";
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    private static byte[] masked(int opcode,byte[] payload) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(128|opcode);
        if(payload.length<126)out.write(128|payload.length);else{out.write(254);out.write(payload.length>>8);out.write(payload.length&255);}
        byte[] mask={11,22,33,44};out.write(mask);
        for(int i=0;i<payload.length;i++)out.write(payload[i]^mask[i%4]);
        return out.toByteArray();
    }
    private static void reject(byte[] frame,int code) throws Exception {
        try { WebSocketProtocol.readClientFrame(new ByteArrayInputStream(frame));throw new AssertionError("Invalid frame accepted"); }
        catch(WebSocketProtocol.ProtocolException expected){check(expected.closeCode==code,"Wrong close code: "+expected.closeCode);}
    }
    private static String header(InputStream in) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int state=0;
        while(out.size()<4096){int b=in.read();if(b<0)throw new EOFException();out.write(b);state=((state<<8)|b);if(state==0x0d0a0d0a)return out.toString("US-ASCII");}
        throw new AssertionError("Long handshake");
    }
    private static WebSocketProtocol.Frame serverFrame(InputStream in) throws Exception {
        int a=in.read(),b=in.read();if(a<0||b<0)throw new EOFException();check((a&128)!=0&&(b&128)==0,"Invalid server frame");
        int length=b&127;if(length==126)length=(in.read()<<8)|in.read();check(length<=16384,"Long frame");
        byte[] body=new byte[length];new DataInputStream(in).readFully(body);return new WebSocketProtocol.Frame(a&15,body);
    }
    public static void main(String[] args) throws Exception {
        check(WebSocketProtocol.acceptKey(KEY).equals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),"RFC handshake vector failed");
        check(!WebSocketProtocol.validKey("bad")&&!WebSocketProtocol.validKey("aaaaaaaaaaaaaaaaaaaaaa=="),"Malformed key accepted");
        byte[] ping="ping".getBytes("UTF-8");
        check(Arrays.equals(WebSocketProtocol.readClientFrame(new ByteArrayInputStream(masked(9,ping))).payload,ping),"Mask decode failed");
        reject(new byte[]{(byte)137,0},1002);
        reject(new byte[]{9,(byte)128},1002);
        reject(new byte[]{(byte)137,(byte)254},1002);
        reject(masked(1,new byte[0]),1003);
        reject(masked(8,new byte[]{1}),1002);
        reject(masked(8,new byte[]{3,(byte)237}),1002); // forbidden 1005
        reject(masked(8,new byte[]{3,(byte)232,(byte)255}),1007);
        reject(new byte[]{(byte)129,(byte)254,8,0},1009);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        WebSocketProtocol.writeFrame(encoded,new WebSocketProtocol.Frame(1,new byte[300]));
        check(serverFrame(new ByteArrayInputStream(encoded.toByteArray())).payload.length==300,"Extended length failed");

        WebSocketHub hub=new WebSocketHub();ServerSocket listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        List<Socket> peers=new ArrayList<Socket>();
        try {
            for(int i=0;i<WebSocketHub.MAX_CLIENTS;i++) {
                Socket peer=new Socket("127.0.0.1",listener.getLocalPort());peer.setSoTimeout(3000);peers.add(peer);
                Socket server=listener.accept();
                check(hub.accept(server,KEY,new WebSocketHub.InitialMessage(){public String get(){return "{\"type\":\"ready\"}";}}),"Connection rejected early");
                check(header(peer.getInputStream()).contains("101 Switching Protocols"),"Upgrade failed");
                check(new String(serverFrame(peer.getInputStream()).payload,"UTF-8").contains("ready"),"Initial snapshot missing");
            }
            Socket excess=new Socket("127.0.0.1",listener.getLocalPort()),server=listener.accept();
            check(!hub.accept(server,KEY,new WebSocketHub.InitialMessage(){public String get(){return "{}";}}),"Client limit not enforced");
            excess.close();server.close();
            hub.broadcast("{\"type\":\"messages\",\"revision\":1}");
            for(Socket peer:peers)check(new String(serverFrame(peer.getInputStream()).payload,"UTF-8").contains("messages"),"Broadcast missing");
            Socket first=peers.get(0);
            first.getOutputStream().write(masked(9,ping));first.getOutputStream().flush();
            WebSocketProtocol.Frame pong=serverFrame(first.getInputStream());check(pong.opcode==10&&Arrays.equals(pong.payload,ping),"Pong failed");
            // Frequent notifications must not postpone the transport heartbeat forever.
            long until=System.nanoTime()+21000000000L;boolean heartbeat=false;
            while(System.nanoTime()<until&&!heartbeat) {
                hub.broadcast("{}");
                for(Socket peer:peers) {
                    WebSocketProtocol.Frame frame=serverFrame(peer.getInputStream());
                    if(frame.opcode==9){peer.getOutputStream().write(masked(10,frame.payload));frame=serverFrame(peer.getInputStream());}
                    if(new String(frame.payload,"UTF-8").contains("heartbeat")){heartbeat=true;frame=serverFrame(peer.getInputStream());}
                    check(frame.opcode==1,"Notification missing");
                }
                Thread.sleep(100);
            }
            check(heartbeat,"Heartbeat starved by notifications");
            first.getOutputStream().write(masked(8,new byte[]{3,(byte)232}));first.getOutputStream().flush();
            WebSocketProtocol.Frame close=serverFrame(first.getInputStream());check(close.opcode==8,"Close handshake missing");
        } finally {
            hub.shutdown();for(Socket peer:peers)peer.close();listener.close();
        }
        check(hub.size()==0,"Client resources leaked");
        System.out.println("PASS: RFC handshake, masking, close validation, broadcast, ping/pong, independent heartbeat, bounded clients and shutdown");
    }
}
