package wtf.choco.veinminer.listener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.LazyMetadataValue;
import org.bukkit.metadata.LazyMetadataValue.CacheStrategy;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import wtf.choco.veinminer.VeinMinerPlayer;
import wtf.choco.veinminer.VeinMinerPlugin;
import wtf.choco.veinminer.VeinMinerServer;
import wtf.choco.veinminer.anticheat.AntiCheatHook;
import wtf.choco.veinminer.api.event.player.PlayerVeinMineEvent;
import wtf.choco.veinminer.block.BlockList;
import wtf.choco.veinminer.block.VeinMinerBlock;
import wtf.choco.veinminer.config.VeinMiningConfig;
import wtf.choco.veinminer.economy.SimpleEconomy;
import wtf.choco.veinminer.integration.WorldGuardIntegration;
import wtf.choco.veinminer.manager.VeinMinerManager;
import wtf.choco.veinminer.metrics.StatTracker;
import wtf.choco.veinminer.pattern.VeinMiningPattern;
import wtf.choco.veinminer.platform.BukkitAdapter;
import wtf.choco.veinminer.platform.PlatformPlayer;
import wtf.choco.veinminer.platform.world.BlockAccessor;
import wtf.choco.veinminer.platform.world.BlockState;
import wtf.choco.veinminer.platform.world.BlockType;
import wtf.choco.veinminer.tool.VeinMinerToolCategory;
import wtf.choco.veinminer.tool.VeinMinerToolCategoryHand;
import wtf.choco.veinminer.util.BlockPosition;
import wtf.choco.veinminer.util.VMConstants;
import wtf.choco.veinminer.util.VMEventFactory;

public final class BreakBlockListener implements Listener {

    private final VeinMinerPlugin plugin;

