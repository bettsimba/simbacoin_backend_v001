package io.simbacoin.mlm.path;

import akka.actor.PoisonPill;
import akka.actor.ReceiveTimeout;
import akka.cluster.sharding.ShardRegion;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.RecoveryCompleted;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.grpc.PathProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;

public class PathActor extends AbstractPersistentActor {

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

    private final int snapshotInterval;

    private PathState state = new PathState();

    public PathActor(int snapshotInterval) {
        this.snapshotInterval = snapshotInterval;
    }

    @Override
    public String persistenceId() {
        return "Path-" + getSelf().path().name();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder().match(SimbaProtos.SimbaCoinCommand.class, this::onCommand)
                .match(SaveSnapshotSuccess.class, this::onSaveSnapshotSuccess)
                .matchEquals(ReceiveTimeout.getInstance(), msg -> passivate()).build();
    }

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder().match(PathProtos.PathDomainEvent.class, state::updated)
                .match(SnapshotOffer.class, so -> state = (PathState) so.snapshot())
                .matchEquals(RecoveryCompleted.getInstance(), evt -> onRecoveryCompleted()).build();
    }

    private void onCommand(SimbaProtos.SimbaCoinCommand cmd) {
        try {
            if (cmd.getAction().is(PathProtos.GplRequest.class)) {
                onGplPositionRequest(cmd.getId(), cmd.getAction().unpack(PathProtos.GplRequest.class));
            } else if (cmd.getAction().is(PathProtos.InitPath.class)) {
                onInitPath(cmd.getId(), cmd.getAction().unpack(PathProtos.InitPath.class));
            }
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private void onGplPositionRequest(String id, PathProtos.GplRequest cmd) {
        if (state.getGpl(cmd.getUid()) == null) {
            persist(PathProtos.PathDomainEvent.newBuilder().build(), (PathProtos.PathDomainEvent event) -> {
                state = state.updated(event);
                log.info("GPL Position assigned: {} - {}", cmd.getUid(), state.getGpl(cmd.getUid()));
                getSender().tell(PathProtos.GplResponse.newBuilder().setGpl(state.getGpl(cmd.getUid())).build(), getSelf());
            });
        } else {
            log.info("Duplicate GPL Position request: {}", cmd.getUid());
            getSender().tell(PathProtos.GplResponse.newBuilder().setGpl(state.getGpl(cmd.getUid())).build(), getSelf());
        }
    }

    private void onInitPath(String id, PathProtos.InitPath cmd) {
        if (state.getRef() == null) {
            persist(PathProtos.PathDomainEvent.newBuilder().setEvent(Any.pack(PathProtos.PathInitialized.newBuilder().setRef(cmd.getRef()).build())).build(), (PathProtos.PathDomainEvent event) -> {
                state = state.updated(event);
                log.info("Path initialized: {}", state.getRef());
                getSender().tell(SimbaProtos.Ack.newBuilder().setId(id).build(), getSelf());
            });
        } else {
            log.info("Duplicate InitPath request: {} - {}", id, cmd.getRef());
            getSender().tell(SimbaProtos.Ack.newBuilder().setId(id).build(), getSelf());
        }
    }

    private void onSaveSnapshotSuccess(SaveSnapshotSuccess cmd) {
        persist(PathProtos.PathDomainEvent.newBuilder().setEvent(Any.pack(SimbaProtos.SnapshotSaved.newBuilder().setSnapshotSequenceNr(cmd.metadata().sequenceNr()).build())).build(),
                (PathProtos.PathDomainEvent event) -> {
                    state = state.updated(event);
                    log.info("Snapshot saved: {}", state.getSnapshotSequenceNr());
                });
    }

    private void onRecoveryCompleted() {
        if (snapshotSequenceNr() - state.getSnapshotSequenceNr() >= snapshotInterval) {
            saveSnapshot(state.copy());
        }
    }

    private void passivate() {
        getContext().getParent().tell(new ShardRegion.Passivate(PoisonPill.getInstance()), getSelf());
    }

}
