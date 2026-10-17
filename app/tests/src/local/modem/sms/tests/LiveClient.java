package local.modem.sms.tests;

import java.io.*;
import java.net.Socket;
import org.json.JSONObject;

/** Real RFC 6455 client used by the modem instrumentation tests. */
final class LiveClient implements Closeable {
    final Socket socket;
    final long initialRevision;
    LiveClient() throws Exception {
        socket=new Socket("192.168.100.1",8088);socket.setSoTimeout(5000);
        try {
            String request="GET /ws HTTP/1.1\r\nHost: 192.168.100.1:8088\r\nOrigin: http://192.168.100.1:8088\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
            socket.getOutputStream().write(request.getBytes("US-ASCII"));socket.getOutputStream().flush();
            ByteArrayOutputStream header=new ByteArrayOutputStream();int last=0;
            while(header.size()<4096){int b=socket.getInputStream().read();if(b<0)throw new EOFException();header.write(b);last=(last<<8)|b;if(last==0x0d0a0d0a)break;}
            String response=header.toString("US-ASCII");
            if(!response.startsWith("HTTP/1.1 101 ")||!response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))throw new IOException("Upgrade failed");
            initialRevision=await("ready").getLong("revision");
        } catch(Exception failure) { socket.close();throw failure; }
    }
    JSONObject await(String type) throws Exception {
        InputStream in=socket.getInputStream();long until=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<until) {
            int a=in.read(),b=in.read();if(a<0||b<0)throw new EOFException();
            int length=b&127;if(length==126)length=(in.read()<<8)|in.read();if(length>16384||(b&128)!=0)throw new IOException("Bad server frame");
            byte[] body=new byte[length];new DataInputStream(in).readFully(body);
            if((a&15)==9){sendControl(10,body);continue;}
            if((a&15)==8)throw new IOException("Server closed live socket");
            JSONObject message=new JSONObject(new String(body,"UTF-8"));if(type.equals(message.optString("type")))return message;
        }
        throw new IOException("Live event missing: "+type);
    }
    private void sendControl(int opcode,byte[] payload) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(128|opcode);out.write(128|payload.length);byte[] mask={1,2,3,4};out.write(mask);
        for(int i=0;i<payload.length;i++)out.write(payload[i]^mask[i%4]);socket.getOutputStream().write(out.toByteArray());socket.getOutputStream().flush();
    }
    public void close() throws IOException { socket.close(); }
}
