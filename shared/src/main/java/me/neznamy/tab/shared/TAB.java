package me.neznamy.tab.shared;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.yaml.snakeyaml.error.YAMLException;

import me.neznamy.tab.api.PermissionPlugin;
import me.neznamy.tab.api.PlaceholderManager;
import me.neznamy.tab.api.Platform;
import me.neznamy.tab.api.ProtocolVersion;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.bossbar.BossBarManager;
import me.neznamy.tab.api.config.ConfigurationFile;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import me.neznamy.tab.api.team.ScoreboardTeamManager;
import me.neznamy.tab.shared.command.DisabledCommand;
import me.neznamy.tab.shared.command.TabCommand;
import me.neznamy.tab.shared.config.Configs;
import me.neznamy.tab.shared.cpu.CPUManager;
import me.neznamy.tab.shared.cpu.UsageType;
import me.neznamy.tab.shared.features.AlignedSuffix;
import me.neznamy.tab.shared.features.BelowName;
import me.neznamy.tab.shared.features.GhostPlayerFix;
import me.neznamy.tab.shared.features.GroupRefresher;
import me.neznamy.tab.shared.features.HeaderFooter;
import me.neznamy.tab.shared.features.NameTag;
import me.neznamy.tab.shared.features.PingSpoof;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.Playerlist;
import me.neznamy.tab.shared.features.PluginInfo;
import me.neznamy.tab.shared.features.SpectatorFix;
import me.neznamy.tab.shared.features.YellowNumber;
import me.neznamy.tab.shared.features.layout.Layout;
import me.neznamy.tab.shared.features.scoreboard.ScoreboardManagerImpl;
import me.neznamy.tab.shared.proxy.ProxyTabPlayer;

/**
 * Universal variable and method storage
 */
public class TAB extends TabAPI {

	//plugin instance
	private static TAB instance;

	//version of plugin
	public static final String PLUGIN_VERSION = "3.0.0-SNAPSHOT";

	//player data
	private final Map<UUID, TabPlayer> data = new ConcurrentHashMap<>();

	//the command
	private TabCommand command;

	//command used if plugin is disabled due to a broken configuration file
	private final DisabledCommand disabledCommand = new DisabledCommand();

	//platform interface
	private Platform platform;

	//cpu manager
	private CPUManager cpu;

	//error manager
	private ErrorManagerImpl errorManager;

	//permission plugin interface
	private PermissionPlugin permissionPlugin;

	//feature manager
	private FeatureManagerImpl featureManager;

	//name of broken configuration file filled on load and used in disabledCommand
	private String brokenFile = "-";

	private Configs configuration;

	private boolean debugMode;

	private boolean disabled;

	private PlaceholderManagerImpl placeholderManager;

	//server version, always using latest on proxies
	private ProtocolVersion serverVersion;

	public TAB(Platform platform, ProtocolVersion serverVersion) {
		this.platform = platform;
		this.serverVersion = serverVersion;
		TabAPI.setInstance(this);
	}

	@Override
	public Collection<TabPlayer> getOnlinePlayers(){
		return data.values();
	}

	/**
	 * Returns player by tablist uuid. This is required due to Velocity as player uuid and tablist uuid do ont match there
	 * @param tablistId - tablist id of player
	 * @return the player or null if not found
	 */
	public TabPlayer getPlayerByTablistUUID(UUID tablistId) {
		for (TabPlayer p : data.values()) {
			if (p.getTablistUUID().equals(tablistId)) return p;
		}
		return null;
	}

	/**
	 * Sends console message with tab prefix and specified message and color
	 * @param color - color to use
	 * @param message - message to send
	 */
	public void print(char color, String message) {
		platform.sendConsoleMessage("&" + color + "[TAB] " + message, true);
	}

	/**
	 * Sends a console message with debug prefix if debug is enabled in config
	 * @param message - message to be sent into console
	 */
	public void debug(String message) {
		if (debugMode) platform.sendConsoleMessage("&9[TAB DEBUG] " + message, true);
	}

	/**
	 * Loads the entire plugin
	 */
	public String load() {
		try {
			long time = System.currentTimeMillis();
			this.errorManager = new ErrorManagerImpl(this);
			cpu = new CPUManager(errorManager);
			featureManager = new FeatureManagerImpl();
			configuration = new Configs(this);
			configuration.loadFiles();
			permissionPlugin = platform.detectPermissionPlugin();
			placeholderManager = new PlaceholderManagerImpl();
			featureManager.registerFeature("placeholders", placeholderManager);
			platform.loadFeatures();
			command = new TabCommand(this);
			featureManager.load();
			getOnlinePlayers().forEach(p -> ((ITabPlayer)p).markAsLoaded());
			errorManager.printConsoleWarnCount();
			print('a', "Enabled in " + (System.currentTimeMillis()-time) + "ms");
			platform.callLoadEvent();
			disabled = false;
			return configuration.getTranslation().getString("reloaded");
		} catch (YAMLException e) {
			print('c', "Did not enable due to a broken configuration file.");
			disabled = true;
			return configuration.getReloadFailedMessage().replace("%file%", brokenFile);
		} catch (Exception e) {
			errorManager.criticalError("Failed to enable. Did you just invent a new way to break the plugin by misconfiguring it?", e);
			disabled = true;
			return "&cFailed to enable due to an internal plugin error. Check console for more info.";
		}
	}

