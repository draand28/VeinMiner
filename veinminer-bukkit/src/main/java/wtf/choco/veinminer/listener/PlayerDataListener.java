package wtf.choco.veinminer.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.LazyMetadataValue;
import org.bukkit.metadata.LazyMetadataValue.CacheStrategy;
import org.jetbrains.annotations.NotNull;

import wtf.choco.veinminer.VeinMinerPlayer;
import wtf.choco.veinminer.VeinMinerPlugin;
import wtf.choco.veinminer.VeinMinerServer;
import wtf.choco.veinminer.network.protocol.clientbound.PluginMessageClientboundSetPattern;
import wtf.choco.veinminer.platform.BukkitAdapter;
import wtf.choco.veinminer.platform.PlatformPlayer;
import wtf.choco.veinminer.util.VMConstants;

public final class PlayerDataListener implements Listener {

    private final VeinMinerPlugin plugin;

    public PlayerDataListener(@NotNull VeinMinerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent event) {
        Player bukkitPlayer = event.getPlayer();
        PlatformPlayer platformPlayer = BukkitAdapter.adapt(bukkitPlayer);
        VeinMinerPlayer veinMinerPlayer = plugin.getPlayerManager().getOrRegister(platformPlayer, () -> VeinMinerServer.getInstance().createClientConfig(platformPlayer));

        VeinMinerServer.getInstance().getPersistentDataStorage().load(veinMinerPlayer).whenComplete((player, e) -> {
            if (e != null) {
                e.printStackTrace();
                return;
            }

            bukkitPlayer.setMetadata(VMConstants.METADATA_KEY_VEIN_MINER_ACTIVE, new LazyMetadataValue(plugin, CacheStrategy.NEVER_CACHE, player::isVeinMinerActive));
            bukkitPlayer.setMetadata(VMConstants.METADATA_KEY_VEINMINING, new LazyMetadataValue(plugin, CacheStrategy.NEVER_CACHE, player::isVeinMining));

            // Update the selected pattern on the client
            player.executeWhenClientIsReady(() -> player.sendMessage(new PluginMessageClientboundSetPattern(player.getVeinMiningPattern().getKey())));
        });
    }

    @EventHandler
    private void onPlayerLeave(PlayerQuitEvent event) {
        PlatformPlayer platformPlayer = BukkitAdapter.adapt(event.getPlayer());
        VeinMinerPlayer veinMinerPlayer = plugin.getPlayerManager().remove(platformPlayer);
        if (veinMinerPlayer == null || !veinMinerPlayer.isDirty()) {
            return;
        }

        VeinMinerServer.getInstance().getPersistentDataStorage().save(veinMinerPlayer).exceptionally(e -> {
            e.printStackTrace();
            return null;
        });
    }

}
