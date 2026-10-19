package local.modem.hub;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.TextView;

/** The vendor LightsService unconditionally launches HOME after APN restoration. */
public final class HomeActivity extends Activity {
 public void onCreate(Bundle state){
  super.onCreate(state);
  TextView text=new TextView(this);text.setText("Modem Hub\nhttp://192.168.100.91:8088/");setContentView(text);
  if(getSharedPreferences("hub",0).getBoolean("enabled",false))startService(new Intent(this,HubService.class));
 }
}