	/**
	 * Properly unloads the entire plugin
	 */
	public void unload() {
		if (disabled) return;
		disabled = true;
		try {
			long time = System.currentTimeMillis();
			cpu.cancelAllTasks();
			if (configuration.getMysql() != null) configuration.getMysql().closeConnection();
			featureManager.unload();
			data.clear();
			platform.sendConsoleMessage("&a[TAB] Disabled in " + (System.currentTimeMillis()-time) + "ms", true);
		} catch (Exception e) {
			data.clear();
			errorManager.criticalError("Failed to disable", e);
		}
	}

	/**
	 * Loads universal features present on all platforms with the same configuration
	 */
	public void loadUniversalFeatures() {
		if (configuration.getConfig().getBoolean("header-footer.enabled", true)) featureManager.registerFeature("headerfooter", new HeaderFooter());
		if (configuration.isRemoveGhostPlayers()) featureManager.registerFeature("ghostplayerfix", new GhostPlayerFix());
		if (serverVersion.getMinorVersion() >= 8 && configuration.getConfig().getBoolean("tablist-name-formatting.enabled", true)) {
			Playerlist playerlist = new Playerlist();
			featureManager.registerFeature("playerlist", playerlist);
			if (configuration.getConfig().getBoolean("tablist-name-formatting.align-tabsuffix-on-the-right", false)) featureManager.registerFeature("alignedsuffix", new AlignedSuffix(playerlist));
		}
		if (configuration.getConfig().getBoolean("ping-spoof.enabled", false)) featureManager.registerFeature("pingspoof", new PingSpoof());
		if (configuration.getConfig().getBoolean("yellow-number-in-tablist.enabled", true)) featureManager.registerFeature("tabobjective", new YellowNumber());
		if (configuration.getConfig().getBoolean("prevent-spectator-effect.enabled", false)) featureManager.registerFeature("spectatorfix", new SpectatorFix());
		if (configuration.getConfig().getBoolean("belowname-objective.enabled", true)) featureManager.registerFeature("belowname", new BelowName());
		if (configuration.getConfig().getBoolean("scoreboard.enabled", false)) featureManager.registerFeature("scoreboard", new ScoreboardManagerImpl());
		if (configuration.getLayout().getBoolean("enabled", false)) featureManager.registerFeature("layout", new Layout());
		featureManager.registerFeature("group", new GroupRefresher());
		featureManager.registerFeature("info", new PluginInfo());
		if (platform.getSeparatorType().equals("server")) {
			TAB.getInstance().getCPUManager().startRepeatingMeasuredTask(1000, "refreshing player world", "World refreshing", UsageType.REPEATING_TASK, () -> {
				
				for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
					String world = ((ProxyTabPlayer)all).getAttribute("world");
					if (!all.getWorld().equals(world)){
						((ITabPlayer)all).setWorld(world);
						TAB.getInstance().getFeatureManager().onWorldChange(all.getUniqueId(), all.getWorld());
					}
				}
			});
		}
	}

	public void addPlayer(TabPlayer player) {
		data.put(player.getUniqueId(), player);
	}

	public void removePlayer(TabPlayer player) {
		data.remove(player.getUniqueId());
	}

	public static TAB getInstance() {
		return instance;
	}

	public static void setInstance(TAB instance) {
		TAB.instance = instance;
	}

	@Override
	public FeatureManagerImpl getFeatureManager() {
		return featureManager;
	}

	@Override
	public Platform getPlatform() {
		return platform;
	}

	public CPUManager getCPUManager() {
		return cpu;
	}

	@Override
	public ErrorManagerImpl getErrorManager() {
		return errorManager;
	}

	public Configs getConfiguration() {
		return configuration;
	}

	@Override
	public ProtocolVersion getServerVersion() {
		return serverVersion;
	}

	public boolean isDisabled() {
		return disabled;
	}

	public TabCommand getCommand() {
		return command;
	}

	public PermissionPlugin getPermissionPlugin() {
		return permissionPlugin;
	}

	public void setDebugMode(boolean debug) {
		debugMode = debug;
	}

	@Override
	public void setBrokenFile(String file) {
		brokenFile = file;
	}

	public String getBrokenFile() {
		return brokenFile;
	}

	public DisabledCommand getDisabledCommand() {
		return disabledCommand;
	}

	public boolean isDebugMode() {
		return debugMode;
	}

	@Override
	public BossBarManager getBossBarManager() {
		return (BossBarManager) featureManager.getFeature("bossbar");
	}

	@Override
	public ScoreboardManager getScoreboardManager() {
		return (ScoreboardManager) featureManager.getFeature("scoreboard");
	}

	@Override
	public ScoreboardTeamManager getScoreboardTeamManager() {
		if (featureManager.isFeatureEnabled("nametag16")) return (NameTag) featureManager.getFeature("nametag16");
		return (NameTag) featureManager.getFeature("nametagx");
	}

	@Override
	public PlaceholderManager getPlaceholderManager() {
		return placeholderManager;
	}

	@Override
	public TabPlayer getPlayer(String name) {
		for (TabPlayer p : data.values()) {
			if (p.getName().equals(name)) return p;
		}
		return null;
	}

	@Override
	public TabPlayer getPlayer(UUID uniqueId) {
		return data.get(uniqueId);
	}

	@Override
	public ConfigurationFile getConfig() {
		return configuration.getConfig();
	}
}