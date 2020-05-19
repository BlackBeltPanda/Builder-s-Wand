package de.False.BuildersWand.events;

import com.gmail.nossr50.mcMMO;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import de.False.BuildersWand.ConfigurationFiles.Config;
import de.False.BuildersWand.Main;
import de.False.BuildersWand.api.canBuildHandler;
import de.False.BuildersWand.enums.ParticleShapeHidden;
import de.False.BuildersWand.items.Wand;
import de.False.BuildersWand.manager.InventoryManager;
import de.False.BuildersWand.manager.WandManager;
import de.False.BuildersWand.utilities.MessageUtil;
import de.False.BuildersWand.utilities.ParticleUtil;
import de.False.BuildersWand.utilities.UUIDItemTagType;

import org.apache.commons.lang.ArrayUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.tags.CustomItemTagContainer;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Stream;

public class WandEvents implements Listener {
	private Main plugin;
	private Config config;
	private ParticleUtil particleUtil;
	private WandManager wandManager;
	private InventoryManager inventoryManager;
	private HashMap<Block, List<Block>> blockSelection = new HashMap<Block, List<Block>>();
	private HashMap<Block, List<Block>> replacements = new HashMap<Block, List<Block>>();
	private HashMap<Block, List<Block>> tmpReplacements = new HashMap<Block, List<Block>>();
	public static ArrayList<canBuildHandler> canBuildHandlers = new ArrayList<canBuildHandler>();

	public WandEvents(Main plugin, Config config, ParticleUtil particleUtil, WandManager wandManager, InventoryManager inventoryManager) {
		this.plugin = plugin;
		this.config = config;
		this.particleUtil = particleUtil;
		this.wandManager = wandManager;
		this.inventoryManager = inventoryManager;
		startScheduler();
	}

