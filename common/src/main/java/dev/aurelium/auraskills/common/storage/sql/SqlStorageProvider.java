package dev.aurelium.auraskills.common.storage.sql;

import dev.aurelium.auraskills.api.ability.AbstractAbility;
import dev.aurelium.auraskills.api.mana.ManaAbility;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.stat.Stat;
import dev.aurelium.auraskills.api.stat.StatModifier;
import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.trait.TraitModifier;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier.Operation;
import dev.aurelium.auraskills.common.AuraSkillsPlugin;
import dev.aurelium.auraskills.common.ability.AbilityData;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.mana.ManaAbilityData;
import dev.aurelium.auraskills.common.ref.PlayerRef;
import dev.aurelium.auraskills.common.region.BlockPosition;
import dev.aurelium.auraskills.common.storage.StorageProvider;
import dev.aurelium.auraskills.common.storage.sql.migration.SqlMigrator;
import dev.aurelium.auraskills.common.storage.sql.pool.ConnectionPool;
import dev.aurelium.auraskills.common.ui.ActionBarType;
import dev.aurelium.auraskills.common.user.AntiAfkLog;
import dev.aurelium.auraskills.common.user.SkillLevelMaps;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.user.UserState;
import dev.aurelium.auraskills.common.util.data.KeyIntPair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.*;

public class SqlStorageProvider extends StorageProvider {

    private final ConnectionPool pool;
    private final SqlUserLoader userLoader;
    public static final String TABLE_PREFIX = "auraskills_";

    public static final int STAT_MODIFIER_ID = 1; // Deprecated, only used in SqlUserMigrator
    public static final int ABILITY_DATA_ID = 3;
    public static final int UNCLAIMED_ITEMS_ID = 4;
    public static final int ACTION_BAR_ID = 5;
    public static final int JOBS_ID = 6;
    public static final String MODIFIER_TYPE_STAT = "stat";
    public static final String MODIFIER_TYPE_TRAIT = "trait";
    public static final String LOG_TYPE_ANTI_AFK = "anti_afk";
    public static final int LOG_LEVEL_WARN = 2;
    public static final String JOBS_LAST_SELECT_TIME = "last_select_time";

    public SqlStorageProvider(AuraSkillsPlugin plugin, ConnectionPool pool) {
        super(plugin);
        this.pool = pool;
        this.userLoader = new SqlUserLoader(plugin);
        attemptTableCreation();

        try {
            SqlMigrator migrator = new SqlMigrator(plugin, pool);
            migrator.runMigrations();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to migrate SQL tables. Please report this!", e);
        }
    }

    public ConnectionPool getPool() {
        return pool;
    }

    public void attemptTableCreation() {
        TableCreator tableCreator = new TableCreator(plugin, pool, TABLE_PREFIX);
        tableCreator.createTables();
    }

    @Override
    protected User loadRaw(UUID uuid, @Nullable PlayerRef platformPlayer) throws Exception {
        try (Connection connection = pool.getConnection()) {
            User user = userManager.createNewUser(uuid, platformPlayer);
            userLoader.loadUser(uuid, user, connection);

            return user;
        }
    }

