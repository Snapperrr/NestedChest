package com.xc.nestedchest;

import com.xc.nestedchest.network.NestedChestClickPayload;
import com.xc.nestedchest.network.NestedChestRenamePayload;
import com.xc.nestedchest.network.NestedChestSyncPayload;
import com.xc.nestedchest.screen.ConnectedChestScreenHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NestedChestMod implements ModInitializer {
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
	private static final Map<UUID, QuickCraftSession> QUICK_CRAFT_SESSIONS = new HashMap<>();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(NestedChestClickPayload.ID, NestedChestClickPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(NestedChestRenamePayload.ID, NestedChestRenamePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(NestedChestSyncPayload.ID, NestedChestSyncPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(NestedChestClickPayload.ID, NestedChestMod::receiveNestedClick);
		ServerPlayNetworking.registerGlobalReceiver(NestedChestRenamePayload.ID, NestedChestMod::receiveNestedRename);
	}

	private static void receiveNestedClick(NestedChestClickPayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> applyNestedClick(payload, context.player()));
	}

	private static void receiveNestedRename(NestedChestRenamePayload payload, ServerPlayNetworking.Context context) {
		context.server().execute(() -> applyNestedRename(payload, context.player()));
	}

	private static void applyNestedClick(NestedChestClickPayload payload, ServerPlayerEntity player) {
		if ((payload.nestedSlot() != -999 && payload.nestedSlot() < 0) || payload.nestedSlot() >= MAX_NESTED_CHEST_SIZE + 36) {
			return;
		}

		ScreenHandler handler = player.currentScreenHandler;
		int containerSlots = containerSlotCount(handler);
		if (containerSlots <= 0) {
			return;
		}

		ResolvedNestedContainer resolved = resolveNestedContainer(handler, containerSlots, payload.path());
		if (resolved == null || (payload.nestedSlot() >= 0 && payload.nestedSlot() >= resolved.size() + 36)) {
			return;
		}

		if (payload.actionType() == SlotActionType.QUICK_CRAFT) {
			runVanillaNestedQuickCraft(resolved, handler, player, payload.nestedSlot(), payload.button(), containerSlots);
			return;
		}

		QUICK_CRAFT_SESSIONS.remove(player.getUuid());
		runVanillaNestedClick(resolved, handler, player, payload.nestedSlot(), payload.button(), payload.actionType());
		handler.sendContentUpdates();
		syncPathAndAncestors(player, handler, containerSlots, resolved.path());
	}

	private static void applyNestedRename(NestedChestRenamePayload payload, ServerPlayerEntity player) {
		ScreenHandler handler = player.currentScreenHandler;
		int containerSlots = containerSlotCount(handler);
		if (containerSlots <= 0) {
			return;
		}

		ResolvedChest resolved = resolveChest(handler, containerSlots, payload.path());
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

		writeResolvedChest(resolved);
		handler.sendContentUpdates();
		syncPathAndAncestors(player, handler, containerSlots, payload.path());
	}

	public static boolean isChestItem(ItemStack stack) {
		return stack.isOf(Items.CHEST) || stack.isOf(Items.TRAPPED_CHEST);
	}

	public static DefaultedList<ItemStack> getNestedStacks(ItemStack stack) {
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
		ContainerComponent component = stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
		component.copyTo(stacks);
		return stacks;
	}

	public static void setNestedStacks(ItemStack stack, DefaultedList<ItemStack> stacks) {
		stack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
	}

	public static ItemStack sanitizeDroppedChestItem(ItemStack stack) {
		if (!isChestItem(stack)) {
			return stack;
		}
		return new ItemStack(stack.getItem(), stack.getCount());
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

	private static void runVanillaNestedClick(ResolvedNestedContainer resolved, ScreenHandler realHandler, ServerPlayerEntity player, int slot, int button, SlotActionType actionType) {
		SimpleInventory inventory = copyToInventory(resolved);
		GenericContainerScreenHandler nestedHandler = createNestedHandler(resolved, realHandler, player, inventory);
		nestedHandler.setCursorStack(realHandler.getCursorStack().copy());
		nestedHandler.onSlotClick(slot, button, actionType, player);
		realHandler.setCursorStack(nestedHandler.getCursorStack().copy());

		writeNestedContainer(resolved, copyFromInventory(inventory, resolved.size()));
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
			writeNestedContainer(session.resolved(), copyFromInventory(session.inventory(), session.resolved().size()));
			QUICK_CRAFT_SESSIONS.remove(playerId);
			realHandler.sendContentUpdates();
			syncPathAndAncestors(player, realHandler, containerSlots, session.resolved().path());
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

	private static ResolvedChest resolveChest(ScreenHandler handler, int containerSlots, List<Integer> path) {
		if (path.isEmpty()) {
			return null;
		}

		int rootSlotIndex = path.getFirst();
		if (rootSlotIndex < 0 || rootSlotIndex >= containerSlots || rootSlotIndex >= handler.slots.size()) {
			return null;
		}

		Slot rootSlot = handler.getSlot(rootSlotIndex);
		ItemStack current = rootSlot.getStack();
		if (!canOpenNestedWindow(current)) {
			return null;
		}

		ResolvedChest resolved = new ResolvedChest(rootSlot, current, List.of());
		for (int depth = 1; depth < path.size(); depth++) {
			int nestedSlot = path.get(depth);
			ResolvedChest adjacent = resolveAdjacentChest(handler, containerSlots, List.copyOf(path.subList(0, depth)));
			int parentSize = adjacent == null ? NESTED_CHEST_SIZE : DOUBLE_NESTED_CHEST_SIZE;
			if (nestedSlot < 0 || nestedSlot >= parentSize) {
				return null;
			}

			ResolvedChest parent = nestedSlot < NESTED_CHEST_SIZE ? resolved : adjacent;
			if (parent == null) {
				return null;
			}
			int actualSlot = nestedSlot % NESTED_CHEST_SIZE;
			DefaultedList<ItemStack> parentStacks = getNestedStacks(parent.stack());
			current = parentStacks.get(actualSlot);
			if (!canOpenNestedWindow(current)) {
				return null;
			}

			List<ResolvedFrame> frames = new ArrayList<>(parent.frames());
			frames.add(new ResolvedFrame(parent.stack(), parentStacks, actualSlot));
			resolved = new ResolvedChest(parent.rootSlot(), current, List.copyOf(frames));
		}

		return resolved;
	}

	private static ResolvedNestedContainer resolveNestedContainer(ScreenHandler handler, int containerSlots, List<Integer> path) {
		List<Integer> normalizedPath = normalizePathForServer(handler, containerSlots, path);
		ResolvedChest primary = resolveChest(handler, containerSlots, normalizedPath);
		if (primary == null) {
			return null;
		}

		ResolvedChest secondary = resolveAdjacentChest(handler, containerSlots, normalizedPath);
		int size = secondary == null ? NESTED_CHEST_SIZE : DOUBLE_NESTED_CHEST_SIZE;
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(size, ItemStack.EMPTY);
		copyInto(stacks, 0, getNestedStacks(primary.stack()));
		if (secondary != null) {
			copyInto(stacks, NESTED_CHEST_SIZE, getNestedStacks(secondary.stack()));
		}
		return new ResolvedNestedContainer(normalizedPath, primary, secondary, stacks);
	}

	private static List<Integer> normalizePathForServer(ScreenHandler handler, int containerSlots, List<Integer> path) {
		if (path.isEmpty()) {
			return path;
		}
		int lastSlot = path.getLast();
		DefaultedList<ItemStack> parentStacks = stacksForParentPath(handler, containerSlots, List.copyOf(path.subList(0, path.size() - 1)));
		int pairStart = getNestedChestPairStart(parentStacks, lastSlot);
		if (pairStart < 0 || pairStart == lastSlot) {
			return path;
		}
		List<Integer> normalizedPath = new ArrayList<>(path);
		normalizedPath.set(normalizedPath.size() - 1, pairStart);
		return List.copyOf(normalizedPath);
	}

	private static ResolvedChest resolveAdjacentChest(ScreenHandler handler, int containerSlots, List<Integer> leftPath) {
		if (leftPath.isEmpty()) {
			return null;
		}
		DefaultedList<ItemStack> parentStacks = stacksForParentPath(handler, containerSlots, List.copyOf(leftPath.subList(0, leftPath.size() - 1)));
		int lastSlot = leftPath.getLast();
		int secondarySlot = getNestedChestPairSecondarySlot(parentStacks, lastSlot);
		if (secondarySlot < 0) {
			return null;
		}
		List<Integer> rightPath = new ArrayList<>(leftPath);
		rightPath.set(rightPath.size() - 1, secondarySlot);
		return resolveChest(handler, containerSlots, rightPath);
	}

	private static DefaultedList<ItemStack> stacksForParentPath(ScreenHandler handler, int containerSlots, List<Integer> parentPath) {
		if (parentPath.isEmpty()) {
			DefaultedList<ItemStack> stacks = DefaultedList.ofSize(containerSlots, ItemStack.EMPTY);
			for (int i = 0; i < stacks.size() && i < handler.slots.size(); i++) {
				stacks.set(i, handler.getSlot(i).getStack());
			}
			return stacks;
		}

		ResolvedNestedContainer parent = resolveNestedContainer(handler, containerSlots, parentPath);
		return parent == null ? DefaultedList.ofSize(0, ItemStack.EMPTY) : parent.stacks();
	}

	private static void copyInto(DefaultedList<ItemStack> target, int offset, DefaultedList<ItemStack> source) {
		for (int i = 0; i < NESTED_CHEST_SIZE && i < source.size() && offset + i < target.size(); i++) {
			target.set(offset + i, source.get(i).copy());
		}
	}

	private static void writeNestedContainer(ResolvedNestedContainer resolved, DefaultedList<ItemStack> stacks) {
		DefaultedList<ItemStack> primaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
		for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
			primaryStacks.set(i, stacks.get(i).copy());
		}
		setNestedStacks(resolved.primary().stack(), primaryStacks);

		if (resolved.secondary() != null) {
			DefaultedList<ItemStack> secondaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
			for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
				secondaryStacks.set(i, stacks.get(NESTED_CHEST_SIZE + i).copy());
			}
			setNestedStacks(resolved.secondary().stack(), secondaryStacks);
			writeResolvedChestPair(resolved.primary(), resolved.secondary());
		} else {
			writeResolvedChest(resolved.primary());
		}
	}

	private static void writeResolvedChestPair(ResolvedChest primary, ResolvedChest secondary) {
		if (!canWriteAsSiblingPair(primary, secondary)) {
			writeResolvedChest(primary);
			writeResolvedChest(secondary);
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
		lastPrimaryFrame.stacks().set(lastPrimaryFrame.childSlot(), primary.stack());
		lastPrimaryFrame.stacks().set(lastSecondaryFrame.childSlot(), secondary.stack());

		ItemStack child = lastPrimaryFrame.stack();
		setNestedStacks(child, lastPrimaryFrame.stacks());
		for (int i = lastFrameIndex - 1; i >= 0; i--) {
			ResolvedFrame frame = primary.frames().get(i);
			frame.stacks().set(frame.childSlot(), child);
			setNestedStacks(frame.stack(), frame.stacks());
			child = frame.stack();
		}
		primary.rootSlot().setStack(child);
		primary.rootSlot().markDirty();
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

	private static void writeResolvedChest(ResolvedChest resolved) {
		ItemStack child = resolved.stack();
		for (int i = resolved.frames().size() - 1; i >= 0; i--) {
			ResolvedFrame frame = resolved.frames().get(i);
			frame.stacks().set(frame.childSlot(), child);
			setNestedStacks(frame.stack(), frame.stacks());
			child = frame.stack();
		}
		resolved.rootSlot().setStack(child);
		resolved.rootSlot().markDirty();
	}

	private static void syncPathAndAncestors(ServerPlayerEntity player, ScreenHandler handler, int containerSlots, List<Integer> path) {
		for (int size = 1; size <= path.size(); size++) {
			List<Integer> subPath = List.copyOf(path.subList(0, size));
			ResolvedNestedContainer resolved = resolveNestedContainer(handler, containerSlots, subPath);
			if (resolved != null) {
				ServerPlayNetworking.send(player, new NestedChestSyncPayload(resolved.path(), resolved.stacks()));
			}
		}
	}

	public static boolean canOpenNestedWindow(ItemStack stack) {
		return isChestItem(stack) && stack.getCount() == 1;
	}

	public static int getNestedChestPairStart(DefaultedList<ItemStack> stacks, int slot) {
		if (!isPairableNestedSlot(stacks, slot)) {
			return -1;
		}

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

		ItemStack remaining = stack;
		boolean[] handledSlots = new boolean[inventory.size()];
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
			copyInto(nestedStacks, 0, getNestedStacks(primary));
			if (secondarySlot >= 0) {
				copyInto(nestedStacks, NESTED_CHEST_SIZE, getNestedStacks(inventory.getStack(secondarySlot)));
			}

			remaining = insertIntoStacks(nestedStacks, remaining);
			if (remaining.getCount() != stack.getCount()) {
				writeBoundNestedStacks(inventory, primarySlot, secondarySlot, nestedStacks);
				inventory.markDirty();
			}
		}
		return remaining;
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

	private static void writeBoundNestedStacks(Inventory inventory, int primarySlot, int secondarySlot, DefaultedList<ItemStack> stacks) {
		ItemStack primary = inventory.getStack(primarySlot);
		DefaultedList<ItemStack> primaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
		for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
			primaryStacks.set(i, stacks.get(i).copy());
		}
		setNestedStacks(primary, primaryStacks);
		inventory.setStack(primarySlot, primary);

		if (secondarySlot >= 0) {
			ItemStack secondary = inventory.getStack(secondarySlot);
			DefaultedList<ItemStack> secondaryStacks = DefaultedList.ofSize(NESTED_CHEST_SIZE, ItemStack.EMPTY);
			for (int i = 0; i < NESTED_CHEST_SIZE; i++) {
				secondaryStacks.set(i, stacks.get(NESTED_CHEST_SIZE + i).copy());
			}
			setNestedStacks(secondary, secondaryStacks);
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

	private record ResolvedFrame(ItemStack stack, DefaultedList<ItemStack> stacks, int childSlot) {
	}

	private record ResolvedNestedContainer(List<Integer> path, ResolvedChest primary, ResolvedChest secondary, DefaultedList<ItemStack> stacks) {
		private int size() {
			return stacks.size();
		}
	}

	private record QuickCraftSession(List<Integer> path, ResolvedNestedContainer resolved, SimpleInventory inventory, GenericContainerScreenHandler handler) {
	}
}