	private void startScheduler() {
		Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
			@Override
			public void run() {
				blockSelection.clear();
				tmpReplacements.clear();
				for (Player player : Bukkit.getOnlinePlayers()) {
					if (player.getGameMode() == GameMode.SPECTATOR) continue;

					ItemStack mainHand = player.getInventory().getItemInMainHand();
					Wand wand = wandManager.getWand(mainHand);
					Block block = player.getTargetBlock((Set<Material>) null, 5);
					if (block.getType().equals(Material.AIR) || wand == null || player.getLocation().add(0, 1, 0).getBlock().getType() != Material.AIR) {
						continue;
					}

					List<Block> lastBlocks = player.getLastTwoTargetBlocks((Set<Material>) null, 5);
					BlockFace blockFace = lastBlocks.get(1).getFace(lastBlocks.get(0));
					Block blockNext = block.getRelative(blockFace);
					if (blockNext == null) {
						continue;
					}

					int itemCount = getItemCount(player, block, mainHand);
					blockSelection.put(block, new ArrayList<>());
					tmpReplacements.put(block, new ArrayList<>());

					setBlockSelection(player, blockFace, itemCount, block, block, wand);
					replacements = tmpReplacements;
					List<Block> selection = blockSelection.get(block);

					if (wand.isParticleEnabled()) {
						for (Block selectionBlock : selection) {
							renderBlockOutlines(blockFace, selectionBlock, selection, wand, player);
						}
					}
				}
			}
		}, 0L, config.getRenderTime());
	}

	@EventHandler
	public void placeBlock(BlockPlaceEvent event) {
		Player player = event.getPlayer();
		ItemStack mainHand = player.getInventory().getItemInMainHand();
		Wand wand = wandManager.getWand(mainHand);
		if (wand == null) {
			return;
		}

		event.setCancelled(true);
	}

	@EventHandler
	public void playerInteract(PlayerInteractEvent event) {
		Player player = event.getPlayer();

		if (player.getGameMode() == GameMode.SPECTATOR) return;

		ItemStack mainHand = player.getInventory().getItemInMainHand();
		Wand wand = wandManager.getWand(mainHand);

		if (wand == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		Block against = event.getClickedBlock();
		List<Block> selection = replacements.get(against);
		if (selection == null) {
			return;
		}

		if (!player.hasPermission("buildersWand.use") || (!player.hasPermission("buildersWand.bypass") && !isAllowedToBuildForExternalPlugins(player, selection))
				|| wand.hasPermission() && !player.hasPermission(wand.getPermission()) || !canBuildHandlerCheck(player, selection)) {
			MessageUtil.sendMessage(player, "noPermissions");
			return;
		}

		BlockData blockData = against.getBlockData();
		ItemStack itemStack = new ItemStack(against.getType());
		event.setCancelled(true);
		Bukkit.getScheduler().runTaskLater(plugin, () -> {

			for (Block selectionBlock : selection) {
				Plugin mcMMOPlugin = getExternalPlugin("mcMMO");
				if (mcMMOPlugin != null) {
					mcMMO.getPlaceStore().setTrue(selectionBlock);
				}

				selectionBlock.setBlockData(blockData);

				BlockPlaceEvent blockPlaceEvent = new BlockPlaceEvent(selectionBlock, selectionBlock.getState(), against, mainHand, player, true, EquipmentSlot.HAND);
				Bukkit.getServer().getPluginManager().callEvent(blockPlaceEvent);
			}

		}, 1L);

		Integer amount = selection.size();
		if (wand.isConsumeItems()) {
			removeItemStack(itemStack, amount, player, mainHand);
		}
		if (wand.isDurabilityEnabled() && amount >= 1) {
			removeDurability(mainHand, player, wand);
		}
	}

	private boolean canBuildHandlerCheck(Player player, List<Block> selection) {
		for (canBuildHandler canBuildHandler : canBuildHandlers) {
			for (Block selectionBlock : selection) {
				if (!canBuildHandler.canBuild(player, selectionBlock.getLocation())) {
					return false;
				}
			}
		}

		return true;
	}

	private boolean canBuildHandlerCheck(Player player, Location location) {
		for (canBuildHandler canBuildHandler : canBuildHandlers) {
			if (!canBuildHandler.canBuild(player, location)) {
				return false;
			}
		}

		return true;
	}

	@EventHandler
	private void craftItemEvent(CraftItemEvent event) {
		if (!(event.getWhoClicked() instanceof Player)) {
			return;
		}

		Player player = (Player) event.getWhoClicked();
		ItemStack result = event.getRecipe().getResult();
		Wand wand = wandManager.getWand(result);
		if (wand == null) {
			return;
		}

		if (!player.hasPermission("buildersWand.craft")) {
			MessageUtil.sendMessage(player, "noPermissions");
			event.setCancelled(true);
		}

		if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
			event.setCancelled(true);
			player.updateInventory();
			int itemsChecked = 0;
			int possibleCreations = 1;
			if (event.isShiftClick()) {
				for (ItemStack item : event.getInventory().getMatrix()) {
					if (item != null && !item.getType().equals(Material.AIR)) {
						if (itemsChecked == 0) possibleCreations = item.getAmount();
						else possibleCreations = Math.min(possibleCreations, item.getAmount());
						itemsChecked++;
					}
				}
			}
			long emptySpace = Stream.of(player.getInventory().getStorageContents()).filter(i -> i == null).count();
			int amountOfItems = Math.toIntExact(Math.min(possibleCreations, emptySpace));
			Stream.of(event.getInventory().getMatrix()).filter(i -> i != null).forEach(i -> i.setAmount(i.getAmount() - amountOfItems));
			for (int x = 0; x < amountOfItems; x++) {
				ItemStack itemStack = wand.getRecipeResult();
				ItemMeta itemMeta = itemStack.getItemMeta();
				NamespacedKey key = new NamespacedKey(plugin, "uuid");
				itemMeta.getCustomTagContainer().setCustomTag(key, new UUIDItemTagType(), UUID.randomUUID());
				itemStack.setItemMeta(itemMeta);
				player.getInventory().addItem(itemStack);
			}
			player.closeInventory();
		}
		else {
			ItemMeta itemMeta = event.getCurrentItem().getItemMeta();
			NamespacedKey key = new NamespacedKey(plugin, "uuid");
			itemMeta.getCustomTagContainer().setCustomTag(key, new UUIDItemTagType(), UUID.randomUUID());

			event.getCurrentItem().setItemMeta(itemMeta);
		}
	}

	private int getItemCount(Player player, Block block, ItemStack mainHand) {
		int count = 0;
		Inventory inventory = player.getInventory();
		Material blockMaterial = block.getType();

		if (mainHand.getType() == Material.AIR) {
			return 0;
		}

		NamespacedKey key = new NamespacedKey(plugin, "uuid");
		CustomItemTagContainer tagContainer = mainHand.getItemMeta().getCustomTagContainer();
		UUID uuid = tagContainer.getCustomTag(key, new UUIDItemTagType());

		ItemStack[] itemStacks = (ItemStack[]) ArrayUtils.addAll(inventory.getContents(), inventoryManager.getInventory(uuid));

		if (player.getGameMode() == GameMode.CREATIVE) {
			return Integer.MAX_VALUE;
		}

		for (ItemStack itemStack : itemStacks) {
			if (itemStack == null) {
				continue;
			}
			Material itemMaterial = itemStack.getType();

			if (!itemMaterial.equals(blockMaterial)) {
				continue;
			}

			count += itemStack.getAmount();
		}

		return count;
	}

	private void removeDurability(ItemStack wandItemStack, Player player, Wand wand) {
		Inventory inventory = player.getInventory();
		if (player.getGameMode() == GameMode.CREATIVE) {
			return;
		}

		Integer durability = getDurability(wandItemStack, wand);
		Integer newDurability = durability - 1;

		if (newDurability <= 0) {
			NamespacedKey key = new NamespacedKey(plugin, "uuid");
			CustomItemTagContainer tagContainer = wandItemStack.getItemMeta().getCustomTagContainer();
			UUID uuid = tagContainer.getCustomTag(key, new UUIDItemTagType());

			ItemStack[] inventoryItemStacks = inventoryManager.getInventory(uuid);
			for (ItemStack inventoryItemStack : inventoryItemStacks) {
				if (inventoryItemStack == null) {
					continue;
				}
				player.getWorld().dropItem(player.getLocation(), inventoryItemStack);
			}
			inventory.removeItem(wandItemStack);
		}

		ItemMeta itemMeta = wandItemStack.getItemMeta();
		List<String> lore = itemMeta.getLore();
		String durabilityText = MessageUtil.colorize(wand.getDurabilityText().replace("{durability}", newDurability + ""));
		if (lore == null) {
			lore = new ArrayList<>();
			lore.add(durabilityText);
		}
		else {
			lore.set(0, durabilityText);
		}

		itemMeta.setLore(lore);
		wandItemStack.setItemMeta(itemMeta);
	}

	private void removeItemStack(ItemStack itemStack, int amount, Player player, ItemStack mainHand) {
		Inventory inventory = player.getInventory();
		Material material = itemStack.getType();
		ItemStack[] itemStacks = inventory.getContents();

		if (player.getGameMode() == GameMode.CREATIVE) {
			return;
		}

		for (ItemStack inventoryItemStack : itemStacks) {
			if (inventoryItemStack == null) {
				continue;
			}
			Material itemMaterial = inventoryItemStack.getType();
			if (!itemMaterial.equals(material)) {
				continue;
			}

			int itemAmount = inventoryItemStack.getAmount();
			if (amount >= itemAmount) {

				HashMap<Integer, ItemStack> didntRemovedItems = inventory.removeItem(inventoryItemStack);

				if (didntRemovedItems.size() == 1) {
					player.getInventory().setItemInOffHand(null);
				}

				amount -= itemAmount;
				player.updateInventory();
			}
			else {
				inventoryItemStack.setAmount(itemAmount - amount);
				player.updateInventory();
				return;
			}
		}

		NamespacedKey key = new NamespacedKey(plugin, "uuid");
		CustomItemTagContainer tagContainer = mainHand.getItemMeta().getCustomTagContainer();
		UUID uuid = tagContainer.getCustomTag(key, new UUIDItemTagType());

		ItemStack[] inventoryItemStacks = inventoryManager.getInventory(uuid);
		ArrayList<ItemStack> inventoryItemStacksList = new ArrayList<>(Arrays.asList(inventoryItemStacks));
		for (ItemStack inventoryItemStack : inventoryItemStacks) {
			if (inventoryItemStack == null) {
				continue;
			}
			Material itemMaterial = inventoryItemStack.getType();
			if (!itemMaterial.equals(material)) {
				continue;
			}
			int itemAmount = inventoryItemStack.getAmount();
			if (amount >= itemAmount) {
				inventoryItemStacksList.remove(inventoryItemStack);
				amount -= itemAmount;
			}
			else {
				int index = inventoryItemStacksList.indexOf(inventoryItemStack);
				inventoryItemStack.setAmount(itemAmount - amount);
				inventoryItemStacksList.set(index, inventoryItemStack);
				inventoryManager.setInventory(uuid, inventoryItemStacksList.toArray(new ItemStack[inventoryItemStacksList.size()]));
				return;
			}
		}
		inventoryManager.setInventory(uuid, inventoryItemStacksList.toArray(new ItemStack[inventoryItemStacksList.size()]));
	}

	private void setBlockSelection(Player player, BlockFace blockFace, int maxLocations, Block startBlock, Block blockToCheck, Wand wand) {
		Location startLocation = startBlock.getLocation();
		Location checkLocation = blockToCheck.getLocation();
		Material startMaterial = startBlock.getType();
		Material blockToCheckMaterial = blockToCheck.getType();
		Material relativeBlock = blockToCheck.getRelative(blockFace).getType();
		List<Block> selection = blockSelection.get(startBlock);
		List<Block> replacementsList = tmpReplacements.get(startBlock);

		if (startLocation.distance(checkLocation) >= wand.getMaxSize() || !(startMaterial.equals(blockToCheckMaterial)) || maxLocations <= selection.size() || selection.contains(blockToCheck)
				|| !relativeBlock.equals(Material.AIR) || (!isAllowedToBuildForExternalPlugins(player, checkLocation) && !player.hasPermission("buildersWand.bypass"))
				|| !canBuildHandlerCheck(player, checkLocation) || !player.hasPermission("buildersWand.use") || wand.hasPermission() && !player.hasPermission(wand.getPermission())) {
			return;
		}

		selection.add(blockToCheck);
		replacementsList.add(blockToCheck.getRelative(blockFace));
		Block blockEast = blockToCheck.getRelative(BlockFace.EAST);
		Block blockWest = blockToCheck.getRelative(BlockFace.WEST);
		Block blockNorth = blockToCheck.getRelative(BlockFace.NORTH);
		Block blockSouth = blockToCheck.getRelative(BlockFace.SOUTH);
		Block blockUp = blockToCheck.getRelative(BlockFace.UP);
		Block blockDown = blockToCheck.getRelative(BlockFace.DOWN);
		switch (blockFace) {
		case UP:
		case DOWN:
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockEast, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockWest, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockNorth, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockSouth, wand);
		case EAST:
		case WEST:
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockNorth, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockSouth, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockDown, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockUp, wand);
		case SOUTH:
		case NORTH:
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockWest, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockEast, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockDown, wand);
			setBlockSelection(player, blockFace, maxLocations, startBlock, blockUp, wand);
		default:
			break;
		}
	}

	private void renderBlockOutlines(BlockFace blockFace, Block selectionBlock, List<Block> selection, Wand wand, Player player) {
		List<ParticleShapeHidden> shapes = new ArrayList<>();

		Block blockEast = selectionBlock.getRelative(BlockFace.EAST);
		Block blockWest = selectionBlock.getRelative(BlockFace.WEST);
		Block blockNorth = selectionBlock.getRelative(BlockFace.NORTH);
		Block blockSouth = selectionBlock.getRelative(BlockFace.SOUTH);
		Block blockUp = selectionBlock.getRelative(BlockFace.UP);
		Block blockDown = selectionBlock.getRelative(BlockFace.DOWN);
		Block blockNorthWest = selectionBlock.getRelative(BlockFace.NORTH_WEST);
		Block blockNorthEast = selectionBlock.getRelative(BlockFace.NORTH_EAST);
		Block blockSouthEast = selectionBlock.getRelative(BlockFace.SOUTH_EAST);
		Block blockSouthWest = selectionBlock.getRelative(BlockFace.SOUTH_WEST);
		Block blockDownEast = selectionBlock.getRelative(1, -1, 0);
		Block blockUpEast = selectionBlock.getRelative(1, 1, 0);
		Block blockDownWest = selectionBlock.getRelative(-1, -1, 0);
		Block blockUpWest = selectionBlock.getRelative(-1, 1, 0);
		Block blockDownSouth = selectionBlock.getRelative(0, -1, 1);
		Block blockUpSouth = selectionBlock.getRelative(0, 1, 1);
		Block blockDownNorth = selectionBlock.getRelative(0, -1, -1);
		Block blockUpNorth = selectionBlock.getRelative(0, 1, -1);

		Boolean blockEastContains = selection.contains(blockEast);
		Boolean blockWestContains = selection.contains(blockWest);
		Boolean blockNorthContains = selection.contains(blockNorth);
		Boolean blockSouthContains = selection.contains(blockSouth);
		Boolean blockUpContains = selection.contains(blockUp);
		Boolean blockDownContains = selection.contains(blockDown);
		Boolean blockNorthWestContains = selection.contains(blockNorthWest);
		Boolean blockNorthEastContains = selection.contains(blockNorthEast);
		Boolean blockSouthEastContains = selection.contains(blockSouthEast);
		Boolean blockSouthWestContains = selection.contains(blockSouthWest);
		Boolean blockDownEastContains = selection.contains(blockDownEast);
		Boolean blockUpEastContains = selection.contains(blockUpEast);
		Boolean blockDownWestContains = selection.contains(blockDownWest);
		Boolean blockUpWestContains = selection.contains(blockUpWest);
		Boolean blockDownSouthContains = selection.contains(blockDownSouth);
		Boolean blockUpSouthContains = selection.contains(blockUpSouth);
		Boolean blockDownNorthContains = selection.contains(blockDownNorth);
		Boolean blockUpNorthContains = selection.contains(blockUpNorth);

		if (blockEastContains) {
			shapes.add(ParticleShapeHidden.EAST);
		}
		if (blockWestContains) {
			shapes.add(ParticleShapeHidden.WEST);
		}
		if (blockNorthContains) {
			shapes.add(ParticleShapeHidden.NORTH);
		}
		if (blockSouthContains) {
			shapes.add(ParticleShapeHidden.SOUTH);
		}
		if (blockUpContains) {
			shapes.add(ParticleShapeHidden.UP);
		}
		if (blockDownContains) {
			shapes.add(ParticleShapeHidden.DOWN);
		}
		if (blockNorthWestContains) {
			shapes.add(ParticleShapeHidden.NORTH_WEST);
		}
		if (blockNorthEastContains) {
			shapes.add(ParticleShapeHidden.NORTH_EAST);
		}
		if (blockSouthEastContains) {
			shapes.add(ParticleShapeHidden.SOUTH_EAST);
		}
		if (blockSouthWestContains) {
			shapes.add(ParticleShapeHidden.SOUTH_WEST);
		}
		if (blockDownEastContains) {
			shapes.add(ParticleShapeHidden.DOWN_EAST);
		}
		if (blockUpEastContains) {
			shapes.add(ParticleShapeHidden.UP_EAST);
		}
		if (blockDownWestContains) {
			shapes.add(ParticleShapeHidden.DOWN_WEST);
		}
		if (blockUpWestContains) {
			shapes.add(ParticleShapeHidden.UP_WEST);
		}
		if (blockDownSouthContains) {
			shapes.add(ParticleShapeHidden.DOWN_SOUTH);
		}
		if (blockUpSouthContains) {
			shapes.add(ParticleShapeHidden.UP_SOUTH);
		}
		if (blockDownNorthContains) {
			shapes.add(ParticleShapeHidden.DOWN_NORTH);
		}
		if (blockUpNorthContains) {
			shapes.add(ParticleShapeHidden.UP_NORTH);
		}

		particleUtil.drawBlockOutlines(blockFace, shapes, selectionBlock.getRelative(blockFace).getLocation(), wand, player);
	}

	private boolean isAllowedToBuildForExternalPlugins(Player player, Location location) {
		Plugin townyPlugin = getExternalPlugin("Towny");
		if (townyPlugin != null) {
			return PlayerCacheUtil.getCachePermission(player, location, Material.STONE, TownyPermission.ActionType.BUILD);
		}

		Plugin worldGuardPlugin = getExternalPlugin("WorldGuard");
		if (worldGuardPlugin != null && worldGuardPlugin instanceof WorldGuardPlugin) {
			RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
			if (!query.testState(BukkitAdapter.adapt(location), WorldGuardPlugin.inst().wrapPlayer(player), Flags.BUILD)) {
				return false;
			}
		}

		return true;
	}

	private boolean isAllowedToBuildForExternalPlugins(Player player, List<Block> selection) {
		Plugin townyPlugin = getExternalPlugin("Towny");
		if (townyPlugin != null) {
			for (Block selectionBlock : selection) {
				if (!PlayerCacheUtil.getCachePermission(player, selectionBlock.getLocation(), selectionBlock.getType(), TownyPermission.ActionType.BUILD)) {
					return false;
				}
			}
		}

		Plugin worldGuardPlugin = getExternalPlugin("WorldGuard");
		if (worldGuardPlugin != null && worldGuardPlugin instanceof WorldGuardPlugin) {
			for (Block selectionBlock : selection) {
				RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
				if (!query.testState(BukkitAdapter.adapt(selectionBlock.getLocation()), WorldGuardPlugin.inst().wrapPlayer(player), Flags.BUILD)) {
					return false;
				}
			}
		}

		return true;
	}

	private Plugin getExternalPlugin(String name) {
		return plugin.getServer().getPluginManager().getPlugin(name);
	}

	private int getDurability(ItemStack wandItemStack, Wand wand) {
		ItemMeta itemMeta = wandItemStack.getItemMeta();
		List<String> lore = itemMeta.getLore();
		if (lore == null) {
			return wand.getDurability();
		}
		String durabilityString = lore.get(0);
		durabilityString = ChatColor.stripColor(durabilityString);
		durabilityString = durabilityString.replaceAll("[^0-9]", "");

		return Integer.parseInt(durabilityString);
	}
}