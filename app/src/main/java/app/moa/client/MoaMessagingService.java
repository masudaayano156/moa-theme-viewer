package app.moa.client;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** No WebView, source cookies, or long-running work is used by the receiver. */
public final class MoaMessagingService extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        // The relay sends data-only payloads. Remote titles and content are never displayed.
        if (message.getNotification() != null) return;
        if ("registration".equals(message.getData().get("kind"))) {
            PushRelayClient.acceptRegistrationMessage(this, message.getData());
            return;
        }
        PushNotifications.show(this, message.getData());
    }

    @Override public void onNewToken(String token) {
        if (PushClient.acceptToken(this, token)) PushRelayClient.refreshToken(this, token);
    }
}
