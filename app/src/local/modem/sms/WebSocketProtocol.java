package local.modem.sms;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.security.MessageDigest;

/** Small RFC 6455 transport for server notifications, without Android dependencies. */
public final class WebSocketProtocol {
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    public static final class Frame {
        public final int opcode;
        public final byte[] payload;
        public Frame(int opcode, byte[] payload) { this.opcode=opcode; this.payload=payload; }
    }
    public static final class ProtocolException extends IOException {
        public final int closeCode;
        ProtocolException(int code) { super("WebSocket protocol violation"); closeCode=code; }
    }
    private WebSocketProtocol() { }
    public static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9+/]{21}[AQgw]==");
    }
    public static String acceptKey(String key) throws Exception {
        if (!validKey(key)) throw new IllegalArgumentException("Invalid WebSocket key");
        byte[] digest=MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("US-ASCII"));
        StringBuilder result=new StringBuilder();
        for(int i=0;i<digest.length;i+=3) {
            int a=digest[i]&255,b=i+1<digest.length?digest[i+1]&255:0,c=i+2<digest.length?digest[i+2]&255:0;
            result.append(BASE64.charAt(a>>2)); result.append(BASE64.charAt(((a&3)<<4)|(b>>4)));
            result.append(i+1<digest.length?BASE64.charAt(((b&15)<<2)|(c>>6)):'=');
            result.append(i+2<digest.length?BASE64.charAt(c&63):'=');
        }
        return result.toString();
    }
    private static int octet(InputStream in) throws IOException {
        int value=in.read(); if(value<0)throw new EOFException(); return value;
    }
    public static Frame readClientFrame(InputStream in) throws IOException {
        int first=octet(in),second=octet(in),opcode=first&15;
        if((first&112)!=0 || (second&128)==0)throw new ProtocolException(1002);
        if(opcode!=1 && opcode!=2 && opcode!=8 && opcode!=9 && opcode!=10 && opcode!=0)throw new ProtocolException(1002);
        boolean control=(opcode&8)!=0;
        int length=second&127;
        if(control && ((first&128)==0 || length>125))throw new ProtocolException(1002);
        if(length==126) {
            length=(octet(in)<<8)|octet(in);
            if(length<126)throw new ProtocolException(1002);
        } else if(length==127) { throw new ProtocolException(1009); }
        if(length>1024)throw new ProtocolException(1009);
        // This endpoint has no application commands or fragmented messages.
        if(!control)throw new ProtocolException(1003);
        byte[] mask=new byte[4],payload=new byte[length];
        for(int i=0;i<4;i++)mask[i]=(byte)octet(in);
        for(int i=0;i<length;i++)payload[i]=(byte)(octet(in)^(mask[i%4]&255));
        if(opcode==8) {
            if(length==1)throw new ProtocolException(1002);
            if(length>=2) {
                int code=((payload[0]&255)<<8)|(payload[1]&255);
                if(!((code>=1000&&code<=1014&&code!=1004&&code!=1005&&code!=1006)||(code>=3000&&code<=4999)))throw new ProtocolException(1002);
                try { Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload,2,length-2)); }
                catch(CharacterCodingException invalid) { throw new ProtocolException(1007); }
            }
        }
        return new Frame(opcode,payload);
    }
    public static void writeFrame(OutputStream out, Frame frame) throws IOException {
        int length=frame.payload.length;
        if(length>16384 || ((frame.opcode&8)!=0&&length>125))throw new IOException("Frame too large");
        out.write(128|frame.opcode);
        if(length<126)out.write(length); else { out.write(126); out.write(length>>8); out.write(length&255); }
        out.write(frame.payload); out.flush();
    }
    public static Frame closeFrame(int code) { return new Frame(8,new byte[]{(byte)(code>>8),(byte)code}); }
}
