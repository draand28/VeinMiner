package wtf.choco.veinminer.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.jetbrains.annotations.NotNull;

import wtf.choco.veinminer.VeinMinerPlugin;

/**
 * An implementation of {@link PersistentDataStorage} for SQLite databases.
 */
public final class PersistentDataStorageSQLite extends PersistentDataStorageSQL {

    // Create tables

    private static final String CREATE_TABLE_PLAYERS = """
            CREATE TABLE IF NOT EXISTS player_data (
                player_uuid              TEXT PRIMARY KEY,
                activation_strategy_id   TEXT,
                disabled_categories      TEXT,
                vein_mining_pattern_id   TEXT
            )
            """;

    private static final String INSERT_PLAYER_DATA = """
            INSERT INTO player_data VALUES (?, ?, ?, ?)
                ON CONFLICT (player_uuid) DO
                UPDATE SET
                    activation_strategy_id = excluded.activation_strategy_id,
                    disabled_categories = excluded.disabled_categories,
                    vein_mining_pattern_id = excluded.vein_mining_pattern_id
            """;

    private static final String SELECT_PLAYER_DATA = """
            SELECT * FROM player_data WHERE player_uuid = ?
            """;

    private final String connectionURL;

    /**
     * Construct a new {@link PersistentDataStorageSQLite}.
     *
     * @param plugin the plugin instance
     * @param fileName the name of the file at which the database is located
     *
     * @throws IOException if an exception occurs while creating the database file (if it was
     * not already created)
     */
    public PersistentDataStorageSQLite(@NotNull VeinMinerPlugin plugin, @NotNull String fileName) throws IOException {
        Path databaseFilePath = plugin.getDataFolder().toPath().resolve(fileName);
        if (Files.notExists(databaseFilePath)) {
            try {
                Files.createFile(databaseFilePath);
            } catch (IOException e) {
                throw e;
            }
        }

        this.connectionURL = "jdbc:sqlite:plugins/" + plugin.getDataFolder().getName() + "/" + fileName;
    }

    @NotNull
    @Override
    public Type getType() {
        return Type.SQLITE;
    }

    @Override
    protected void initDriver() throws ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
    }

    @NotNull
    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(connectionURL);
    }

    @NotNull
    @Override
    protected String getCreatePlayersTableStatement() {
        return CREATE_TABLE_PLAYERS;
    }

    @NotNull
    @Override
    protected String getInsertPlayerDataStatement() {
        return INSERT_PLAYER_DATA;
    }

    @NotNull
    @Override
    protected String getSelectAllPlayerDataQuery() {
        return SELECT_PLAYER_DATA;
    }

}
