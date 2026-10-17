package local.modem.hub;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;

/** ADB-only network-mode selection through this firmware's inspected Qualcomm FeatureService. */
public final class UfiRadioActivity extends Activity {
    private boolean bound;
    private int mode;
    private final Handler handler=new Handler();
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName component,IBinder binder) {
            Parcel request=Parcel.obtain(),response=Parcel.obtain();
            try {
                request.writeInterfaceToken("com.qualcomm.qti.phonefeature.IServiceBinder");
                request.writeInt(0);request.writeInt(mode);request.writeInt(0);
                if(!binder.transact(3,request,response,0))throw new IllegalStateException("Unsupported FeatureService");
                response.readException();Log.i("SmsUfiRadio","Requested standard network mode "+mode);
            } catch(Exception ex) { Log.w("SmsUfiRadio","Mode change failed: "+ex.getClass().getSimpleName()); }
            finally {request.recycle();response.recycle();}
        }
        public void onServiceDisconnected(ComponentName component) { }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);mode=getIntent().getIntExtra("mode",-1);
        if(mode!=0&&mode!=1&&mode!=2&&mode!=9&&mode!=12){finish();return;}
        if(!getSharedPreferences("sms-radio",0).contains("originalMode"))getSharedPreferences("sms-radio",0).edit()
            .putInt("originalMode",Settings.Global.getInt(getContentResolver(),"preferred_network_mode",12)).commit();
        Intent intent=new Intent().setComponent(new ComponentName("com.qualcomm.qti.phonefeature","com.qualcomm.qti.phonefeature.FeatureService"));
        bound=bindService(intent,connection,BIND_AUTO_CREATE);
        handler.postDelayed(new Runnable(){public void run(){finish();}},5000);
    }
    @Override public void onDestroy() {handler.removeCallbacksAndMessages(null);if(bound)unbindService(connection);super.onDestroy();}
}
