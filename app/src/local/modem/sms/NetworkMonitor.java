package local.modem.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import org.json.JSONObject;

/** Observes radio registration, SIM and signal; never enables data or changes the radio. */
public final class NetworkMonitor {
    private final Context context;
    private final TelephonyManager phone;
    private final Handler handler=new Handler();
    private final Runnable changed;
    private volatile String snapshot="{}";
    private String signature="";
    private String lastError="";
    private int service=-1;
    private SignalStrength signal;
    private boolean stopped,receiverRegistered;
    private final Runnable refresh=new Runnable(){public void run(){publish();}};
    private final Runnable periodic=new Runnable(){public void run(){publish();if(!stopped)handler.postDelayed(this,10000);}};
    private final PhoneStateListener listener=new PhoneStateListener(){
        @Override public void onServiceStateChanged(ServiceState value) { service=value==null?-1:value.getState(); schedule(); }
        @Override public void onSignalStrengthsChanged(SignalStrength value) { signal=value; schedule(); }
    };
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context unused,Intent intent) { schedule(); }
    };
    public NetworkMonitor(Context context,Runnable changed) {
        this.context=context.getApplicationContext(); this.changed=changed;
        phone=(TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
    }
    public void start() {
        publish();
        try {
            if(phone!=null)phone.listen(listener,PhoneStateListener.LISTEN_SERVICE_STATE|PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            IntentFilter filter=new IntentFilter("android.intent.action.SIM_STATE_CHANGED");
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            context.registerReceiver(receiver,filter); receiverRegistered=true;
        } catch(Exception ex) { Log.w("SmsNetwork","Radio observer unavailable: "+ex.getClass().getSimpleName()); }
        handler.postDelayed(periodic,10000);
    }
    private void schedule() { if(!stopped){handler.removeCallbacks(refresh);handler.postDelayed(refresh,150);} }
    public JSONObject snapshot() throws Exception { return new JSONObject(snapshot); }
    private static Integer hidden(SignalStrength value,String method) {
        try { return (Integer)value.getClass().getMethod(method).invoke(value); } catch(Exception unavailable) { return null; }
    }
    private void publish() {
        if(stopped)return;
        try {
            int sim=phone==null?0:phone.getSimState();
            boolean ready=sim==TelephonyManager.SIM_STATE_READY,registered=ready&&service==ServiceState.STATE_IN_SERVICE;
            JSONObject data=new JSONObject();
            data.put("simState",simState(sim)); data.put("simPresent",sim==0?JSONObject.NULL:sim!=TelephonyManager.SIM_STATE_ABSENT);
            data.put("simReady",ready); data.put("registered",registered); data.put("serviceState",serviceState(service));
            data.put("operator",ready&&phone!=null?phone.getNetworkOperatorName():"");
            int type=ready&&phone!=null?phone.getNetworkType():0;
            data.put("technology",technology(type));
            Integer dbm=null,level=null; String metric="";
            if(registered&&signal!=null) {
                if(type==TelephonyManager.NETWORK_TYPE_LTE) {
                    Integer rsrp=hidden(signal,"getLteRsrp");
                    if(rsrp!=null&&rsrp>=-140&&rsrp<=-44){dbm=rsrp;metric="RSRP";}
                } else if(signal.isGsm()) {
                    int asu=signal.getGsmSignalStrength();
                    if(asu>=0&&asu<=31){dbm=-113+2*asu;metric="RSSI";}
                } else {
                    int value=signal.getCdmaDbm();
                    if(value>=-160&&value<=-20){dbm=value;metric="RSSI";}
                }
                level=hidden(signal,"getLevel");
                if(level!=null&&(level<0||level>4))level=null;
                // Unknown/sentinel dBm remains null rather than a fabricated measurement.
                if(dbm==null)level=null;
                if(level==null&&dbm!=null) {
                    int v=dbm;
                    level=type==TelephonyManager.NETWORK_TYPE_LTE?(v>=-95?4:v>=-105?3:v>=-115?2:1):(v>=-89?4:v>=-97?3:v>=-103?2:1);
                }
            }
            data.put("signalLevel",level==null?JSONObject.NULL:level); data.put("signalDbm",dbm==null?JSONObject.NULL:dbm);
            data.put("signalMetric",metric); data.put("available",phone!=null);
            saveSnapshot(data);lastError="";
        } catch(Exception ex) {
            String error=ex.getClass().getSimpleName();
            if(!error.equals(lastError)){Log.w("SmsNetwork","Radio snapshot unavailable: "+error);lastError=error;}
            try {
                saveSnapshot(new JSONObject().put("simState","UNKNOWN").put("simPresent",JSONObject.NULL).put("simReady",false)
                    .put("registered",false).put("serviceState","UNKNOWN").put("operator","").put("technology","")
                    .put("signalLevel",JSONObject.NULL).put("signalDbm",JSONObject.NULL).put("signalMetric","").put("available",false));
            } catch(Exception ignored) { }
        }
    }
    private void saveSnapshot(JSONObject data) throws Exception {
        String next=data.toString();
        if(!next.equals(signature)){signature=next;data.put("updatedAt",System.currentTimeMillis());snapshot=data.toString();changed.run();}
    }
    static String simState(int state) {
        switch(state){case 1:return "ABSENT";case 2:return "PIN_REQUIRED";case 3:return "PUK_REQUIRED";case 4:return "NETWORK_LOCKED";case 5:return "READY";case 6:return "NOT_READY";default:return "UNKNOWN";}
    }
    static String serviceState(int state) {
        switch(state){case 0:return "IN_SERVICE";case 1:return "OUT_OF_SERVICE";case 2:return "EMERGENCY_ONLY";case 3:return "POWER_OFF";default:return "UNKNOWN";}
    }
    static String technology(int type) {
        switch(type){case 1:return "GPRS";case 2:return "EDGE";case 3:return "UMTS";case 4:return "CDMA";case 5:return "EVDO";case 6:return "EVDO";case 7:return "1xRTT";case 8:return "HSDPA";case 9:return "HSUPA";case 10:return "HSPA";case 12:return "EVDO";case 13:return "LTE";case 14:return "eHRPD";case 15:return "HSPA+";default:return "";}
    }
    public void stop() {
        stopped=true; handler.removeCallbacksAndMessages(null);
        try { if(phone!=null)phone.listen(listener,PhoneStateListener.LISTEN_NONE); } catch(Exception ignored) { }
        if(receiverRegistered)context.unregisterReceiver(receiver);
    }
}
