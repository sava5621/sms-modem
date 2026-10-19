package local.modem.hub;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i) {if(c.getSharedPreferences("hub",0).getBoolean("enabled",false))c.startService(new Intent(c,HubService.class));}
}
