package local.modem.wifioff;
import android.app.Activity;
import android.os.Bundle;
public final class EnableActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WifiOffReceiver.disable(this);
        finish();
    }
}
