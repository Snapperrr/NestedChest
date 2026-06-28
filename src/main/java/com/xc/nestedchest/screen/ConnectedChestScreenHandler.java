package com.xc.nestedchest.screen;

import com.xc.nestedchest.NestedChestMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

public class ConnectedChestScreenHandler extends ScreenHandler {
	public static final int COLUMNS = 9;
	public static final int VISIBLE_ROWS = 6;
	public static final int VISIBLE_CHEST_SLOTS = COLUMNS * VISIBLE_ROWS;
	private static final int PLAYER_INVENTORY_Y = 140;
	private static final int PLAYER_HOTBAR_Y = 198;

	private final Inventory inventory;
	private final int rows;
	private int scrollRow;

	public ConnectedChestScreenHandler(int syncId, PlayerInventory playerInventory) {
		this(syncId, playerInventory, new SimpleInventory(VISIBLE_CHEST_SLOTS));
	}

	public ConnectedChestScreenHandler(int syncId, PlayerInventory playerInventory, int totalSlots) {
		this(syncId, playerInventory, new SimpleInventory(Math.max(VISIBLE_CHEST_SLOTS, totalSlots)));
	}

	public ConnectedChestScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
		super(NestedChestMod.CONNECTED_CHEST_SCREEN_HANDLER, syncId);
		this.inventory = inventory;
		this.rows = Math.max(VISIBLE_ROWS, (int) Math.ceil(inventory.size() / (double) COLUMNS));
		inventory.onOpen(playerInventory.player);

		for (int row = 0; row < VISIBLE_ROWS; row++) {
			for (int column = 0; column < COLUMNS; column++) {
				addSlot(new ScrollingSlot(inventory, row * COLUMNS + column, 8 + column * 18, 18 + row * 18));
			}
		}

		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, PLAYER_INVENTORY_Y + row * 18));
			}
		}

		for (int column = 0; column < 9; column++) {
			addSlot(new Slot(playerInventory, column, 8 + column * 18, PLAYER_HOTBAR_Y));
		}
	}

	public int rows() {
		return rows;
	}

	public int totalSlots() {
		return inventory.size();
	}

	public int scrollRow() {
		return scrollRow;
	}

	public boolean canScroll() {
		return rows > VISIBLE_ROWS;
	}

	public void scrollTo(float position) {
		int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
		this.scrollRow = Math.round(position * maxScroll);
		refreshVisibleSlots();
	}

	public void scrollBy(double amount) {
		int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
		this.scrollRow = Math.max(0, Math.min(maxScroll, this.scrollRow + (int) Math.signum(amount)));
		refreshVisibleSlots();
	}

	public float scrollPosition() {
		int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
		return maxScroll == 0 ? 0.0F : scrollRow / (float) maxScroll;
	}

	@Override
	public boolean onButtonClick(PlayerEntity player, int id) {
		scrollTo(id / 1000.0F);
		return true;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return inventory.canPlayerUse(player);
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot == null || !slot.hasStack()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getStack();
		result = stack.copy();
		if (index < VISIBLE_CHEST_SLOTS) {
			if (!this.insertItem(stack, VISIBLE_CHEST_SLOTS, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (!this.insertItem(stack, 0, VISIBLE_CHEST_SLOTS, false)) {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.setStack(ItemStack.EMPTY);
		} else {
			slot.markDirty();
		}
		return result;
	}

	@Override
	public void onClosed(PlayerEntity player) {
		super.onClosed(player);
		inventory.onClose(player);
	}

	private void refreshVisibleSlots() {
		for (int i = 0; i < VISIBLE_CHEST_SLOTS; i++) {
			Slot slot = this.slots.get(i);
			if (slot instanceof ScrollingSlot scrollingSlot) {
				scrollingSlot.setBackingIndex(scrollRow * COLUMNS + i);
			}
		}
		sendContentUpdates();
	}

	private class ScrollingSlot extends Slot {
		private int backingIndex;

		ScrollingSlot(Inventory inventory, int backingIndex, int x, int y) {
			super(inventory, backingIndex, x, y);
			this.backingIndex = backingIndex;
		}

		void setBackingIndex(int backingIndex) {
			this.backingIndex = backingIndex;
		}

		@Override
		public int getIndex() {
			return backingIndex;
		}

		@Override
		public boolean isEnabled() {
			return backingIndex >= 0 && backingIndex < inventory.size();
		}

		@Override
		public ItemStack getStack() {
			return isEnabled() ? inventory.getStack(backingIndex) : ItemStack.EMPTY;
		}

		@Override
		public void setStack(ItemStack stack) {
			if (isEnabled()) {
				inventory.setStack(backingIndex, stack);
				markDirty();
			}
		}

		@Override
		public void setStack(ItemStack stack, ItemStack previousStack) {
			setStack(stack);
		}

		@Override
		public void setStackNoCallbacks(ItemStack stack) {
			setStack(stack);
		}

		@Override
		public ItemStack takeStack(int amount) {
			return isEnabled() ? inventory.removeStack(backingIndex, amount) : ItemStack.EMPTY;
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return isEnabled() && inventory.isValid(backingIndex, stack);
		}

		@Override
		public void markDirty() {
			inventory.markDirty();
		}
	}
}