    public BreakBlockListener(@NotNull VeinMinerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    private void onBlockBreak(BlockBreakEvent event) {
        if (event.getClass() != BlockBreakEvent.class) { // For plugins such as McMMO, who fire custom events
            return;
        }

        Block origin = event.getBlock();
        if (origin.hasMetadata(VMConstants.METADATA_KEY_TO_BE_VEINMINED)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        VeinMinerToolCategory category = plugin.getToolCategoryRegistry().get(BukkitAdapter.adapt(item), cat -> player.hasPermission(VMConstants.PERMISSION_VEINMINE.apply(cat)));
        if (category == null) {
            return;
        }

        VeinMinerManager veinMinerManager = plugin.getVeinMinerManager();
        BlockData originBlockData = origin.getBlockData();
        BlockState originBlockState = BukkitAdapter.adapt(originBlockData);

        if (!veinMinerManager.isVeinMineable(originBlockState, category)) {
            return;
        }

        // Invalid player state check
        PlatformPlayer platformPlayer = BukkitAdapter.adapt(player);
        VeinMinerPlayer veinMinerPlayer = plugin.getPlayerManager().get(platformPlayer);
        if (veinMinerPlayer == null) {
            return;
        }

        VeinMiningConfig veinMinerConfig = category.getConfig();

        if (!veinMinerPlayer.isVeinMinerActive()
                || !veinMinerPlayer.isVeinMinerEnabled(category)
                || veinMinerManager.isDisabledGameMode(BukkitAdapter.adapt(player.getGameMode()))
                || veinMinerConfig.isDisabledWorld(origin.getWorld().getName())
                || !player.hasPermission(VMConstants.PERMISSION_VEINMINE.apply(category))) {
            return;
        }

        // WorldGuard check
        boolean worldGuard = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
        if (worldGuard && !WorldGuardIntegration.queryFlagVeinMiner(origin, player)) {
            player.sendMessage(ChatColor.GRAY + "You are not allowed to vein mine in this area.");
            return;
        }

        // Economy check
        SimpleEconomy economy = VeinMinerServer.getInstance().getEconomy();
        if (economy.shouldCharge(platformPlayer)) {
            if (!economy.hasSufficientBalance(platformPlayer, veinMinerConfig.getCost())) {
                player.sendMessage(ChatColor.GRAY + "You have insufficient funds to vein mine (Required: " + ChatColor.YELLOW + veinMinerConfig.getCost() + ChatColor.GRAY + ")");
                return;
            }

            economy.withdraw(platformPlayer, veinMinerConfig.getCost());
        }

        // Fetch the target block face
        BlockFace targetBlockFace;
        RayTraceResult rayTraceResult = player.rayTraceBlocks(6, FluidCollisionMode.NEVER);

        if (rayTraceResult == null || (targetBlockFace = rayTraceResult.getHitBlockFace()) == null) {
            return;
        }

        // TIME TO VEINMINE
        VeinMinerBlock originVeinMinerBlock = veinMinerManager.getVeinMinerBlock(originBlockState, category);
        assert originVeinMinerBlock != null; // If this is null, something is broken internally

        World world = origin.getWorld();
        BlockAccessor blockAccessor = BukkitAdapter.adapt(world);
        VeinMiningPattern pattern = veinMinerPlayer.getVeinMiningPattern();
        BlockPosition originPosition = new BlockPosition(origin.getX(), origin.getY(), origin.getZ());
        BlockList aliasBlockList = veinMinerManager.getAlias(originVeinMinerBlock);
        wtf.choco.veinminer.util.BlockFace vmBlockFace = BukkitAdapter.adapt(targetBlockFace);

        Set<BlockPosition> blockPositions = pattern.allocateBlocks(blockAccessor, originPosition, vmBlockFace, originVeinMinerBlock, veinMinerConfig, aliasBlockList);
        Set<Block> blocks = new HashSet<>();

        for (BlockPosition blockPosition : blockPositions) {
            Block block = world.getBlockAt(blockPosition.x(), blockPosition.y(), blockPosition.z());

            if (block.isEmpty()) {
                continue;
            }

            blocks.add(block);
        }

        if (blockPositions.isEmpty()) {
            return;
        }

        // Fire a new PlayerVeinMineEvent
        PlayerVeinMineEvent veinmineEvent = VMEventFactory.callPlayerVeinMineEvent(player, origin, originVeinMinerBlock, item, category, blocks, pattern);
        if (veinmineEvent.isCancelled() || blockPositions.isEmpty()) {
            return;
        }

        // Apply metadata to all blocks to be vein mined and all other relevant objects/entities
        veinMinerPlayer.setVeinMining(true);
        blocks.forEach(block -> {
            block.setMetadata(VMConstants.METADATA_KEY_TO_BE_VEINMINED, new FixedMetadataValue(plugin, true));
            block.setMetadata(VMConstants.METADATA_KEY_VEINMINER_SOURCE, new LazyMetadataValue(plugin, CacheStrategy.CACHE_ETERNALLY, origin::getLocation));
        });

        // Anticheat support
        List<AntiCheatHook> hooks = plugin.getAnticheatHooks();
        hooks.forEach(h -> h.exempt(player));

        // Actually destroying the allocated blocks
        FileConfiguration config = plugin.getConfig();
        int maxDurability = item.getType().getMaxDurability() - (config.getBoolean(VMConstants.CONFIG_REPAIR_FRIENDLY, false) ? 1 : 0);
        float hungerModifier = ((float) Math.max((config.getDouble(VMConstants.CONFIG_HUNGER_HUNGER_MODIFIER)), 0.0D)) * 0.025F;
        int minimumFoodLevel = Math.max(config.getInt(VMConstants.CONFIG_HUNGER_MINIMUM_FOOD_LEVEL), 0);

        String hungryMessage = config.getString(VMConstants.CONFIG_HUNGER_HUNGRY_MESSAGE, "");
        if (hungryMessage == null) {
            hungryMessage = "";
        }

        hungryMessage = ChatColor.translateAlternateColorCodes('&', hungryMessage);
        boolean isHandCategory = category instanceof VeinMinerToolCategoryHand;
        boolean shouldApplyHunger = !player.hasPermission(VMConstants.PERMISSION_FREE_HUNGER);

        for (Block block : blocks) {
            // Apply hunger
            if (hungerModifier != 0.0 && shouldApplyHunger) {
                this.applyHungerDebuff(player, hungerModifier);

                if (player.getFoodLevel() <= minimumFoodLevel) {
                    if (!hungryMessage.isEmpty()) {
                        player.sendMessage(hungryMessage);
                    }

                    break;
                }
            }

            // Check for tool damage
            if (maxDurability > 0 && !isHandCategory) {
                if (item == null || item.getType().isAir()) {
                    break;
                }

                ItemMeta meta = item.getItemMeta();
                if (meta == null || ((Damageable) meta).getDamage() >= maxDurability) {
                    break;
                }
            }

            // Break the block
            BlockType blockType = BukkitAdapter.adaptBlock(block.getType());
            if (block == origin || player.breakBlock(block)) {
                StatTracker.accumulateVeinMinedMaterial(blockType);
            }
        }

        // Remove applied metadata
        veinMinerPlayer.setVeinMining(false);
        blocks.forEach(block -> {
            block.removeMetadata(VMConstants.METADATA_KEY_TO_BE_VEINMINED, plugin);
            block.removeMetadata(VMConstants.METADATA_KEY_VEINMINER_SOURCE, plugin);
        });

        // VEINMINER - DONE

        // Unexempt from anticheats
        hooks.stream().filter(h -> h.shouldUnexempt(player)).forEach(h -> h.unexempt(player));
    }

    // Modified version of https://github.com/portablejim/VeinMiner/blob/1.9/src/main/java/portablejim/veinminer/core/MinerInstance.java#L231-L254
    private void applyHungerDebuff(Player player, float hungerModifier) {
        int foodLevel = player.getFoodLevel();
        float saturation = player.getSaturation();
        float exhaustion = player.getExhaustion();

        exhaustion = (exhaustion + hungerModifier) % 4;
        saturation -= (int) ((exhaustion + hungerModifier) / 4);

        if (saturation < 0) {
            foodLevel += saturation;
            saturation = 0;
        }

        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
    }

}
