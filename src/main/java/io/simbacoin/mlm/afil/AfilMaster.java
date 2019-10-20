package io.simbacoin.mlm.afil;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.persistence.AbstractPersistentActorWithTimers;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import com.google.cloud.firestore.EventListener;
import com.google.cloud.firestore.*;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.grpc.AfilProtos;
import io.simbacoin.mlm.grpc.AfilProtos.UpsertProfile;
import io.simbacoin.mlm.grpc.SimbaProtos;
import io.simbacoin.mlm.grpc.SimbaProtos.*;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.simbacoin.mlm.Utils.*;

public class AfilMaster extends AbstractPersistentActorWithTimers {
    private static final Object TICK_KEY = "MasterTickKey";
    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
    private final ActorRef afilRegion;
    private final int snapshotInterval;
    private final Firestore db;
    private final FiniteDuration workTimeout;
    private final String scUid;
    private ListenerRegistration listener;
    private List<Task> tasks = new ArrayList<>();
    private Map<String, TaskExecState> taskStates = new HashMap<>();
    private AfilMasterState state = new AfilMasterState();
    public AfilMaster(String scUId, Firestore db, ActorRef afilRegion, FiniteDuration workTimeout, int snapshotInterval) {
        this.scUid = scUId;
        this.afilRegion = afilRegion;
        this.snapshotInterval = snapshotInterval;
        this.workTimeout = workTimeout;
        this.db = db;
    }

    public static Props props(String scUid, Firestore db, ActorRef afilRegion, int snapshotInterval) {
        return Props.create(AfilMaster.class, () -> new AfilMaster(scUid, db, afilRegion, scala.concurrent.duration.Duration.create(10, TimeUnit.SECONDS), snapshotInterval));
    }

    public static Props props(String scUid, Firestore db, ActorRef afilRegion, FiniteDuration workTimeout, int snapshotInterval) {
        return Props.create(AfilMaster.class, () -> new AfilMaster(scUid, db, afilRegion, workTimeout, snapshotInterval));
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        timers().startPeriodicTimer(TICK_KEY, new Tick(), Duration.create(5, TimeUnit.SECONDS));
        attachListener();
    }

    @Override
    public void postStop() throws Exception {
        timers().cancel(TICK_KEY);
        listener.remove();
        super.postStop();
    }

