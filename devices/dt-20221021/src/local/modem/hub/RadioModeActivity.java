package local.modem.hub;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.io.File;

/** ADB-only diagnostic using the inspected PhoneFeatures.apk Binder interface. */
public final class RadioModeActivity extends Activity {
 private boolean bound;
 private int mode;
 private final Handler handler=new Handler();
 private final ServiceConnection connection=new ServiceConnection(){
  public void onServiceConnected(ComponentName name,IBinder binder){
   Parcel input=Parcel.obtain(),output=Parcel.obtain();
   try{
    input.writeInterfaceToken("com.qualcomm.qti.phonefeature.IServiceBinder");
    input.writeInt(0);input.writeInt(mode);input.writeInt(0);
    // Transaction 3: setPreferredNetwork(sub, network, optional Message).
    if(!binder.transact(3,input,output,0))throw new IllegalStateException("Unsupported transaction");
    output.readException();Log.i("ModemHub","Requested radio mode "+mode+" through Qualcomm FeatureService");
   }catch(Exception e){Log.e("ModemHub","Radio mode failed",e);finish();}
   finally{input.recycle();output.recycle();}
  }
  public void onServiceDisconnected(ComponentName name){}
 };
 public void onCreate(Bundle state){
  super.onCreate(state);mode=getIntent().getIntExtra("mode",-1);
  if(mode!=9&&mode!=12&&mode!=0&&mode!=2){finish();return;}
  try{
   File backup=new File(getFilesDir(),"radio-mode-before.txt");
   if(!backup.exists())DeviceControl.write(backup.getAbsolutePath(),Settings.Global.getString(getContentResolver(),"preferred_network_mode"));
   Intent intent=new Intent().setComponent(new ComponentName("com.qualcomm.qti.phonefeature","com.qualcomm.qti.phonefeature.FeatureService"));
   bound=bindService(intent,connection,BIND_AUTO_CREATE);
   if(!bound)throw new IllegalStateException("FeatureService unavailable");
   handler.postDelayed(new Runnable(){public void run(){finish();}},15000);
  }catch(Exception e){Log.e("ModemHub","Radio mode setup failed",e);finish();}
 }
 public void onDestroy(){handler.removeCallbacksAndMessages(null);if(bound)unbindService(connection);super.onDestroy();}
}
