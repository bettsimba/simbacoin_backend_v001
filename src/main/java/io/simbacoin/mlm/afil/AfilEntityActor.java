package io.simbacoin.mlm.afil;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.ReceiveTimeout;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ShardRegion;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.persistence.AbstractPersistentActorWithTimers;
import akka.persistence.RecoveryCompleted;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.Utils;
import io.simbacoin.mlm.grpc.AfilProtos;
import io.simbacoin.mlm.grpc.FireProtos;
import io.simbacoin.mlm.grpc.PathProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AfilEntityActor extends AbstractPersistentActorWithTimers {

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
    private final Object TICK_KEY;
    private final ActorRef dbRouter, fcmRouter, pathRegion, afilRegion;
    private final int snapshotInterval;
    private final FiniteDuration taskTimeout;
    private AfilEntityState state = new AfilEntityState();
    private List<SimbaProtos.Task> tasks = new ArrayList<>();
    private List<String> keys = new ArrayList<>();
    private Map<String, Utils.TaskExecState> taskStates = new HashMap<>();
    private FiniteDuration tickerTimeout = null;

    public AfilEntityActor(ActorRef dbRouter, ActorRef fcmRouter, ActorRef pathRegion, FiniteDuration taskTimeout, int snapshotInterval) {
        this.dbRouter = dbRouter;
        this.fcmRouter = fcmRouter;
        this.pathRegion = pathRegion;
        this.taskTimeout = taskTimeout;
        this.snapshotInterval = snapshotInterval;
        this.afilRegion = ClusterSharding.get(getContext().getSystem()).shardRegion("Afil");
        TICK_KEY = "AfilTickKey-" + getSelf().path().name();
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        getContext().setReceiveTimeout(Duration.create(120, TimeUnit.SECONDS));
    }

    @Override
    public String persistenceId() {
        return "Afil-" + getSelf().path().name();
    }

    @Override
    public Receive createReceive() {
        tasks.clear();
        return receiveBuilder().match(SimbaProtos.SimbaCoinCommand.class, this::onSimbaCommand)

                .match(SimbaProtos.Ack.class, this::onAck).match(SaveSnapshotSuccess.class, this::onSaveSnapshotSuccess)
                .match(PathProtos.GplResponse.class, this::onGplResponse)
                .match(AfilProtos.PathRefResponse.class, this::onPathRefResponse)
                .matchEquals(ReceiveTimeout.getInstance(), cmd -> passivate()).build();
    }

    private void onSimbaCommand(SimbaProtos.SimbaCoinCommand cmd) {
        if (state.isAccepted(cmd.getId())) {
            try {
                if (cmd.getAction().is(AfilProtos.UpsertProfile.class)) {
                    onUpsertProfile(cmd.getId(), cmd.getAction().unpack(AfilProtos.UpsertProfile.class));

                } else if (cmd.getAction().is(AfilProtos.PathRefRequest.class)) {
                    onPathRefRequest(cmd.getAction().unpack(AfilProtos.PathRefRequest.class));

                }
            } catch (InvalidProtocolBufferException e) {
                log.error("Illegal proto argument: {}", e);
                throw new IllegalArgumentException(e.getMessage());
            }
        } else {
            // TODO -> respond appropriately
            log.info("Duplicate command: {}", cmd.getId());
            getSelf().tell(SimbaProtos.Ack.newBuilder().setId(cmd.getId()), getSelf());
        }
    }

    private void onUpsertProfile(String id, AfilProtos.UpsertProfile cmd) {
        SimbaProtos.Affiliate afil = state.getAfil();
        List<SimbaProtos.Referee> pathRefs = new ArrayList<>();
        String tid = Utils.id();
        ZonedDateTime now = Utils.now();
        if (afil == null) {
            if (cmd.getAffiliate().getApl() == 1) {
                // Update profile task
                buildDBProfileRegisteredTask(id, tid, now.toString(), cmd.getAffiliate());
                // Init Left Path
                tid = Utils.id();
                pathRefs.add(SimbaProtos.Referee.newBuilder().mergeFrom(Utils.refFromAfil(afil)).setLeg(SimbaProtos.Leg.Left).build());
                tasks.add(SimbaProtos.Task.newBuilder().setCid(id).setId(tid).setEid(afil.getId() + "-Left")
                        .setAction(Any.pack(PathProtos.InitPath.newBuilder().setRef(pathRefs.get(0)).build())).build());

                tid = Utils.id();
                pathRefs.add(SimbaProtos.Referee.newBuilder().mergeFrom(Utils.refFromAfil(afil)).setLeg(SimbaProtos.Leg.Right).build());
                tasks.add(SimbaProtos.Task.newBuilder().setCid(id).setId(tid).setEid(afil.getId() + "-Right")
                        .setAction(Any.pack(PathProtos.InitPath.newBuilder().setRef(pathRefs.get(1)).build())).build());

            } else {
                // Ask parent for PathRef
                tasks.add(SimbaProtos.Task.newBuilder().setCid(id).setEid(cmd.getAffiliate().getParent().getUid())
                        .setAction(Any.pack(AfilProtos.PathRefRequest.newBuilder().setLeg(cmd.getAffiliate().getParent().getLeg()).build()))
                        .setIat(now.toString()).build());
            }
        } else {
            // TODO -> Pure Afil profile update
        }

        persist(buildEvent(id, Any.pack(AfilProtos.ProfileUpserted.newBuilder().build())), (AfilProtos.AfilDomainEvent event) -> {
            state = state.updated(event);
            log.info("Profile upserted: {} - {} - {}", id, afil.getEmail(), afil.getId());
            processTasks();
            getSender().tell(SimbaProtos.Ack.newBuilder().setId(id).build(), getSelf());
        });
    }

    private void buildDBProfileRegisteredTask(String cid, String tid, String iat, SimbaProtos.Affiliate afil) {
        tasks.add(SimbaProtos.Task.newBuilder().setId(tid).setCid(cid).setIat(iat).setAction(Any.pack(FireProtos.UpsertData.newBuilder()
                .setId(tid).setCollection("profiles").setDocumentId(afil.getId()).setAction(FireProtos.DBAction.UPDATE)
                .setIat(iat).addAllKeys(keys).setData(Any.pack(afil))
                .build())).build());
    }

    private void onPathRefRequest(AfilProtos.PathRefRequest cmd) {
        getSender().tell(AfilProtos.PathRefResponse.newBuilder().setRef(SimbaProtos.Referee.newBuilder().mergeFrom(cmd.getLeg() == SimbaProtos.Leg.Left ? state.getLeftPathRef() : state.getRightPathRef()).setLeg(cmd.getLeg()).build()).build(), getSelf());
    }

    private void onPathRefResponse(AfilProtos.PathRefResponse cmd) {
        // Request Gpl
        String tid = Utils.id();
        String eid = cmd.getRef().getUid() + "-" + (cmd.getRef().getLeg() == SimbaProtos.Leg.Left ? "Left" : "Right");
        tasks.add(SimbaProtos.Task.newBuilder().setCid(tid).setId(tid).setIat(Utils.now().toString()).setEid(eid)
                .setAction(Any.pack(PathProtos.GplRequest.newBuilder().setUid(state.getAfil().getId()).build())).build());

        persist(AfilProtos.AfilDomainEvent.newBuilder().setEvent(Any.pack(AfilProtos.PathRefSaved.newBuilder().addRefs(cmd.getRef()).build())).build(), (AfilProtos.AfilDomainEvent event) -> {
            state = state.updated(event);
            log.info("Parent path ref established: {} - {}", state.getAfil().getId(), cmd.getRef());
            processTasks();
        });
    }

    private void onGplResponse(PathProtos.GplResponse response) {
        ZonedDateTime now = Utils.now();
        SimbaProtos.Affiliate afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(state.getAfil()).setGpl(response.getGpl())
                .setRegStep(SimbaProtos.RegStep.Complete).build();

        // Establish own path ref
        SimbaProtos.Referee pathRef = SimbaProtos.Referee.newBuilder().mergeFrom(Utils.refFromAfil(state.getAfil()))
                .setLeg(state.getAfil().getParent().getLeg() == SimbaProtos.Leg.Left ? SimbaProtos.Leg.Right : SimbaProtos.Leg.Left)
                .setGpl(response.getGpl()).build();

        String tid = Utils.id();
        tasks.add(SimbaProtos.Task.newBuilder().setId(tid).setIat(now.toString())
                .setEid(pathRef.getUid() + "-" + (pathRef.getLeg() == SimbaProtos.Leg.Left ? "Left" : "Right"))
                .setAction(Any.pack(PathProtos.InitPath.newBuilder().setRef(pathRef).build()))
                .build());

        // Associate to Parent
        tid = Utils.id();
        tasks.add(SimbaProtos.Task.newBuilder().setId(tid).setIat(now.toString()).setEid(state.getAfil().getParent().getUid())
                .setAction(Any.pack(AfilProtos.TagAffiliate.newBuilder()
                        .setReferee(SimbaProtos.Referee.newBuilder().mergeFrom(pathRef).setLeg(state.getAfil().getParent().getLeg()).build()).build()))
                .build());

        // Update DB Entry...
        tid = Utils.id();
        tasks.add(SimbaProtos.Task.newBuilder().setId(tid).setIat(now.toString())
                .setAction(Any.pack(FireProtos.UpsertData.newBuilder().setId(tid).setCollection("profiles").setDocumentId("")
                        .build())).build());
    }

    private void onAck(SimbaProtos.Ack ack) {
        if (state.hasTask(ack.getId())) {
            persist(AfilProtos.AfilDomainEvent.newBuilder().setEvent(Any.pack(SimbaProtos.Acked.newBuilder().setId(ack.getId()).build())).build(), (AfilProtos.AfilDomainEvent event) -> {
                state = state.updated(event);
                log.info("Acked: {}", ack.getId());
            });
        }
    }

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder().match(AfilProtos.AfilDomainEvent.class, state::updated)
                .match(SnapshotOffer.class, so -> state = (AfilEntityState) so.snapshot())
                .matchEquals(RecoveryCompleted.getInstance(), event -> onRecoveryCompleted()).build();
    }

    private void onTick() {
        if (!state.getTasks().isEmpty()) {
            state.getTasks().values().stream().forEach(t -> {
                if (!taskStates.containsKey(t.getId()) || taskStates.containsKey(t.getId()) && taskStates.get(t.getId()).deadline.isOverdue()) {
                    taskStates.put(t.getId(), new Utils.TaskExecState(t.getId(), 1, taskTimeout.fromNow()));
                    tasks.add(t);
                }
            });
        }
        if (tasks.size() > 0) {
            processTasks();
        }

        if (snapshotSequenceNr() - state.getSnapshotSequenceNr() >= snapshotInterval) {
            saveSnapshot(state.copy());
        }
    }

    private void processTasks() {
        tasks.stream().forEach(t -> {
            try {
                if (t.getAction().is(AfilProtos.TagAffiliate.class) || t.getAction().is(AfilProtos.PathRefRequest.class)) {
                    afilRegion.tell(Utils.buildSimbaCommand(t), getSelf());
                } else if (t.getAction().is(FireProtos.UpsertData.class)) {
                    dbRouter.tell(t.getAction().unpack(FireProtos.UpsertData.class), getSelf());
                } else if (t.getAction().is(FireProtos.PublishFCM.class)) {
                    fcmRouter.tell(t.getAction().unpack(FireProtos.PublishFCM.class), getSelf());
                } else if (t.getAction().is(PathProtos.InitPath.class) || t.getAction().is(PathProtos.GplRequest.class)) {
                    pathRegion.tell(Utils.buildSimbaCommand(t), getSelf());
                }
            } catch (InvalidProtocolBufferException e) {
                log.error("Illegal proto argument: {}", e);
                throw new IllegalArgumentException(e.getMessage());
            }
        });

        if (tickerTimeout == null)
            tickerTimeout = Duration.create(5, TimeUnit.SECONDS);
        else if (tickerTimeout.fromNow().isOverdue() && tasks.size() > 0) {
            timers().startSingleTimer(TICK_KEY, new Tick(), taskTimeout.div(2));
            tickerTimeout = Duration.create(5, TimeUnit.SECONDS);
        }
    }

    private void onSaveSnapshotSuccess(SaveSnapshotSuccess cmd) {
        persist(AfilProtos.AfilDomainEvent.newBuilder().setEvent(Any.pack(SimbaProtos.SnapshotSaved.newBuilder()
                .setSnapshotSequenceNr(cmd.metadata().sequenceNr()).build())).build(), (AfilProtos.AfilDomainEvent event) -> {
            state = state.updated(event);
            log.info("Snapshot saved: {}", state.getSnapshotSequenceNr());
        });
    }

    private void onRecoveryCompleted() {
        onTick();
    }

    private void passivate() {
        getContext().getParent().tell(new ShardRegion.Passivate(PoisonPill.getInstance()), getSelf());
    }

    private AfilProtos.AfilDomainEvent buildEvent(String id, Any event) {
        return AfilProtos.AfilDomainEvent.newBuilder().setId(id).setEvent(event).addAllTasks(tasks).build();
    }

    private final static class Tick {
    }

}
