package com.xc.nestedchest.mixin;

import com.xc.nestedchest.NestedChestMod;
import com.xc.nestedchest.world.ConnectedChestFinder;
import com.xc.nestedchest.world.ConnectedChestInventory;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
	@Inject(method = "serverTick", at = @At("HEAD"))
	private static void nestedchest$beginHopperTransferWorld(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, CallbackInfo ci) {
		// transfer 回调只有 Inventory，没有 World；这里用线程上下文把当前漏斗世界传过去。
		NestedChestMod.setHopperTransferWorld(world);
	}

	@Inject(method = "serverTick", at = @At("RETURN"))
	private static void nestedchest$endHopperTransferWorld(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, CallbackInfo ci) {
		NestedChestMod.clearHopperTransferWorld();
	}

	@Inject(method = "transfer(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/inventory/Inventory;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/math/Direction;)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"), cancellable = true)
	private static void nestedchest$transferIntoBoundNestedChest(Inventory from, Inventory to, ItemStack stack, Direction side, CallbackInfoReturnable<ItemStack> cir) {
		ItemStack remaining = NestedChestMod.transferIntoBoundNestedChest(to, stack);
		if (remaining.getCount() != stack.getCount()) {
			// 只在确实写入了箱中箱时截断原版流程，剩余物品继续按漏斗规则处理。
			cir.setReturnValue(remaining);
		}
	}

	@Inject(method = "getInventoryAt(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/inventory/Inventory;", at = @At("RETURN"), cancellable = true)
	private static void nestedchest$useConnectedInventoryForHoppers(World world, BlockPos pos, CallbackInfoReturnable<Inventory> cir) {
		if (!ConnectedChestFinder.isSupportedChest(world.getBlockState(pos))) {
			return;
		}
		ConnectedChestInventory connectedInventory = ConnectedChestFinder.find(world, pos);
		if (connectedInventory.chestCount() > 1) {
			// 漏斗看到的是整个连接箱子组，而不是某一个单独 ChestBlockEntity。
			// EN: Hoppers target the connected inventory view, not the single touched ChestBlockEntity.
			cir.setReturnValue(connectedInventory);
		}
	}
}
