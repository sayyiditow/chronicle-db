package chronicle.db.service;

import static chronicle.db.utils.ChronicleUtils.CHRONICLE_UTILS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.tinylog.Logger;

import com.jsoniter.spi.TypeLiteral;

import chronicle.db.Server;
import chronicle.db.utils.JsonUtils;

public final class DeadlockService {
    private DeadlockService() {
    }

    @SuppressWarnings("unchecked")
    public static void recoverDeadLocks() throws Throwable {
        final var deadlocksJsonPath = Server.getResourceDir() + "/deadlocks.json";
        final var config = JsonUtils.fromJsonFileToObj(deadlocksJsonPath,
                new TypeLiteral<List<Map<String, Object>>>() {
                });
        final int configSize = config.size();

        if (configSize == 0) {
            return;
        }

        Logger.info("\n-----------Releasing Deadlocks-----------");

        CHRONICLE_UTILS.processInParallel(config, map -> {
            try {
                final var fqn = (String) map.get("fqn");
                final var dbDir = (String) map.get("dbDir");
                final var filePath = (String) map.get("filePath");
                final var fileNames = (List<String>) map.get("fileNames");
                final var dao = ChronicleDaoService.CHRONICLE_DAO_SERVICE.getDao(fqn, dbDir, filePath);
                if (fileNames == null || fileNames.isEmpty()) {
                    dao.recoverAllData();
                } else {
                    CHRONICLE_UTILS.processInParallel(fileNames, fileName -> {
                        try {
                            dao.recoverData(fileName);
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            } catch (final UncheckedIOException e) {
                throw e;
            } catch (final Throwable e) {
                throw new RuntimeException(e);
            }
        });

        JsonUtils.toJsonFileFromObj(deadlocksJsonPath, Collections.emptyList());
        Logger.info("\n-----------Deadlocks Released-----------");
    }
}
