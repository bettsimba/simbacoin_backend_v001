package io.simbacoin.mlm.firebase;

import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.simbacoin.mlm.grpc.FireProtos;
import io.simbacoin.mlm.grpc.SimbaProtos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FirestoreWorker extends AbstractActor {

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
    private final Firestore db;

    public FirestoreWorker(Firestore db) {
        this.db = db;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder().match(FireProtos.UpsertData.class, this::onUpsertData).build();
    }

    private Map<String, Object> map = new HashMap<>();

    private void onUpsertData(FireProtos.UpsertData cmd) {
        generateDataMap(cmd.getData(), cmd.getKeysList());
        log.info("Collection: {}, doc: {}, data: {}", cmd.getCollection(), cmd.getDocumentId(), map);
        if (cmd.getAction() == FireProtos.DBAction.INSERT) {
            if (cmd.getMerge())
                db.collection(cmd.getCollection()).document(cmd.getDocumentId()).set(map, SetOptions.merge());
            else
                db.collection(cmd.getCollection()).document(cmd.getDocumentId()).set(map);
        } else if (cmd.getAction() == FireProtos.DBAction.UPDATE) {
            db.collection(cmd.getCollection()).document(cmd.getDocumentId()).update(map);
        } else if (cmd.getAction() == FireProtos.DBAction.DELETE) {
            db.collection(cmd.getCollection()).document(cmd.getDocumentId()).delete();
        } else {
            log.warning("Unknown DBAction: {}", cmd.getAction());
        }
        getSender().tell(SimbaProtos.Ack.newBuilder().setId(cmd.getId()).build(), getSelf());
    }

    private void generateDataMap(Any data, List<String> keys) {
        map.clear();
        try {
            if (data.is(SimbaProtos.Affiliate.class)) {
                afilMap(data.unpack(SimbaProtos.Affiliate.class), keys);
            } else if (data.is(SimbaProtos.Referee.class)) {
                refMap(data.unpack(SimbaProtos.Referee.class), keys);
            }
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private void afilMap(SimbaProtos.Affiliate afil, List<String> keys) {
        if (keys.contains("reg_no")) {
            map.put("reg_no", afil.getRegNo());
        }
        if (keys.contains("gpl")) {
            map.put("gpl", afil.getGpl());
        }
        if (keys.contains("rpl")) {
            map.put("rpl", afil.getGpl());
        }
        if (keys.contains("apl")) {
            map.put("apl", afil.getGpl());
        }
        if (keys.contains("reg_step")) {
            map.put("reg_step", afil.getRegStep().getNumber());
        }
    }

    private void refMap(SimbaProtos.Referee ref, List<String> keys) {
        if (keys.contains("id"))
            map.put("id", ref.getId());
        if (keys.contains("uid"))
            map.put("uid", ref.getUid());
        if (keys.contains("leg"))
            map.put("leg", ref.getLeg().getNumber());
        if (keys.contains("rpl"))
            map.put("rpl", ref.getRpl());
        if (keys.contains("gpl"))
            map.put("gpl", ref.getGpl());
        if (keys.contains("apl"))
            map.put("apl", ref.getApl());
        if (keys.contains("reg_no"))
            map.put("reg_no", ref.getRegNo());
        if (keys.contains("country_code"))
            map.put("country_code", ref.getCountryCode());
        if (keys.contains("iat"))
            map.put("iat", ref.getIat());
    }
}
