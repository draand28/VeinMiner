package wtf.choco.veinminer;

import com.google.common.base.Enums;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import wtf.choco.veinminer.anticheat.AntiCheatHook;
import wtf.choco.veinminer.anticheat.AntiCheatHookAAC;
import wtf.choco.veinminer.anticheat.AntiCheatHookAntiAura;
import wtf.choco.veinminer.anticheat.AntiCheatHookLightAntiCheat;
import wtf.choco.veinminer.anticheat.AntiCheatHookMatrix;
import wtf.choco.veinminer.anticheat.AntiCheatHookNCP;
import wtf.choco.veinminer.anticheat.AntiCheatHookSpartan;
import wtf.choco.veinminer.block.BlockList;
import wtf.choco.veinminer.command.CommandBlocklist;
import wtf.choco.veinminer.command.CommandToollist;
import wtf.choco.veinminer.command.CommandVeinMiner;
import wtf.choco.veinminer.config.ClientConfig;
import wtf.choco.veinminer.config.VeinMinerConfig;
import wtf.choco.veinminer.data.PersistentDataStorage;
import wtf.choco.veinminer.data.PersistentDataStorageJSON;
import wtf.choco.veinminer.data.PersistentDataStorageMySQL;
import wtf.choco.veinminer.data.PersistentDataStorageNoOp;
import wtf.choco.veinminer.data.PersistentDataStorageSQLite;
import wtf.choco.veinminer.economy.EconomyModifier;
import wtf.choco.veinminer.economy.EmptyEconomyModifier;
import wtf.choco.veinminer.economy.VaultBasedEconomyModifier;
import wtf.choco.veinminer.integration.PlaceholderExpansionVeinMiner;
import wtf.choco.veinminer.integration.WorldGuardIntegration;
import wtf.choco.veinminer.listener.BreakBlockListener;
import wtf.choco.veinminer.listener.ItemCollectionListener;
import wtf.choco.veinminer.listener.McMMOIntegrationListener;
import wtf.choco.veinminer.listener.PlayerDataListener;
import wtf.choco.veinminer.manager.VeinMinerManager;
import wtf.choco.veinminer.manager.VeinMinerPlayerManager;
import wtf.choco.veinminer.metrics.AntiCheat;
import wtf.choco.veinminer.metrics.StatTracker;
import wtf.choco.veinminer.network.BukkitChannelHandler;
import wtf.choco.veinminer.pattern.PatternRegistry;
import wtf.choco.veinminer.pattern.VeinMiningPattern;
import wtf.choco.veinminer.pattern.VeinMiningPatternDefault;
import wtf.choco.veinminer.pattern.VeinMiningPatternStaircase;
import wtf.choco.veinminer.pattern.VeinMiningPatternStaircase.Direction;
import wtf.choco.veinminer.pattern.VeinMiningPatternTunnel;
import wtf.choco.veinminer.platform.BukkitServerPlatform;
import wtf.choco.veinminer.platform.GameMode;
import wtf.choco.veinminer.tool.ToolCategoryRegistry;
import wtf.choco.veinminer.tool.VeinMinerToolCategory;
import wtf.choco.veinminer.tool.VeinMinerToolCategoryHand;
import wtf.choco.veinminer.util.ConfigWrapper;
import wtf.choco.veinminer.util.UpdateChecker;
import wtf.choco.veinminer.util.UpdateChecker.UpdateReason;
import wtf.choco.veinminer.util.VMConstants;

/**
 * The VeinMiner {@link JavaPlugin} class.
 */
public final class VeinMinerPlugin extends JavaPlugin {

    private static VeinMinerPlugin instance;

    private final List<AntiCheatHook> anticheatHooks = new ArrayList<>();

    private final BukkitChannelHandler channelHandler = new BukkitChannelHandler(this);
    private final VeinMinerPlayerManager playerManager = new VeinMinerPlayerManager();

    private EconomyModifier economyModifier;

    private PersistentDataStorage persistentDataStorage;

    private ConfigWrapper categoriesConfig;

