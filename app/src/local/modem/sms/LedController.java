package local.modem.sms;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** Only the mapped lamp channels; never SIM/ESIM GPIOs. Green and blue were visually verified. */
public final class LedController {
    private static final String RED="/sys/class/leds/4G_S/brightness";
    private static final String GREEN="/sys/class/leds/4G_L2/brightness";
    private static final String BLUE="/sys/class/leds/WIFI_S/brightness";
    private final LedPolicy policy=new LedPolicy();
    private final HandlerThread thread=new HandlerThread("sms-led");
    private final Handler handler;
    private final PowerManager.WakeLock wake;
    private volatile boolean stopped,hardwareAvailable;
    private boolean warned;
    private long awakeUntil;
    private final Runnable tick=new Runnable(){public void run(){
        if(stopped)return;
        long now=SystemClock.elapsedRealtime();
        LedPolicy.Color color=policy.color(now);
        // A short, bounded lock makes the five-second switch-off survive CPU sleep.
        if(color==LedPolicy.Color.GREEN) {
            long until=now+policy.remaining(now);
            if(until!=awakeUntil){wake.acquire(policy.remaining(now)+250);awakeUntil=until;}
        } else releaseWake();
        apply(color);
        long delay=color==LedPolicy.Color.GREEN?Math.min(250,Math.max(1,policy.remaining(SystemClock.elapsedRealtime()))):1000;
        handler.postDelayed(this,delay);
    }};
    public LedController(Context context) {
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SmsInbox:page-led");
        wake.setReferenceCounted(false);
        thread.start();handler=new Handler(thread.getLooper());
    }
    public void start() { schedule(); }
    public void network(JSONObject state) {
        policy.network(state.optBoolean("available"),state.optBoolean("simReady"),state.optBoolean("registered"),state.optString("simState"));
        schedule();
    }
    public void pageOpened() { policy.pageOpened(SystemClock.elapsedRealtime());schedule(); }
    private void schedule() {
        if(!stopped)handler.post(new Runnable(){public void run(){
            if(!stopped){handler.removeCallbacks(tick);tick.run();}
        }});
    }
    public JSONObject snapshot() throws Exception {
        long now=SystemClock.elapsedRealtime();
        return new JSONObject().put("color",policy.color(now).name()).put("fault",policy.fault())
            .put("visitRemainingMs",policy.remaining(now)).put("hardwareAvailable",hardwareAvailable);
    }
    private static void set(String path,boolean on) throws Exception {
        String current;
        FileInputStream input=new FileInputStream(path);
        try { byte[] value=new byte[16];int count=input.read(value);current=count<0?"":new String(value,0,count,"US-ASCII").trim(); } finally { input.close(); }
        if(current.equals(on?"255":"0"))return;
        FileOutputStream output=new FileOutputStream(path);
        try { output.write((on?"255\n":"0\n").getBytes("US-ASCII")); } finally { output.close(); }
    }
    private void apply(LedPolicy.Color color) {
        try {
            // Turn unused channels off first to avoid mixed colors on a transition.
            set(RED,false);
            if(color!=LedPolicy.Color.GREEN)set(GREEN,false);
            if(color!=LedPolicy.Color.BLUE)set(BLUE,false);
            if(color==LedPolicy.Color.GREEN)set(GREEN,true);
            if(color==LedPolicy.Color.BLUE)set(BLUE,true);
            hardwareAvailable=true;warned=false;
        } catch(Exception ex) {
            hardwareAvailable=false;
            if(!warned){Log.w("SmsLed","LED control unavailable: "+ex.getClass().getSimpleName());warned=true;}
        }
    }
    private void releaseWake() { if(wake.isHeld())wake.release();awakeUntil=0; }
    public void stop() {
        stopped=true;handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable(){public void run(){apply(LedPolicy.Color.OFF);releaseWake();thread.quitSafely();}});
    }
}
