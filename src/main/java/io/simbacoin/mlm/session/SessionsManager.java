package io.simbacoin.mlm.session;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.EventListener;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.protobuf.Any;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.persistence.AbstractPersistentActorWithTimers;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import io.simbacoin.mlm.grpc.SessionProtos.Device;
import io.simbacoin.mlm.grpc.SessionProtos.EndSession;
import io.simbacoin.mlm.grpc.SessionProtos.Session;
import io.simbacoin.mlm.grpc.SessionProtos.SessionDomainEvent;
import io.simbacoin.mlm.grpc.SessionProtos.SessionTask;
import io.simbacoin.mlm.grpc.SessionProtos.SessionsUpserted;
import io.simbacoin.mlm.grpc.SessionProtos.UpsertSession;
import io.simbacoin.mlm.grpc.SessionProtos.UpsertSessions;
import io.simbacoin.mlm.grpc.SimbaProtos.Ack;
import io.simbacoin.mlm.grpc.SimbaProtos.Acked;
import io.simbacoin.mlm.grpc.SimbaProtos.SimbaCoinCommand;
import io.simbacoin.mlm.grpc.SimbaProtos.SnapshotSaved;

public class SessionsManager extends AbstractPersistentActorWithTimers {

    public static Props props(ActorRef afilProxy, Firestore db, int snapshotInterval) {
        return Props.create(SessionsManager.class, () -> new SessionsManager(afilProxy, db, snapshotInterval));
    }

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

    private final Object TICK_KEY = "SessionsManagerKey";

    private static final class Tick {
    }

    private final Firestore firestore;
    private final ActorRef affiliateProxy;
    private final int snapshotInterval;
    private SessionsState state = new SessionsState();

    private ListenerRegistration listener = null;

    public SessionsManager(ActorRef affiliateProxy, Firestore firestore, int snapshotInterval) {
        this.firestore = firestore;
        this.affiliateProxy = affiliateProxy;
        this.snapshotInterval = snapshotInterval;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();

        timers().startPeriodicTimer(TICK_KEY, new Tick(), Duration.ofSeconds(5));

        attachSessionsListener();
    }

    @Override
    public void postStop() throws Exception {
        if (listener != null)
            listener.remove();
        super.postStop();
    }

