package local.modem.sms;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startService(new Intent(this, SmsService.class));
        TextView text = new TextView(this);
        text.setText("SMS modem\nhttp://192.168.100.1:8088/\nService is running.");
        text.setTextSize(20);
        text.setPadding(24, 24, 24, 24);
        setContentView(text);
    }
}
