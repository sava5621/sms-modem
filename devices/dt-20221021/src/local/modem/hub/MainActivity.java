package local.modem.hub;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
public class MainActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);
  android.content.SharedPreferences.Editor e=getSharedPreferences("hub",0).edit().putBoolean("enabled",true);
  if(getIntent().hasExtra("apply_configuration"))e.putBoolean("configurationEnabled",getIntent().getBooleanExtra("apply_configuration",false));
  if(getIntent().hasExtra("apply_security"))e.putBoolean("securityEnabled",getIntent().getBooleanExtra("apply_security",false));
  e.commit();startService(new Intent(this,HubService.class));finish();
 }
}