    private SkillLevelMaps loadSkillLevels(Connection connection, UUID uuid, int userId) throws SQLException {
        Map<Skill, Integer> levelsMap = new HashMap<>();
        Map<Skill, Double> xpMap = new HashMap<>();

        String loadQuery = "SELECT * FROM " + TABLE_PREFIX + "skill_levels WHERE user_id=?";
        try (PreparedStatement statement = connection.prepareStatement(loadQuery)) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    // Convert skill name to Skill object
                    String skillName = resultSet.getString("skill_name");
                    NamespacedId skillId = NamespacedId.fromString(skillName);
                    try {
                        Skill skill = plugin.getSkillRegistry().get(skillId);

                        int level = resultSet.getInt("skill_level");
                        double xp = resultSet.getDouble("skill_xp");
                        levelsMap.put(skill, level);
                        xpMap.put(skill, xp);
                    } catch (IllegalArgumentException e) { // If skill not found in registry
                        plugin.logger().warn("Failed to load skill level for player " + uuid + " because " + skillName + " is not a registered skill");
                    }
                }
            }
        }
        return new SkillLevelMaps(levelsMap, xpMap);
    }

    private Map<String, StatModifier> loadStatModifiers(Connection connection, UUID uuid, int userId) throws SQLException {
        Map<String, StatModifier> modifiers = new HashMap<>();
        String query = "SELECT type_id, modifier_name, modifier_value, modifier_operation, expiration_time, remaining_duration FROM " +
                TABLE_PREFIX + "modifiers WHERE user_id=? AND modifier_type=?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, userId);
            statement.setString(2, MODIFIER_TYPE_STAT);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String typeId = resultSet.getString("type_id");
                    if (typeId == null) continue;

                    Stat stat = plugin.getStatRegistry().getOrNull(NamespacedId.fromString(typeId));
                    if (stat == null) {
                        plugin.logger().warn("Failed to load stat modifier for player " + uuid + " because " + typeId + " is not a registered stat");
                        continue;
                    }

                    String modifierName = resultSet.getString("modifier_name");
                    double value = resultSet.getDouble("modifier_value");
                    Operation operation = Operation.fromSqlId(resultSet.getByte("modifier_operation"));

                    StatModifier modifier = new StatModifier(modifierName, stat, value, operation);

                    loadTemporary(resultSet, modifier);

                    modifiers.put(modifierName, modifier);
                }
            }
        }
        return modifiers;
    }

    private Map<String, TraitModifier> loadTraitModifiers(Connection connection, UUID uuid, int userId) throws SQLException {
        Map<String, TraitModifier> modifiers = new HashMap<>();
        String query = "SELECT type_id, modifier_name, modifier_value, modifier_operation, expiration_time, remaining_duration FROM " +
                TABLE_PREFIX + "modifiers WHERE user_id=? AND modifier_type=?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, userId);
            statement.setString(2, MODIFIER_TYPE_TRAIT);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String typeId = resultSet.getString("type_id");
                    if (typeId == null) continue;

                    Trait trait = plugin.getTraitRegistry().getOrNull(NamespacedId.fromString(typeId));
                    if (trait == null) {
                        plugin.logger().warn("Failed to load trait modifier for player " + uuid + " because " + typeId + " is not a registered trait");
                        continue;
                    }

                    String modifierName = resultSet.getString("modifier_name");
                    double value = resultSet.getDouble("modifier_value");
                    Operation operation = Operation.fromSqlId(resultSet.getByte("modifier_operation"));

                    TraitModifier modifier = new TraitModifier(modifierName, trait, value, operation);

                    loadTemporary(resultSet, modifier);

                    modifiers.put(modifierName, modifier);
                }
            }
        }
        return modifiers;
    }

    private void loadTemporary(ResultSet resultSet, AuraSkillsModifier<?> modifier) throws SQLException {
        long expirationTime = resultSet.getLong("expiration_time");
        long remainingDuration = resultSet.getLong("remaining_duration");
        boolean pauseOffline = false;
        if (remainingDuration != 0) {
            expirationTime = System.currentTimeMillis() + remainingDuration;
            pauseOffline = true;
        }

        if (expirationTime != 0) {
            modifier.makeTemporary(expirationTime, pauseOffline);
        }
    }

    @Override
    public @NotNull UserState loadState(UUID uuid) throws Exception {
        String query = "SELECT * FROM " + TABLE_PREFIX + "users WHERE player_uuid=?";
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) { // If the player doesn't exist in the database
                        return UserState.createEmpty(uuid, plugin);
                    }
                    int userId = resultSet.getInt("user_id");
                    // Load skill levels and xp
                    SkillLevelMaps skillLevelMaps = loadSkillLevels(connection, uuid, userId);
                    // Load stat modifiers
                    Map<String, StatModifier> statModifiers = loadStatModifiers(connection, uuid, userId);
                    // Load trait modifiers
                    Map<String, TraitModifier> traitModifiers = loadTraitModifiers(connection, uuid, userId);
                    // Load mana
                    double mana = resultSet.getDouble("mana");

                    connection.close();
                    return new UserState(uuid, skillLevelMaps.levels(), skillLevelMaps.xp(), statModifiers, traitModifiers, mana);
                }
            }
        }
    }

    @Override
    public void applyState(UserState state) throws Exception {
        // Insert into users database
        String usersQuery = "INSERT INTO " + TABLE_PREFIX + "users (player_uuid, mana) VALUES (?, ?) ON DUPLICATE KEY UPDATE mana=?";
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(usersQuery)) {
                statement.setString(1, state.uuid().toString());
                statement.setDouble(2, state.mana());
                statement.setDouble(3, state.mana());
                statement.executeUpdate();
            }
            // Insert into skill_levels database
            int userId = getUserId(connection, state.uuid());
            String skillLevelsQuery = "INSERT INTO " + TABLE_PREFIX + "skill_levels (user_id, skill_name, skill_level, skill_xp) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE skill_level=?, skill_xp=?";
            try (PreparedStatement statement = connection.prepareStatement(skillLevelsQuery)) {
                statement.setInt(1, userId);
                for (Map.Entry<Skill, Integer> entry : state.skillLevels().entrySet()) {
                    String skillName = entry.getKey().getId().toString();
                    int level = entry.getValue();
                    double xp = state.skillXp().get(entry.getKey());
                    statement.setString(2, skillName);
                    statement.setInt(3, level);
                    statement.setDouble(4, xp);
                    statement.setInt(5, level);
                    statement.setDouble(6, xp);
                    statement.executeUpdate();
                }
            }
            Map<String, AuraSkillsModifier<?>> modifiers = new HashMap<>();
            modifiers.putAll(state.statModifiers());
            modifiers.putAll(state.traitModifiers());

            saveModifierRows(connection, userId, getModifierRows(modifiers));
        }
    }

    public int getUserId(Connection connection, UUID uuid) throws SQLException {
        // Get user_id from users database
        String query = "SELECT user_id FROM " + TABLE_PREFIX + "users WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("user_id");
                } else {
                    throw new RuntimeException("Failed to get user_id for player " + uuid);
                }
            }
        }
    }

    @Override
    public void save(@NotNull User user) throws Exception {
        if (user.shouldNotSave()) return;

        // Don't save blank profiles if the option is disabled
        if (!plugin.configBoolean(Option.SAVE_BLANK_PROFILES) && user.isBlankProfile()) {
            try (Connection connection = pool.getConnection()) {
                deleteUser(connection, user);
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                plugin.logger().severe("Error deleting blank profile of user with UUID " + user.getUuid());
                throw e;
            }
            return;
        }

        try (Connection connection = pool.getConnection()) {
            saveUsersTable(connection, user);
            saveSkillLevelsTable(connection, user);
            int userId = getUserId(connection, user.getUuid());
            saveKeyValuesTable(connection, user, userId);
            saveModifiersTable(connection, user, userId);
            saveLogsTable(connection, user);
        }
    }

    private void saveUsersTable(Connection connection, User user) throws SQLException {
        String usersQuery = "INSERT INTO " + TABLE_PREFIX + "users (player_uuid, locale, mana) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE locale=?, mana=?";
        try (PreparedStatement statement = connection.prepareStatement(usersQuery)) {
            statement.setString(1, user.getUuid().toString());
            int curr = 2; // Current index to set
            for (int i = 0; i < 2; i++) { // Repeat twice to set duplicate values
                if (user.hasLocale()) {
                    statement.setString(curr++, user.getLocale().toLanguageTag());
                } else {
                    statement.setNull(curr++, Types.VARCHAR);
                }
                statement.setDouble(curr++, user.getMana());
            }
            statement.executeUpdate();
        }
    }

    private void saveSkillLevelsTable(Connection connection, User user) throws SQLException {
        int userId = getUserId(connection, user.getUuid());
        String skillLevelsQuery = "INSERT INTO " + TABLE_PREFIX + "skill_levels (user_id, skill_name, skill_level, skill_xp) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE skill_level=?, skill_xp=?";
        try (PreparedStatement statement = connection.prepareStatement(skillLevelsQuery)) {
            statement.setInt(1, userId);
            for (Map.Entry<Skill, Integer> entry : user.getSkillLevelMap().entrySet()) {
                String skillName = entry.getKey().getId().toString();
                int level = entry.getValue();
                double xp = user.getSkillXpMap().get(entry.getKey());
                statement.setString(2, skillName);
                statement.setInt(3, level);
                statement.setDouble(4, xp);
                statement.setInt(5, level);
                statement.setDouble(6, xp);
                statement.executeUpdate();
            }
        }
    }

    private void saveKeyValuesTable(Connection connection, User user, int userId) throws SQLException {
        // Delete existing key values
        deleteKeyValues(connection, userId);
        // Save key values
        List<KeyValueRow> rows = new ArrayList<>();
        rows.addAll(getAbilityDataRows(user.getAbilityDataMap(), user.getManaAbilityDataMap()));
        rows.addAll(getUnclaimedItemsRow(user.getUnclaimedItems()));
        rows.addAll(getActionBarRow(user));
        rows.addAll(getJobsRow(user, user.getJobs()));
        // Insert all key values in a batch
        saveKeyValueRows(connection, userId, rows);
    }

    private void saveModifiersTable(Connection connection, User user, int userId) throws SQLException {
        deleteModifiers(connection, userId);

        Map<String, AuraSkillsModifier<?>> modifiers = new HashMap<>();
        modifiers.putAll(user.getStatModifiers());
        modifiers.putAll(user.getTraitModifiers());

        List<ModifierRow> rows = getModifierRows(modifiers);

        saveModifierRows(connection, userId, rows);
    }

    private void saveLogsTable(Connection connection, User user) throws SQLException {
        saveAntiAfkLogs(user.getSessionAntiAfkLogs(), connection, user);
    }

    private void saveKeyValueRows(Connection connection, int userId, List<KeyValueRow> rows) throws SQLException {
        final String query = "INSERT INTO " + TABLE_PREFIX + "key_values (user_id, data_id, category_id, key_name, value) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE value=?";
        try (PreparedStatement ps = connection.prepareStatement(query)) {
            for (KeyValueRow row : rows) {
                ps.setInt(1, userId);
                ps.setInt(2, row.dataId());
                ps.setString(3, row.categoryId());
                ps.setString(4, row.keyName());
                ps.setString(5, row.value());
                ps.setString(6, row.value());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void saveModifierRows(Connection connection, int userId, List<ModifierRow> rows) throws SQLException {
        String sql = """
                INSERT INTO auraskills_modifiers (
                    user_id,
                    modifier_type,
                    type_id,
                    modifier_name,
                    modifier_value,
                    modifier_operation,
                    expiration_time,
                    remaining_duration,
                    metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    modifier_value = VALUES(modifier_value),
                    expiration_time = VALUES(expiration_time),
                    remaining_duration = VALUES(remaining_duration),
                    metadata = VALUES(metadata)
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (ModifierRow row : rows) {
                ps.setInt(1, userId);
                ps.setString(2, row.modifierType());
                if (row.typeId() != null) {
                    ps.setString(3, row.typeId());
                } else {
                    ps.setNull(3, Types.VARCHAR);
                }
                ps.setString(4, row.modifierName());
                ps.setDouble(5, row.modifierValue());
                ps.setByte(6, row.modifierOperation());
                ps.setLong(7, row.expirationTime());
                ps.setLong(8, row.remainingDuration());
                if (row.metadata() != null) {
                    ps.setString(9, row.metadata());
                } else {
                    ps.setNull(9, Types.LONGVARCHAR);
                }

                ps.addBatch();
            }

            ps.executeBatch();
        }
    }

    private void deleteUser(Connection connection, User user) throws SQLException {
        connection.setAutoCommit(false);
        String getUserIdQuery = "SELECT user_id FROM " + TABLE_PREFIX + "users WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(getUserIdQuery)) {
            statement.setString(1, user.getUuid().toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    int userId = rs.getInt("user_id");

                    String deleteKeyValuesQuery = "DELETE FROM " + TABLE_PREFIX + "key_values WHERE user_id=?;";
                    try (PreparedStatement delStatement = connection.prepareStatement(deleteKeyValuesQuery)) {
                        delStatement.setInt(1, userId);
                        delStatement.executeUpdate();
                    }

                    deleteSkillLevelsUsers(connection, userId);

                    connection.commit();
                } else {
                    connection.rollback();
                }
            }
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void deleteSkillLevelsUsers(Connection connection, int userId) throws SQLException {
        String deleteSkillLevelsQuery = "DELETE FROM " + TABLE_PREFIX + "skill_levels WHERE user_id=?;";
        try (PreparedStatement delStatement = connection.prepareStatement(deleteSkillLevelsQuery)) {
            delStatement.setInt(1, userId);
            delStatement.executeUpdate();
        }

        String deleteUsersQuery = "DELETE FROM " + TABLE_PREFIX + "users WHERE user_id=?;";
        try (PreparedStatement delStatement = connection.prepareStatement(deleteUsersQuery)) {
            delStatement.setInt(1, userId);
            delStatement.executeUpdate();
        }
    }

    private void deleteKeyValues(Connection connection, int userId) throws SQLException {
        String query = "DELETE FROM " + TABLE_PREFIX + "key_values WHERE user_id=?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    private void deleteModifiers(Connection connection, int userId) throws SQLException {
        String query = "DELETE FROM " + TABLE_PREFIX + "modifiers WHERE user_id=?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    private List<ModifierRow> getModifierRows(Map<String, AuraSkillsModifier<?>> modifiers) {
        List<ModifierRow> rows = new ArrayList<>();
        if (modifiers.isEmpty()) {
            return rows;
        }
        for (var modifier : modifiers.values()) {
            if (modifier.isNonPersistent()) {
                continue;
            }

            String statId = modifier.type().getId().toString();
            byte operationId = modifier.operation().getSqlId();
            long expTime = modifier.getExpirationTime();
            long remainingDuration = 0;
            if (modifier.isTemporary() && modifier.isPauseOffline()) {
                remainingDuration = modifier.getExpirationTime() - System.currentTimeMillis();
            }
            var row = new ModifierRow(
                    modifier instanceof StatModifier ? MODIFIER_TYPE_STAT : MODIFIER_TYPE_TRAIT,
                    statId,
                    modifier.name(),
                    modifier.value(),
                    operationId,
                    expTime,
                    remainingDuration,
                    null
            );
            rows.add(row);
        }
        return rows;
    }

    private List<KeyValueRow> getAbilityDataRows(Map<AbstractAbility, AbilityData> abilityDataMap, Map<ManaAbility, ManaAbilityData> manaAbilityDataMap) {
        List<KeyValueRow> rows = new ArrayList<>();
        if (abilityDataMap.isEmpty()) {
            return rows;
        }
        for (AbilityData abilityData : abilityDataMap.values()) {
            String categoryId = abilityData.getAbility().getId().toString();
            for (Map.Entry<String, Object> dataEntry : abilityData.getDataMap().entrySet()) {
                var row = new KeyValueRow(ABILITY_DATA_ID, categoryId, dataEntry.getKey(), String.valueOf(dataEntry.getValue()));
                rows.add(row);
            }
        }
        for (ManaAbilityData data : manaAbilityDataMap.values()) {
            if (data.getCooldown() <= 0) continue;

            String categoryId = data.getManaAbility().getId().toString();
            var row = new KeyValueRow(ABILITY_DATA_ID, categoryId, "cooldown", String.valueOf(data.getCooldown()));
            rows.add(row);
        }
        return rows;
    }

    private List<KeyValueRow> getUnclaimedItemsRow(List<KeyIntPair> unclaimedItems) {
        List<KeyValueRow> rows = new ArrayList<>();
        if (unclaimedItems.isEmpty()) {
            return rows;
        }
        for (KeyIntPair unclaimedItem : unclaimedItems) {
            var row = new KeyValueRow(UNCLAIMED_ITEMS_ID, null, unclaimedItem.getKey(), String.valueOf(unclaimedItem.getValue()));
            rows.add(row);
        }
        return rows;
    }

    private List<KeyValueRow> getActionBarRow(User user) {
        List<KeyValueRow> rows = new ArrayList<>();
        boolean shouldSave = false;
        // Only save if one of the action bars is disabled
        for (ActionBarType type : ActionBarType.values()) {
            if (!user.isActionBarEnabled(type)) {
                shouldSave = true;
            }
        }
        if (!shouldSave) {
            return rows;
        }

        ActionBarType type = ActionBarType.IDLE;
        String keyName = type.toString().toLowerCase(Locale.ROOT);
        String value = String.valueOf(user.isActionBarEnabled(type));

        var row = new KeyValueRow(ACTION_BAR_ID, null, keyName, value);
        rows.add(row);

        return rows;
    }

    private List<KeyValueRow> getJobsRow(User user, Set<Skill> jobs) {
        List<KeyValueRow> rows = new ArrayList<>();
        if (jobs.isEmpty()) {
            return rows;
        }

        String jobCommaList = String.join(",", jobs.stream().map(s -> s.getId().toString()).toList());
        var jobsRow = new KeyValueRow(JOBS_ID, null, "jobs", jobCommaList);
        rows.add(jobsRow);

        // Save the timestamp of the last time user selected a job for cooldown feature
        var timeRow = new KeyValueRow(JOBS_ID, null, JOBS_LAST_SELECT_TIME, String.valueOf(user.getLastJobSelectTime()));
        rows.add(timeRow);

        return rows;
    }

    private void saveAntiAfkLogs(List<AntiAfkLog> logs, Connection connection, User user) throws SQLException {
        connection.setAutoCommit(false);
        final String query = "INSERT IGNORE INTO " + TABLE_PREFIX + "logs (log_type, log_time, log_level, log_message, player_uuid, player_coords, world_name) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(query)) {
            for (AntiAfkLog log : logs) {
                ps.setString(1, LOG_TYPE_ANTI_AFK);
                ps.setTimestamp(2, new Timestamp(log.timestamp()));
                ps.setInt(3, LOG_LEVEL_WARN);
                ps.setString(4, log.message());
                ps.setString(5, user.getUuid().toString());
                ps.setString(6, log.coords().toString());
                ps.setString(7, log.world());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public void delete(UUID uuid) throws Exception {
        try (Connection connection = pool.getConnection()) {
            int userId = getUserId(connection, uuid);

            deleteSkillLevelsUsers(connection, userId);
        }
    }

    @Override
    public List<UserState> loadStates(boolean ignoreOnline, boolean skipModifiers) throws Exception {
        List<UserState> states = new ArrayList<>();

        Map<Integer, Map<Skill, Integer>> loadedSkillLevels = new HashMap<>();
        Map<Integer, Map<Skill, Double>> loadedSkillXp = new HashMap<>();

        try (Connection connection = pool.getConnection()) {
            String skillLevelsQuery = "SELECT user_id, skill_name, skill_level, skill_xp FROM " + TABLE_PREFIX + "skill_levels;";
            try (PreparedStatement statement = connection.prepareStatement(skillLevelsQuery)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        int userId = resultSet.getInt("user_id");
                        String skillName = resultSet.getString("skill_name");
                        Skill skill = plugin.getSkillRegistry().getOrNull(NamespacedId.fromString(skillName));
                        if (skill == null) continue;

                        int level = resultSet.getInt("skill_level");
                        double xp = resultSet.getDouble("skill_xp");

                        loadedSkillLevels.computeIfAbsent(userId, k -> new HashMap<>()).put(skill, level);
                        loadedSkillXp.computeIfAbsent(userId, k -> new HashMap<>()).put(skill, xp);
                    }
                }
            }
            String usersQuery = "SELECT user_id, player_uuid, mana FROM " + TABLE_PREFIX + "users;";
            try (PreparedStatement statement = connection.prepareStatement(usersQuery)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        int userId = resultSet.getInt("user_id");
                        UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));

                        if (ignoreOnline && userManager.hasUser(uuid)) {
                            continue; // Skip if player is online
                        }

                        double mana = resultSet.getDouble("mana");

                        Map<String, StatModifier> statModifiers;
                        Map<String, TraitModifier> traitModifiers;
                        if (!skipModifiers) {
                            statModifiers = loadStatModifiers(connection, uuid, userId);
                            traitModifiers = loadTraitModifiers(connection, uuid, userId);
                        } else {
                            statModifiers = Collections.emptyMap();
                            traitModifiers = Collections.emptyMap();
                        }

                        Map<Skill, Integer> skillLevelMap = loadedSkillLevels.getOrDefault(userId, new HashMap<>());
                        Map<Skill, Double> skillXpMap = loadedSkillXp.getOrDefault(userId, new HashMap<>());

                        UserState state = new UserState(uuid, skillLevelMap, skillXpMap, statModifiers, traitModifiers, mana);
                        states.add(state);
                    }
                }
            }
        }
        return states;
    }

    @Override
    public List<AntiAfkLog> loadAntiAfkLogs(UUID uuid) {
        try (Connection connection = pool.getConnection()) {
            String query = "SELECT log_time, log_message, player_coords, world_name FROM " + TABLE_PREFIX + "logs WHERE player_uuid=? AND log_type=?";

            List<AntiAfkLog> logs = new ArrayList<>();

            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, LOG_TYPE_ANTI_AFK);

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        long timestamp = resultSet.getTimestamp("log_time").getTime();

                        String message = resultSet.getString("log_message");
                        if (message == null) {
                            message = "";
                        }

                        String coordsStr = resultSet.getString("player_coords");
                        if (coordsStr == null) {
                            coordsStr = "";
                        }
                        var coords = BlockPosition.fromCommaString(coordsStr);

                        String worldName = resultSet.getString("world_name");

                        logs.add(new AntiAfkLog(timestamp, message, coords, worldName));
                    }
                }
            }

            return logs;
        } catch (SQLException e) {
            plugin.logger().warn("Failed to load anti-AFK logs from storage for UUID " + uuid);
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

}
