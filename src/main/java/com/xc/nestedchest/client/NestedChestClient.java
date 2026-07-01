package com.xc.nestedchest.client;

import com.xc.nestedchest.NestedChestMod;
import com.xc.nestedchest.client.screen.ConnectedChestScreen;
import com.xc.nestedchest.network.NestedChestSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public class NestedChestClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NestedChestClientConfig.initialize();
		HandledScreens.register(NestedChestMod.CONNECTED_CHEST_SCREEN_HANDLER, ConnectedChestScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(NestedChestSyncPayload.ID, (payload, context) ->
				context.client().execute(() -> NestedChestOverlay.sync(payload.path(), payload.stacks())));
	}
}