    @Override
    public String persistenceId() {
        return "SessionsManager";
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder().match(UpsertSessions.class, this::onUpsertSessions).match(Ack.class, this::onAck)
                .match(Tick.class, tick -> onTick()).match(SaveSnapshotSuccess.class, this::onSaveSnapshotSuccess)
                .build();
    }

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder().match(SessionDomainEvent.class, state::updated)
                .match(SnapshotOffer.class, so -> state = (SessionsState) so.snapshot()).build();
    }

    private List<SessionTask> tasks = new ArrayList<>();

    private void onUpsertSessions(UpsertSessions cmd) {
        tasks.clear();

        for (Iterator<Map.Entry<String, String>> iter = cmd.getInactiveSidsMap().entrySet().iterator(); iter
                .hasNext(); ) {
            Map.Entry<String, String> entry = iter.next();
            String id = getId();
            tasks.add(SessionTask.newBuilder().setId(id).setSid(entry.getKey()).setIsActive(false)
                    .setIat(ZonedDateTime.now().toString()).setUid(entry.getValue()).build());
        }

        cmd.getSessionsList().stream().forEach(s -> {
            String id = getId();
            tasks.add(SessionTask.newBuilder().setId(id).setSid(s.getId()).setUid(s.getUid()).setIsActive(true)
                    .setIat(ZonedDateTime.now().toString()).build());
        });

        persist(buildEvent(cmd.getId(),
                Any.pack(buildSessionsUpserted(cmd.getSessionsList(), cmd.getInactiveSidsMap().keySet())), tasks),
                (SessionDomainEvent event) -> {
                    state = state.updated(event);
                    log.info("Sessions upserted: {}: {}", cmd.getSessionsList(), cmd.getInactiveSidsMap());
                    processTasks(event.getTasksList());
                });

    }

    private void onAck(Ack ack) {
        tasks.clear();
        if (state.isTaskExecuted(ack.getId())) {
            persist(buildEvent(ack.getId(), Any.pack(Acked.newBuilder().setId(ack.getId()).build()), tasks),
                    (SessionDomainEvent event) -> {
                        state = state.updated(event);
                        log.info("Session task executed: {}", ack.getId());
                    });
        }
    }

    private void onSaveSnapshotSuccess(SaveSnapshotSuccess cmd) {
        persist(buildEvent(UUID.randomUUID().toString(),
                Any.pack(SnapshotSaved.newBuilder().setSnapshotSequenceNr(cmd.metadata().sequenceNr()).build()),
                new ArrayList<>()), (SessionDomainEvent event) -> {
            state = state.updated(event);
            log.info("Snapshot saved: {}", state.getSnapshotSequenceNr());
        });
    }

    private void onTick() {
        tasks.clear();
        for (SessionTask task : state.getTasks().values()) {
            if (ZonedDateTime.parse(task.getIat()).plusSeconds(10l).isBefore(ZonedDateTime.now())) {
                tasks.add(task);
            }
        }

        if (!tasks.isEmpty()) {
            processTasks(tasks);
        }

        if (lastSequenceNr() - state.getSnapshotSequenceNr() >= snapshotInterval) {
            saveSnapshot(state.copy());
        }
    }

    private SessionsUpserted buildSessionsUpserted(List<Session> sessions, Set<String> inactiveSids) {
        return SessionsUpserted.newBuilder().addAllSessions(sessions).addAllInactiveSids(inactiveSids).build();
    }

    private SessionDomainEvent buildEvent(String id, Any event, List<SessionTask> tasks) {
        return SessionDomainEvent.newBuilder().setId(id).setEvent(event).addAllTasks(tasks).build();
    }

    private String getId() {
        return UUID.randomUUID().toString();
    }

    private void processTasks(List<SessionTask> tasks) {
        tasks.stream().forEach(t -> {
            if (t.getIsActive()) {
                tellAffiliate(buildSimbaCoinCommand(t.getId(), t.getUid(),
                        Any.pack(UpsertSession.newBuilder().setSession(state.getSession(t.getSid())).build()),
                        t.getIat()));
            } else {
                tellAffiliate(buildSimbaCoinCommand(t.getId(), t.getUid(),
                        Any.pack(EndSession.newBuilder().setSid(t.getSid()).build()), t.getIat()));
            }
        });
    }

    private void tellAffiliate(SimbaCoinCommand command) {
        affiliateProxy.tell(command, getSelf());
    }

    private SimbaCoinCommand buildSimbaCoinCommand(String id, String eid, Any action, String iat) {
        return SimbaCoinCommand.newBuilder().setId(id).setEid(eid).setAction(action).setIat(iat).build();
    }

    private void attachSessionsListener() {
        log.info("Attaching Sessions collection listener . . .");
        listener = firestore.collection("sessions").whereEqualTo("is_active", true)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {

                    @Override
                    public void onEvent(QuerySnapshot snapshots, FirestoreException error) {
                        if (error != null) {
                            log.error("Sessions firestore listener failed: {}", error);
                            return;
                        }

                        Set<String> sids = new HashSet<>();
                        List<Session> sessions = new ArrayList<>();

                        for (DocumentSnapshot snapshot : snapshots) {
                            log.info("Session snapshot: {} - {}", snapshot.getId(), snapshot.getData());

                            Session session = state.getSession(snapshot.getId());
                            if (session == null || (session != null
                                    && session.getFcmToken().equals(snapshot.getString("fcm_token")))) {

                                sids.add(snapshot.getId());

                                session = Session.newBuilder().setId(snapshot.getId())
                                        .setUid(snapshot.getData().get("uid").toString())
                                        .setDevice(Device.newBuilder().setId(snapshot.getString("device_id"))
                                                .setOs(snapshot.getString("device_os"))
                                                .setModel(snapshot.getString("device_model"))
                                                .setType(snapshot.getString("device_type")).build())
                                        // .setLatLng(LatLng.newBuilder().setLat(snapshot.getDouble("lat"))
                                        // .setLng(snapshot.getDouble("lng")).build())
                                        .setFcmToken(snapshot.getString("fcm_token")).setIat(snapshot.getString("iat"))
                                        .setLat(snapshot.getString("iat")).build();

                                sessions.add(session);

                            }

                        }

                        Set<String> currSids = state.getSessions().keySet();
                        currSids.removeAll(sids);

                        getSelf().tell(UpsertSessions.newBuilder().addAllSessions(sessions)
                                .putAllInactiveSids(state.getSessionUIDs(currSids)).build(), getSelf());
                    }
                });
    }

}