    @Override
    public void onLoad() {
        VeinMinerPlugin.instance = this;

        VeinMinerServer veinMiner = VeinMinerServer.getInstance();
        veinMiner.setPlatform(BukkitServerPlatform.getInstance());

        PatternRegistry patternRegistry = veinMiner.getPatternRegistry();
        patternRegistry.register(VeinMiningPatternDefault.getInstance());
        patternRegistry.register(new VeinMiningPatternTunnel());
        patternRegistry.register(new VeinMiningPatternStaircase(Direction.UP));
        patternRegistry.register(new VeinMiningPatternStaircase(Direction.DOWN));

        VeinMiner.PROTOCOL.registerChannels(channelHandler);

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            this.getLogger().info("Found WorldGuard. Registering custom region flag.");
            WorldGuardIntegration.init(this);
        }
    }

    @Override
    public void onEnable() {
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();

        this.reloadGeneralConfig();

        // Assign persistent storage
        this.setupPersistentStorage();

        // Configuration handling
        this.categoriesConfig = new ConfigWrapper(this, "categories.yml");

        // Fetching the default pattern to use for all players that have not yet explicitly set one
        String defaultVeinMiningPatternId = getConfig().getString(VMConstants.CONFIG_DEFAULT_VEIN_MINING_PATTERN, getDefaultVeinMiningPattern().getKey().toString());
        assert defaultVeinMiningPatternId != null;

        this.setDefaultVeinMiningPattern(getPatternRegistry().getOrDefault(defaultVeinMiningPatternId, getDefaultVeinMiningPattern()));

        // Enable anti cheat hooks if required
        PluginManager manager = Bukkit.getPluginManager();
        this.registerAntiCheatHookIfEnabled(manager, "AAC5", AntiCheatHookAAC::new);
        this.registerAntiCheatHookIfEnabled(manager, "AntiAura", () -> new AntiCheatHookAntiAura(this));
        this.registerAntiCheatHookIfEnabled(manager, "LightAntiCheat", () -> new AntiCheatHookLightAntiCheat(this));
        this.registerAntiCheatHookIfEnabled(manager, "Matrix", AntiCheatHookMatrix::new);
        this.registerAntiCheatHookIfEnabled(manager, "NoCheatPlus", () -> new AntiCheatHookNCP(this));
        this.registerAntiCheatHookIfEnabled(manager, "Spartan", () -> new AntiCheatHookSpartan(this));

        // Register events
        this.getLogger().info("Registering events");
        manager.registerEvents(new BreakBlockListener(this), this);
        manager.registerEvents(new ItemCollectionListener(this), this);
        manager.registerEvents(new PlayerDataListener(this), this);

        Plugin mcMMOPlugin = manager.getPlugin("mcMMO");
        if (mcMMOPlugin != null && manager.isPluginEnabled("mcMMO")) {
            // Integrate with McMMO, but don't integrate with mcMMO-Classic, version 1.x
            if (!mcMMOPlugin.getDescription().getVersion().startsWith("1")) {
                manager.registerEvents(new McMMOIntegrationListener(this), this);
            }
            else if (getConfig().getBoolean(VMConstants.CONFIG_NERF_MCMMO, false)) {
                this.getLogger().warning(VMConstants.CONFIG_NERF_MCMMO + " is enabled but McMMO-Classic is installed.");
                this.getLogger().warning("This version of McMMO is not supported and therefore this configuration option will not work!");
                this.getLogger().warning("Consider updating your version of McMMO.");
            }
        }

        Plugin placeholderAPIPlugin = manager.getPlugin("PlaceholderAPI");
        if (placeholderAPIPlugin != null && manager.isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderExpansionVeinMiner(this).register();
        }

        // Register commands
        this.getLogger().info("Registering commands");

        this.registerCommand("blocklist", new CommandBlocklist(this));
        this.registerCommand("toollist", new CommandToollist(this));
        this.registerCommand("veinminer", new CommandVeinMiner(this, getCommandOrThrow("blocklist"), getCommandOrThrow("toollist")));

        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            this.getLogger().info("Vault found. Attempting to enable economy support...");
            this.economyModifier = new VaultBasedEconomyModifier();
            this.getLogger().info(((VaultBasedEconomyModifier) economyModifier).hasEconomyPlugin()
                    ? "Economy found! Hooked successfully."
                    : "Cancelled. No economy plugin found.");
        } else {
            this.getLogger().info("Vault not found. Economy support suspended");
            this.economyModifier = EmptyEconomyModifier.get();
        }

        // Metrics
        if (getConfig().getBoolean(VMConstants.CONFIG_METRICS_ENABLED, true)) {
            this.getLogger().info("Enabling Plugin Metrics");

            Metrics metrics = new Metrics(this, 1938); // https://bstats.org/what-is-my-plugin-id
            metrics.addCustomChart(new AdvancedPie("blocks_veinmined", StatTracker::getVeinMinedCountAsData));
            metrics.addCustomChart(new SingleLineChart("using_client_mod", playerManager::getPlayerCountUsingClientMod));
            metrics.addCustomChart(new DrilldownPie("installed_anticheats", StatTracker::getInstalledAntiCheatsAsData));

            this.getLogger().info("Thanks for enabling Metrics! The anonymous stats are appreciated");
        }

        // Load configuration data into memory
        this.getLogger().info("Loading configuration options to local memory");
        this.reloadVeinMinerManagerConfig();
        this.reloadToolCategoryRegistryConfig();

        // Special case for reloads
        this.persistentDataStorage.load(Collections2.transform(Bukkit.getOnlinePlayers(), player -> getPlayerManager().get(BukkitServerPlatform.getInstance().getPlatformPlayer(player.getUniqueId()))));

        // Update check (https://www.spigotmc.org/resources/veinminer.12038/)
        UpdateChecker updateChecker = UpdateChecker.init(this, 12038);
        if (getConfig().getBoolean(VMConstants.CONFIG_PERFORM_UPDATE_CHECKS, true)) {
            this.getLogger().info("Performing an update check!");
            updateChecker.requestUpdateCheck().whenComplete((result, exception) -> {
                if (result.isUpdateAvailable()) {
                    this.getLogger().info(String.format("An update is available! VeinMiner %s may be downloaded on SpigotMC", result.getNewestVersion()));
                    return;
                }

                UpdateReason reason = result.getReason();
                if (reason == UpdateReason.UP_TO_DATE) {
                    this.getLogger().info(String.format("Your version of VeinMiner (%s) is up to date!", result.getNewestVersion()));
                } else if (reason == UpdateReason.UNRELEASED_VERSION) {
                    this.getLogger().info(String.format("Your version of VeinMiner (%s) is more recent than the one publicly available. Are you on a development build?", result.getNewestVersion()));
                } else {
                    this.getLogger().warning("Could not check for a new version of VeinMiner. Reason: " + reason);
                }
            });
        }
    }

    @Override
    public void onDisable() {
        this.getLogger().info("Clearing localized data");
        this.getVeinMinerManager().clear();
        this.anticheatHooks.clear();

        this.getPatternRegistry().unregisterAll();
        this.getToolCategoryRegistry().unregisterAll();

        this.persistentDataStorage.save(playerManager.getAll());
    }

    /**
     * Get an instance of the main VeinMiner class (for VeinMiner API).
     *
     * @return an instance of the VeinMiner class
     */
    @NotNull
    public static VeinMinerPlugin getInstance() {
        return instance;
    }

    /**
     * Get the {@link VeinMinerManager}.
     *
     * @return the vein miner manager
     */
    @NotNull
    public VeinMinerManager getVeinMinerManager() {
        return VeinMinerServer.getInstance().getVeinMinerManager();
    }

    /**
     * Get the {@link ToolCategoryRegistry}.
     *
     * @return the tool category registry
     */
    @NotNull
    public ToolCategoryRegistry getToolCategoryRegistry() {
        return VeinMinerServer.getInstance().getToolCategoryRegistry();
    }

    /**
     * Get the {@link PatternRegistry}.
     *
     * @return the pattern registry
     */
    @NotNull
    public PatternRegistry getPatternRegistry() {
        return VeinMinerServer.getInstance().getPatternRegistry();
    }

    /**
     * Get the {@link VeinMinerPlayerManager} instance.
     *
     * @return the player manager
     */
    @NotNull
    public VeinMinerPlayerManager getPlayerManager() {
        return playerManager;
    }

    /**
     * Set the default {@link VeinMiningPattern} to be used for new players.
     *
     * @param pattern the default vein mining pattern
     */
    public void setDefaultVeinMiningPattern(@NotNull VeinMiningPattern pattern) {
        VeinMinerServer.getInstance().setDefaultVeinMiningPattern(pattern);
    }

    /**
     * Get the default {@link VeinMiningPattern} to be used for new players.
     *
     * @return the default vein mining pattern
     */
    @NotNull
    public VeinMiningPattern getDefaultVeinMiningPattern() {
        return VeinMinerServer.getInstance().getDefaultVeinMiningPattern();
    }

    /**
     * Get the {@link PersistentDataStorage} instance used to store player data.
     *
     * @return the persistent data storage
     */
    @NotNull
    public PersistentDataStorage getPersistentDataStorage() {
        return persistentDataStorage;
    }

    /**
     * Get an instance of the categories configuration file.
     *
     * @return the categories config
     */
    @NotNull
    public ConfigWrapper getCategoriesConfig() {
        return categoriesConfig;
    }

    /**
     * Get the economy abstraction layer for a Vault economy.
     *
     * @return economy abstraction
     */
    @NotNull
    public EconomyModifier getEconomyModifier() {
        return economyModifier;
    }

    /**
     * Register an anti cheat hook to VeinMiner. Hooks should be registered for all anti cheat plugins
     * as to support VeinMining and not false-flag players with fast-break.
     *
     * @param hook the hook to register
     *
     * @return true if registered, false if unsupported
     */
    public boolean registerAntiCheatHook(@NotNull AntiCheatHook hook) {
        Preconditions.checkNotNull(hook, "Cannot register a null anticheat hook implementation");

        if (!hook.isSupported()) {
            return false;
        }

        this.anticheatHooks.add(hook);
        return true;
    }

    /**
     * Get an immutable list of all anti cheat hooks.
     *
     * @return all anti cheat hooks
     */
    @NotNull
    public List<AntiCheatHook> getAnticheatHooks() {
        return Collections.unmodifiableList(anticheatHooks);
    }

    /**
     * Reload general configuration values.
     */
    public void reloadGeneralConfig() {
        String defaultActivationStrategyString = getConfig().getString(VMConstants.CONFIG_DEFAULT_ACTIVATION_STRATEGY, "SNEAK");
        assert defaultActivationStrategyString != null;
        VeinMinerServer.getInstance().setDefaultActivationStrategy(Enums.getIfPresent(ActivationStrategy.class, defaultActivationStrategyString.toUpperCase()).or(ActivationStrategy.SNEAK));
    }

    /**
     * Reload the {@link VeinMinerManager}'s values from config into memory.
     */
    public void reloadVeinMinerManagerConfig() {
        this.getVeinMinerManager().clear();

        FileConfiguration config = getConfig();

        // Global block list and config
        this.getVeinMinerManager().setGlobalBlockList(BlockList.parseBlockList(config.getStringList("BlockList.Global"), getLogger()));
        this.getVeinMinerManager().setGlobalConfig(VeinMinerConfig.builder()
                .repairFriendly(config.getBoolean(VMConstants.CONFIG_REPAIR_FRIENDLY, false))
                .maxVeinSize(config.getInt(VMConstants.CONFIG_MAX_VEIN_SIZE, 64))
                .cost(config.getDouble(VMConstants.CONFIG_COST, 0.0))
                .disableWorlds(config.getStringList(VMConstants.CONFIG_DISABLED_WORLDS))
                .build()
        );

        // Disabled game modes
        Set<GameMode> disabledGameModes = new HashSet<>();
        for (String disabledGameModeString : config.getStringList(VMConstants.CONFIG_DISABLED_GAME_MODES)) {
            GameMode gameMode = GameMode.getById(disabledGameModeString);

            if (gameMode == null) {
                this.getLogger().info(String.format("Unrecognized game mode for input \"%s\". Did you spell it correctly?", disabledGameModeString));
                continue;
            }

            disabledGameModes.add(gameMode);
        }

        this.getVeinMinerManager().setDisabledGameModes(disabledGameModes);

        // Aliases
        int aliasesAdded = 0;
        for (String aliasString : config.getStringList(VMConstants.CONFIG_ALIASES)) {
            List<String> aliasStringEntries = List.of(aliasString.split(";"));
            if (aliasStringEntries.size() <= 1) {
                this.getLogger().info(String.format("Alias \"%s\" contains %d entries but must have at least 2. Not adding.", aliasString, aliasStringEntries.size()));
                continue;
            }

            BlockList aliasBlockList = BlockList.parseBlockList(aliasStringEntries, getLogger());

            if (aliasBlockList.isEmpty()) {
                continue;
            }

            this.getVeinMinerManager().addAlias(aliasBlockList);
            aliasesAdded++;
        }

        this.getLogger().info("Added " + aliasesAdded + " aliases.");
    }

    /**
     * Reload the {@link ToolCategoryRegistry}'s values from config into memory.
     */
    public void reloadToolCategoryRegistryConfig() {
        getToolCategoryRegistry().unregisterAll(); // Unregister all the categories before re-loading them

        FileConfiguration config = getConfig();
        FileConfiguration categoriesConfig = getCategoriesConfig().asRawConfig();

        for (String categoryId : categoriesConfig.getKeys(false)) {
            if (categoryId.contains(" ")) {
                this.getLogger().info(String.format("Category id \"%s\" is invalid. Must not contain spaces (' ')", categoryId));
                continue;
            }

            if (categoryId.equalsIgnoreCase("Hand")) {
                this.getLogger().info("Redefinition of the Hand category is illegal. Ignoring.");
                continue;
            }

            ConfigurationSection categoryRoot = categoriesConfig.getConfigurationSection(categoryId);
            assert categoryRoot != null;

            VeinMinerConfig globalVeinMinerConfig = getVeinMinerManager().getGlobalConfig();

            Collection<String> disabledWorlds = categoryRoot.contains(VMConstants.CONFIG_DISABLED_WORLDS) ? categoryRoot.getStringList(VMConstants.CONFIG_DISABLED_WORLDS) : globalVeinMinerConfig.getDisabledWorlds();
            VeinMinerConfig veinMinerConfig = VeinMinerConfig.builder()
                    .repairFriendly(categoryRoot.getBoolean(VMConstants.CONFIG_REPAIR_FRIENDLY, globalVeinMinerConfig.isRepairFriendly()))
                    .maxVeinSize(categoryRoot.getInt(VMConstants.CONFIG_MAX_VEIN_SIZE, globalVeinMinerConfig.getMaxVeinSize()))
                    .cost(categoryRoot.getDouble(VMConstants.CONFIG_COST, globalVeinMinerConfig.getCost()))
                    .disableWorlds(disabledWorlds)
                    .build();

            Set<Material> items = EnumSet.noneOf(Material.class);
            for (String materialIdString : categoryRoot.getStringList("Items")) {
                Material material = Material.matchMaterial(materialIdString);

                if (material == null) {
                    this.getLogger().info(String.format("Unknown item for input \"%s\". Did you spell it correctly?", materialIdString));
                    continue;
                }

                if (!material.isItem()) {
                    this.getLogger().info(String.format("Item input \"%s\" is not an item. Ignoring...", materialIdString));
                    continue;
                }

                items.add(material);
            }

            if (items.isEmpty()) {
                this.getLogger().info(String.format("Category with id \"%s\" has no items. Ignoring registration.", categoryId));
                continue;
            }

            List<String> blockStateStrings = config.getStringList("BlockList." + categoryId);
            if (blockStateStrings.isEmpty()) {
                this.getLogger().info(String.format("No block list configured for category with id \"%s\". Ignoring registration.", categoryId));
                continue;
            }

            BlockList blocklist = BlockList.parseBlockList(blockStateStrings, getLogger());
            if (blocklist.size() == 0) {
                this.getLogger().info(String.format("No block list configured for category with id \"%s\". Ignoring registration.", categoryId));
                continue;
            }

            int priority = categoryRoot.getInt(VMConstants.CONFIG_PRIORITY, 0);
            String nbtValue = categoryRoot.getString(VMConstants.CONFIG_NBT); // Should be allowed to be null

            getToolCategoryRegistry().register(new VeinMinerToolCategory(categoryId, priority, nbtValue, blocklist, veinMinerConfig, BukkitServerPlatform.toItemType(items, HashSet::new)));
            this.getLogger().info(String.format("Registered category with id \"%s\" holding %d unique items and %d unique blocks.", categoryId, items.size(), blocklist.size()));
        }

        // Also register the hand category (required)
        getToolCategoryRegistry().register(new VeinMinerToolCategoryHand(BlockList.parseBlockList(config.getStringList("BlockList.Hand"), getLogger()), getVeinMinerManager().getGlobalConfig()));

        // Register permissions dynamically
        PluginManager pluginManager = Bukkit.getPluginManager();

        Permission veinminePermissionParent = getOrRegisterPermission(pluginManager, "veinminer.veinmine.*");
        Permission blocklistPermissionParent = getOrRegisterPermission(pluginManager, "veinminer.blocklist.list.*");
        Permission toollistPermissionParent = getOrRegisterPermission(pluginManager, "veinminer.toollist.list.*");

        for (VeinMinerToolCategory category : getToolCategoryRegistry().getAll()) {
            String id = category.getId().toLowerCase();
            Permission veinminePermission = new Permission("veinminer.veinmine." + id, "Allows players to vein mine using the " + category.getId() + " category", PermissionDefault.TRUE);
            Permission blocklistPermission = new Permission("veinminer.blocklist.list." + id, "Allows players to list blocks in the " + category.getId() + " category", PermissionDefault.OP);
            Permission toollistPermission = new Permission("veinminer.toollist.list." + id, "Allows players to list tools in the " + category.getId() + " category", PermissionDefault.OP);

            veinminePermissionParent.getChildren().put(veinminePermission.getName(), true);
            blocklistPermissionParent.getChildren().put(blocklistPermission.getName(), true);
            toollistPermissionParent.getChildren().put(toollistPermission.getName(), true);
        }

        veinminePermissionParent.recalculatePermissibles();
        blocklistPermissionParent.recalculatePermissibles();
        toollistPermissionParent.recalculatePermissibles();
    }

    /**
     * Get a {@link NamespacedKey} with VeinMiner's namespace.
     *
     * @param key the key
     *
     * @return a VeinMiner namespaced key
     */
    @NotNull
    public static NamespacedKey key(String key) {
        return new NamespacedKey(instance, key);
    }

    /**
     * Create a default {@link ClientConfig} with values supplied by {@link VeinMinerPlugin}'s
     * config.yml.
     *
     * @param player the player for whom to create the config
     *
     * @return the default client config
     */
    @NotNull
    public static ClientConfig createClientConfig(@NotNull Player player) {
        FileConfiguration config = getInstance().getConfig();

        return ClientConfig.builder()
                .allowActivationKeybind(config.getBoolean(VMConstants.CONFIG_CLIENT_ALLOW_ACTIVATION_KEYBIND, true) && player.hasPermission(VMConstants.PERMISSION_CLIENT_ACTIVATION))
                .allowPatternSwitchingKeybind(config.getBoolean(VMConstants.CONFIG_CLIENT_ALLOW_PATTERN_SWITCHING_KEYBIND, true) && player.hasPermission(VMConstants.PERMISSION_CLIENT_PATTERNS))
                .allowWireframeRendering(config.getBoolean(VMConstants.CONFIG_CLIENT_ALLOW_WIREFRAME_RENDERING, true) && player.hasPermission(VMConstants.PERMISSION_CLIENT_WIREFRAME))
                .build();
    }

    private void setupPersistentStorage() {
        String storageTypeId = getConfig().getString(VMConstants.CONFIG_STORAGE_TYPE);
        assert storageTypeId != null;
        PersistentDataStorage.Type storageType = Enums.getIfPresent(PersistentDataStorage.Type.class, storageTypeId.toUpperCase()).or(PersistentDataStorage.Type.SQLITE);

        try {
            this.persistentDataStorage = switch (storageType) {
                case JSON -> {
                    String jsonDirectoryName = getConfig().getString(VMConstants.CONFIG_STORAGE_JSON_DIRECTORY);

                    if (jsonDirectoryName == null) {
                        this.getLogger().warning("Incomplete configuration for JSON persistent storage. Requires a valid directory.");
                        yield PersistentDataStorageNoOp.INSTANCE;
                    }

                    File jsonDirectory = new File(".", jsonDirectoryName.replace("%plugin%", "plugins/" + getDataFolder().getName()));
                    yield new PersistentDataStorageJSON(jsonDirectory);
                }
                case SQLITE -> new PersistentDataStorageSQLite(getDataFolder().toPath(), "veinminer.db");
                case MYSQL -> {
                    String host = getConfig().getString(VMConstants.CONFIG_STORAGE_MYSQL_HOST);
                    int port = getConfig().getInt(VMConstants.CONFIG_STORAGE_MYSQL_PORT);
                    String username = getConfig().getString(VMConstants.CONFIG_STORAGE_MYSQL_USERNAME);
                    String password = getConfig().getString(VMConstants.CONFIG_STORAGE_MYSQL_PASSWORD);
                    String database = getConfig().getString(VMConstants.CONFIG_STORAGE_MYSQL_DATABASE);
                    String tablePrefix = getConfig().getString(VMConstants.CONFIG_STORAGE_MYSQL_TABLE_PREFIX);

                    if (host == null || database == null || username == null || password == null || tablePrefix == null) {
                        this.getLogger().warning("Incomplete configuration for MySQL persistent storage. Requires a valid host, port, database, username, password, and table prefix.");
                        yield PersistentDataStorageNoOp.INSTANCE;
                    }

                    yield new PersistentDataStorageMySQL(host, port, username, password, database, tablePrefix);
                }
                default -> {
                    this.getLogger().warning("No persistent storage is available. This may be a bug.");
                    yield PersistentDataStorageNoOp.INSTANCE;
                }
            };

            this.persistentDataStorage.init().whenComplete((result, e) -> {
                if (e != null) {
                    e.printStackTrace();
                    return;
                }

                this.getLogger().info("Using " + persistentDataStorage.getType() + " for persistent storage.");
            });
        } catch (IOException e) {
            this.getLogger().severe("Could not setup persistent file storage. Player data cannot be saved nor loaded. Investigate IMMEDIATELY.");
            e.printStackTrace();
        }
    }

    private void registerAntiCheatHookIfEnabled(@NotNull PluginManager manager, @NotNull String pluginName, @NotNull Supplier<@NotNull ? extends AntiCheatHook> hookSupplier) {
        if (!manager.isPluginEnabled(pluginName)) {
            return;
        }

        this.getLogger().info("Anti cheat detected. Enabling anti cheat support for \"" + pluginName + "\"");

        AntiCheatHook hook = hookSupplier.get();
        if (!registerAntiCheatHook(hook)) {
            this.getLogger().warning("The installed version of \"%s\" is incompatible with VeinMiner. Please contact the author of VeinMiner.".formatted(pluginName));
            return;
        }

        if (hook instanceof Listener hookListener) {
            manager.registerEvents(hookListener, this);
        }

        Plugin antiCheatPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (antiCheatPlugin != null) {
            StatTracker.recognizeInstalledAntiCheat(new AntiCheat(antiCheatPlugin.getName(), antiCheatPlugin.getDescription().getVersion()));
        }
    }

    private void registerCommand(@NotNull String commandName, @NotNull CommandExecutor executor) {
        PluginCommand command = getCommand(commandName);
        if (command == null) {
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
    }

    private PluginCommand getCommandOrThrow(@NotNull String commandName) {
        PluginCommand command = getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException("Missing command: " + commandName);
        }

        return command;
    }

    @NotNull
    private Permission getOrRegisterPermission(@NotNull PluginManager manager, @NotNull String permissionName) {
        Preconditions.checkArgument(manager != null, "manager must not be null");
        Preconditions.checkArgument(permissionName != null, "permissionName must not be null");

        Permission permission = manager.getPermission(permissionName);
        if (permission == null) {
            permission = new Permission(permissionName, PermissionDefault.OP);
            manager.addPermission(permission);
        }

        return permission;
    }

}
