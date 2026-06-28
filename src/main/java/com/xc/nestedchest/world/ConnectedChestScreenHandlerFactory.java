package com.xc.nestedchest.world;

import com.xc.nestedchest.screen.ConnectedChestScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class ConnectedChestScreenHandlerFactory implements ExtendedScreenHandlerFactory<Integer> {
	private final ConnectedChestInventory inventory;

	public ConnectedChestScreenHandlerFactory(ConnectedChestInventory inventory) {
		this.inventory = inventory;
	}

	@Override
	public Integer getScreenOpeningData(ServerPlayerEntity player) {
		return inventory.size();
	}

	@Override
	public Text getDisplayName() {
		return Text.literal("连接箱子 x" + inventory.chestCount());
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new ConnectedChestScreenHandler(syncId, playerInventory, inventory);
	}
}
