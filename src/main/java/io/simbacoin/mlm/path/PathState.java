package io.simbacoin.mlm.path;

import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.grpc.PathProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;

import java.util.HashMap;
import java.util.Map;

public class PathState {
    private final SimbaProtos.Referee ref;
    private final Map<String, Long> gpls;
    private final long currentGpl;
    private final long snapshotSequenceNr;

    public PathState() {
        this(null, new HashMap<String, Long>(), 0l, 0l);
    }

    public PathState(SimbaProtos.Referee ref, Map<String, Long> gpls, long currentGpl, long snapshotSequenceNr) {
        this.ref = ref;
        this.gpls = gpls;
        this.currentGpl = currentGpl;
        this.snapshotSequenceNr = snapshotSequenceNr;
    }

    public PathState copy() {
        return new PathState(ref, new HashMap<>(gpls), currentGpl, snapshotSequenceNr);
    }

    public PathState updated(PathProtos.PathDomainEvent event) {
        return new PathState(this, event);
    }

    public PathState(PathState state, PathProtos.PathDomainEvent event) {
        SimbaProtos.Referee tmp_ref = state.ref;
        Map<String, Long> tmp_gpls = state.gpls;
        long tmp_currentGpl = state.currentGpl;
        long tmp_snapshotSequenceNr = state.snapshotSequenceNr;

        if (event.getEvent().isInitialized()) {
            try {
                if (event.getEvent().is(PathProtos.GplRequested.class)) {
                    PathProtos.GplRequested evt = event.getEvent().unpack(PathProtos.GplRequested.class);
                    tmp_gpls.put(evt.getUid(), evt.getGpl());
                    tmp_currentGpl = evt.getGpl();

                } else if (event.getEvent().is(SimbaProtos.SnapshotSaved.class)) {
                    SimbaProtos.SnapshotSaved evt = event.getEvent().unpack(SimbaProtos.SnapshotSaved.class);
                    tmp_snapshotSequenceNr = evt.getSnapshotSequenceNr();

                } else if (event.getEvent().is(PathProtos.PathInitialized.class)) {
                    PathProtos.PathInitialized evt = event.getEvent().unpack(PathProtos.PathInitialized.class);
                    tmp_ref = evt.getRef();
                    tmp_currentGpl = evt.getRef().getGpl();

                }
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        ref = tmp_ref;
        gpls = tmp_gpls;
        currentGpl = tmp_currentGpl;
        snapshotSequenceNr = tmp_snapshotSequenceNr;
    }

    public SimbaProtos.Referee getRef() {
        return ref;
    }

    public Map<String, Long> getGpls() {
        return gpls;
    }

    public Long getGpl(String id) {
        return gpls.get(id);
    }

    public long getCurrentGpl() {
        return currentGpl;
    }

    public long getSnapshotSequenceNr() {
        return snapshotSequenceNr;
    }
}
