package io.simbacoin.mlm.session;

import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.grpc.SessionProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class SessionsState {
    private final Map<String, SessionProtos.Session> sessions;
    private final Map<String, SessionProtos.SessionTask> tasks;
    private final long snapshotSequenceNr;

    public SessionsState(Map<String, SessionProtos.Session> sessions, Map<String, SessionProtos.SessionTask> tasks, long snapshotSequenceNr) {
        this.sessions = sessions;
        this.tasks = tasks;
        this.snapshotSequenceNr = snapshotSequenceNr;
    }

    public SessionsState() {
        this(new HashMap<String, SessionProtos.Session>(), new HashMap<>(), 0l);
    }

    public SessionsState copy() {
        return new SessionsState(new HashMap<>(sessions), new HashMap<>(tasks), snapshotSequenceNr);
    }

    public SessionsState updated(SessionProtos.SessionDomainEvent event) {
        return new SessionsState(this, event);
    }

    public SessionsState(SessionsState state, SessionProtos.SessionDomainEvent event) {
        Map<String, SessionProtos.Session> tmp_sessions = state.sessions;
        Map<String, SessionProtos.SessionTask> tmp_tasks = state.tasks;
        long tmp_snapshotSequenceNr = state.snapshotSequenceNr;

        if (event.getEvent() != null) {
            try {
                if (event.getEvent().is(SessionProtos.SessionsUpserted.class)) {
                    SessionProtos.SessionsUpserted evt = event.getEvent().unpack(SessionProtos.SessionsUpserted.class);
                    evt.getSessionsList().stream().forEach(s -> tmp_sessions.put(s.getId(), s));
                    evt.getInactiveSidsList().stream().forEach(sid -> tmp_sessions.remove(sid));

                } else if (event.getEvent().is(SimbaProtos.Acked.class)) {
                    SimbaProtos.Acked evt = event.getEvent().unpack(SimbaProtos.Acked.class);
                    tmp_tasks.remove(evt.getId());
                } else if (event.getEvent().is(SimbaProtos.SnapshotSaved.class)) {
                    SimbaProtos.SnapshotSaved evt = event.getEvent().unpack(SimbaProtos.SnapshotSaved.class);
                    tmp_snapshotSequenceNr = evt.getSnapshotSequenceNr();
                }
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        event.getTasksList().stream().forEach(t -> tmp_tasks.put(t.getId(), t));

        sessions = tmp_sessions;
        tasks = tmp_tasks;
        snapshotSequenceNr = tmp_snapshotSequenceNr;
    }

    public Map<String, SessionProtos.Session> getSessions() {
        return sessions;
    }

    public SessionProtos.Session getSession(String id) {
        return sessions.get(id);
    }

    public Map<String, String> getSessionUIDs(Set<String> sids) {
        Map<String, String> sidUids = new HashMap<>();
        sids.stream().forEach(sid -> sidUids.put(sid, sessions.get(sid).getId()));
        return sidUids;
    }

    public Map<String, SessionProtos.SessionTask> getTasks() {
        return tasks;
    }

    public boolean isTaskExecuted(String id) {
        return tasks.containsKey(id);
    }

    public long getSnapshotSequenceNr() {
        return snapshotSequenceNr;
    }
}
