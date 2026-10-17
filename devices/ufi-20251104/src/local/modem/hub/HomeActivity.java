package local.modem.hub;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Keeps the already selected HOME component available after replacing Modem Hub. */
public final class HomeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);startService(new Intent(this,SmsService.class));
        TextView view=new TextView(this);view.setText("SMS modem 2\nhttp://192.168.101.1:8088/");setContentView(view);
    }
}
