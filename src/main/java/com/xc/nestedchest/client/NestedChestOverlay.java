package com.xc.nestedchest.client;

import com.xc.nestedchest.NestedChestMod;
import com.xc.nestedchest.mixin.ScreenAccessor;
import com.xc.nestedchest.network.NestedChestClickPayload;
import com.xc.nestedchest.network.NestedChestOpenPayload;
import com.xc.nestedchest.network.NestedChestRenamePayload;
import com.xc.nestedchest.network.NestedChestSortPayload;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class NestedChestOverlay {
	private static final int SLOT_SIZE = 18;
	private static final int GRID_COLUMNS = 9;
	private static final int TITLE_BAR_HEIGHT = 16;
	private static final int NAV_BAR_HEIGHT = 22;
	private static final int PADDING = 7;
	private static final int GRID_WIDTH = GRID_COLUMNS * SLOT_SIZE;
	private static final int WINDOW_WIDTH = GRID_WIDTH + PADDING * 2;
	private static final int PANEL_RADIUS = 4;
	private static final int BUTTON_RADIUS = 3;
	private static final int BACK_BUTTON_SIZE = 16;
	private static final int EXTRA_BUTTON_WIDTH = 20;
	private static final int TOOL_BUTTON_WIDTH = 22;
	private static final int PATH_FIELD_HEIGHT = 18;
	private static final int WINDOW_CLOSE_SIZE = 16;
	private static final int GLOBAL_CLOSE_SIZE = 12;
	private static final int RENAME_WIDTH = 190;
	private static final int RENAME_HEIGHT = 86;
	private static final int RENAME_BUTTON_WIDTH = 78;
	private static final int RENAME_BUTTON_HEIGHT = 20;
	private static final String ROOT_PATH_NAME = "杩炴帴绠卞瓙";
	// 箱中箱 UI 是叠在原版容器上的浮层，Z 值要压过原版槽位和提示。
	private static final float OVERLAY_Z = 500.0F;
	private static final float WINDOW_LAYER_Z = 300.0F;
	private static final float MAX_WINDOW_Z = 6000.0F;
	private static final float DETAIL_LAYER_Z = 7500.0F;
	private static final float CURSOR_LAYER_Z = 9000.0F;
	private static final List<NestedWindow> WINDOWS = new ArrayList<>();
	// 路径显示用箱子名字，协议仍用槽位编号；缓存让手写路径和重命名后的导航能对上。
	private static final Map<List<Integer>, List<String>> PATH_NAME_CACHE = new HashMap<>();
	private static final Map<List<Integer>, DefaultedList<ItemStack>> PATH_STACK_CACHE = new HashMap<>();

	// 这些状态分别处理拖窗口、弹窗焦点、双击记忆、快速合成拖拽和普通按住拖放。
	private static NestedWindow draggedWindow;
	private static RenameDialog renameDialog;
	private static TextFieldWidget pathField;
	private static NestedWindow pathFieldWindow;
	private static ClickMemory lastClick;
	private static QuickCraftDrag quickCraftDrag;
	private static DragPickup dragPickup;
	private static int dragOffsetX;
	private static int dragOffsetY;

	private NestedChestOverlay() {
	}

	public static void reset() {
		WINDOWS.clear();
		PATH_NAME_CACHE.clear();
		PATH_STACK_CACHE.clear();
		draggedWindow = null;
		lastClick = null;
		quickCraftDrag = null;
		dragPickup = null;
		closePathField();
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
		if (!WINDOWS.isEmpty()) {
			renderGlobalClose(context, mouseX, mouseY);
			flushLayer(context);
		}

		for (int layer = 0; layer < WINDOWS.size(); layer++) {
			NestedWindow window = WINDOWS.get(layer);
			context.getMatrices().push();
			// 父窗口先画，子窗口后画，保证最深层目录永远在视觉最上面。
			context.getMatrices().translate(0.0F, 0.0F, windowLayerZ(layer));
			RenderSystem.disableDepthTest();
			window.clampToScreen();
			if (window == getRaisedWindow()) {
				bindPathField(window);
			}
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
		// 鼠标指针上拿着的物品最后渲染，避免被父级或子级窗口盖住。
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
		// 新点击不继承旧的窗口拖动状态，避免在槽位里轻微拖动时把浮窗拽走。
		draggedWindow = null;
		if (renameDialog != null) {
			return renameDialog.mouseClicked(mouseX, mouseY, button);
		}
		if (pathField != null && pathFieldWindow != null && pathFieldWindow.pathFieldContains(mouseX, mouseY)) {
			pathField.mouseClicked(mouseX, mouseY, button);
			pathField.setFocused(true);
			Screen screen = MinecraftClient.getInstance().currentScreen;
			if (screen != null) {
				screen.setFocused(pathField);
			}
			return true;
		}

		if (button != 0 && button != 1) {
			return false;
		}
		if (pathField != null && pathField.isFocused()) {
			pathField.setFocused(false);
			updatePathField(pathFieldWindow);
		}
		dragPickup = null;
		if (button == 0 && globalCloseContains(mouseX, mouseY)) {
			WINDOWS.clear();
			closePathField();
			return true;
		}

		sortForAncestorLayering();
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			NestedWindow window = WINDOWS.get(i);
			if (!window.contains(mouseX, mouseY)) {
				continue;
			}

			raise(window);
			if (button == 0 && window.closeContains(mouseX, mouseY)) {
				removeWindow(window);
				return true;
			}
			if (button == 0 && window.backContains(mouseX, mouseY)) {
				navigateBack(window);
				return true;
			}
			if (button == 0 && window.extraContains(mouseX, mouseY)) {
				duplicateWindow(window);
				return true;
			}
			if (button == 0 && window.sortContains(mouseX, mouseY)) {
				ClientPlayNetworking.send(new NestedChestSortPayload(window.path, window.sortMode.ordinal(), window.sortAscending));
				window.cycleSortMode();
				return true;
			}
			if (button == 0 && window.sortDirectionContains(mouseX, mouseY)) {
				window.sortAscending = !window.sortAscending;
				ClientPlayNetworking.send(new NestedChestSortPayload(window.path, window.sortMode.ordinal(), window.sortAscending));
				return true;
			}
			if (button == 0 && window.backgroundContains(mouseX, mouseY)) {
				NestedChestClientConfig.cycleBackgroundMode();
				return true;
			}

			if (button == 0 && window.titleContains(mouseX, mouseY)) {
				draggedWindow = window;
				quickCraftDrag = null;
				dragOffsetX = (int) mouseX - window.x;
				dragOffsetY = (int) mouseY - window.y;
				return true;
			}

			int nestedSlot = window.slotAt(mouseX, mouseY);
			if (nestedSlot >= 0) {
				ItemStack stack = window.stacks.get(nestedSlot);
				if (isDoubleClick(window, nestedSlot, button)) {
					// 双击要先于快速合成判断，否则拿着物品时会被误认为拖拽分配。
					sendMouseClick(window, nestedSlot, button);
					dragPickup = new DragPickup(window, true, nestedSlot, button);
					return true;
				}
				if (beginQuickCraftIfNeeded(handler, window, nestedSlot, button)) {
					return true;
				}
				if (button == 1 && NestedChestMod.canOpenNestedWindow(stack)) {
					if (Screen.hasShiftDown()) {
						openRenameDialog(window.childPath(nestedSlot), stack);
					} else {
						List<Integer> childPath = normalizeChildPath(window, nestedSlot);
						navigateWindow(window, childPath, containerViewFromSlots(window.stacks, childPath.getLast()));
					}
					return true;
				}

				sendMouseClick(window, nestedSlot, button);
				dragPickup = new DragPickup(window, true, nestedSlot, button);
				return true;
			}

			return true;
		}

		NestedWindow activeWindow = getRaisedWindow();
		Slot playerSlot = getPlayerSlotAt(handler, mouseX, mouseY);
		if (activeWindow != null && playerSlot != null) {
			int playerSlotOffset = playerSlot.id - containerSlotCount(handler);
			// 服务端临时嵌套 Handler 的槽位顺序是：当前箱中箱槽位 + 玩家背包槽位。
			int nestedPlayerSlot = activeWindow.stacks.size() + playerSlotOffset;
			if (isDoubleClick(activeWindow, nestedPlayerSlot, button)) {
				sendMouseClick(activeWindow, nestedPlayerSlot, button);
				dragPickup = new DragPickup(activeWindow, false, nestedPlayerSlot, button);
				return true;
			}
			if (beginQuickCraftIfNeeded(handler, activeWindow, nestedPlayerSlot, button)) {
				return true;
			}
			sendMouseClick(activeWindow, nestedPlayerSlot, button);
			dragPickup = new DragPickup(activeWindow, false, nestedPlayerSlot, button);
			return true;
		}

		Slot parentSlot = getParentSlotAt(handler, mouseX, mouseY);
		if (button == 1 && parentSlot != null && NestedChestMod.canOpenNestedWindow(parentSlot.getStack())) {
			if (Screen.hasShiftDown()) {
				openRenameDialog(List.of(parentSlot.id), parentSlot.getStack());
			} else {
				List<Integer> path = normalizeRootPath(handler, parentSlot.id);
				openMain(path, containerViewFromSlots(getRootStacks(handler), path.getLast()));
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
		if (draggedWindow != null && button == 0) {
			draggedWindow = null;
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
					NestedSlotTarget nestedTarget = nestedSlotTargetAt(mouseX, mouseY);
					if (nestedTarget != null) {
						// 按下和释放在同一嵌套槽位时不补第二次点击，防止释放阶段抵消普通点击。
						if (nestedTarget.window != dragPickup.window || nestedTarget.slot != dragPickup.startSlot) {
							sendClick(nestedTarget.window, nestedTarget.slot, button, SlotActionType.PICKUP);
						}
						dragPickup = null;
						return true;
					}
					Slot playerSlot = getPlayerSlotAt(client.player.currentScreenHandler, mouseX, mouseY);
					if (playerSlot != null && !isPointInsideNestedWindow(mouseX, mouseY)) {
						int playerSlotOffset = playerSlot.id - containerSlotCount(client.player.currentScreenHandler);
						sendClick(dragPickup.window, dragPickup.window.stacks.size() + playerSlotOffset, button, SlotActionType.PICKUP);
						dragPickup = null;
						return true;
					}
				} else {
					NestedSlotTarget nestedTarget = nestedSlotTargetAt(mouseX, mouseY);
					if (nestedTarget != null) {
						sendClick(nestedTarget.window, nestedTarget.slot, button, SlotActionType.PICKUP);
						dragPickup = null;
						return true;
					}
				}
			}
			dragPickup = null;
			return true;
		}
		if (button >= 0 && button <= 1 && isPointInsideNestedWindow(mouseX, mouseY)) {
			// 鼠标释放落在浮窗内部时吞掉事件，避免穿透到底层玩家背包。
			return true;
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
		if (draggedWindow == null || button != 0) {
			return false;
		}

		draggedWindow.x = clamp((int) mouseX - dragOffsetX, 0, Math.max(0, MinecraftClient.getInstance().getWindow().getScaledWidth() - draggedWindow.width()));
		draggedWindow.y = clamp((int) mouseY - dragOffsetY, 0, Math.max(0, MinecraftClient.getInstance().getWindow().getScaledHeight() - draggedWindow.height()));
		return true;
	}

	public static boolean keyPressed(ScreenHandler handler, int keyCode, int scanCode, int modifiers) {
		if (pathField != null && pathField.isFocused()) {
			if (keyCode == 257 || keyCode == 335) {
				applyPathField(handler);
				return true;
			}
			if (keyCode == 256) {
				pathField.setFocused(false);
				updatePathField(pathFieldWindow);
				return true;
			}
			pathField.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
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

	public static boolean charTyped(char chr, int modifiers) {
		if (pathField != null && pathField.isFocused()) {
			pathField.charTyped(chr, modifiers);
			return true;
		}
		if (renameDialog != null) {
			return renameDialog.charTyped(chr, modifiers);
		}
		return false;
	}

	public static void sync(List<Integer> path, DefaultedList<ItemStack> stacks) {
		ContainerView view = getContainerViewForSyncedPath(path, stacks);
		for (NestedWindow window : WINDOWS) {
			if (window.path.equals(path)) {
				window.setPath(path);
				if (!window.hasPendingDescendantOf(path)) {
					window.clearPendingPath();
				}
				window.setSource(view);
				rememberPath(path, view);
				updatePathField(window);
			}
		}
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			NestedWindow window = WINDOWS.get(i);
			if (window.pendingPathMatches(path)) {
				// 客户端会先开占位窗口，等服务端同步回来后再用真实路径和物品覆盖。
				window.setPath(path);
				window.clearPendingPath();
				window.setSource(view);
				rememberPath(path, view);
				updatePathField(window);
				return;
			}
		}
	}

	private static void openMain(List<Integer> path, ContainerView view) {
		if (view == null) {
			return;
		}
		NestedWindow main = getMainWindow();
		if (main != null) {
			navigateWindow(main, path, view);
			raise(main);
			return;
		}
		openWindow(path, view, true, 0);
	}

	private static void openWindow(List<Integer> path, ContainerView view, boolean main, int offset) {
		openWindow(path, view, main, offset, false);
	}

	private static void openWindow(List<Integer> path, ContainerView view, boolean main, int offset, boolean forceNew) {
		if (view == null) {
			return;
		}
		if (!forceNew) {
			for (NestedWindow window : WINDOWS) {
				if (window.path.equals(path) && window.main == main) {
					raise(window);
					window.setPendingPath(path);
					requestServerSync(path);
					return;
				}
			}
		}

		MinecraftClient client = MinecraftClient.getInstance();
		int x = clamp((client.getWindow().getScaledWidth() - WINDOW_WIDTH) / 2 + offset, 0, Math.max(0, client.getWindow().getScaledWidth() - WINDOW_WIDTH));
		NestedWindow window = new NestedWindow(path, x, 24 + offset, view, main);
		window.setPendingPath(path);
		rememberPath(path, view);
		window.clampToScreen();
		WINDOWS.add(window);
		raise(window);
		requestServerSync(path);
	}

	private static void navigateWindow(NestedWindow window, List<Integer> path, ContainerView view) {
		if (view == null) {
			return;
		}
		window.setPath(path);
		window.setPendingPath(path);
		window.setSource(view);
		rememberPath(path, view);
		updatePathField(window);
		requestServerSync(path);
	}

	private static void navigateBack(NestedWindow window) {
		if (window.path.size() <= 1) {
			return;
		}
		List<Integer> parentPath = List.copyOf(window.path.subList(0, window.path.size() - 1));
		ContainerView view = containerViewFromPath(parentPath);
		if (view == null) {
			view = placeholderView(parentPath);
		}
		navigateWindow(window, parentPath, view);
	}

	private static void duplicateWindow(NestedWindow window) {
		int offset = WINDOWS.size() * 12;
		openWindow(window.path, new ContainerView(window.title, window.stacks), false, offset, true);
	}

	private static NestedWindow getMainWindow() {
		for (NestedWindow window : WINDOWS) {
			if (window.main) {
				return window;
			}
		}
		return null;
	}

	private static void removeWindow(NestedWindow window) {
		WINDOWS.remove(window);
		if (pathFieldWindow == window) {
			pathFieldWindow = null;
			if (WINDOWS.isEmpty()) {
				closePathField();
			} else {
				bindPathField(getRaisedWindow());
			}
		}
	}

	private static void renderGlobalClose(DrawContext context, int mouseX, int mouseY) {
		MinecraftClient client = MinecraftClient.getInstance();
		int x = client.getWindow().getScaledWidth() - GLOBAL_CLOSE_SIZE - 4;
		int y = 4;
		drawSmallButton(context, mouseX, mouseY, x, y, GLOBAL_CLOSE_SIZE, GLOBAL_CLOSE_SIZE, Text.literal("x"), globalCloseContains(mouseX, mouseY));
	}

	private static boolean globalCloseContains(double mouseX, double mouseY) {
		if (WINDOWS.isEmpty()) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		int x = client.getWindow().getScaledWidth() - GLOBAL_CLOSE_SIZE - 4;
		int y = 4;
		return mouseX >= x && mouseX < x + GLOBAL_CLOSE_SIZE && mouseY >= y && mouseY < y + GLOBAL_CLOSE_SIZE;
	}

	private static void bindPathField(NestedWindow window) {
		if (window == null) {
			closePathField();
			return;
		}
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (screen == null) {
			return;
		}
		if (pathField == null) {
			// 路径输入框挂到当前 Screen 的 selectable children，才能拿到键盘焦点。
			pathField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 1, PATH_FIELD_HEIGHT, Text.literal("璺緞"));
			pathField.setMaxLength(160);
			((ScreenAccessor) screen).nestedchest$addSelectableChild(pathField);
		}
		if (pathFieldWindow != window) {
			pathFieldWindow = window;
			updatePathField(window);
			pathField.setFocused(false);
		}
		pathField.setDimensionsAndPosition(window.pathFieldWidth(), PATH_FIELD_HEIGHT, window.pathFieldX(), window.pathFieldY());
	}

	private static void closePathField() {
		if (pathField == null) {
			pathFieldWindow = null;
			return;
		}
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (screen != null) {
			((ScreenAccessor) screen).nestedchest$remove(pathField);
		}
		pathField = null;
		pathFieldWindow = null;
	}

	private static void updatePathField(NestedWindow window) {
		if (pathField != null && window != null && pathFieldWindow == window && !pathField.isFocused()) {
			pathField.setText(formatPath(window.path, window.title));
		}
	}

	private static void applyPathField(ScreenHandler handler) {
		if (pathField == null || pathFieldWindow == null) {
			return;
		}
		List<Integer> path = parsePath(handler, pathField.getText());
		if (path.isEmpty()) {
			updatePathField(pathFieldWindow);
			pathField.setFocused(false);
			return;
		}
		ContainerView view = containerViewFromPath(path);
		if (view == null) {
			view = placeholderView(path);
		}
		navigateWindow(pathFieldWindow, path, view);
		pathField.setFocused(false);
	}

	private static String formatPath(List<Integer> path, Text fallbackTitle) {
		List<String> names = PATH_NAME_CACHE.get(path);
		if (names == null) {
			names = buildNamePathFromVisibleState(path, fallbackTitle);
		}
		return String.join("/", names);
	}

	private static List<String> buildNamePathFromVisibleState(List<Integer> path, Text fallbackTitle) {
		List<String> names = new ArrayList<>();
		names.add(rootPathName());
		for (int depth = 0; depth < path.size(); depth++) {
			List<Integer> currentPath = List.copyOf(path.subList(0, depth + 1));
			List<String> cached = PATH_NAME_CACHE.get(currentPath);
			if (cached != null && cached.size() > depth + 1) {
				names = new ArrayList<>(cached);
				continue;
			}
			Text name = depth == path.size() - 1 && fallbackTitle != null ? fallbackTitle : stackNameForPathPrefix(currentPath);
			names.add(sanitizePathSegment(name == null ? Text.literal(String.valueOf(path.get(depth) + 1)) : name));
		}
		return names;
	}

	private static List<Integer> parsePath(ScreenHandler handler, String rawPath) {
		String cleaned = rawPath.trim().replace('\\', '/');
		if (cleaned.isEmpty()) {
			return List.of();
		}
		String root = rootPathName();
		if (cleaned.equals(root)) {
			return List.of();
		}
		if (cleaned.startsWith(root + "/")) {
			cleaned = cleaned.substring(root.length() + 1);
		} else if (cleaned.startsWith(ROOT_PATH_NAME + "/")) {
			cleaned = cleaned.substring(ROOT_PATH_NAME.length() + 1);
		}
		while (cleaned.startsWith("/")) {
			cleaned = cleaned.substring(1);
		}
		if (cleaned.isBlank()) {
			return List.of();
		}
		String[] parts = cleaned.split("/+");
		List<Integer> path = new ArrayList<>();
		DefaultedList<ItemStack> currentStacks = getRootStacks(handler);
		for (String part : parts) {
			String segment = sanitizePathSegment(Text.literal(part.trim()));
			if (segment.isBlank()) {
				continue;
			}
			int slot = findNamedSlot(currentStacks, segment);
			if (slot < 0) {
				return List.of();
			}
			path.add(slot);
			DefaultedList<ItemStack> cachedStacks = PATH_STACK_CACHE.get(List.copyOf(path));
			if (cachedStacks != null) {
				// 手写路径只能继续解析客户端已经见过的目录；缺失时交给服务端同步补齐。
				currentStacks = cachedStacks;
				continue;
			}
			ContainerView view = containerViewFromSlots(currentStacks, slot);
			if (view == null && path.size() < parts.length) {
				return List.of();
			} else if (view != null) {
				currentStacks = view.stacks();
			}
		}
		return List.copyOf(path);
	}

	private static ContainerView placeholderView(List<Integer> path) {
		return new ContainerView(Text.literal(lastPathSegment(path)), DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY));
	}

	private static String rootPathName() {
		Screen screen = MinecraftClient.getInstance().currentScreen;
		if (screen != null) {
			String title = sanitizePathSegment(screen.getTitle());
			if (!title.isBlank()) {
				return title;
			}
		}
		return ROOT_PATH_NAME;
	}

	private static String sanitizePathSegment(Text text) {
		if (text == null) {
			return "";
		}
		String value = text.getString().trim().replace('\\', '/').replace('/', '_');
		value = value.replaceFirst("\\s+[xX脳]\\s*\\{?\\d+\\}?$", "").trim();
		return value.isBlank() ? ROOT_PATH_NAME : value;
	}

	private static Text stackNameForPathPrefix(List<Integer> path) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || path.isEmpty()) {
			return null;
		}
		ItemStack stack = getStackForPath(client.player.currentScreenHandler, path);
		return stack.isEmpty() ? null : stack.getName();
	}

	private static int findNamedSlot(DefaultedList<ItemStack> stacks, String segment) {
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (NestedChestMod.canOpenNestedWindow(stack) && sanitizePathSegment(stack.getName()).equals(segment)) {
				return normalizeSlotForPair(stacks, slot);
			}
		}
		int numericSlot = parseNumericSlot(segment);
		if (numericSlot < 0 || numericSlot >= stacks.size()) {
			return -1;
		}
		return NestedChestMod.canOpenNestedWindow(stacks.get(numericSlot)) ? normalizeSlotForPair(stacks, numericSlot) : numericSlot;
	}

	private static int parseNumericSlot(String segment) {
		try {
			int slot = Integer.parseInt(segment) - 1;
			return slot >= 0 && slot < NestedChestMod.MAX_NESTED_CHEST_SIZE ? slot : -1;
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private static int normalizeSlotForPair(DefaultedList<ItemStack> stacks, int slot) {
		int pairStart = NestedChestMod.getNestedChestPairStart(stacks, slot);
		return pairStart >= 0 ? pairStart : slot;
	}

	private static void rememberPath(List<Integer> path, ContainerView view) {
		PATH_STACK_CACHE.put(List.copyOf(path), copyStacks(view.stacks()));
		List<String> names = new ArrayList<>();
		if (path.size() > 1) {
			List<String> parentNames = PATH_NAME_CACHE.get(List.copyOf(path.subList(0, path.size() - 1)));
			if (parentNames != null) {
				names.addAll(parentNames);
			}
		}
		if (names.isEmpty()) {
			names.add(rootPathName());
		}
		if (names.size() > path.size()) {
			names = new ArrayList<>(names.subList(0, path.size()));
		}
		while (names.size() < path.size()) {
			int depth = names.size() - 1;
			List<Integer> prefix = List.copyOf(path.subList(0, depth + 1));
			Text name = stackNameForPathPrefix(prefix);
			names.add(sanitizePathSegment(name == null ? Text.literal(String.valueOf(path.get(depth) + 1)) : name));
		}
		names.add(sanitizePathSegment(view.title()));
		PATH_NAME_CACHE.put(List.copyOf(path), List.copyOf(names));
	}

	private static String lastPathSegment(List<Integer> path) {
		List<String> names = PATH_NAME_CACHE.get(path);
		if (names == null) {
			names = buildNamePathFromVisibleState(path, null);
		}
		if (!names.isEmpty()) {
			return names.getLast();
		}
		return path.isEmpty() ? rootPathName() : String.valueOf(path.getLast() + 1);
	}

	private static DefaultedList<ItemStack> copyStacks(DefaultedList<ItemStack> source) {
		DefaultedList<ItemStack> copy = DefaultedList.ofSize(source.size(), ItemStack.EMPTY);
		for (int i = 0; i < source.size(); i++) {
			copy.set(i, source.get(i).copy());
		}
		return copy;
	}

	private static ContainerView containerViewFromPath(List<Integer> path) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || path.isEmpty()) {
			return null;
		}
		if (path.size() == 1) {
			return containerViewFromSlots(getRootStacks(client.player.currentScreenHandler), path.getFirst());
		}
		NestedWindow parent = findWindow(List.copyOf(path.subList(0, path.size() - 1)));
		if (parent == null) {
			return null;
		}
		return containerViewFromSlots(parent.stacks, path.getLast());
	}

	private static ContainerView getContainerViewForSyncedPath(List<Integer> path, DefaultedList<ItemStack> stacks) {
		Text title = Text.literal(lastPathSegment(path));
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			ItemStack stack = getStackForPath(client.player.currentScreenHandler, path);
			if (!stack.isEmpty()) {
				title = stack.getName();
			}
		}
		return new ContainerView(title, stacks);
	}

	private static void requestServerSync(List<Integer> path) {
		ClientPlayNetworking.send(new NestedChestOpenPayload(path));
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
		// 祖先窗口排在前面、子路径排在后面，渲染和命中检测才会保持子目录在最上层。
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

	private static void drawPanel(DrawContext context, int x, int y, int width, int height) {
		fillRoundedRect(context, x + 2, y + 3, width, height, PANEL_RADIUS, 0x66000000);
		fillRoundedRect(context, x, y, width, height, PANEL_RADIUS, 0xFFC6C6C6);
		drawRoundedBorder(context, x, y, width, height, PANEL_RADIUS, 0xFFFFFFFF, 0xFF595959);
		drawRoundedBorder(context, x + 1, y + 1, width - 2, height - 2, PANEL_RADIUS - 1, 0xFFE7E7E7, 0xFF8A8A8A);
	}

	private static void drawSlot(DrawContext context, int x, int y) {
		drawSlot(context, x, y, false);
	}

	private static void drawSlot(DrawContext context, int x, int y, boolean translucentCenter) {
		if (translucentCenter) {
			context.drawHorizontalLine(x, x + SLOT_SIZE - 1, y, 0xB06A6A6A);
			context.drawVerticalLine(x, y, y + SLOT_SIZE - 1, 0xB06A6A6A);
			context.drawHorizontalLine(x + 1, x + SLOT_SIZE - 2, y + 1, 0xAA3F3F3F);
			context.drawVerticalLine(x + 1, y + 1, y + SLOT_SIZE - 2, 0xAA3F3F3F);
			context.drawHorizontalLine(x + 1, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, 0xCCE6E6E6);
			context.drawVerticalLine(x + SLOT_SIZE - 2, y + 1, y + SLOT_SIZE - 2, 0xCCE6E6E6);
			return;
		}
		context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFF6A6A6A);
		context.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0xFF8A8A8A);
		context.drawHorizontalLine(x + 1, x + SLOT_SIZE - 2, y + 1, 0xFF3F3F3F);
		context.drawVerticalLine(x + 1, y + 1, y + SLOT_SIZE - 2, 0xFF3F3F3F);
		context.drawHorizontalLine(x + 1, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, 0xFFE6E6E6);
		context.drawVerticalLine(x + SLOT_SIZE - 2, y + 1, y + SLOT_SIZE - 2, 0xFFE6E6E6);
		context.fill(x + 2, y + 2, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, 0xFFB7B7B7);
	}

	private static boolean drawGridBackgroundImage(DrawContext context, int x, int y, int width, int height) {
		NestedChestClientConfig.LoadedBackground background = NestedChestClientConfig.background();
		if (background == null || background.width() <= 0 || background.height() <= 0) {
			return false;
		}

		int drawX = x;
		int drawY = y;
		int drawWidth = width;
		int drawHeight = height;
		float u = 0.0F;
		float v = 0.0F;
		int regionWidth = background.width();
		int regionHeight = background.height();

		NestedChestClientConfig.BackgroundMode mode = NestedChestClientConfig.backgroundMode();
		if (mode == NestedChestClientConfig.BackgroundMode.FIT) {
			// 自适应保留图片比例，剩余区域继续显示原版槽位底色。
			float scale = Math.min(width / (float) background.width(), height / (float) background.height());
			drawWidth = Math.max(1, Math.round(background.width() * scale));
			drawHeight = Math.max(1, Math.round(background.height() * scale));
			drawX = x + (width - drawWidth) / 2;
			drawY = y + (height - drawHeight) / 2;
		} else if (mode == NestedChestClientConfig.BackgroundMode.FILL) {
			// 填充会裁切图片中心区域，保证储物格背景铺满。
			float scale = Math.max(width / (float) background.width(), height / (float) background.height());
			regionWidth = Math.max(1, Math.round(width / scale));
			regionHeight = Math.max(1, Math.round(height / scale));
			u = (background.width() - regionWidth) / 2.0F;
			v = (background.height() - regionHeight) / 2.0F;
		}

		context.draw();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		context.enableScissor(x, y, x + width, y + height);
		context.drawTexture(background.texture(), drawX, drawY, drawWidth, drawHeight, u, v, regionWidth, regionHeight, background.width(), background.height());
		context.disableScissor();
		context.draw();
		RenderSystem.disableBlend();
		return true;
	}

	private static void drawSmallButton(DrawContext context, int mouseX, int mouseY, int x, int y, int width, int height, Text label, boolean hovered) {
		MinecraftClient client = MinecraftClient.getInstance();
		int base = hovered ? 0xFFE4E4E4 : 0xFFC9C9C9;
		fillRoundedRect(context, x + 1, y + 2, width, height, BUTTON_RADIUS, 0x55000000);
		fillRoundedRect(context, x, y, width, height, BUTTON_RADIUS, base);
		drawRoundedBorder(context, x, y, width, height, BUTTON_RADIUS, hovered ? 0xFFFFFFFF : 0xFFF2F2F2, 0xFF505050);
		context.drawHorizontalLine(x + 2, x + width - 3, y + 1, hovered ? 0xFFFFFFFF : 0xFFECECEC);
		context.drawHorizontalLine(x + 2, x + width - 3, y + height - 2, 0xFF8A8A8A);
		int textWidth = client.textRenderer.getWidth(label);
		context.drawText(client.textRenderer, label, x + (width - textWidth) / 2, y + (height - 8) / 2, 0xFF404040, false);
	}

	private static void fillRoundedRect(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		if (radius <= 0) {
			context.fill(x, y, x + width, y + height, color);
			return;
		}
		context.fill(x + radius, y, x + width - radius, y + height, color);
		context.fill(x, y + radius, x + width, y + height - radius, color);
		context.fill(x + 1, y + 1, x + width - 1, y + radius, color);
		context.fill(x + 1, y + height - radius, x + width - 1, y + height - 1, color);
		context.fill(x, y + radius, x + radius, y + height - radius, color);
		context.fill(x + width - radius, y + radius, x + width, y + height - radius, color);
	}

	private static void drawRoundedBorder(DrawContext context, int x, int y, int width, int height, int radius, int lightColor, int darkColor) {
		if (width <= 1 || height <= 1) {
			return;
		}
		context.drawHorizontalLine(x + radius, x + width - radius - 1, y, lightColor);
		context.drawVerticalLine(x, y + radius, y + height - radius - 1, lightColor);
		context.drawHorizontalLine(x + radius, x + width - radius - 1, y + height - 1, darkColor);
		context.drawVerticalLine(x + width - 1, y + radius, y + height - radius - 1, darkColor);
		context.fill(x + 1, y + 1, x + radius + 1, y + 2, lightColor);
		context.fill(x + 1, y + 1, x + 2, y + radius + 1, lightColor);
		context.fill(x + width - radius - 1, y + height - 2, x + width - 1, y + height - 1, darkColor);
		context.fill(x + width - 2, y + height - radius - 1, x + width - 1, y + height - 1, darkColor);
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
			ItemStack stack = getStackForPath(handler, window.path);
			if (stack.isEmpty() && window.path.size() > 1) {
				continue;
			}
			if (!NestedChestMod.canOpenNestedWindow(stack)) {
				iterator.remove();
				if (pathFieldWindow == window) {
					pathFieldWindow = null;
				}
				continue;
			}
			window.setTitle(stack.getName());
		}
		if (WINDOWS.isEmpty()) {
			closePathField();
		} else if (pathFieldWindow == null) {
			bindPathField(getRaisedWindow());
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

	private static NestedSlotTarget nestedSlotTargetAt(double mouseX, double mouseY) {
		// 从最上层窗口往下找命中的槽位，重叠 UI 不会误点到父级窗口。
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			NestedWindow window = WINDOWS.get(i);
			if (!window.contains(mouseX, mouseY)) {
				continue;
			}
			int slot = window.slotAt(mouseX, mouseY);
			return slot >= 0 ? new NestedSlotTarget(window, slot) : null;
		}
		return null;
	}

	private static boolean isPointInsideNestedWindow(double mouseX, double mouseY) {
		for (int i = WINDOWS.size() - 1; i >= 0; i--) {
			if (WINDOWS.get(i).contains(mouseX, mouseY)) {
				return true;
			}
		}
		return false;
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

	private static boolean isDoubleClick(NestedWindow window, int slot, int button) {
		long now = System.currentTimeMillis();
		return button == 0 && lastClick != null && lastClick.window == window && lastClick.slot == slot && lastClick.button == button && now - lastClick.time <= 250L;
	}

	private static void sendMouseClick(NestedWindow window, int slot, int button) {
		long now = System.currentTimeMillis();
		boolean doubleClick = isDoubleClick(window, slot, button);
		if (doubleClick) {
			// 原版双击收集同类物品对应 PICKUP_ALL。
			sendClick(window, slot, button, SlotActionType.PICKUP_ALL);
		} else if (Screen.hasShiftDown()) {
			sendClick(window, slot, button, SlotActionType.QUICK_MOVE);
		} else {
			sendClick(window, slot, button, SlotActionType.PICKUP);
		}
		lastClick = new ClickMemory(window, slot, button, now);
	}

	private static void sendClick(NestedWindow window, int slot, int button, SlotActionType actionType) {
		window.setPendingPath(window.path);
		ClientPlayNetworking.send(new NestedChestClickPayload(window.path, slot, button, actionType));
	}

	private static String backgroundModeIcon() {
		return switch (NestedChestClientConfig.backgroundMode()) {
			case FIT -> "□";
			case STRETCH -> "↔";
			case FILL -> "■";
		};
	}

	private static boolean beginQuickCraftIfNeeded(ScreenHandler handler, NestedWindow window, int slot, int button) {
		if (button != 0 && button != 1) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || handler.getCursorStack().isEmpty()) {
			return false;
		}
		// 鼠标上拿着物品再拖过多个槽位时，走原版 QUICK_CRAFT 三阶段流程。
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

	private static ItemStack getStackForPath(ScreenHandler handler, List<Integer> path) {
		if (path.isEmpty()) {
			return ItemStack.EMPTY;
		}

		if (path.size() == 1) {
			int rootSlot = path.getFirst();
			if (rootSlot < 0 || rootSlot >= containerSlotCount(handler) || rootSlot >= handler.slots.size()) {
				return ItemStack.EMPTY;
			}
			return handler.getSlot(rootSlot).getStack();
		}

		List<Integer> parentPath = List.copyOf(path.subList(0, path.size() - 1));
		NestedWindow parent = findWindow(parentPath);
		int nestedSlot = path.getLast();
		if (parent == null || nestedSlot < 0 || nestedSlot >= parent.stacks.size()) {
			return ItemStack.EMPTY;
		}
		return parent.stacks.get(nestedSlot);
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
		int secondarySlot = NestedChestMod.getNestedChestPairSecondarySlot(stacks, slot);
		if (secondarySlot >= 0) {
			DefaultedList<ItemStack> doubleStacks = DefaultedList.ofSize(NestedChestMod.DOUBLE_NESTED_CHEST_SIZE, ItemStack.EMPTY);
			return new ContainerView(primary.getName(), doubleStacks);
		}
		return new ContainerView(primary.getName(), nestedStacks);
	}

	private static NestedWindow findWindow(List<Integer> path) {
		for (NestedWindow window : WINDOWS) {
			if (window.path.equals(path)) {
				return window;
			}
		}
		return null;
	}

	private static boolean isNestedPairStart(DefaultedList<ItemStack> stacks, int slot) {
		return NestedChestMod.getNestedChestPairSecondarySlot(stacks, slot) >= 0;
	}

	private static boolean hasSameParentAndLastSlotPair(List<Integer> left, List<Integer> right) {
		if (left.size() != right.size() || left.isEmpty()) {
			return false;
		}
		int lastIndex = left.size() - 1;
		if (!left.subList(0, lastIndex).equals(right.subList(0, lastIndex))) {
			return false;
		}
		return Math.abs(left.get(lastIndex) - right.get(lastIndex)) == 1;
	}

	private static boolean pathsMatchForSync(List<Integer> pendingPath, List<Integer> syncedPath) {
		if (pendingPath.isEmpty()) {
			return false;
		}
		if (pendingPath.equals(syncedPath)) {
			return true;
		}
		if (pendingPath.size() == syncedPath.size() && hasSameParentAndLastSlotPair(pendingPath, syncedPath)) {
			return true;
		}
		// 双箱目录可能从右半边点进去，服务端同步回来时会规范到左半边。
		List<Integer> normalizedPending = normalizeVisiblePathForPairs(pendingPath);
		return normalizedPending.equals(syncedPath) || normalizeVisiblePathForPairs(syncedPath).equals(pendingPath);
	}

	private static List<Integer> normalizeVisiblePathForPairs(List<Integer> path) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || path.isEmpty()) {
			return path;
		}

		List<Integer> normalizedPath = new ArrayList<>(path);
		DefaultedList<ItemStack> currentStacks = getRootStacks(client.player.currentScreenHandler);
		for (int depth = 0; depth < normalizedPath.size(); depth++) {
			int slot = normalizedPath.get(depth);
			int pairStart = NestedChestMod.getNestedChestPairStart(currentStacks, slot);
			if (pairStart >= 0 && pairStart < slot) {
				int slotOffset = slot - pairStart;
				slot = pairStart;
				normalizedPath.set(depth, pairStart);
				if (depth + 1 < normalizedPath.size()) {
					normalizedPath.set(depth + 1, normalizedPath.get(depth + 1) + slotOffset * NestedChestMod.NESTED_CHEST_SIZE);
				}
			} else if (depth == normalizedPath.size() - 1 && pairStart >= 0) {
				slot = pairStart;
				normalizedPath.set(depth, pairStart);
			}

			if (depth + 1 < normalizedPath.size()) {
				DefaultedList<ItemStack> nextStacks = stacksForVisiblePathPrefix(List.copyOf(normalizedPath.subList(0, depth + 1)), currentStacks, slot);
				if (nextStacks == null) {
					break;
				}
				currentStacks = nextStacks;
			}
		}
		return List.copyOf(normalizedPath);
	}

	private static DefaultedList<ItemStack> stacksForVisiblePathPrefix(List<Integer> prefix, DefaultedList<ItemStack> parentStacks, int slot) {
		NestedWindow window = findWindow(prefix);
		if (window != null) {
			return window.stacks;
		}
		DefaultedList<ItemStack> cached = PATH_STACK_CACHE.get(prefix);
		if (cached != null) {
			return cached;
		}
		ContainerView view = containerViewFromSlots(parentStacks, slot);
		return view == null ? null : view.stacks();
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
		private List<Integer> path;
		private List<Integer> pendingPath = List.of();
		private final boolean main;
		private DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY);
		private Text title;
		private SortMode sortMode = SortMode.NAME;
		private boolean sortAscending = true;
		private int x;
		private int y;

		private NestedWindow(List<Integer> path, int x, int y, ContainerView view, boolean main) {
			this.path = List.copyOf(path);
			this.main = main;
			this.x = x;
			this.y = y;
			setSource(view);
		}

		private void setPath(List<Integer> path) {
			this.path = List.copyOf(path);
		}

		private void setPendingPath(List<Integer> path) {
			this.pendingPath = List.copyOf(path);
		}

		private void clearPendingPath() {
			this.pendingPath = List.of();
		}

		private boolean pendingPathMatches(List<Integer> path) {
			return pathsMatchForSync(this.pendingPath, path);
		}

		private boolean hasPendingDescendantOf(List<Integer> path) {
			return !this.pendingPath.isEmpty() && isProperDescendant(this.pendingPath, path);
		}

		private void setSource(ContainerView view) {
			this.title = view.title();
			setStacks(view.stacks());
		}

		private void setTitle(Text title) {
			this.title = title;
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

		private void render(DrawContext context, int mouseX, int mouseY) {
			MinecraftClient client = MinecraftClient.getInstance();
			drawPanel(context, x, y, width(), height());
			fillRoundedRect(context, x + 3, y + 3, width() - 6, TITLE_BAR_HEIGHT - 1, 3, 0x33FFFFFF);
			int titleWidth = Math.max(0, backgroundX() - x - 10);
			context.drawText(client.textRenderer, client.textRenderer.trimToWidth(title.getString(), titleWidth), x + 8, y + 5, 0xFF5A5A5A, false);
			drawSmallButton(context, mouseX, mouseY, backgroundX(), titleButtonY(), TOOL_BUTTON_WIDTH, WINDOW_CLOSE_SIZE, Text.literal(backgroundModeIcon()), backgroundContains(mouseX, mouseY));
			drawSmallButton(context, mouseX, mouseY, sortX(), titleButtonY(), TOOL_BUTTON_WIDTH, WINDOW_CLOSE_SIZE, Text.literal(sortMode.icon), sortContains(mouseX, mouseY));
			drawSmallButton(context, mouseX, mouseY, sortDirectionX(), titleButtonY(), TOOL_BUTTON_WIDTH, WINDOW_CLOSE_SIZE, Text.literal(sortAscending ? "↑" : "↓"), sortDirectionContains(mouseX, mouseY));
			drawSmallButton(context, mouseX, mouseY, extraX(), titleButtonY(), EXTRA_BUTTON_WIDTH, WINDOW_CLOSE_SIZE, Text.literal("+"), extraContains(mouseX, mouseY));
			drawSmallButton(context, mouseX, mouseY, closeX(), titleButtonY(), WINDOW_CLOSE_SIZE, WINDOW_CLOSE_SIZE, Text.literal("x"), closeContains(mouseX, mouseY));

			int navY = y + TITLE_BAR_HEIGHT + 4;
			drawSmallButton(context, mouseX, mouseY, backX(), navY + 1, BACK_BUTTON_SIZE, BACK_BUTTON_SIZE, Text.literal("<"), backContains(mouseX, mouseY));
			fillRoundedRect(context, pathFieldX() - 1, pathFieldY() - 1, pathFieldWidth() + 2, PATH_FIELD_HEIGHT + 2, 3, 0x55919191);
			fillRoundedRect(context, pathFieldX(), pathFieldY(), pathFieldWidth(), PATH_FIELD_HEIGHT, 3, 0xFFD6D6D6);
			context.drawHorizontalLine(pathFieldX() + 2, pathFieldX() + pathFieldWidth() - 3, pathFieldY(), 0xFF8A8A8A);
			if (pathField != null && pathFieldWindow == this) {
				pathField.render(context, mouseX, mouseY, 0.0F);
			}

			int gridX = x + PADDING;
			int gridY = y + TITLE_BAR_HEIGHT + NAV_BAR_HEIGHT + PADDING;
			int gridPanelX = gridX - 4;
			int gridPanelY = gridY - 4;
			int gridPanelWidth = GRID_WIDTH + 8;
			int gridPanelHeight = rows() * SLOT_SIZE + 8;
			fillRoundedRect(context, gridPanelX, gridPanelY, gridPanelWidth, gridPanelHeight, PANEL_RADIUS, 0xFF9A9A9A);
			drawRoundedBorder(context, gridPanelX, gridPanelY, gridPanelWidth, gridPanelHeight, PANEL_RADIUS, 0xFF5E5E5E, 0xFFE7E7E7);
			fillRoundedRect(context, gridPanelX + 2, gridPanelY + 2, gridPanelWidth - 4, gridPanelHeight - 4, PANEL_RADIUS - 1, 0xFFAEAEAE);
			boolean customGridBackground = drawGridBackgroundImage(context, gridX, gridY, GRID_WIDTH, rows() * SLOT_SIZE);
			for (int slot = 0; slot < stacks.size(); slot++) {
				int slotX = gridX + (slot % GRID_COLUMNS) * SLOT_SIZE;
				int slotY = gridY + (slot / GRID_COLUMNS) * SLOT_SIZE;
				drawSlot(context, slotX, slotY, customGridBackground);
				if (mouseX >= slotX && mouseX < slotX + 18 && mouseY >= slotY && mouseY < slotY + 18) {
					context.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x44FFFFFF);
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
			return mouseX >= x && mouseX < extraX() && mouseY >= y && mouseY < y + TITLE_BAR_HEIGHT;
		}

		private boolean closeContains(double mouseX, double mouseY) {
			return mouseX >= closeX() && mouseX < closeX() + WINDOW_CLOSE_SIZE && mouseY >= titleButtonY() && mouseY < titleButtonY() + WINDOW_CLOSE_SIZE;
		}

		private boolean extraContains(double mouseX, double mouseY) {
			return mouseX >= extraX() && mouseX < extraX() + EXTRA_BUTTON_WIDTH && mouseY >= titleButtonY() && mouseY < titleButtonY() + WINDOW_CLOSE_SIZE;
		}

		private boolean sortContains(double mouseX, double mouseY) {
			return mouseX >= sortX() && mouseX < sortX() + TOOL_BUTTON_WIDTH && mouseY >= titleButtonY() && mouseY < titleButtonY() + WINDOW_CLOSE_SIZE;
		}

		private boolean sortDirectionContains(double mouseX, double mouseY) {
			return mouseX >= sortDirectionX() && mouseX < sortDirectionX() + TOOL_BUTTON_WIDTH && mouseY >= titleButtonY() && mouseY < titleButtonY() + WINDOW_CLOSE_SIZE;
		}

		private boolean backgroundContains(double mouseX, double mouseY) {
			return mouseX >= backgroundX() && mouseX < backgroundX() + TOOL_BUTTON_WIDTH && mouseY >= titleButtonY() && mouseY < titleButtonY() + WINDOW_CLOSE_SIZE;
		}

		private void cycleSortMode() {
			sortMode = sortMode.next();
		}

		private boolean backContains(double mouseX, double mouseY) {
			return mouseX >= backX() && mouseX < backX() + BACK_BUTTON_SIZE && mouseY >= y + TITLE_BAR_HEIGHT + 5 && mouseY < y + TITLE_BAR_HEIGHT + 5 + BACK_BUTTON_SIZE;
		}

		private boolean pathFieldContains(double mouseX, double mouseY) {
			return mouseX >= pathFieldX() && mouseX < pathFieldX() + pathFieldWidth() && mouseY >= pathFieldY() && mouseY < pathFieldY() + PATH_FIELD_HEIGHT;
		}

		private int slotAt(double mouseX, double mouseY) {
			int gridX = x + PADDING;
			int gridY = y + TITLE_BAR_HEIGHT + NAV_BAR_HEIGHT + PADDING;
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
			return TITLE_BAR_HEIGHT + NAV_BAR_HEIGHT + PADDING + rows() * SLOT_SIZE + PADDING + 4;
		}

		private int titleButtonY() {
			return y;
		}

		private int closeX() {
			return x + WINDOW_WIDTH - WINDOW_CLOSE_SIZE - 2;
		}

		private int extraX() {
			return closeX() - EXTRA_BUTTON_WIDTH - 2;
		}

		private int sortDirectionX() {
			return extraX() - TOOL_BUTTON_WIDTH - 2;
		}

		private int sortX() {
			return sortDirectionX() - TOOL_BUTTON_WIDTH - 2;
		}

		private int backgroundX() {
			return sortX() - TOOL_BUTTON_WIDTH - 2;
		}

		private int backX() {
			return x + PADDING;
		}

		private int pathFieldX() {
			return x + PADDING + BACK_BUTTON_SIZE + 4;
		}

		private int pathFieldY() {
			return y + TITLE_BAR_HEIGHT + 4;
		}

		private int pathFieldWidth() {
			return WINDOW_WIDTH - PADDING * 2 - BACK_BUTTON_SIZE - 4;
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
			this.nameField = new TextFieldWidget(client.textRenderer, 0, 0, RENAME_WIDTH - 20, 20, Text.literal("鍚嶇О"));
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
			drawPanel(context, x, y, RENAME_WIDTH, RENAME_HEIGHT);
			fillRoundedRect(context, x + 3, y + 3, RENAME_WIDTH - 6, TITLE_BAR_HEIGHT - 1, 3, 0x33FFFFFF);
			context.drawText(client.textRenderer, Text.literal("重命名箱子"), x + 8, y + 6, 0xFF5A5A5A, false);
			fillRoundedRect(context, x + 9, y + 27, RENAME_WIDTH - 18, 22, 3, 0x55919191);
			nameField.render(context, mouseX, mouseY, 0.0F);
			drawButton(context, mouseX, mouseY, cancelX(), buttonY(), Text.literal("鍙栨秷"));
			drawButton(context, mouseX, mouseY, doneX(), buttonY(), Text.literal("纭畾"));
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

		private boolean charTyped(char chr, int modifiers) {
			nameField.charTyped(chr, modifiers);
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
			drawSmallButton(context, mouseX, mouseY, buttonX, buttonY, RENAME_BUTTON_WIDTH, RENAME_BUTTON_HEIGHT, label, buttonContains(mouseX, mouseY, buttonX, buttonY));
		}

		private boolean buttonContains(double mouseX, double mouseY, int buttonX, int buttonY) {
			return mouseX >= buttonX && mouseX < buttonX + RENAME_BUTTON_WIDTH && mouseY >= buttonY && mouseY < buttonY + RENAME_BUTTON_HEIGHT;
		}
	}

	private record ClickMemory(NestedWindow window, int slot, int button, long time) {
	}

	private record NestedSlotTarget(NestedWindow window, int slot) {
	}

	private record DragPickup(NestedWindow window, boolean startedInNested, int startSlot, int button) {
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

	private enum SortMode {
		NAME("A"),
		COUNT("#"),
		CATEGORY("◆");

		private final String icon;

		SortMode(String icon) {
			this.icon = icon;
		}

		private SortMode next() {
			SortMode[] values = values();
			return values[(ordinal() + 1) % values.length];
		}
	}
}

