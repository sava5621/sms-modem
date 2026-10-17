package local.modem.sms;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.HashMap;

/** The deletion code is verified on the modem, never in the page. */
public final class DeleteGuard {
    private static final String EXPECTED = "f23d089e7f2e05f6fe745bf632b49aa89d9b9abb01bfe905a9c6f64a554ae57e";
    private final HashMap<String,Attempt> failures = new HashMap<String,Attempt>();
    private static final class Attempt { int count; long blockedUntil; long last; }
    /** 0 = accepted; -1 = wrong code; positive = retry delay in seconds. */
    public synchronized int check(String peer, String code) throws Exception {
        long now=SystemClock.elapsedRealtime(); Attempt attempt=failures.get(peer);
        if(attempt!=null && attempt.blockedUntil>now)return (int)((attempt.blockedUntil-now+999)/1000);
        if(attempt!=null && now-attempt.last>60000){failures.remove(peer);attempt=null;}
        byte[] hash=MessageDigest.getInstance("SHA-256").digest((code==null?"":code).getBytes("UTF-8"));
        int different=0;
        for(int i=0;i<hash.length;i++) different|=(hash[i]&255)^Integer.parseInt(EXPECTED.substring(i*2,i*2+2),16);
        if(different==0){failures.remove(peer);return 0;}
        if(attempt==null){attempt=new Attempt();failures.put(peer,attempt);}
        attempt.last=now;attempt.count++;
        if(attempt.count>=5){attempt.blockedUntil=now+60000;attempt.count=0;return 60;}
        return -1;
    }
}
