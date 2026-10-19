package local.modem.hub;
import java.io.*;
import java.util.*;
public final class HttpRequest {
 final String method,target;
 final Map<String,String> headers=new HashMap<String,String>();
 final int length;
 HttpRequest(InputStream in)throws IOException {
  ByteArrayOutputStream out=new ByteArrayOutputStream();int tail=0;
  while(true){int b=in.read();if(b<0)throw new IOException("Incomplete headers");out.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;if(out.size()>=8192)throw new IOException("Header limit");}
  String[] lines=out.toString("ISO-8859-1").split("\r\n");String[] first=lines[0].split(" ",-1);
  if(first.length!=3||!first[2].equals("HTTP/1.1")||!first[1].startsWith("/")||first[1].startsWith("//")||first[1].indexOf('#')>=0)throw new IOException("Bad request line");
  method=first[0];target=first[1];
  for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<1||Character.isWhitespace(lines[i].charAt(0)))throw new IOException("Bad header");String name=lines[i].substring(0,colon).toLowerCase(Locale.US);if(!name.matches("[a-z0-9-]+"))throw new IOException("Bad header name");String value=lines[i].substring(colon+1).trim();if(headers.put(name,value)!=null)throw new IOException("Duplicate header");}
  if(!headers.containsKey("host")||headers.containsKey("transfer-encoding"))throw new IOException("Unsupported framing");
  String raw=get("content-length");try{if(!raw.isEmpty()&&!raw.matches("[0-9]{1,7}"))throw new NumberFormatException();length=raw.isEmpty()?0:Integer.parseInt(raw);if(length>2*1024*1024)throw new NumberFormatException();}catch(NumberFormatException e){throw new IOException("Body limit");}
 }
 String get(String key){String v=headers.get(key);return v==null?"":v;}
 String body(InputStream in)throws IOException {byte[] b=new byte[length];int n=0;while(n<b.length){int got=in.read(b,n,b.length-n);if(got<0)throw new IOException("Incomplete body");n+=got;}return new String(b,"UTF-8");}
 static boolean validHost(String host){return host.equals("192.168.100.1:8088")||host.equals("192.168.100.1:8000")||host.equals("192.168.100.1:80")||host.equals("192.168.100.1")||host.equals("localhost:18080")||host.equals("127.0.0.1:18080")||host.equals("127.0.0.1:18088");}
 static boolean validOrigin(String origin,String host){return origin.isEmpty()||origin.equals("http://"+host);}
}
