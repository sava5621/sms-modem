package local.modem.sms.tests;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import local.modem.sms.SmsReceiver;
import local.modem.sms.SmsStore;
import local.modem.sms.DeleteGuard;
import org.json.JSONArray;
import org.json.JSONObject;

public final class InboxTest extends Instrumentation {
    private static final String FIRST = "Тест приёма: <script> & \"текст\"\n";
    private static final String SECOND = "Вторая часть. QA-9bd4980d-20260907";
    private static byte[] pdu(String text, int part) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String header = "004005912143F5000862907021540000";
        for (int i=0; i<header.length(); i+=2) out.write(Integer.parseInt(header.substring(i,i+2),16));
        byte[] encoded = text.getBytes("UTF-16BE");
        out.write(encoded.length+6);
        out.write(new byte[]{5,0,3,66,2,(byte)part}); out.write(encoded);
        return out.toByteArray();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject http(String method,String path,JSONObject body,String origin,int expected) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://192.168.100.1:8088"+path).openConnection();
        connection.setRequestMethod(method);connection.setConnectTimeout(2000);connection.setReadTimeout(3000);
        if(origin!=null)connection.setRequestProperty("Origin",origin);
        try {
            if(body!=null){byte[] bytes=body.toString().getBytes("UTF-8");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("X-SMS-Action","delete");connection.setFixedLengthStreamingMode(bytes.length);java.io.OutputStream out=connection.getOutputStream();out.write(bytes);out.close();}
            int status=connection.getResponseCode();check(status==expected,"Unexpected HTTP status for "+method+" "+path+": "+status+" expected "+expected);
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
            if(stream==null)return new JSONObject();
            try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] part=new byte[4096];int n;while((n=stream.read(part))!=-1)bytes.write(part,0,n);String text=bytes.toString("UTF-8");return text.startsWith("{")?new JSONObject(text):new JSONObject();}finally{stream.close();}
        }finally{connection.disconnect();}
    }
    private static JSONObject deletion(String code,long... ids) throws Exception {
        JSONArray values=new JSONArray();for(long id:ids)values.put(id);return new JSONObject().put("code",code).put("ids",values);
    }
    private static long idFor(SmsStore store,String body) {
        Cursor c=store.getReadableDatabase().query("messages",new String[]{"_id"},"body=?",new String[]{body},null,null,null);
        try{check(c.moveToFirst(),"Synthetic message missing");long id=c.getLong(0);check(!c.moveToNext(),"Duplicate message");return id;}finally{c.close();}
    }
    private static boolean exists(SmsStore store,long id) {
        Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM messages WHERE _id=?",new String[]{String.valueOf(id)});
        try{return c.moveToFirst();}finally{c.close();}
    }
    private static String response(HttpURLConnection request) throws Exception {
        InputStream stream = request.getInputStream();
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] part = new byte[4096]; int n; while ((n=stream.read(part))!=-1) bytes.write(part,0,n); return bytes.toString("UTF-8"); }
        finally { stream.close(); request.disconnect(); }
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1;
        Context context = getTargetContext(); SmsStore store = SmsStore.get(context);
        String body = FIRST + SECOND;
        String tag="QA-v2-"+System.currentTimeMillis(),senderA=tag+" + & A",senderB=tag+" B";
        java.util.ArrayList<Long> existing=new java.util.ArrayList<Long>();java.util.ArrayList<String> keys=new java.util.ArrayList<String>();
        java.util.ArrayList<LiveClient> live=new java.util.ArrayList<LiveClient>();
        Cursor original=store.getReadableDatabase().rawQuery("SELECT _id FROM messages",null);
        try{while(original.moveToNext())existing.add(original.getLong(0));}finally{original.close();}
        try {
            int before = store.list(0, 1).getInt("total");
            Intent sms = new Intent("android.provider.Telephony.SMS_RECEIVED");
            sms.putExtra("format", "3gpp");
            sms.putExtra("pdus", (java.io.Serializable)new Object[]{pdu(FIRST,1),pdu(SECOND,2)});
            new SmsReceiver().onReceive(context, sms);
            check(store.list(0,1).getInt("total") == before+1, "Multipart SMS was not saved");
            new SmsReceiver().onReceive(context, sms);
            check(store.list(0,1).getInt("total") == before+1, "Duplicate SMS was not suppressed");
            Cursor cursor = store.getReadableDatabase().query("messages", new String[]{"sender","body"}, "body=?", new String[]{body}, null,null,null);
            try { check(cursor.moveToFirst(), "Unicode body missing"); check(cursor.getString(0).equals("+12345"), "Sender parsing failed"); check(cursor.getString(1).equals(body), "Multipart or Unicode parsing failed"); } finally { cursor.close(); }
            String payload = null;
            for(int i=0;i<30;i++) {
                try {
                    HttpURLConnection request = (HttpURLConnection)new URL("http://192.168.100.1:8088/api/sms").openConnection();
                    request.setConnectTimeout(1000);request.setReadTimeout(2000);
                    check(request.getResponseCode()==200,"API unavailable");payload=response(request);break;
                } catch(java.io.IOException ex) { Thread.sleep(200); }
            }
            check(payload!=null,"HTTP service did not start");
            JSONObject data=new JSONObject(payload);
            check(data.getJSONArray("messages").getJSONObject(0).getString("body").equals(body),"HTTP JSON lost message content");
            for(int i=0;i<5;i++)live.add(new LiveClient());
            JSONObject state=http("GET","/api/status",null,null,200).getJSONObject("network");
            check(state.has("simState")&&state.has("registered")&&state.has("signalDbm")&&state.has("signalLevel"),"Network status schema missing");
            if(!state.getBoolean("registered"))check(state.isNull("signalDbm")&&state.isNull("signalLevel"),"Stale signal shown without registration");
            http("GET","/ws",null,"http://example.invalid",403);
            http("GET","/ws",null,null,403);
            long stamp=System.currentTimeMillis();store.add(senderA,tag+" first",stamp,stamp,"qa");store.add(senderA,tag+" second",stamp+1,stamp+1,"qa");store.add(senderB,tag+" third",stamp+2,stamp+2,"qa");
            long first=idFor(store,tag+" first"),second=idFor(store,tag+" second"),third=idFor(store,tag+" third");
            JSONObject addedEvent=live.get(0).await("messages");
            check(addedEvent.getLong("revision")>live.get(0).initialRevision,"Insert push revision did not advance");
            check(!addedEvent.has("body")&&!addedEvent.has("sender"),"Notification leaked message content");
            long revisionBeforeDuplicate=http("GET","/api/status",null,null,200).getLong("revision");
            store.add(senderB,tag+" third",stamp+2,stamp+2,"qa");
            check(http("GET","/api/status",null,null,200).getLong("revision")==revisionBeforeDuplicate,"Duplicate generated a change event");
            Cursor synthetic=store.getReadableDatabase().rawQuery("SELECT message_key FROM messages WHERE sender=? OR sender=?",new String[]{senderA,senderB});
            try{while(synthetic.moveToNext())keys.add(synthetic.getString(0));}finally{synthetic.close();}
            String path="/api/sms?sender="+java.net.URLEncoder.encode(senderA,"UTF-8")+"&limit=1";
            JSONObject filtered=http("GET",path,null,null,200);
            check(filtered.getInt("total")==2&&filtered.getBoolean("hasMore"),"Sender filter or pagination count failed");
            check(filtered.getJSONArray("messages").getJSONObject(0).getLong("id")==second,"Sender order failed");
            JSONObject next=http("GET",path+"&offset=1",null,null,200);check(next.getJSONArray("messages").getJSONObject(0).getLong("id")==first&&!next.getBoolean("hasMore"),"Pagination failed");
            JSONArray groups=http("GET","/api/senders?limit=200",null,null,200).getJSONArray("senders");boolean groupA=false,groupB=false;
            for(int i=0;i<groups.length();i++){JSONObject group=groups.getJSONObject(i);if(group.getString("sender").equals(senderA))groupA=group.getInt("count")==2&&group.getString("preview").equals(tag+" second");if(group.getString("sender").equals(senderB))groupB=group.getInt("count")==1;}
            check(groupA&&groupB,"Sender grouping failed");
            check(http("POST","/api/sms/delete",deletion("0000",first,second),null,403).getString("error").equals("wrong_code"),"Wrong code not rejected");
            http("POST","/api/sms/delete",deletion("",first),null,403);
            http("POST","/api/sms/delete",deletion("3412",first),"http://example.invalid",403);
            http("POST","/api/sms/delete",deletion("3412",-1),null,400);
            http("POST","/api/sms/delete",deletion("3412"),null,400);
            http("GET","/api/sms/delete",null,null,404);
            check(exists(store,first)&&exists(store,second)&&exists(store,third),"Rejected deletion changed messages");
            JSONObject removed=http("POST","/api/sms/delete",deletion("3412",first,second,first),null,200);
            check(removed.getInt("deleted")==2&&!exists(store,first)&&!exists(store,second)&&exists(store,third),"Bulk selection deletion failed");
            check(live.get(0).await("messages").getLong("revision")>addedEvent.getLong("revision"),"Deletion push missing");
            store.add(senderA,tag+" first",stamp,stamp,"system");store.add(senderA,tag+" second",stamp+1,stamp+1,"received");
            check(store.list(0,100,senderA).getInt("total")==0,"Deleted SMS reappeared during reimport");
            check(http("POST","/api/sms/delete",deletion("3412",first,second),null,200).getInt("deleted")==0,"Deletion retry not idempotent");
            DeleteGuard guard=new DeleteGuard();check(guard.check("test-peer","3412")==0,"Correct code rejected");
            for(int i=0;i<4;i++)check(guard.check("test-peer","0000")==-1,"Early rate limit");
            check(guard.check("test-peer","0000")>0&&guard.check("test-peer","3412")>0,"Brute-force limit missing");check(guard.check("other-peer","3412")==0,"Rate limit leaked across peers");
            for(Long id:existing)check(exists(store,id),"Pre-existing message changed");
            result.putString("status","passed"); result.putString("tests","multipart Unicode, duplicate suppression, sender grouping/filter/pagination, HTTP JSON, wrong/missing code, origin rejection, invalid IDs, bulk deletion, idempotent retry, reimport suppression, rate limit, WebSocket handshake/insert/delete push, five simultaneous sockets with HTTP available, network status schema, WebSocket Origin rejection");result.putInt("existing_messages_preserved",existing.size());
        } catch(Throwable ex) { code=0;result.putString("status","FAILED");result.putString("error",ex.toString()); }
        finally {
            for(LiveClient client:live)try{client.close();}catch(Exception ignored){}
            Cursor cleanup=store.getReadableDatabase().query("messages",new String[]{"_id","message_key"},"(body=? AND source=?) OR sender=? OR sender=?",new String[]{body,"received",senderA,senderB},null,null,null);
            java.util.ArrayList<Long> cleanupIds=new java.util.ArrayList<Long>();
            try{while(cleanup.moveToNext()){cleanupIds.add(cleanup.getLong(0));keys.add(cleanup.getString(1));}}finally{cleanup.close();}
            store.delete(cleanupIds);
            for(String key:keys)store.getWritableDatabase().delete("deleted_messages","message_key=?",new String[]{key});
            result.putString("cleanup","Removed only synthetic QA messages and their tombstones");
        }
        finish(code,result);
    }
}
