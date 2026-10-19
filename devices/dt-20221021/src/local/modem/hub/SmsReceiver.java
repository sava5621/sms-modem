package local.modem.hub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences("hub",0).getBoolean("enabled",false)) return;
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) return;
            StringBuilder body = new StringBuilder();
            SmsMessage first = null;
            for (SmsMessage part : parts) {
                if (part == null) continue;
                if (first == null) first = part;
                if (part.getMessageBody() != null) body.append(part.getMessageBody());
            }
            if (first == null) return;
            SmsStore.get(context).add(first.getDisplayOriginatingAddress(), body.toString(), first.getTimestampMillis(), System.currentTimeMillis(), "received");
            context.startService(new Intent(context, HubService.class));
            Log.i("SmsInbox", "Incoming SMS saved");
        } catch (Exception ex) { Log.e("SmsInbox", "Unable to save incoming SMS", ex); }
    }
}
