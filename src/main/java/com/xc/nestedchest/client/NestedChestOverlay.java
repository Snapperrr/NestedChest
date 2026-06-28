package com.xc.nestedchest.client;

import com.xc.nestedchest.NestedChestMod;
import com.xc.nestedchest.mixin.ScreenAccessor;
import com.xc.nestedchest.network.NestedChestClickPayload;
import com.xc.nestedchest.network.NestedChestRenamePayload;
import com.xc.nestedchest.screen.ConnectedChestScreenHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class NestedChestOverlay {
	private static final int SLOT_SIZE = 18;
	private static final int GRID_COLUMNS = 9;
	private static final int TITLE_BAR_HEIGHT = 16;
	private static final int PADDING = 7;
	private static final int WINDOW_WIDTH = PADDING * 2 + GRID_COLUMNS * SLOT_SIZE;
	private static final int RENAME_WIDTH = 190;
	private static final int RENAME_HEIGHT = 86;
	private static final int RENAME_BUTTON_WIDTH = 78;
	private static final int RENAME_BUTTON_HEIGHT = 20;
	private static final float OVERLAY_Z = 500.0F;
	private static final float WINDOW_LAYER_Z = 300.0F;
	private static final float MAX_WINDOW_Z = 6000.0F;
	private static final float DETAIL_LAYER_Z = 7500.0F;
	private static final float CURSOR_LAYER_Z = 9000.0F;
	private static final List<NestedWindow> WINDOWS = new ArrayList<>();

	private static NestedWindow draggedWindow;
	private static RenameDialog renameDialog;
	private static ClickMemory lastClick;
	private static QuickCraftDrag quickCraftDrag;
	private static DragPickup dragPickup;
	private static int dragOffsetX;
	private static int dragOffsetY;

	private NestedChestOverlay() {
	}

	public static void reset() {
		WINDOWS.clear();
		draggedWindow = null;
		lastClick = null;
		quickCraftDrag = null;
		dragPickup = null;
		closeRenameDialog(false);
	}

	public static void render(ScreenHandler handler, DrawContext context, int mouseX, int mouseY) {
		removeInvalidWindows(handler);
		removeInvalidRenameDialog(handler);
		sortForAncestorLayering();

		boolean hasRootMarkers = hasRootDoubleChestMarkers(handler);
		if (!hasRootMarkers && WINDOWS.isEmpty() && renameDialog == null) {
			return;
		}

		RenderSystem.disableDepthTest();
		context.getMatrices().push();
		context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);

		if (hasRootMarkers) {
			renderRootDoubleChestMarkers(handler, context);
			flushLayer(context);
		}

		for (int layer = 0; layer < WINDOWS.size(); layer++) {
			NestedWindow window = WINDOWS.get(layer);
			context.getMatrices().push();
			context.getMatrices().translate(0.0F, 0.0F, windowLayerZ(layer));
			RenderSystem.disableDepthTest();
			window.clampToScreen();
			window.render(context, mouseX, mouseY);
			flushLayer(context);
			context.getMatrices().pop();
		}

		context.getMatrices().push();
		context.getMatrices().translate(0.0F, 0.0F, DETAIL_LAYER_Z);
		ItemStack hoveredStack = handler.getCursorStack().isEmpty() ? getHoveredStack(mouseX, mouseY) : ItemStack.EMPTY;
		if (renameDialog != null) {
			renameDialog.render(context, mouseX, mouseY);
			flushLayer(context);
		} else if (!hoveredStack.isEmpty()) {
			context.drawItemTooltip(MinecraftClient.getInstance().textRenderer, hoveredStack, mouseX, mouseY);
			flushLayer(context);
		}
		context.getMatrices().pop();

		renderCursorStack(handler, context, mouseX, mouseY);

		context.getMatrices().pop();
		RenderSystem.enableDepthTest();
	}

	private static void renderCursorStack(ScreenHandler handler, DrawContext context, int mouseX, int mouseY) {
		ItemStack cursorStack = handler.getCursorStack();
		if (cursorStack.isEmpty()) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		context.getMatrices().push();
		context.getMatrices().translate(0.0F, 0.0F, CURSOR_LAYER_Z);
		RenderSystem.disableDepthTest();
		context.drawItem(cursorStack, mouseX - 8, mouseY - 8);
		context.drawItemInSlot(client.textRenderer, cursorStack, mouseX - 8, mouseY - 8);
		flushLayer(context);
		context.getMatrices().pop();
	}

	private static void flushLayer(DrawContext context) {
		context.draw();
		RenderSystem.disableDepthTest();
	}

	private static float windowLayerZ(int layer) {
		return Math.min(layer * WINDOW_LAYER_Z, MAX_WINDOW_Z);
	}

	public static boolean blocksVanillaTooltip(double mouseX, double mouseY) {
		if (renameDialog != null) {
			return true;
		}
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			if (WINDOWS.get(i).contains(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
	}

	public static boolean mouseClicked(ScreenHandler handler, double mouseX, double mouseY, int button) {
		if (renameDialog != null) {
			return renameDialog.mouseClicked(mouseX, mouseY, button);
		}

		if (button != 0 && button != 1) {
			return false;
		}
		dragPickup = null;

		sortForAncestorLayering();
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			NestedWindow window = WINDOWS.get(i);
			if (!window.contains(mouseX, mouseY)) {
				continue;
			}

			raise(window);
			if (window.closeContains(mouseX, mouseY)) {
				removeWindowAndDescendants(window);
				return true;
			}

			if (window.titleContains(mouseX, mouseY)) {
				draggedWindow = window;
				quickCraftDrag = null;
				dragOffsetX = (int) mouseX - window.x;
				dragOffsetY = (int) mouseY - window.y;
				return true;
			}

			int nestedSlot = window.slotAt(mouseX, mouseY);
			if (nestedSlot >= 0) {
				ItemStack stack = window.stacks.get(nestedSlot);
				if (beginQuickCraftIfNeeded(handler, window, nestedSlot, button)) {
					return true;
				}
				if (button == 1 && NestedChestMod.canOpenNestedWindow(stack)) {
					if (Screen.hasShiftDown()) {
						openRenameDialog(window.childPath(nestedSlot), stack);
					} else {
						List<Integer> childPath = normalizeChildPath(window, nestedSlot);
						ContainerView view = window.containerViewForSlot(childPath.getLast());
						if (view != null) {
							open(childPath, view);
						}
					}
					return true;
				}

				sendMouseClick(window, nestedSlot, button);
				dragPickup = new DragPickup(window, true, button);
				return true;
			}

			return true;
		}

		NestedWindow activeWindow = getRaisedWindow();
		Slot playerSlot = getPlayerSlotAt(handler, mouseX, mouseY);
		if (activeWindow != null && playerSlot != null) {
			int playerSlotOffset = playerSlot.id - containerSlotCount(handler);
			int nestedPlayerSlot = activeWindow.stacks.size() + playerSlotOffset;
			if (beginQuickCraftIfNeeded(handler, activeWindow, nestedPlayerSlot, button)) {
				return true;
			}
			sendMouseClick(activeWindow, nestedPlayerSlot, button);
			dragPickup = new DragPickup(activeWindow, false, button);
			return true;
		}

		Slot parentSlot = getParentSlotAt(handler, mouseX, mouseY);
		if (button == 1 && parentSlot != null && NestedChestMod.canOpenNestedWindow(parentSlot.getStack())) {
			if (Screen.hasShiftDown()) {
				openRenameDialog(List.of(parentSlot.id), parentSlot.getStack());
			} else {
				List<Integer> path = normalizeRootPath(handler, parentSlot.id);
				ContainerView view = getContainerViewForPath(handler, path);
				if (view != null) {
					open(path, view);
				}
			}
			return true;
		}

		return false;
	}

	private static List<Integer> normalizeRootPath(ScreenHandler handler, int rootSlot) {
		int pairStart = NestedChestMod.getNestedChestPairStart(getRootStacks(handler), rootSlot);
		if (pairStart < 0 || pairStart == rootSlot) {
			return List.of(rootSlot);
		}
		return List.of(pairStart);
	}

	private static List<Integer> normalizeChildPath(NestedWindow window, int nestedSlot) {
		int pairStart = NestedChestMod.getNestedChestPairStart(window.stacks, nestedSlot);
		if (pairStart < 0 || pairStart == nestedSlot) {
			return window.childPath(nestedSlot);
		}
		return window.childPath(pairStart);
	}

	public static boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (renameDialog != null) {
			return true;
		}
		if (quickCraftDrag != null && quickCraftDrag.button == button) {
			if (quickCraftDrag.visitedSlots.isEmpty()) {
				int slot = slotAtForQuickCraft(quickCraftDrag.window, mouseX, mouseY);
				sendClick(quickCraftDrag.window, slot < 0 ? -999 : slot, button, SlotActionType.PICKUP);
			} else {
				sendQuickCraft(quickCraftDrag.window, -999, button, 0);
				for (int slot : quickCraftDrag.visitedSlots) {
					sendQuickCraft(quickCraftDrag.window, slot, button, 1);
				}
				sendQuickCraft(quickCraftDrag.window, -999, button, 2);
			}
			quickCraftDrag = null;
			return true;
		}
		if (dragPickup != null && dragPickup.button == button) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player != null) {
				if (dragPickup.startedInNested) {
					Slot playerSlot = getPlayerSlotAt(client.player.currentScreenHandler, mouseX, mouseY);
					if (playerSlot != null) {
						int playerSlotOffset = playerSlot.id - containerSlotCount(client.player.currentScreenHandler);
						sendClick(dragPickup.window, dragPickup.window.stacks.size() + playerSlotOffset, button, SlotActionType.PICKUP);
						dragPickup = null;
						return true;
					}
				} else {
					NestedWindow topWindow = getTopWindow();
					if (topWindow != null) {
						int hoveredSlot = topWindow.slotAt(mouseX, mouseY);
						if (hoveredSlot >= 0) {
							sendClick(topWindow, hoveredSlot, button, SlotActionType.PICKUP);
							dragPickup = null;
							return true;
						}
					}
				}
			}
			dragPickup = null;
		}
		NestedWindow topWindow = getTopWindow();
		if (topWindow != null && button >= 0 && button <= 1) {
			int hoveredSlot = topWindow.slotAt(mouseX, mouseY);
			MinecraftClient client = MinecraftClient.getInstance();
			if (hoveredSlot >= 0 && client.player != null && !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
				sendClick(topWindow, hoveredSlot, button, SlotActionType.PICKUP);
				return true;
			}
		}
		if (quickCraftDrag != null) {
			quickCraftDrag = null;
			return true;
		}
		if (draggedWindow != null) {
			draggedWindow = null;
			return true;
		}
		return false;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (renameDialog != null) {
			return true;
		}
		if (quickCraftDrag != null && quickCraftDrag.button == button) {
			int slot = slotAtForQuickCraft(quickCraftDrag.window, mouseX, mouseY);
			if (slot >= 0 && !quickCraftDrag.visitedSlots.contains(slot)) {
				if (quickCraftDrag.visitedSlots.isEmpty() && quickCraftDrag.startSlot >= 0 && quickCraftDrag.startSlot != slot) {
					quickCraftDrag.visitedSlots.add(quickCraftDrag.startSlot);
				}
				quickCraftDrag.visitedSlots.add(slot);
			}
			return true;
		}
		if (draggedWindow == null) {
			return false;
		}

		draggedWindow.x = clamp((int) mouseX - dragOffsetX, 0, Math.max(0, MinecraftClient.getInstance().getWindow().getScaledWidth() - draggedWindow.width()));
		draggedWindow.y = clamp((int) mouseY - dragOffsetY, 0, Math.max(0, MinecraftClient.getInstance().getWindow().getScaledHeight() - draggedWindow.height()));
		return true;
	}

	public static boolean keyPressed(ScreenHandler handler, int keyCode, int scanCode, int modifiers) {
		if (renameDialog == null) {
			NestedWindow window = getTopWindow();
			if (window == null) {
				return false;
			}
			int hoveredSlot = getHoveredSlotInWindow(window);
			if (hoveredSlot < 0) {
				return false;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			for (int i = 0; i < client.options.hotbarKeys.length; i++) {
				if (client.options.hotbarKeys[i].matchesKey(keyCode, scanCode)) {
					sendClick(window, hoveredSlot, i, SlotActionType.SWAP);
					return true;
				}
			}
			if (client.options.swapHandsKey.matchesKey(keyCode, scanCode)) {
				sendClick(window, hoveredSlot, 40, SlotActionType.SWAP);
				return true;
			}
			if (client.options.dropKey.matchesKey(keyCode, scanCode)) {
				sendClick(window, hoveredSlot, Screen.hasControlDown() ? 1 : 0, SlotActionType.THROW);
				return true;
			}
			if (client.options.pickItemKey.matchesKey(keyCode, scanCode) && client.interactionManager != null && client.interactionManager.hasCreativeInventory()) {
				sendClick(window, hoveredSlot, 2, SlotActionType.CLONE);
				return true;
			}
			return false;
		}
		return renameDialog.keyPressed(keyCode, scanCode, modifiers);
	}

	public static void sync(List<Integer> path, DefaultedList<ItemStack> stacks) {
		for (NestedWindow window : WINDOWS) {
			if (window.path.equals(path)) {
				window.setStacks(stacks);
			}
		}
	}

	private static void open(List<Integer> path, ContainerView view) {
		for (NestedWindow window : WINDOWS) {
			if (window.path.equals(path)) {
				raise(window);
				return;
			}
		}

		MinecraftClient client = MinecraftClient.getInstance();
		int offset = WINDOWS.size() * 12;
		int x = clamp((client.getWindow().getScaledWidth() - WINDOW_WIDTH) / 2 + offset, 0, Math.max(0, client.getWindow().getScaledWidth() - WINDOW_WIDTH));
		NestedWindow window = new NestedWindow(path, x, 24 + offset, view);
		window.clampToScreen();
		WINDOWS.add(window);
	}

	private static void openRenameDialog(List<Integer> path, ItemStack stack) {
		closeRenameDialog(false);
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (screen == null) {
			return;
		}
		renameDialog = new RenameDialog(screen, path, stack);
		((ScreenAccessor) screen).nestedchest$addSelectableChild(renameDialog.nameField);
		screen.setFocused(renameDialog.nameField);
		renameDialog.nameField.setFocused(true);
	}

	private static void closeRenameDialog(boolean save) {
		if (renameDialog == null) {
			return;
		}

		RenameDialog dialog = renameDialog;
		renameDialog = null;
		((ScreenAccessor) dialog.screen).nestedchest$remove(dialog.nameField);
		if (save) {
			ClientPlayNetworking.send(new NestedChestRenamePayload(dialog.path, dialog.nameField.getText()));
		}
	}

	private static void raise(NestedWindow window) {
		WINDOWS.remove(window);
		WINDOWS.add(window);
		sortForAncestorLayering();
	}

	private static void removeWindowAndDescendants(NestedWindow window) {
		WINDOWS.removeIf(candidate -> isSameOrDescendant(candidate.path, window.path));
	}

	private static boolean isSameOrDescendant(List<Integer> path, List<Integer> parentPath) {
		return path.size() >= parentPath.size() && path.subList(0, parentPath.size()).equals(parentPath);
	}

	private static boolean isProperDescendant(List<Integer> path, List<Integer> parentPath) {
		return path.size() > parentPath.size() && path.subList(0, parentPath.size()).equals(parentPath);
	}

	private static void sortForAncestorLayering() {
		WINDOWS.sort((left, right) -> {
			if (isProperDescendant(left.path, right.path)) {
				return 1;
			}
			if (isProperDescendant(right.path, left.path)) {
				return -1;
			}
			return 0;
		});
	}

	private static void renderRootDoubleChestMarkers(ScreenHandler handler, DrawContext context) {
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (!(screen instanceof NestedChestScreenBridge bridge)) {
			return;
		}
		for (int slot = 0; slot < containerSlotCount(handler) - 1 && slot + 1 < handler.slots.size(); slot++) {
			if (!isNestedPairStart(getRootStacks(handler), slot)) {
				continue;
			}
			int x = bridge.nestedchest$getRootX() + handler.getSlot(slot).x;
			int y = bridge.nestedchest$getRootY() + handler.getSlot(slot).y;
			drawDoubleChestMarker(context, x, y);
		}
	}

	private static boolean hasRootDoubleChestMarkers(ScreenHandler handler) {
		if (!(MinecraftClient.getInstance().currentScreen instanceof NestedChestScreenBridge)) {
			return false;
		}
		DefaultedList<ItemStack> stacks = getRootStacks(handler);
		for (int slot = 0; slot < containerSlotCount(handler) - 1 && slot + 1 < handler.slots.size(); slot++) {
			if (isNestedPairStart(stacks, slot)) {
				return true;
			}
		}
		return false;
	}

	private static void drawDoubleChestMarker(DrawContext context, int x, int y) {
		context.fill(x - 1, y - 1, x + SLOT_SIZE * 2 - 1, y + SLOT_SIZE - 1, 0x2218A058);
		context.drawBorder(x - 1, y - 1, SLOT_SIZE * 2, SLOT_SIZE, 0xFF1E8E50);
		context.drawVerticalLine(x + SLOT_SIZE - 1, y, y + SLOT_SIZE - 3, 0x661E8E50);
	}

	private static Slot getParentSlotAt(ScreenHandler handler, double mouseX, double mouseY) {
		for (int i = 0; i < containerSlotCount(handler) && i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (isPointOverSlot(slot, mouseX, mouseY)) {
				return slot;
			}
		}
		return null;
	}

	private static Slot getPlayerSlotAt(ScreenHandler handler, double mouseX, double mouseY) {
		int firstPlayerSlot = containerSlotCount(handler);
		for (int i = firstPlayerSlot; i < firstPlayerSlot + 36 && i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (isPointOverSlot(slot, mouseX, mouseY)) {
				return slot;
			}
		}
		return null;
	}

	private static boolean isPointOverSlot(Slot slot, double mouseX, double mouseY) {
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (!(screen instanceof NestedChestScreenBridge bridge)) {
			return false;
		}
		int left = bridge.nestedchest$getRootX() + slot.x;
		int top = bridge.nestedchest$getRootY() + slot.y;
		return mouseX >= left && mouseX < left + 16 && mouseY >= top && mouseY < top + 16;
	}

	private static void removeInvalidWindows(ScreenHandler handler) {
		for (Iterator<NestedWindow> iterator = WINDOWS.iterator(); iterator.hasNext(); ) {
			NestedWindow window = iterator.next();
			ContainerView view = getContainerViewForPath(handler, window.path);
			if (view == null) {
				iterator.remove();
				continue;
			}
			window.setSource(view);
		}
	}

	private static ItemStack getHoveredStack(int mouseX, int mouseY) {
		if (renameDialog != null) {
			return ItemStack.EMPTY;
		}
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			NestedWindow window = WINDOWS.get(i);
			if (!window.contains(mouseX, mouseY)) {
				continue;
			}
			int slot = window.slotAt(mouseX, mouseY);
			return slot >= 0 ? window.stacks.get(slot) : ItemStack.EMPTY;
		}
		return ItemStack.EMPTY;
	}

	private static NestedWindow getTopWindow() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.mouse == null) {
			return null;
		}
		double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
		double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			NestedWindow window = WINDOWS.get(i);
			if (window.contains(mouseX, mouseY)) {
				return window;
			}
		}
		return null;
	}

	private static NestedWindow getRaisedWindow() {
		if (WINDOWS.isEmpty()) {
			return null;
		}
		return WINDOWS.getLast();
	}

	private static int getHoveredSlotInWindow(NestedWindow window) {
		MinecraftClient client = MinecraftClient.getInstance();
		double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
		double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
		return window.slotAt(mouseX, mouseY);
	}

	private static void sendMouseClick(NestedWindow window, int slot, int button) {
		long now = System.currentTimeMillis();
		boolean doubleClick = button == 0 && lastClick != null && lastClick.window == window && lastClick.slot == slot && lastClick.button == button && now - lastClick.time <= 250L;
		if (doubleClick) {
			sendClick(window, slot, button, SlotActionType.PICKUP_ALL);
		} else if (Screen.hasShiftDown()) {
			sendClick(window, slot, button, SlotActionType.QUICK_MOVE);
		} else {
			sendClick(window, slot, button, SlotActionType.PICKUP);
		}
		lastClick = new ClickMemory(window, slot, button, now);
	}

	private static void sendClick(NestedWindow window, int slot, int button, SlotActionType actionType) {
		ClientPlayNetworking.send(new NestedChestClickPayload(window.path, slot, button, actionType));
	}

	private static boolean beginQuickCraftIfNeeded(ScreenHandler handler, NestedWindow window, int slot, int button) {
		if (button != 0 && button != 1) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || handler.getCursorStack().isEmpty()) {
			return false;
		}
		quickCraftDrag = new QuickCraftDrag(window, button);
		quickCraftDrag.startSlot = slot;
		return true;
	}

	private static int slotAtForQuickCraft(NestedWindow window, double mouseX, double mouseY) {
		int nestedSlot = window.slotAt(mouseX, mouseY);
		if (nestedSlot >= 0) {
			return nestedSlot;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (!(client.currentScreen instanceof NestedChestScreenBridge) || client.player == null || containerSlotCount(client.player.currentScreenHandler) <= 0) {
			return -1;
		}
		ScreenHandler handler = client.player.currentScreenHandler;
		Slot playerSlot = getPlayerSlotAt(handler, mouseX, mouseY);
		if (playerSlot == null) {
			return -1;
		}
		int playerSlotOffset = playerSlot.id - containerSlotCount(handler);
		return window.stacks.size() + playerSlotOffset;
	}

	private static void sendQuickCraft(NestedWindow window, int slot, int button, int stage) {
		sendClick(window, slot, ScreenHandler.packQuickCraftData(stage, button), SlotActionType.QUICK_CRAFT);
	}

	private static void removeInvalidRenameDialog(ScreenHandler handler) {
		if (renameDialog != null && !NestedChestMod.canOpenNestedWindow(getStackForPath(handler, renameDialog.path))) {
			closeRenameDialog(false);
		}
	}

	private static ContainerView getContainerViewForPath(ScreenHandler handler, List<Integer> path) {
		if (path.isEmpty()) {
			return null;
		}

		int rootSlot = path.getFirst();
		if (rootSlot < 0 || rootSlot >= containerSlotCount(handler) || rootSlot >= handler.slots.size()) {
			return null;
		}

		ItemStack current = handler.getSlot(rootSlot).getStack();
		if (!NestedChestMod.canOpenNestedWindow(current)) {
			return null;
		}

		ContainerView view = containerViewFromSlots(getRootStacks(handler), rootSlot);
		for (int i = 1; i < path.size(); i++) {
			if (view == null) {
				return null;
			}
			int nestedSlot = path.get(i);
			if (nestedSlot < 0 || nestedSlot >= view.stacks.size()) {
				return null;
			}
			current = view.stacks.get(nestedSlot);
			if (!NestedChestMod.canOpenNestedWindow(current)) {
				return null;
			}
			view = containerViewFromSlots(view.stacks, nestedSlot);
		}

		return view;
	}

	private static ItemStack getStackForPath(ScreenHandler handler, List<Integer> path) {
		if (path.isEmpty()) {
			return ItemStack.EMPTY;
		}

		int rootSlot = path.getFirst();
		if (rootSlot < 0 || rootSlot >= containerSlotCount(handler) || rootSlot >= handler.slots.size()) {
			return ItemStack.EMPTY;
		}

		ItemStack current = handler.getSlot(rootSlot).getStack();
		if (!NestedChestMod.canOpenNestedWindow(current)) {
			return ItemStack.EMPTY;
		}

		ContainerView view = containerViewFromSlots(getRootStacks(handler), rootSlot);
		for (int i = 1; i < path.size(); i++) {
			int nestedSlot = path.get(i);
			if (view == null || nestedSlot < 0 || nestedSlot >= view.stacks.size()) {
				return ItemStack.EMPTY;
			}
			current = view.stacks.get(nestedSlot);
			if (!NestedChestMod.canOpenNestedWindow(current)) {
				return ItemStack.EMPTY;
			}
			view = containerViewFromSlots(view.stacks, nestedSlot);
		}

		return current;
	}

	private static DefaultedList<ItemStack> getRootStacks(ScreenHandler handler) {
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(containerSlotCount(handler), ItemStack.EMPTY);
		for (int i = 0; i < stacks.size() && i < handler.slots.size(); i++) {
			stacks.set(i, handler.getSlot(i).getStack());
		}
		return stacks;
	}

	private static ContainerView containerViewFromSlots(DefaultedList<ItemStack> stacks, int slot) {
		if (slot < 0 || slot >= stacks.size()) {
			return null;
		}
		ItemStack primary = stacks.get(slot);
		if (!NestedChestMod.canOpenNestedWindow(primary)) {
			return null;
		}

		DefaultedList<ItemStack> nestedStacks = DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY);
		copyInto(nestedStacks, 0, NestedChestMod.getNestedStacks(primary));
		int secondarySlot = NestedChestMod.getNestedChestPairSecondarySlot(stacks, slot);
		if (secondarySlot >= 0) {
			DefaultedList<ItemStack> doubleStacks = DefaultedList.ofSize(NestedChestMod.DOUBLE_NESTED_CHEST_SIZE, ItemStack.EMPTY);
			copyInto(doubleStacks, 0, nestedStacks);
			copyInto(doubleStacks, NestedChestMod.NESTED_CHEST_SIZE, NestedChestMod.getNestedStacks(stacks.get(secondarySlot)));
			return new ContainerView(primary.getName(), doubleStacks);
		}
		return new ContainerView(primary.getName(), nestedStacks);
	}

	private static boolean isNestedPairStart(DefaultedList<ItemStack> stacks, int slot) {
		return NestedChestMod.getNestedChestPairSecondarySlot(stacks, slot) >= 0;
	}

	private static void copyInto(DefaultedList<ItemStack> target, int offset, DefaultedList<ItemStack> source) {
		for (int i = 0; i < source.size() && offset + i < target.size(); i++) {
			target.set(offset + i, source.get(i).copy());
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public static boolean supports(ScreenHandler handler) {
		return containerSlotCount(handler) > 0;
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

	private static final class NestedWindow {
		private final List<Integer> path;
		private DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY);
		private Text title;
		private int x;
		private int y;

		private NestedWindow(List<Integer> path, int x, int y, ContainerView view) {
			this.path = List.copyOf(path);
			this.x = x;
			this.y = y;
			setSource(view);
		}

		private void setSource(ContainerView view) {
			this.title = view.title();
			setStacks(view.stacks());
		}

		private void setStacks(DefaultedList<ItemStack> source) {
			if (this.stacks.size() != source.size()) {
				this.stacks = DefaultedList.ofSize(source.size(), ItemStack.EMPTY);
			}
			for (int i = 0; i < this.stacks.size(); i++) {
				this.stacks.set(i, i < source.size() ? source.get(i).copy() : ItemStack.EMPTY);
			}
		}

		private List<Integer> childPath(int nestedSlot) {
			List<Integer> childPath = new ArrayList<>(this.path);
			childPath.add(nestedSlot);
			return List.copyOf(childPath);
		}

		private ContainerView containerViewForSlot(int slot) {
			return containerViewFromSlots(this.stacks, slot);
		}

		private void render(DrawContext context, int mouseX, int mouseY) {
			MinecraftClient client = MinecraftClient.getInstance();
			context.fill(x + 2, y + 3, x + width() + 2, y + height() + 3, 0x66000000);
			context.fill(x, y, x + width(), y + height(), 0xFFF7F7F7);
			context.fill(x, y, x + WINDOW_WIDTH, y + TITLE_BAR_HEIGHT, 0xFF3A4D5F);
			context.drawText(client.textRenderer, client.textRenderer.trimToWidth(title.getString(), WINDOW_WIDTH - 28), x + 6, y + 5, 0xFFFFFFFF, false);
			context.drawText(client.textRenderer, Text.literal("x"), x + WINDOW_WIDTH - 12, y + 5, 0xFFFFFFFF, false);
			context.drawBorder(x, y, width(), height(), 0xFF202830);

			int gridX = x + PADDING;
			int gridY = y + TITLE_BAR_HEIGHT + PADDING;
			for (int slot = 0; slot < stacks.size(); slot++) {
				int slotX = gridX + (slot % GRID_COLUMNS) * SLOT_SIZE;
				int slotY = gridY + (slot / GRID_COLUMNS) * SLOT_SIZE;
				context.fill(slotX, slotY, slotX + 18, slotY + 18, 0xFF8B8B8B);
				context.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFFC6C6C6);
				if (mouseX >= slotX && mouseX < slotX + 18 && mouseY >= slotY && mouseY < slotY + 18) {
					context.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x66FFFFFF);
				}
				ItemStack stack = stacks.get(slot);
				if (!stack.isEmpty()) {
					context.drawItem(stack, slotX + 1, slotY + 1);
					context.drawItemInSlot(client.textRenderer, stack, slotX + 1, slotY + 1);
				}
			}
			for (int slot = 0; slot < stacks.size() - 1; slot++) {
				if (isNestedPairStart(stacks, slot)) {
					int slotX = gridX + (slot % GRID_COLUMNS) * SLOT_SIZE;
					int slotY = gridY + (slot / GRID_COLUMNS) * SLOT_SIZE;
					drawDoubleChestMarker(context, slotX, slotY);
				}
			}
		}

		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width() && mouseY >= y && mouseY < y + height();
		}

		private boolean titleContains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + WINDOW_WIDTH && mouseY >= y && mouseY < y + TITLE_BAR_HEIGHT;
		}

		private boolean closeContains(double mouseX, double mouseY) {
			return mouseX >= x + WINDOW_WIDTH - 16 && mouseX < x + WINDOW_WIDTH && mouseY >= y && mouseY < y + TITLE_BAR_HEIGHT;
		}

		private int slotAt(double mouseX, double mouseY) {
			int gridX = x + PADDING;
			int gridY = y + TITLE_BAR_HEIGHT + PADDING;
			if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + GRID_COLUMNS * SLOT_SIZE || mouseY >= gridY + rows() * SLOT_SIZE) {
				return -1;
			}
			int column = ((int) mouseX - gridX) / SLOT_SIZE;
			int row = ((int) mouseY - gridY) / SLOT_SIZE;
			return row * GRID_COLUMNS + column;
		}

		private int rows() {
			return Math.max(1, stacks.size() / GRID_COLUMNS);
		}

		private int width() {
			return WINDOW_WIDTH;
		}

		private int height() {
			return TITLE_BAR_HEIGHT + PADDING + rows() * SLOT_SIZE + PADDING;
		}

		private void clampToScreen() {
			MinecraftClient client = MinecraftClient.getInstance();
			x = clamp(x, 0, Math.max(0, client.getWindow().getScaledWidth() - width()));
			y = clamp(y, 0, Math.max(0, client.getWindow().getScaledHeight() - height()));
		}
	}

	private static final class RenameDialog {
		private final Screen screen;
		private final List<Integer> path;
		private final TextFieldWidget nameField;
		private int x;
		private int y;

		private RenameDialog(Screen screen, List<Integer> path, ItemStack stack) {
			MinecraftClient client = MinecraftClient.getInstance();
			this.screen = screen;
			this.path = List.copyOf(path);
			this.nameField = new TextFieldWidget(client.textRenderer, 0, 0, RENAME_WIDTH - 20, 20, Text.literal("名称"));
			this.nameField.setMaxLength(NestedChestMod.MAX_CHEST_NAME_LENGTH);
			this.nameField.setText(stack.contains(DataComponentTypes.CUSTOM_NAME) ? stack.getName().getString() : "");
			this.nameField.setPlaceholder(stack.getName());
			this.nameField.setFocused(true);
			layout();
		}

		private void layout() {
			MinecraftClient client = MinecraftClient.getInstance();
			x = clamp((client.getWindow().getScaledWidth() - RENAME_WIDTH) / 2, 0, Math.max(0, client.getWindow().getScaledWidth() - RENAME_WIDTH));
			y = clamp((client.getWindow().getScaledHeight() - RENAME_HEIGHT) / 2, 0, Math.max(0, client.getWindow().getScaledHeight() - RENAME_HEIGHT));
			nameField.setDimensionsAndPosition(RENAME_WIDTH - 20, 20, x + 10, y + 28);
		}

		private void render(DrawContext context, int mouseX, int mouseY) {
			layout();
			MinecraftClient client = MinecraftClient.getInstance();
			context.fill(0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0x55000000);
			context.fill(x + 2, y + 3, x + RENAME_WIDTH + 2, y + RENAME_HEIGHT + 3, 0x66000000);
			context.fill(x, y, x + RENAME_WIDTH, y + RENAME_HEIGHT, 0xFFF7F7F7);
			context.fill(x, y, x + RENAME_WIDTH, y + TITLE_BAR_HEIGHT, 0xFF3A4D5F);
			context.drawText(client.textRenderer, Text.literal("重命名箱子"), x + 8, y + 5, 0xFFFFFFFF, false);
			context.drawBorder(x, y, RENAME_WIDTH, RENAME_HEIGHT, 0xFF202830);
			nameField.render(context, mouseX, mouseY, 0.0F);
			drawButton(context, mouseX, mouseY, cancelX(), buttonY(), Text.literal("取消"));
			drawButton(context, mouseX, mouseY, doneX(), buttonY(), Text.literal("确定"));
		}

		private boolean mouseClicked(double mouseX, double mouseY, int button) {
			layout();
			if (button != 0) {
				return true;
			}
			if (buttonContains(mouseX, mouseY, doneX(), buttonY())) {
				closeRenameDialog(true);
				return true;
			}
			if (buttonContains(mouseX, mouseY, cancelX(), buttonY())) {
				closeRenameDialog(false);
				return true;
			}
			nameField.mouseClicked(mouseX, mouseY, button);
			nameField.setFocused(true);
			screen.setFocused(nameField);
			return true;
		}

		private boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (keyCode == 257 || keyCode == 335) {
				closeRenameDialog(true);
				return true;
			}
			if (keyCode == 256) {
				closeRenameDialog(false);
				return true;
			}
			nameField.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}

		private int cancelX() {
			return x + 10;
		}

		private int doneX() {
			return x + RENAME_WIDTH - RENAME_BUTTON_WIDTH - 10;
		}

		private int buttonY() {
			return y + RENAME_HEIGHT - RENAME_BUTTON_HEIGHT - 10;
		}

		private void drawButton(DrawContext context, int mouseX, int mouseY, int buttonX, int buttonY, Text label) {
			MinecraftClient client = MinecraftClient.getInstance();
			boolean hovered = buttonContains(mouseX, mouseY, buttonX, buttonY);
			int color = hovered ? 0xFF5A6E80 : 0xFF465A6C;
			context.fill(buttonX, buttonY, buttonX + RENAME_BUTTON_WIDTH, buttonY + RENAME_BUTTON_HEIGHT, color);
			context.drawBorder(buttonX, buttonY, RENAME_BUTTON_WIDTH, RENAME_BUTTON_HEIGHT, 0xFF202830);
			context.drawCenteredTextWithShadow(client.textRenderer, label, buttonX + RENAME_BUTTON_WIDTH / 2, buttonY + 6, 0xFFFFFFFF);
		}

		private boolean buttonContains(double mouseX, double mouseY, int buttonX, int buttonY) {
			return mouseX >= buttonX && mouseX < buttonX + RENAME_BUTTON_WIDTH && mouseY >= buttonY && mouseY < buttonY + RENAME_BUTTON_HEIGHT;
		}
	}

	private record ClickMemory(NestedWindow window, int slot, int button, long time) {
	}

	private record DragPickup(NestedWindow window, boolean startedInNested, int button) {
	}

	private static final class QuickCraftDrag {
		private final NestedWindow window;
		private final int button;
		private final List<Integer> visitedSlots = new ArrayList<>();
		private int startSlot = -999;

		private QuickCraftDrag(NestedWindow window, int button) {
			this.window = window;
			this.button = button;
		}
	}

	private record ContainerView(Text title, DefaultedList<ItemStack> stacks) {
	}
}
