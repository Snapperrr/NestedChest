package com.xc.nestedchest;

import com.mojang.logging.LogUtils;
import com.xc.nestedchest.debug.NestedChestDebugCommands;
import com.xc.nestedchest.network.NestedChestClickPayload;
import com.xc.nestedchest.network.NestedChestOpenPayload;
import com.xc.nestedchest.network.NestedChestRenamePayload;
import com.xc.nestedchest.network.NestedChestSortPayload;
import com.xc.nestedchest.network.NestedChestSyncPayload;
import com.xc.nestedchest.screen.ConnectedChestScreenHandler;
import com.xc.nestedchest.storage.NestedChestStorage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NestedChestMod implements ModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final String MOD_ID = "nestedchest";
	public static final ScreenHandlerType<ConnectedChestScreenHandler> CONNECTED_CHEST_SCREEN_HANDLER = Registry.register(
			Registries.SCREEN_HANDLER,
			MOD_ID + ":connected_chest",
			new ExtendedScreenHandlerType<>(ConnectedChestScreenHandler::new, PacketCodecs.VAR_INT)
	);
	public static final int NESTED_CHEST_SIZE = 27;
	public static final int DOUBLE_NESTED_CHEST_SIZE = 54;
	public static final int MAX_NESTED_CHEST_SIZE = DOUBLE_NESTED_CHEST_SIZE;
	public static final int MAX_CHEST_NAME_LENGTH = 32;
	private static final int DATABASE_CHECKPOINT_TICKS = 20 * 60;
	private static final long SLOW_OPERATION_WARNING_NANOS = 50_000_000L;
	// 快速合成会跨多个客户端包分阶段发送，这里按玩家暂存一次原版 ScreenHandler 会话。
	private static final Map<UUID, QuickCraftSession> QUICK_CRAFT_SESSIONS = new HashMap<>();
	// 漏斗插入回调拿不到世界对象时，用本线程上下文把当前漏斗所在世界传进来。
	private static final ThreadLocal<World> HOPPER_TRANSFER_WORLD = new ThreadLocal<>();
	private static int checkpointTicks;

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(NestedChestClickPayload.ID, NestedChestClickPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(NestedChestOpenPayload.ID, NestedChestOpenPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(NestedChestRenamePayload.ID, NestedChestRenamePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(NestedChestSortPayload.ID, NestedChestSortPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(NestedChestSyncPayload.ID, NestedChestSyncPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(NestedChestClickPayload.ID, NestedChestMod::receiveNestedClick);
		ServerPlayNetworking.registerGlobalReceiver(NestedChestOpenPayload.ID, NestedChestMod::receiveNestedOpen);
		ServerPlayNetworking.registerGlobalReceiver(NestedChestRenamePayload.ID, NestedChestMod::receiveNestedRename);
		ServerPlayNetworking.registerGlobalReceiver(NestedChestSortPayload.ID, NestedChestMod::receiveNestedSort);
		ServerTickEvents.END_SERVER_TICK.register(NestedChestMod::checkpointStorage);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> NestedChestStorage.closeAll());
		NestedChestDebugCommands.register();
	}

	private static void receiveNestedClick(NestedChestClickPayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> applyNestedClick(payload, context.player()));
	}

	private static void receiveNestedOpen(NestedChestOpenPayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> syncNestedOpen(payload, context.player()));
	}

	private static void receiveNestedRename(NestedChestRenamePayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> applyNestedRename(payload, context.player()));
	}

	private static void receiveNestedSort(NestedChestSortPayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> applyNestedSort(payload, context.player()));
	}

	private static void checkpointStorage(MinecraftServer server) {
		checkpointTicks++;
		if (checkpointTicks < DATABASE_CHECKPOINT_TICKS) {
			return;
		}
		checkpointTicks = 0;
		// 周期性 checkpoint WAL，避免意外中断后只留下过大的日志文件。
		NestedChestStorage.checkpointAll();
	}

	private static void syncNestedOpen(NestedChestOpenPayload payload, ServerPlayerEntity player) {
		long start = System.nanoTime();
		ScreenHandler handler = player.currentScreenHandler;
		int containerSlots = containerSlotCount(handler);
		if (containerSlots <= 0) {
			return;
		}

		NestedChestStorage storage = NestedChestStorage.get(player.server);
		ResolvedNestedContainer resolved = resolveNestedContainer(storage, handler, containerSlots, payload.path(), false);
		if (resolved != null) {
			sendNestedSync(player, resolved);
		}
		warnSlowOperation("open", payload.path(), start);
	}

	private static void applyNestedClick(NestedChestClickPayload payload, ServerPlayerEntity player) {
		long start = System.nanoTime();
		if ((payload.nestedSlot() != -999 && payload.nestedSlot() < 0) || payload.nestedSlot() >= MAX_NESTED_CHEST_SIZE + 36) {
			return;
		}

		ScreenHandler handler = player.currentScreenHandler;
		int containerSlots = containerSlotCount(handler);
		if (containerSlots <= 0) {
			return;
		}

		NestedChestStorage storage = NestedChestStorage.get(player.server);
		ResolvedNestedContainer resolved = resolveNestedContainer(storage, handler, containerSlots, payload.path(), true);
		if (resolved == null) {
			return;
		}
		int nestedSlot = translateNestedClickSlot(resolved, payload.path(), payload.nestedSlot());
		if (nestedSlot >= 0 && nestedSlot >= resolved.size() + 36) {
			return;
		}

		if (payload.actionType() == SlotActionType.QUICK_CRAFT) {
			runVanillaNestedQuickCraft(resolved, handler, player, nestedSlot, payload.button(), containerSlots);
			return;
		}

		QUICK_CRAFT_SESSIONS.remove(player.getUuid());
		DefaultedList<ItemStack> updatedStacks = runVanillaNestedClick(storage, resolved, handler, player, nestedSlot, payload.button(), payload.actionType());
		handler.sendContentUpdates();
		sendNestedSync(player, resolved.path(), updatedStacks);
		warnSlowOperation("click", resolved.path(), start);
	}

	private static void applyNestedRename(NestedChestRenamePayload payload, ServerPlayerEntity player) {
		long start = System.nanoTime();
		ScreenHandler handler = player.currentScreenHandler;
		int containerSlots = containerSlotCount(handler);
		if (containerSlots <= 0) {
			return;
		}

		NestedChestStorage storage = NestedChestStorage.get(player.server);
		ResolvedChest resolved = resolveChest(storage, handler, containerSlots, payload.path());
		if (resolved == null) {
			return;
		}

		String name = payload.name().trim();
		if (name.length() > MAX_CHEST_NAME_LENGTH) {
			name = name.substring(0, MAX_CHEST_NAME_LENGTH);
		}
		if (name.isEmpty()) {
			resolved.stack().remove(DataComponentTypes.CUSTOM_NAME);
		} else {
			resolved.stack().set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
		}

		writeResolvedChest(storage, resolved);
		handler.sendContentUpdates();
		syncPathAndAncestors(storage, player, handler, containerSlots, payload.path());
		warnSlowOperation("rename", payload.path(), start);
	}

	private static void applyNestedSort(NestedChestSortPayload payload, ServerPlayerEntity player) {
		long start = System.nanoTime();
		ScreenHandler handler = player.currentScreenHandler;
		int containerSlots = containerSlotCount(handler);
		if (containerSlots <= 0 || payload.path().isEmpty()) {
			return;
		}

		NestedChestStorage storage = NestedChestStorage.get(player.server);
		ResolvedNestedContainer resolved = resolveNestedContainer(storage, handler, containerSlots, payload.path(), true);
		if (resolved == null) {
			return;
		}

		DefaultedList<ItemStack> sorted = sortNestedStacks(resolved.stacks(), SortMode.fromIndex(payload.mode()), payload.ascending());
		sorted = writeNestedContainer(storage, resolved, sorted);
		handler.sendContentUpdates();
		sendNestedSync(player, resolved.path(), sorted);
		warnSlowOperation("sort", resolved.path(), start);
	}

	private static void warnSlowOperation(String operation, List<Integer> path, long startNanos) {
		long elapsed = System.nanoTime() - startNanos;
		if (elapsed >= SLOW_OPERATION_WARNING_NANOS) {
			LOGGER.warn("Slow nested chest {}: {} ms at depth {}", operation, elapsed / 1_000_000L, path.size());
		}
	}

	private static DefaultedList<ItemStack> sortNestedStacks(DefaultedList<ItemStack> stacks, SortMode mode, boolean ascending) {
		DefaultedList<ItemStack> sorted = DefaultedList.ofSize(stacks.size(), ItemStack.EMPTY);
		List<SortableStack> movable = new ArrayList<>();
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (isSortLockedStack(stack)) {
				sorted.set(slot, stack.copy());
			} else {
				movable.add(new SortableStack(stack.copy(), slot));
			}
		}

		// 整理时先合并同类堆叠，再排序；箱子和漏斗仍留在原位，避免破坏目录结构和漏斗绑定。
		movable = mergeSortableStacks(movable);
		movable.sort(sortComparator(mode, ascending));
		int movableIndex = 0;
		for (int slot = 0; slot < sorted.size() && movableIndex < movable.size(); slot++) {
			if (!sorted.get(slot).isEmpty()) {
				continue;
			}
			sorted.set(slot, movable.get(movableIndex).stack());
			movableIndex++;
		}
		return sorted;
	}

	private static List<SortableStack> mergeSortableStacks(List<SortableStack> stacks) {
		List<SortableStack> merged = new ArrayList<>();
		for (SortableStack sortable : stacks) {
			ItemStack remaining = sortable.stack().copy();
			if (remaining.isEmpty()) {
				continue;
			}
			for (SortableStack target : merged) {
				if (remaining.isEmpty()) {
					break;
				}
				if (!canMergeStacks(target.stack(), remaining)) {
					continue;
				}
				int moved = Math.min(remaining.getCount(), target.stack().getMaxCount() - target.stack().getCount());
				target.stack().increment(moved);
				remaining.decrement(moved);
			}
			while (!remaining.isEmpty()) {
				int moved = Math.min(remaining.getCount(), Math.max(1, remaining.getMaxCount()));
				merged.add(new SortableStack(remaining.copyWithCount(moved), sortable.originalSlot()));
				remaining.decrement(moved);
			}
		}
		return merged;
	}

	private static boolean canMergeStacks(ItemStack target, ItemStack source) {
		return !target.isEmpty()
				&& !source.isEmpty()
				&& target.getCount() < target.getMaxCount()
				&& ItemStack.areItemsAndComponentsEqual(target, source);
	}

	private static Comparator<SortableStack> sortComparator(SortMode mode, boolean ascending) {
		Comparator<SortableStack> nameComparator = Comparator
				.comparing((SortableStack sortable) -> sortable.stack().getName().getString(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(sortable -> Registries.ITEM.getId(sortable.stack().getItem()).toString());
		Comparator<SortableStack> comparator = switch (mode) {
			case COUNT -> Comparator
					.comparingInt((SortableStack sortable) -> sortable.stack().getCount())
					.thenComparing(nameComparator);
			case CATEGORY -> Comparator
					.comparingInt((SortableStack sortable) -> itemCategory(sortable.stack()))
					.thenComparing(nameComparator);
			case NAME -> nameComparator;
		};
		if (!ascending) {
			comparator = comparator.reversed();
		}
		return comparator.thenComparingInt(SortableStack::originalSlot);
	}

	private static boolean isSortLockedStack(ItemStack stack) {
		return isChestItem(stack) || stack.isOf(Items.HOPPER);
	}

	private static int itemCategory(ItemStack stack) {
		if (stack.isIn(ItemTags.PICKAXES) || stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.SWORDS) || stack.isDamageable()) {
			return 10;
		}
		if (stack.contains(DataComponentTypes.FOOD)) {
			return 20;
		}
		if (stack.contains(DataComponentTypes.POTION_CONTENTS)) {
			return 30;
		}
		if (stack.contains(DataComponentTypes.STORED_ENCHANTMENTS) || stack.contains(DataComponentTypes.ENCHANTMENTS)) {
			return 40;
		}
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		if (id.contains("_block") || id.contains("planks") || id.contains("stone") || id.contains("brick") || id.contains("glass")) {
			return 0;
		}
		if (id.contains("ore") || id.contains("ingot") || id.contains("gem") || id.contains("dust")) {
			return 50;
		}
		return 100;
	}

	public static boolean isChestItem(ItemStack stack) {
		return stack.isOf(Items.CHEST) || stack.isOf(Items.TRAPPED_CHEST);
	}

	private static DefaultedList<ItemStack> getNestedStacks(NestedChestStorage storage, ItemStack stack) {
		return storage.getStacks(stack);
	}

	private static void setNestedStacks(NestedChestStorage storage, ItemStack stack, DefaultedList<ItemStack> stacks) {
		storage.setStacks(stack, stacks);
	}

	public static ItemStack sanitizeDroppedChestItem(ItemStack stack) {
		if (!isChestItem(stack)) {
			return stack;
		}
		// 掉落物不能带目录数据库 ID 或旧版容器组件，否则玩家可以通过拆放复制整棵箱中箱。
		ItemStack sanitized = new ItemStack(stack.getItem(), stack.getCount());
		return sanitized;
	}

	public static void sanitizeChestDrops(Inventory inventory) {
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			ItemStack sanitized = sanitizeDroppedChestItem(stack);
			if (sanitized != stack) {
				inventory.setStack(slot, sanitized);
			}
		}
	}

	public static void sanitizeChestDrops(World world, BlockPos pos, Inventory inventory) {
		if (world.isClient() || world.getServer() == null) {
			sanitizeChestDrops(inventory);
			return;
		}

		NestedChestStorage storage = NestedChestStorage.get(world.getServer());
		Set<String> visitedStorageIds = new HashSet<>();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (isChestItem(stack)) {
				dropStoredNestedContents(world, pos, storage, stack, visitedStorageIds);
			}
			ItemStack sanitized = sanitizeDroppedChestItem(stack);
			if (sanitized != stack) {
				inventory.setStack(slot, sanitized);
			}
		}
	}

	private static void dropStoredNestedContents(World world, BlockPos pos, NestedChestStorage storage, ItemStack rootStack, Set<String> visitedStorageIds) {
		List<ItemStack> pendingChests = new ArrayList<>();
		pendingChests.add(rootStack.copy());

		// 打破外层箱子时递归吐出深层内容，但所有箱子掉落物本身会被清成空白箱子。
		while (!pendingChests.isEmpty()) {
			ItemStack chestStack = pendingChests.removeLast();
			DefaultedList<ItemStack> storedStacks = loadExistingNestedStacks(storage, chestStack, visitedStorageIds);
			if (storedStacks == null) {
				continue;
			}

			for (ItemStack childStack : storedStacks) {
				if (childStack.isEmpty()) {
					continue;
				}
				if (isChestItem(childStack)) {
					scatterNestedDrop(world, pos, sanitizeDroppedChestItem(childStack));
					if (hasStoredNestedContents(storage, childStack)) {
						pendingChests.add(childStack.copy());
					}
				} else {
					scatterNestedDrop(world, pos, childStack.copy());
				}
			}
		}
	}

	private static DefaultedList<ItemStack> loadExistingNestedStacks(NestedChestStorage storage, ItemStack stack, Set<String> visitedStorageIds) {
		String storageId = storage.getStorageId(stack);
		if (!storageId.isBlank()) {
			if (!visitedStorageIds.add(storageId)) {
				return null;
			}
			return getNestedStacks(storage, stack);
		}

		ContainerComponent legacyContainer = stack.get(DataComponentTypes.CONTAINER);
		if (legacyContainer == null || legacyContainer == ContainerComponent.DEFAULT) {
			return null;
		}

		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
		legacyContainer.copyTo(stacks);
		return stacks;
	}

	private static boolean hasStoredNestedContents(NestedChestStorage storage, ItemStack stack) {
		if (!isChestItem(stack)) {
			return false;
		}
		if (!storage.getStorageId(stack).isBlank()) {
			return true;
		}
		ContainerComponent legacyContainer = stack.get(DataComponentTypes.CONTAINER);
		return legacyContainer != null && legacyContainer != ContainerComponent.DEFAULT;
	}

	private static void scatterNestedDrop(World world, BlockPos pos, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		ItemScatterer.spawn(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack);
	}

	private static DefaultedList<ItemStack> runVanillaNestedClick(NestedChestStorage storage, ResolvedNestedContainer resolved, ScreenHandler realHandler, ServerPlayerEntity player, int slot, int button, SlotActionType actionType) {
		SimpleInventory inventory = copyToInventory(resolved);
		GenericContainerScreenHandler nestedHandler = createNestedHandler(resolved, realHandler, player, inventory);
		nestedHandler.setCursorStack(realHandler.getCursorStack().copy());
		// 让原版 GenericContainerScreenHandler 处理点击，尽量继承原版箱子的双击、Shift、数字键等规则。
		nestedHandler.onSlotClick(slot, button, actionType, player);
		realHandler.setCursorStack(nestedHandler.getCursorStack().copy());

		DefaultedList<ItemStack> updatedStacks = copyFromInventory(inventory, resolved.size());
		updatedStacks = writeNestedContainer(storage, resolved, updatedStacks);
		return updatedStacks;
	}

	private static int translateNestedClickSlot(ResolvedNestedContainer resolved, List<Integer> originalPath, int slot) {
		if (slot < 0 || resolved.secondary() == null || resolved.path().isEmpty() || originalPath.isEmpty()) {
			return slot;
		}
		// 玩家点的是双箱右半边路径时，服务端会规范到左半边，所以槽位需要平移 27 格。
		int pathOffset = Math.floorMod(originalPath.getLast(), NESTED_CHEST_SIZE) - Math.floorMod(resolved.path().getLast(), NESTED_CHEST_SIZE);
		if (pathOffset <= 0 || pathOffset > 1) {
			return slot;
		}
		return slot + pathOffset * NESTED_CHEST_SIZE;
	}

	private static void runVanillaNestedQuickCraft(ResolvedNestedContainer resolved, ScreenHandler realHandler, ServerPlayerEntity player, int slot, int packedButton, int containerSlots) {
		int stage = ScreenHandler.unpackQuickCraftStage(packedButton);
		UUID playerId = player.getUuid();
		QuickCraftSession session = QUICK_CRAFT_SESSIONS.get(playerId);

		if (stage == 0) {
			SimpleInventory inventory = copyToInventory(resolved);
			GenericContainerScreenHandler nestedHandler = createNestedHandler(resolved, realHandler, player, inventory);
			nestedHandler.setCursorStack(realHandler.getCursorStack().copy());
			session = new QuickCraftSession(resolved.path(), resolved, inventory, nestedHandler);
			QUICK_CRAFT_SESSIONS.put(playerId, session);
		} else if (session == null || !session.path().equals(resolved.path())) {
			QUICK_CRAFT_SESSIONS.remove(playerId);
			return;
		}

		session.handler().onSlotClick(slot, packedButton, SlotActionType.QUICK_CRAFT, player);

		if (stage == 2) {
			realHandler.setCursorStack(session.handler().getCursorStack().copy());
			NestedChestStorage storage = NestedChestStorage.get(player.server);
			DefaultedList<ItemStack> updatedStacks = copyFromInventory(session.inventory(), session.resolved().size());
			updatedStacks = writeNestedContainer(storage, session.resolved(), updatedStacks);
			QUICK_CRAFT_SESSIONS.remove(playerId);
			realHandler.sendContentUpdates();
			sendNestedSync(player, session.resolved().path(), updatedStacks);
		}
	}

	private static SimpleInventory copyToInventory(ResolvedNestedContainer resolved) {
		SimpleInventory inventory = new SimpleInventory(resolved.size());
		for (int i = 0; i < resolved.size(); i++) {
			inventory.setStack(i, resolved.stacks().get(i).copy());
		}
		return inventory;
	}

	private static DefaultedList<ItemStack> copyFromInventory(SimpleInventory inventory, int size) {
		DefaultedList<ItemStack> updated = DefaultedList.ofSize(size, ItemStack.EMPTY);
		for (int i = 0; i < size; i++) {
			updated.set(i, inventory.getStack(i).copy());
		}
		return updated;
	}

	private static GenericContainerScreenHandler createNestedHandler(ResolvedNestedContainer resolved, ScreenHandler realHandler, ServerPlayerEntity player, SimpleInventory inventory) {
		int rows = resolved.size() / 9;
		return new GenericContainerScreenHandler(screenHandlerType(rows), realHandler.syncId, player.getInventory(), inventory, rows);
	}

	private static ScreenHandlerType<GenericContainerScreenHandler> screenHandlerType(int rows) {
		return rows == 6 ? ScreenHandlerType.GENERIC_9X6 : ScreenHandlerType.GENERIC_9X3;
	}

	private static ResolvedChest resolveChest(NestedChestStorage storage, ScreenHandler handler, int containerSlots, List<Integer> path) {
		if (path.isEmpty()) {
			return null;
		}

		ResolvedContainer container = rootContainer(handler, containerSlots);
		ResolvedChest resolved = null;
		for (int depth = 0; depth < path.size(); depth++) {
			int slot = path.get(depth);
			resolved = resolveSlot(storage, handler, containerSlots, container, slot);
			if (resolved == null) {
				return null;
			}
			if (depth < path.size() - 1) {
				container = containerForResolvedChest(storage, handler, containerSlots, container, slot, resolved);
				if (container == null) {
					return null;
				}
			}
		}

		return resolved;
	}

	private static ResolvedNestedContainer resolveNestedContainer(NestedChestStorage storage, ScreenHandler handler, int containerSlots, List<Integer> path, boolean persistResolvedIds) {
		ResolvedContainer container = rootContainer(handler, containerSlots);
		List<Integer> normalizedPath = new ArrayList<>(path);
		ResolvedChest primary = null;

		// 路径里记录的是玩家看到的槽位；遇到成对目录箱时，内部统一落到左侧主箱。
		for (int depth = 0; depth < normalizedPath.size(); depth++) {
			int slot = normalizedPath.get(depth);
			int pairStart = getNestedChestPairStart(container.stacks(), slot);
			if (pairStart >= 0 && pairStart < slot) {
				int slotOffset = slot - pairStart;
				slot = pairStart;
				normalizedPath.set(depth, pairStart);
				if (depth + 1 < normalizedPath.size()) {
					normalizedPath.set(depth + 1, normalizedPath.get(depth + 1) + slotOffset * NESTED_CHEST_SIZE);
				}
			}
			if (depth == normalizedPath.size() - 1) {
				if (pairStart >= 0) {
					slot = pairStart;
					normalizedPath.set(depth, pairStart);
				}
			}

			primary = resolveSlot(storage, handler, containerSlots, container, slot);
			if (primary == null) {
				return null;
			}
			if (depth < normalizedPath.size() - 1) {
				container = containerForResolvedChest(storage, handler, containerSlots, container, slot, primary);
				if (container == null) {
					return null;
				}
			}
		}

		if (primary == null) {
			return null;
		}

		ResolvedContainer resolvedContainer = containerForResolvedChest(storage, handler, containerSlots, container, normalizedPath.getLast(), primary);
		if (resolvedContainer == null) {
			return null;
		}
		ResolvedChest secondary = resolvedContainer.secondary();
		if (secondary != null && detachDuplicateSecondaryStorageId(storage, primary, secondary)) {
			resolvedContainer = containerForResolvedChest(storage, handler, containerSlots, container, normalizedPath.getLast(), primary);
			secondary = resolvedContainer.secondary();
		}
		if (persistResolvedIds) {
			if (secondary != null) {
				writeResolvedChestPair(storage, primary, secondary);
			} else {
				writeResolvedChest(storage, primary);
			}
		}
		return new ResolvedNestedContainer(List.copyOf(normalizedPath), primary, secondary, resolvedContainer.stacks());
	}

	private static ResolvedContainer rootContainer(ScreenHandler handler, int containerSlots) {
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(containerSlots, ItemStack.EMPTY);
		for (int i = 0; i < stacks.size() && i < handler.slots.size(); i++) {
			stacks.set(i, handler.getSlot(i).getStack());
		}
		return new ResolvedContainer(stacks, null, null);
	}

	private static ResolvedChest resolveSlot(NestedChestStorage storage, ScreenHandler handler, int containerSlots, ResolvedContainer container, int slot) {
		if (slot < 0 || slot >= container.stacks().size()) {
			return null;
		}

		if (container.primary() == null) {
			if (slot >= containerSlots || slot >= handler.slots.size()) {
				return null;
			}
			Slot rootSlot = handler.getSlot(slot);
			ItemStack stack = rootSlot.getStack();
			return canOpenNestedWindow(stack) ? new ResolvedChest(rootSlot, stack, List.of()) : null;
		}

		ResolvedChest parent = slot < NESTED_CHEST_SIZE ? container.primary() : container.secondary();
		if (parent == null) {
			return null;
		}

		int actualSlot = slot % NESTED_CHEST_SIZE;
		DefaultedList<ItemStack> parentStacks = getNestedStacks(storage, parent.stack());
		ItemStack stack = parentStacks.get(actualSlot);
		if (!canOpenNestedWindow(stack)) {
			return null;
		}

		List<ResolvedFrame> frames = new ArrayList<>(parent.frames());
		frames.add(new ResolvedFrame(parent.stack(), parentStacks, actualSlot, stack.copy()));
		return new ResolvedChest(parent.rootSlot(), stack, List.copyOf(frames));
	}

	private static ResolvedContainer containerForResolvedChest(NestedChestStorage storage, ScreenHandler handler, int containerSlots, ResolvedContainer parentContainer, int slot, ResolvedChest primary) {
		ResolvedChest secondary = null;
		int secondarySlot = getNestedChestPairSecondarySlot(parentContainer.stacks(), slot);
		if (secondarySlot >= 0) {
			secondary = resolveSlot(storage, handler, containerSlots, parentContainer, secondarySlot);
		}

		int size = secondary == null ? NESTED_CHEST_SIZE : DOUBLE_NESTED_CHEST_SIZE;
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(size, ItemStack.EMPTY);
		copyInto(stacks, 0, getNestedStacks(storage, primary.stack()));
		if (secondary != null) {
			copyInto(stacks, NESTED_CHEST_SIZE, getNestedStacks(storage, secondary.stack()));
		}
		return new ResolvedContainer(stacks, primary, secondary);
	}

	private static boolean detachDuplicateSecondaryStorageId(NestedChestStorage storage, ResolvedChest primary, ResolvedChest secondary) {
		String primaryId = storage.getStorageId(primary.stack());
		if (primaryId.isBlank() || !primaryId.equals(storage.getStorageId(secondary.stack()))) {
			return false;
		}
		// 两个相邻箱子如果意外共用同一个存储 ID，会表现成镜像物品；拆开右箱 ID 让它重新拥有独立页面。
		storage.removeStorageId(secondary.stack());
		writeResolvedChestPair(storage, primary, secondary);
		return true;
	}

	private static void copyInto(DefaultedList<ItemStack> target, int offset, DefaultedList<ItemStack> source) {
		for (int i = 0; i < NESTED_CHEST_SIZE && i < source.size() && offset + i < target.size(); i++) {
			target.set(offset + i, source.get(i).copy());
		}
	}

	private static DefaultedList<ItemStack> writeNestedContainer(NestedChestStorage storage, ResolvedNestedContainer resolved, DefaultedList<ItemStack> stacks) {
		DefaultedList<ItemStack> sanitizedStacks = sanitizeDuplicateNestedChestIds(storage, stacks);
		DefaultedList<ItemStack> primaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
		for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
			primaryStacks.set(i, sanitizedStacks.get(i).copy());
		}
		setNestedStacks(storage, resolved.primary().stack(), primaryStacks);

		if (resolved.secondary() != null) {
			DefaultedList<ItemStack> secondaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
			for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
				secondaryStacks.set(i, sanitizedStacks.get(NESTED_CHEST_SIZE + i).copy());
			}
			setNestedStacks(storage, resolved.secondary().stack(), secondaryStacks);
			writeResolvedChestPair(storage, resolved.primary(), resolved.secondary());
		} else {
			writeResolvedChest(storage, resolved.primary());
		}
		return sanitizedStacks;
	}

	private static DefaultedList<ItemStack> sanitizeDuplicateNestedChestIds(NestedChestStorage storage, DefaultedList<ItemStack> stacks) {
		DefaultedList<ItemStack> sanitized = DefaultedList.ofSize(stacks.size(), ItemStack.EMPTY);
		Set<String> seenIds = new HashSet<>();
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot).copy();
			if (isChestItem(stack)) {
				String id = storage.getStorageId(stack);
				if (!id.isBlank() && !seenIds.add(id)) {
					storage.removeStorageId(stack);
				}
			}
			sanitized.set(slot, stack);
		}
		return sanitized;
	}

	private static void writeResolvedChestPair(NestedChestStorage storage, ResolvedChest primary, ResolvedChest secondary) {
		if (!canWriteAsSiblingPair(primary, secondary)) {
			writeResolvedChest(storage, primary);
			writeResolvedChest(storage, secondary);
			return;
		}

		if (primary.frames().isEmpty()) {
			primary.rootSlot().setStack(primary.stack());
			primary.rootSlot().markDirty();
			secondary.rootSlot().setStack(secondary.stack());
			secondary.rootSlot().markDirty();
			return;
		}

		int lastFrameIndex = primary.frames().size() - 1;
		ResolvedFrame lastPrimaryFrame = primary.frames().get(lastFrameIndex);
		ResolvedFrame lastSecondaryFrame = secondary.frames().get(lastFrameIndex);
		if (!stacksEqual(lastPrimaryFrame.originalChild(), primary.stack()) || !stacksEqual(lastSecondaryFrame.originalChild(), secondary.stack())) {
			lastPrimaryFrame.stacks().set(lastPrimaryFrame.childSlot(), primary.stack());
			lastPrimaryFrame.stacks().set(lastSecondaryFrame.childSlot(), secondary.stack());
			setNestedStacks(storage, lastPrimaryFrame.stack(), lastPrimaryFrame.stacks());
		}

		writeChangedAncestorChain(storage, primary.rootSlot(), primary.frames(), lastPrimaryFrame.stack(), lastFrameIndex - 1);
	}

	private static boolean canWriteAsSiblingPair(ResolvedChest primary, ResolvedChest secondary) {
		if (primary.frames().size() != secondary.frames().size()) {
			return false;
		}
		if (primary.frames().isEmpty()) {
			return true;
		}
		if (primary.rootSlot().id != secondary.rootSlot().id) {
			return false;
		}
		for (int i = 0; i < primary.frames().size() - 1; i++) {
			if (primary.frames().get(i).childSlot() != secondary.frames().get(i).childSlot()) {
				return false;
			}
		}
		return true;
	}

	private static void writeResolvedChest(NestedChestStorage storage, ResolvedChest resolved) {
		writeChangedAncestorChain(storage, resolved.rootSlot(), resolved.frames(), resolved.stack(), resolved.frames().size() - 1);
	}

	private static void writeChangedAncestorChain(NestedChestStorage storage, Slot rootSlot, List<ResolvedFrame> frames, ItemStack child, int startFrameIndex) {
		// 子箱子的 ItemStack 变化后，需要从最深层一路写回父箱，最后再写回真正打开的世界箱子槽位。
		for (int i = startFrameIndex; i >= 0; i--) {
			ResolvedFrame frame = frames.get(i);
			if (!stacksEqual(frame.originalChild(), child)) {
				frame.stacks().set(frame.childSlot(), child);
				setNestedStacks(storage, frame.stack(), frame.stacks());
			}
			child = frame.stack();
		}
		rootSlot.setStack(child);
		rootSlot.markDirty();
	}

	private static boolean stacksEqual(ItemStack left, ItemStack right) {
		if (left.isEmpty() && right.isEmpty()) {
			return true;
		}
		return left.getCount() == right.getCount() && ItemStack.areItemsAndComponentsEqual(left, right);
	}

	private static void syncPathAndAncestors(NestedChestStorage storage, ServerPlayerEntity player, ScreenHandler handler, int containerSlots, List<Integer> path) {
		for (int size = 1; size <= path.size(); size++) {
			List<Integer> subPath = List.copyOf(path.subList(0, size));
			syncNestedPath(storage, player, handler, containerSlots, subPath);
		}
	}

	private static void syncNestedPath(NestedChestStorage storage, ServerPlayerEntity player, ScreenHandler handler, int containerSlots, List<Integer> path) {
		ResolvedNestedContainer resolved = resolveNestedContainer(storage, handler, containerSlots, path, false);
		if (resolved != null) {
			sendNestedSync(player, resolved);
		}
	}

	private static void sendNestedSync(ServerPlayerEntity player, ResolvedNestedContainer resolved) {
		sendNestedSync(player, resolved.path(), resolved.stacks());
	}

	private static void sendNestedSync(ServerPlayerEntity player, List<Integer> path, DefaultedList<ItemStack> stacks) {
		ServerPlayNetworking.send(player, new NestedChestSyncPayload(path, displayStacksForSync(stacks)));
	}

	private static DefaultedList<ItemStack> displayStacksForSync(DefaultedList<ItemStack> stacks) {
		DefaultedList<ItemStack> display = DefaultedList.ofSize(stacks.size(), ItemStack.EMPTY);
		for (int slot = 0; slot < stacks.size(); slot++) {
			display.set(slot, displayStackForSync(stacks.get(slot)));
		}
		return display;
	}

	private static ItemStack displayStackForSync(ItemStack stack) {
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack display = stack.copy();
		// 同步给客户端只需要图标和名字，去掉重型组件可以避免书本/容器数据重新把网络包撑爆。
		display.remove(DataComponentTypes.WRITABLE_BOOK_CONTENT);
		display.remove(DataComponentTypes.WRITTEN_BOOK_CONTENT);
		display.remove(DataComponentTypes.CONTAINER);
		display.remove(DataComponentTypes.BUNDLE_CONTENTS);
		display.remove(DataComponentTypes.BLOCK_ENTITY_DATA);
		display.remove(DataComponentTypes.ENTITY_DATA);
		display.remove(DataComponentTypes.BUCKET_ENTITY_DATA);
		display.remove(DataComponentTypes.BEES);
		return display;
	}

	public static boolean canOpenNestedWindow(ItemStack stack) {
		return isChestItem(stack) && stack.getCount() == 1;
	}

	public static int getNestedChestPairStart(DefaultedList<ItemStack> stacks, int slot) {
		if (!isPairableNestedSlot(stacks, slot)) {
			return -1;
		}

		// 箱中箱目录只允许水平两两配对；从连续箱子串的左端开始按 2 格一组切分。
		int runStart = slot;
		while (runStart % 9 > 0 && isPairableNestedSlot(stacks, runStart - 1)) {
			runStart--;
		}
		return runStart + ((slot - runStart) / 2) * 2;
	}

	public static int getNestedChestPairSecondarySlot(DefaultedList<ItemStack> stacks, int pairStart) {
		if (getNestedChestPairStart(stacks, pairStart) != pairStart) {
			return -1;
		}

		int secondarySlot = pairStart + 1;
		if (pairStart % 9 >= 8 || !isPairableNestedSlot(stacks, secondarySlot)) {
			return -1;
		}
		return getNestedChestPairStart(stacks, secondarySlot) == pairStart ? secondarySlot : -1;
	}

	private static boolean isPairableNestedSlot(DefaultedList<ItemStack> stacks, int slot) {
		return slot >= 0 && slot < stacks.size() && canOpenNestedWindow(stacks.get(slot));
	}

	private static int containerSlotCount(ScreenHandler handler) {
		if (handler instanceof GenericContainerScreenHandler genericHandler) {
			return genericHandler.getRows() * 9;
		}
		if (handler instanceof ConnectedChestScreenHandler) {
			return ConnectedChestScreenHandler.VISIBLE_CHEST_SLOTS;
		}
		return -1;
	}

	public static ItemStack transferIntoBoundNestedChest(Inventory inventory, ItemStack stack) {
		if (stack.isEmpty()) {
			return stack;
		}

		World world = HOPPER_TRANSFER_WORLD.get();
		if (world == null || world.isClient() || world.getServer() == null) {
			return stack;
		}
		NestedChestStorage storage = NestedChestStorage.get(world.getServer());
		ItemStack remaining = stack;
		boolean[] handledSlots = new boolean[inventory.size()];
		// 目录箱上方放漏斗作为绑定标记：外部漏斗插入时优先把物品写进该目录箱的数据库页面。
		for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
			if (handledSlots[slot]) {
				continue;
			}

			ItemStack chest = inventory.getStack(slot);
			if (!canOpenNestedWindow(chest) || !hasHopperBindingAbove(inventory, slot)) {
				continue;
			}

			int primarySlot = normalizeBoundChestSlot(inventory, slot);
			if (primarySlot < 0 || primarySlot >= inventory.size()) {
				continue;
			}

			ItemStack primary = inventory.getStack(primarySlot);
			if (!canOpenNestedWindow(primary)) {
				continue;
			}
			handledSlots[primarySlot] = true;

			int secondarySlot = getRightAdjacentChestSlot(inventory, primarySlot);
			if (secondarySlot >= 0) {
				handledSlots[secondarySlot] = true;
			}

			int size = secondarySlot >= 0 ? DOUBLE_NESTED_CHEST_SIZE : NESTED_CHEST_SIZE;
			DefaultedList<ItemStack> nestedStacks = DefaultedList.ofSize(size, ItemStack.EMPTY);
			copyInto(nestedStacks, 0, getNestedStacks(storage, primary));
			if (secondarySlot >= 0) {
				copyInto(nestedStacks, NESTED_CHEST_SIZE, getNestedStacks(storage, inventory.getStack(secondarySlot)));
			}

			remaining = insertIntoStacks(nestedStacks, remaining);
			if (remaining.getCount() != stack.getCount()) {
				writeBoundNestedStacks(storage, inventory, primarySlot, secondarySlot, nestedStacks);
				inventory.markDirty();
			}
		}
		return remaining;
	}

	public static void setHopperTransferWorld(World world) {
		HOPPER_TRANSFER_WORLD.set(world);
	}

	public static void clearHopperTransferWorld() {
		HOPPER_TRANSFER_WORLD.remove();
	}

	private static int normalizeBoundChestSlot(Inventory inventory, int slot) {
		DefaultedList<ItemStack> stacks = inventoryStacks(inventory);
		int pairStart = getNestedChestPairStart(stacks, slot);
		return pairStart < 0 ? slot : pairStart;
	}

	private static int getRightAdjacentChestSlot(Inventory inventory, int slot) {
		return getNestedChestPairSecondarySlot(inventoryStacks(inventory), slot);
	}

	private static DefaultedList<ItemStack> inventoryStacks(Inventory inventory) {
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
		for (int slot = 0; slot < inventory.size(); slot++) {
			stacks.set(slot, inventory.getStack(slot));
		}
		return stacks;
	}

	private static void writeBoundNestedStacks(NestedChestStorage storage, Inventory inventory, int primarySlot, int secondarySlot, DefaultedList<ItemStack> stacks) {
		ItemStack primary = inventory.getStack(primarySlot);
		DefaultedList<ItemStack> primaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
		for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
			primaryStacks.set(i, stacks.get(i).copy());
		}
		setNestedStacks(storage, primary, primaryStacks);
		inventory.setStack(primarySlot, primary);

		if (secondarySlot >= 0) {
			ItemStack secondary = inventory.getStack(secondarySlot);
			DefaultedList<ItemStack> secondaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
			for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
				secondaryStacks.set(i, stacks.get(NESTED_CHEST_SIZE + i).copy());
			}
			setNestedStacks(storage, secondary, secondaryStacks);
			inventory.setStack(secondarySlot, secondary);
		}
	}

	private static boolean hasHopperBindingAbove(Inventory inventory, int slot) {
		int markerSlot = slot - 9;
		return markerSlot >= 0 && markerSlot < inventory.size() && inventory.getStack(markerSlot).isOf(Items.HOPPER);
	}

	private static ItemStack insertIntoStacks(DefaultedList<ItemStack> stacks, ItemStack incoming) {
		ItemStack remaining = incoming.copy();
		for (int i = 0; i < stacks.size() && !remaining.isEmpty(); i++) {
			ItemStack current = stacks.get(i);
			if (!current.isEmpty() && ItemStack.areItemsAndComponentsEqual(current, remaining) && current.getCount() < current.getMaxCount()) {
				int moved = Math.min(remaining.getCount(), current.getMaxCount() - current.getCount());
				current.increment(moved);
				remaining.decrement(moved);
			}
		}
		for (int i = 0; i < stacks.size() && !remaining.isEmpty(); i++) {
			if (stacks.get(i).isEmpty()) {
				int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
				stacks.set(i, remaining.copyWithCount(moved));
				remaining.decrement(moved);
			}
		}
		return remaining;
	}

	private record ResolvedChest(Slot rootSlot, ItemStack stack, List<ResolvedFrame> frames) {
	}

	private record ResolvedFrame(ItemStack stack, DefaultedList<ItemStack> stacks, int childSlot, ItemStack originalChild) {
	}

	private record ResolvedContainer(DefaultedList<ItemStack> stacks, ResolvedChest primary, ResolvedChest secondary) {
	}

	private record ResolvedNestedContainer(List<Integer> path, ResolvedChest primary, ResolvedChest secondary, DefaultedList<ItemStack> stacks) {
		private int size() {
			return stacks.size();
		}
	}

	private record QuickCraftSession(List<Integer> path, ResolvedNestedContainer resolved, SimpleInventory inventory, GenericContainerScreenHandler handler) {
	}

	private record SortableStack(ItemStack stack, int originalSlot) {
	}

	private enum SortMode {
		NAME,
		COUNT,
		CATEGORY;

		private static SortMode fromIndex(int index) {
			SortMode[] values = values();
			int normalized = Math.floorMod(index, values.length);
			return values[normalized];
		}
	}
}
