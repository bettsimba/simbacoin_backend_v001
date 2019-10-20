package io.simbacoin.mlm.afil;

import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.Utils;
import io.simbacoin.mlm.grpc.AfilProtos;
import io.simbacoin.mlm.grpc.SessionProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;

import java.util.HashMap;
import java.util.Map;

public class AfilEntityState {
    private final SimbaProtos.Affiliate afil;
    private final Map<String, SimbaProtos.Referee> refs;
    private final Map<String, SessionProtos.Session> sessions;
    private final Map<String, SimbaProtos.Task> tasks;
    private final Map<String, String> acceptedIds;
    private final Long snapshotSequenceNr;

    public AfilEntityState() {
        this(null, new HashMap<String, SimbaProtos.Referee>(), new HashMap<String, SessionProtos.Session>(), new HashMap<String, SimbaProtos.Task>(), new HashMap<String, String>(), 0l);
    }

    public AfilEntityState(SimbaProtos.Affiliate afil, Map<String, SimbaProtos.Referee> refs, Map<String, SessionProtos.Session> sessions, Map<String, SimbaProtos.Task> tasks, Map<String, String> acceptedIds, Long snapshotSequenceNr) {
        this.afil = afil;
        this.refs = refs;
        this.sessions = sessions;
        this.tasks = tasks;
        this.acceptedIds = acceptedIds;
        this.snapshotSequenceNr = snapshotSequenceNr;
    }

    public AfilEntityState copy() {
        return new AfilEntityState(afil, new HashMap<>(refs), new HashMap<>(sessions), new HashMap<>(tasks), new HashMap<>(acceptedIds), snapshotSequenceNr);
    }

    public AfilEntityState updated(AfilProtos.AfilDomainEvent event) {
        return new AfilEntityState(this, event);
    }

    public AfilEntityState(AfilEntityState state, AfilProtos.AfilDomainEvent event) {
        SimbaProtos.Affiliate tmp_afil = state.afil;
        Map<String, SimbaProtos.Referee> tmp_refs = state.refs;
        Map<String, SessionProtos.Session> tmp_sessions = state.sessions;
        Map<String, SimbaProtos.Task> tmp_tasks = state.tasks;
        Map<String, String> tmp_acceptedIds = state.acceptedIds;
        Long tmp_snapshotSequenceNr = state.snapshotSequenceNr;

        if (event.getEvent().isInitialized()) {
            try {
                if (event.getEvent().is(AfilProtos.SessionUpserted.class)) {
                    AfilProtos.SessionUpserted evt = event.getEvent().unpack(AfilProtos.SessionUpserted.class);
                    tmp_sessions.put(evt.getSession().getId(), evt.getSession());
                } else if (event.getEvent().is(AfilProtos.SessionEnded.class)) {
                    tmp_sessions.remove(event.getEvent().unpack(AfilProtos.SessionEnded.class).getSid());
                } else if (event.getEvent().is(AfilProtos.AffiliateTagged.class)) {
                    SimbaProtos.Referee ref = event.getEvent().unpack(AfilProtos.AffiliateTagged.class).getReferee();
                    tmp_refs.put(ref.getUid(), ref);
                } else if (event.getEvent().is(SimbaProtos.SnapshotSaved.class)) {
                    tmp_snapshotSequenceNr = event.getEvent().unpack(SimbaProtos.SnapshotSaved.class).getSnapshotSequenceNr();
                } else if (event.getEvent().is(AfilProtos.ProfileUpserted.class)) {
                    AfilProtos.ProfileUpserted evt = event.getEvent().unpack(AfilProtos.ProfileUpserted.class);
                    tmp_afil = evt.getAffiliate();
                    if (evt.getPathRefsCount() > 0) {
                        evt.getPathRefsList().stream().forEach(r -> {
                            tmp_refs.put(r.getLeg() == SimbaProtos.Leg.Left ? "LeftPath" : "RightPath", r);
                        });
                    }
                } else if (event.getEvent().is(AfilProtos.PathRefSaved.class)) {
                    AfilProtos.PathRefSaved evt = event.getEvent().unpack(AfilProtos.PathRefSaved.class);
                    evt.getRefsList().stream().forEach(r -> {
                        tmp_refs.put(r.getLeg() == SimbaProtos.Leg.Left ? "LeftPath" : "RightPath", r);
                    });
                }
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        if (event.getTasksCount() > 0)
            event.getTasksList().stream().forEach(t -> tmp_tasks.put(t.getId(), t));

        if (!event.getId().isEmpty()) {
            tmp_acceptedIds.put(event.getId(), Utils.now().toString());
        }

        afil = tmp_afil;
        refs = tmp_refs;
        sessions = tmp_sessions;
        tasks = tmp_tasks;
        acceptedIds = tmp_acceptedIds;
        snapshotSequenceNr = tmp_snapshotSequenceNr;
    }

    public SimbaProtos.Affiliate getAfil() {
        return afil;
    }

    public Map<String, SimbaProtos.Referee> getRefs() {
        return refs;
    }

    public SimbaProtos.Referee getRef(String id) {
        return refs.get(id);
    }

    public SimbaProtos.Referee getLeftPathRef() {
        return refs.get("LeftPath");
    }

    public SimbaProtos.Referee getRightPathRef() {
        return refs.get("RightPath");
    }

    public Map<String, SessionProtos.Session> getSessions() {
        return sessions;
    }

    public SessionProtos.Session getSession(String id) {
        return sessions.get(id);
    }

    public Map<String, SimbaProtos.Task> getTasks() {
        return tasks;
    }

    public SimbaProtos.Task getTask(String id) {
        return tasks.get(id);
    }

    public boolean hasTask(String id) {
        return tasks.containsKey(id);
    }

    public Map<String, String> getAcceptedIds() {
        return acceptedIds;
    }

    public boolean isAccepted(String id) {
        return acceptedIds.containsKey(id);
    }

    public Long getSnapshotSequenceNr() {
        return snapshotSequenceNr;
    }
}