    @Override
    public String persistenceId() {
        return "master";
    }

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder().match(AfilProtos.AfilDomainEvent.class, state::updated)
                .match(SnapshotOffer.class, so -> state = (AfilMasterState) so.snapshot()).build();
    }

    @Override
    public Receive createReceive() {
        tasks.clear();
        return receiveBuilder().match(AfilProtos.InitAfilRegistration.class, this::onInitAfilRegistration).match(Ack.class, this::onAck)
                .match(Tick.class, tk -> onTick()).match(SaveSnapshotSuccess.class, this::onSaveSnapshot).build();
    }

    private void onAck(Ack ack) {
        persist(buildEvent(ack.getId(), Any.pack(Acked.newBuilder().setId(ack.getId()).build())),
                (AfilProtos.AfilDomainEvent event) -> {
                    state = state.updated(event);
                    log.info("Task acked: {}", ack.getId());
                    taskStates.remove(ack.getId());
                });
    }

    private void onInitAfilRegistration(AfilProtos.InitAfilRegistration cmd) {
        List<SimbaProtos.Referee> refs = new ArrayList<>();
        long regNum = state.getCurrRegNo();
        String id = null;
        ZonedDateTime now = now();
        for (SimbaProtos.Affiliate afil : cmd.getAfilsList()) {
            id = id();
            SimbaProtos.Referee ref = state.getRef(afil.getId());
            if (ref == null) {
                regNum = Long.sum(regNum, 1l);
                afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(afil).setRegNo(regNum)
                        .setApl(afil.getParent().getUid().equals(scUid) ? 1 : Long.sum(state.getRef(afil.getParent().getUid()).getApl(), 1))
                        .build();
                refs.add(refFromAfil(afil));
            } else {
                // NB:: Careful with the fields been updated here
                //TODO :::: RnD
                afil = updateAfil(afil, ref);
                ref = refFromAfil(afil);
                refs.add(ref);
            }
            tasks.add(Task.newBuilder().setId(id).setEid(ref.getUid())
                    .setAction(Any.pack(buildSimbaCommand(id, afil.getId(), Any.pack(UpsertProfile.newBuilder().setAffiliate(afil).build())))).build());
            taskStates.put(id, new TaskExecState(id, 0, workTimeout.fromNow()));

        }

        persist(buildEvent(id(),
                Any.pack(AfilProtos.AfilRegistrationInitialized.newBuilder().addAllRefs(refs).setRegNo(regNum).build())),
                (AfilProtos.AfilDomainEvent event) -> {
                    state = state.updated(event);
                    log.info("Affiliates registration snapshot: {} - {}", event.getId(), refs);
                    processTasks();
                });

    }

    private void onSaveSnapshot(SaveSnapshotSuccess cmd) {
        persist(buildEvent(id(),
                Any.pack(SnapshotSaved.newBuilder().setSnapshotSequenceNr(cmd.metadata().sequenceNr()).build())),
                (AfilProtos.AfilDomainEvent event) -> {
                    state = state.updated(event);
                    log.info("Snapshot saved: {}", state.getSnapshotSequenceNr());
                });
    }

    private void onTick() {
        for (Iterator<Map.Entry<String, TaskExecState>> iter = taskStates.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<String, TaskExecState> entry = iter.next();
            if (entry.getValue().deadline.isOverdue()) {
                taskStates.put(entry.getKey(), entry.getValue().copyWithRetries(entry.getValue().numRetries + 1)).copyWithTimeout(workTimeout.fromNow());
                log.warning("Register afil task timed out: {} - {}", entry.getKey(), entry.getValue().numRetries + 1);
                tasks.add(state.getTask(entry.getKey()));
            }
        }

        if (!tasks.isEmpty())
            processTasks();

        if (Long.sum(lastSequenceNr(), -state.getSnapshotSequenceNr()) >= snapshotInterval) {
            saveSnapshot(state.copy());
        }
    }

    private void processTasks() {
        for (Task task : tasks) {
            try {
                if (task.getAction().is(SimbaProtos.SimbaCoinCommand.class)) {
                    afilRegion.tell(task.getAction().unpack(SimbaProtos.SimbaCoinCommand.class), getSelf());
                }
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
    }

    private AfilProtos.AfilDomainEvent buildEvent(String id, Any event) {
        return AfilProtos.AfilDomainEvent.newBuilder().setId(id).setEvent(event).addAllTasks(tasks).build();
    }

    private void attachListener() {
        listener = db.collection("profiles").whereEqualTo("reg_step", 3)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(QuerySnapshot snapshots, FirestoreException error) {

                        if (error != null) {
                            log.error("Affiliates listener failed: {} - {}", error.getCode(), error.getReason());
                            throw new IllegalArgumentException(error.getMessage());
                        }

                        List<SimbaProtos.Affiliate> afils = new ArrayList<>();

                        for (DocumentChange dc : snapshots.getDocumentChanges()) {
                            DocumentSnapshot snap = dc.getDocument();
                            log.info("Affiliates snapshot listener: {} - {} - {}", dc.getType(), snap.getId(),
                                    snap.getData());

                            switch (dc.getType()) {
                                case ADDED:
                                    afils.add(fromSnapshot(snap));
                                    break;
                                case MODIFIED:
                                    //TODO - Investigate how changes occur...
                                    // tasks.add(AffiliateSnapshot.newBuilder().setTid(id()).setUid(snap.getId())
                                    // .setAfil(fromSnapshot(snap)).setDocChangeType(DocChangeType.Modified).build());
                                    break;
                                case REMOVED:
                                    // tasks.add(AffiliateSnapshot.newBuilder().setTid(id()).setUid(snap.getId())
                                    // .setDocChangeType(DocChangeType.Removed).build());
                                    break;
                                default:
                                    // TODO
                                    log.error("Unhandled document change type: Affiliates listener: {} - {} - {}",
                                            dc.getType(), snap.getId(), snap.getData());
                                    break;
                            }
                        }
                        getSelf().tell(AfilProtos.InitAfilRegistration.newBuilder().addAllAfils(afils).build(), getSelf());
                    }
                });
    }

    private SimbaProtos.Affiliate fromSnapshot(DocumentSnapshot snap) {
        Map<String, Object> map = (Map<String, Object>) snap.getData().get("referee");
        SimbaProtos.Referee ref = SimbaProtos.Referee.newBuilder().setUid(map.get("uid") == null ? scUid : map.get("uid").toString())
                .setLeg(Leg.forNumber(Integer.parseInt(map.get("leg").toString()))).setIat(map.get("iat") != null ? map.get("iat").toString() : now().toString())
                .build();
        Location location = Location.newBuilder().setCountry(snap.getData().get("country").toString())
                .setCountryCode(snap.getData().get("country_code").toString())
                .setCity(snap.getData().get("city").toString()).setTown(snap.getData().get("town").toString())
                .setStreet(snap.getData().get("street").toString()).build();

        return SimbaProtos.Affiliate.newBuilder().setId(snap.getId()).setEmail(snap.getData().get("email").toString())
                .setName(snap.getData().get("name").toString()).setMsisdn(snap.getData().get("msisdn").toString())
                .setDob(snap.getData().get("dob").toString()).setLocation(location)
                .setRegStep(SimbaProtos.RegStep.forNumber(Integer.parseInt(snap.getData().get("reg_step").toString())))
                .setIat(snap.getData().get("iat") != null ? snap.getData().get("iat").toString() : now().toString()).setParent(ref).build();

    }

    private static final class Tick {
    }

}
