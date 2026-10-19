package local.modem.hub;
import android.content.Context;
import android.os.SystemClock;
import android.telephony.*;
import org.json.JSONObject;

final class NetworkStatus {
 private final TelephonyManager phone;
 private volatile ServiceState service;
 private volatile SignalStrength signal;
 private volatile long signalAt;
 NetworkStatus(Context c){phone=(TelephonyManager)c.getSystemService(Context.TELEPHONY_SERVICE);}
 private final PhoneStateListener listener=new PhoneStateListener(){
  public void onServiceStateChanged(ServiceState s){service=new ServiceState(s);}
  public void onSignalStrengthsChanged(SignalStrength s){signal=s;signalAt=SystemClock.elapsedRealtime();}
 };
 void start(){phone.listen(listener,PhoneStateListener.LISTEN_SERVICE_STATE|PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);}
 void stop(){phone.listen(listener,PhoneStateListener.LISTEN_NONE);}
 boolean registered(){ServiceState s=service;if(s==null)return false;if(s.getState()==ServiceState.STATE_IN_SERVICE)return true;try{return ((Integer)ServiceState.class.getMethod("getDataRegState").invoke(s))==ServiceState.STATE_IN_SERVICE;}catch(Exception e){return false;}}
 JSONObject snapshot()throws Exception {
  JSONObject j=new JSONObject();ServiceState s=service;SignalStrength strength=signal;
  int voice=s==null?-1:s.getState(),data=-1;
  if(s!=null)try{data=((Integer)ServiceState.class.getMethod("getDataRegState").invoke(s)).intValue();}catch(Exception ignored){}
  j.put("voiceRegistration",voice);j.put("dataRegistration",data);j.put("simOperator",phone.getSimOperatorName());j.put("networkOperator",phone.getNetworkOperatorName());j.put("networkType",phone.getNetworkType());j.put("roaming",s!=null&&s.getRoaming());
  boolean known=false;
  if(strength!=null){
   int rsrp=metric(strength,"getLteRsrp"),asu=strength.getGsmSignalStrength();
   if(rsrp>=-140&&rsrp<=-43){
    known=true;j.put("signalDbm",rsrp);j.put("signalMetric","RSRP");j.put("signalTechnology","LTE");
    int rsrq=metric(strength,"getLteRsrq"),snr=metric(strength,"getLteRssnr");
    if(rsrq>=-34&&rsrq<=0)j.put("rsrqDb",rsrq);
    if(snr>=-200&&snr<=300)j.put("sinrDb",snr/10.0);
   }else if(strength.isGsm()&&asu>=0&&asu<=31){
    known=true;j.put("signalDbm",-113+2*asu);j.put("signalMetric","RSSI");j.put("signalTechnology","2G/3G");
   }
   if(known){int level=metric(strength,"getLevel");if(level>=0&&level<=4)j.put("signalLevel",level);}
  }
  j.put("signalKnown",known);j.put("signalAgeMs",signalAt==0?JSONObject.NULL:Long.valueOf(SystemClock.elapsedRealtime()-signalAt));
  return j;
 }
 private static int metric(SignalStrength s,String method){try{return ((Integer)SignalStrength.class.getMethod(method).invoke(s)).intValue();}catch(Exception e){return Integer.MAX_VALUE;}}
}
