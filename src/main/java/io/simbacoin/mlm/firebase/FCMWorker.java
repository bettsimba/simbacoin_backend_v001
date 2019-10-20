package io.simbacoin.mlm.firebase;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import io.simbacoin.mlm.grpc.FireProtos.PublishFCM;
import io.simbacoin.mlm.grpc.SimbaProtos.Ack;

public class FCMWorker extends AbstractActor {

    public static Props props() {
        return Props.create(FCMWorker.class, () -> new FCMWorker());
    }

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

    public FCMWorker() {

    }

    @Override
    public Receive createReceive() {
        return receiveBuilder().match(PublishFCM.class, this::onPublishFCM).build();
    }

    private void onPublishFCM(PublishFCM req) {
        try {
            Message message = Message.builder().setToken(req.getAccessToken()).putAllData(req.getDataMap())
                    .setNotification(new Notification(req.getTitle(), req.getBody())).build();

            String response = FirebaseMessaging.getInstance().send(message);
            log.info("FCM Published {} - {}", req.getId(), response);

        } catch (FirebaseMessagingException e) {
            log.error("FCM exception: {} - {}", e.getErrorCode(), e);
            // TODO error type / reason
            // invalid-registration-token
            // registration-token-not-registered (was valid but has been unregistered)
            // unknown-error / internal-error / server-unavailable (retry)
            log.error("FCM exception: {} - {}", e.getErrorCode(), e);

            switch (e.getErrorCode()) {
                // TODO
            }
        }

        // TODO: refactor to respond with appropriate response...e.g. on error
        getSender().tell(Ack.newBuilder().setId(req.getId()).build(), getSelf());
    }

}
