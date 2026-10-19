package local.modem.hub;
import android.app.Activity;import android.os.Bundle;import android.util.Log;
/** USB ADB recovery only; restores the pre-hardening flags and firewall without stopping the modem. */
public class PolicyRollbackActivity extends Activity {
 public void onCreate(Bundle b){super.onCreate(b);new Thread(new Runnable(){public void run(){try{new Uf7Policy(PolicyRollbackActivity.this).restore();Log.i("ModemHub","UF7 policy restored");}catch(Exception e){Log.e("ModemHub","Policy restore failed",e);}}},"policy-restore").start();finish();}
}
