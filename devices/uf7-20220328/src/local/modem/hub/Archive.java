package local.modem.hub;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.*;
final class Archive {
 static int count(SQLiteDatabase db,String sql,String[] args){Cursor q=db.rawQuery(sql,args);try{q.moveToFirst();return q.getInt(0);}finally{q.close();}}
 static JSONObject counts(Context c,Calls calls)throws Exception{SQLiteDatabase sms=SmsStore.get(c).getReadableDatabase(),phone=calls.getReadableDatabase();return new JSONObject().put("smsTotal",count(sms,"SELECT COUNT(*) FROM messages",null)).put("smsUnread",count(sms,"SELECT COUNT(*) FROM messages WHERE is_read=0",null)).put("callsTotal",count(phone,"SELECT COUNT(*) FROM calls",null)).put("unheard",count(phone,"SELECT COUNT(*) FROM calls WHERE file IS NOT NULL AND listened=0",null));}
 static JSONObject contacts(HubService h,int offset,int limit,String search,boolean unread)throws Exception{
  Map<String,JSONObject> map=new HashMap<String,JSONObject>();JSONArray[] groups={SmsStore.get(h).contactStats(search),h.calls.contactStats(search)};
  for(JSONArray a:groups)for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);String key=j.getString("contactKey");JSONObject existing=map.get(key);if(existing==null){existing=new JSONObject().put("contactKey",key).put("sender",j.optString("sender","")).put("smsCount",0).put("unreadCount",0).put("callCount",0).put("unheardCount",0).put("receivedAt",0).put("matches",false);map.put(key,existing);}for(String k:new String[]{"smsCount","unreadCount","callCount","unheardCount"})if(j.has(k))existing.put(k,j.getInt(k));if(j.getLong("receivedAt")>=existing.getLong("receivedAt"))existing.put("receivedAt",j.getLong("receivedAt")).put("sender",j.optString("sender",""));existing.put("matches",existing.getBoolean("matches")||j.getBoolean("matches"));}
  List<JSONObject> sorted=new ArrayList<JSONObject>();for(JSONObject j:map.values())if(j.getBoolean("matches")&&(!unread||j.getInt("unreadCount")+j.getInt("unheardCount")>0))sorted.add(j);
  Collections.sort(sorted,new Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){long x=a.optLong("receivedAt"),y=b.optLong("receivedAt");return x==y?a.optString("contactKey").compareTo(b.optString("contactKey")):x>y?-1:1;}});
  JSONArray result=new JSONArray();for(int i=offset;i<Math.min(sorted.size(),offset+limit);i++){JSONObject j=sorted.get(i);JSONArray messages=SmsStore.get(h).listFiltered(0,1,j.getString("contactKey"),"",false).getJSONArray("messages"),calls=h.calls.listFiltered(0,1,j.getString("contactKey"),"",false).getJSONArray("calls");String preview="";if(messages.length()>0)preview=messages.getJSONObject(0).optString("body");if(calls.length()>0&&(messages.length()==0||calls.getJSONObject(0).optLong("started")>=messages.getJSONObject(0).optLong("receivedAt")))preview=calls.getJSONObject(0).optString("file","").isEmpty()?"Входящий звонок":"Запись звонка";if(calls.length()>0&&!calls.getJSONObject(0).optString("file","").isEmpty())j.put("latestRecording",calls.getJSONObject(0));j.put("preview",preview);j.put("count",j.getInt("smsCount"));result.put(j);}
  return new JSONObject().put("senders",result).put("total",sorted.size()).put("hasMore",offset+result.length()<sorted.size()).put("counts",counts(h,h.calls)).put("revision",Events.revision());
 }
 static JSONObject timeline(HubService h,String key,int offset,int limit,String search,boolean unread)throws Exception{
  JSONObject sms=SmsStore.get(h).listFiltered(0,offset+limit,key,search,unread),calls=h.calls.listFiltered(0,offset+limit,key,search,unread);List<JSONObject> list=new ArrayList<JSONObject>();
  for(String kind:new String[]{"messages","calls"}){JSONArray a=(kind.equals("messages")?sms:calls).getJSONArray(kind);for(int i=0;i<a.length();i++)list.add(a.getJSONObject(i));}
  Collections.sort(list,new Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){long x=a.optLong("receivedAt",a.optLong("started")),y=b.optLong("receivedAt",b.optLong("started"));if(x!=y)return x>y?-1:1;return (a.optString("kind")+a.optLong("id",a.optLong("_id"))).compareTo(b.optString("kind")+b.optLong("id",b.optLong("_id")));}});
  JSONArray result=new JSONArray();for(int i=offset;i<Math.min(list.size(),offset+limit);i++)result.put(list.get(i));int total=sms.getInt("total")+calls.getInt("total");return new JSONObject().put("items",result).put("total",total).put("hasMore",offset+result.length()<total).put("counts",counts(h,h.calls));
 }
}
