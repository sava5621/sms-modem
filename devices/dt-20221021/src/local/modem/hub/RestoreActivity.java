package local.modem.hub;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.util.Log;
public class RestoreActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);getSharedPreferences("hub",0).edit().putBoolean("enabled",false).commit();stopService(new Intent(this,HubService.class));new Thread(new Runnable(){public void run(){try{new DeviceControl(RestoreActivity.this).restore();Log.i("ModemHub","Original configuration restored; reboot recommended");}catch(Exception e){Log.e("ModemHub","Restore failed",e);}}}).start();finish();}
}
