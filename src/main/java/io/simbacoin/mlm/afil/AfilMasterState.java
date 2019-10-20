package io.simbacoin.mlm.afil;

import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.grpc.AfilProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;

import java.util.HashMap;
import java.util.Map;

public class AfilMasterState {
    private final Map<String, SimbaProtos.Referee> refs;
    private final Map<String, SimbaProtos.Task> tasks;
    private final Long currRegNo;
    private final Long snapshotSequenceNr;

    public AfilMasterState() {
        this(new HashMap<String, SimbaProtos.Referee>(), new HashMap<String, SimbaProtos.Task>(), 0l, 0l);
    }

    public AfilMasterState(Map<String, SimbaProtos.Referee> refs, Map<String, SimbaProtos.Task> tasks, Long currRegNo, Long snapshotSequenceNr) {
        this.refs = refs;
        this.tasks = tasks;
        this.currRegNo = currRegNo;
        this.snapshotSequenceNr = snapshotSequenceNr;
    }

    public AfilMasterState copy() {
        return new AfilMasterState(new HashMap<>(refs), new HashMap<>(tasks), currRegNo, snapshotSequenceNr);
    }

    public AfilMasterState updated(AfilProtos.AfilDomainEvent event) {
        return new AfilMasterState(this, event);
    }

    public AfilMasterState(AfilMasterState state, AfilProtos.AfilDomainEvent event) {
        Map<String, SimbaProtos.Referee> tmp_refs = state.refs;
        Map<String, SimbaProtos.Task> tmp_tasks = state.tasks;
        Long tmp_currRegNo = state.currRegNo;
        Long tmp_snapshotSequenceNr = state.snapshotSequenceNr;

        try {
            if (event.getEvent().is(AfilProtos.AfilRegistrationInitialized.class)) {
                AfilProtos.AfilRegistrationInitialized evt = event.getEvent().unpack(AfilProtos.AfilRegistrationInitialized.class);
                evt.getRefsList().stream().forEach(ref -> tmp_refs.put(ref.getId(), ref));
                tmp_currRegNo = evt.getRegNo();
            } else if (event.getEvent().is(SimbaProtos.Acked.class)) {
                tmp_tasks.remove(event.getEvent().unpack(SimbaProtos.Acked.class).getId());
            } else if (event.getEvent().is(SimbaProtos.SnapshotSaved.class)) {
                tmp_snapshotSequenceNr = event.getEvent().unpack(SimbaProtos.SnapshotSaved.class).getSnapshotSequenceNr();
            }
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        refs = tmp_refs;
        tasks = tmp_tasks;
        currRegNo = tmp_currRegNo;
        snapshotSequenceNr = tmp_snapshotSequenceNr;
    }

    public Map<String, SimbaProtos.Referee> getRefs() {
        return refs;
    }

    public SimbaProtos.Referee getRef(String id) {
        return refs.get(id);
    }

    public Map<String, SimbaProtos.Task> getTasks() {
        return tasks;
    }

    public SimbaProtos.Task getTask(String id) {
        return tasks.get(id);
    }

    public Long getCurrRegNo() {
        return currRegNo;
    }

    public Long getSnapshotSequenceNr() {
        return snapshotSequenceNr;
    }
}
