package io.simbacoin.mlm;

import com.google.protobuf.Any;
import io.simbacoin.mlm.grpc.SimbaProtos;
import scala.concurrent.duration.Deadline;

import java.time.ZonedDateTime;
import java.util.UUID;

public final class Utils {

    // Less id, leg, iat
    public static SimbaProtos.Referee refFromAfil(SimbaProtos.Affiliate afil) {
        return SimbaProtos.Referee.newBuilder().setUid(afil.getId()).setApl(afil.getApl()).setRpl(afil.getRpl())
                .setRegNo(afil.getRegNo()).setGpl(afil.getGpl()).setCountryCode(afil.getLocation().getCountryCode())
                .build();
    }

    //
    public static SimbaProtos.Referee updateRef(SimbaProtos.Referee oldRef, SimbaProtos.Referee newRef) {
        if (!oldRef.getId().isEmpty())
            newRef = SimbaProtos.Referee.newBuilder().mergeFrom(newRef).setId(oldRef.getId()).build();
        if (oldRef.getApl() != 0l)
            newRef = SimbaProtos.Referee.newBuilder().mergeFrom(newRef).setApl(oldRef.getApl()).build();
        if (oldRef.getGpl() != 0l)
            newRef = SimbaProtos.Referee.newBuilder().mergeFrom(newRef).setGpl(oldRef.getGpl()).build();
        if (oldRef.getRpl() != 0l)
            newRef = SimbaProtos.Referee.newBuilder().mergeFrom(newRef).setRpl(oldRef.getRpl()).build();
        if (oldRef.getRegNo() != 0l)
            newRef = SimbaProtos.Referee.newBuilder().mergeFrom(newRef).setRegNo(oldRef.getRegNo()).build();
        if (!oldRef.getIat().isEmpty())
            newRef = SimbaProtos.Referee.newBuilder().mergeFrom(newRef).setIat(oldRef.getIat()).build();
        return newRef;
    }

    public static SimbaProtos.Affiliate updateAfil(SimbaProtos.Affiliate afil, SimbaProtos.Referee ref) {
        if (afil.getApl() == 0l && ref.getApl() != 0l)
            afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(afil).setApl(ref.getApl()).build();
        if (afil.getGpl() == 0l && ref.getGpl() != 0l)
            afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(afil).setGpl(ref.getGpl()).build();
        if (afil.getRpl() == 0l && ref.getRpl() != 0l)
            afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(afil).setRpl(ref.getRpl()).build();
        if (afil.getRegNo() == 0l && ref.getRegNo() != 0l)
            afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(afil).setRegNo(ref.getRegNo()).build();
        if (!ref.getIat().isEmpty())
            afil = SimbaProtos.Affiliate.newBuilder().mergeFrom(afil).setIat(ref.getIat()).build();
        return afil;
    }

    public static SimbaProtos.SimbaCoinCommand buildSimbaCommand(String id, String eid, Any action) {
        return SimbaProtos.SimbaCoinCommand.newBuilder().setId(id).setEid(eid).setAction(action).setIat(now().toString()).build();
    }

    public static SimbaProtos.SimbaCoinCommand buildSimbaCommand(SimbaProtos.Task task) {
        return SimbaProtos.SimbaCoinCommand.newBuilder().setId(task.getId()).setEid(task.getEid()).setAction(task.getAction()).setIat(now().toString()).build();
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static ZonedDateTime now() {
        return ZonedDateTime.now();
    }

    public static final class TaskExecState {
        public final String id;
        public final int numRetries;
        public final Deadline deadline;

        public TaskExecState(String id, int numRetries, Deadline deadline) {
            this.id = id;
            this.numRetries = numRetries;
            this.deadline = deadline;
        }

        public TaskExecState copyWithRetries(int numRetries) {
            return new TaskExecState(id, numRetries, deadline);
        }

        public TaskExecState copyWithTimeout(Deadline deadline) {
            return new TaskExecState(id, numRetries, deadline);
        }
    }
}
