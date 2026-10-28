package com.hboj.hPlaytime;

import java.util.Map;
import java.util.UUID;

/** Worker-confined persistence. Stable operations are applied exactly once, including resets. */
public interface PlaytimeStorage extends AutoCloseable {
    void initialize() throws Exception;
    StorageView loadView() throws Exception;
    default Map<UUID, PlayerData> loadPlayers() throws Exception { return loadView().players(); }
    void apply(StorageMutation mutation) throws Exception;
    @Override void close() throws Exception;
}
