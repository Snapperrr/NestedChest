package com.xc.nestedchest.mixin;

import com.xc.nestedchest.NestedChestMod;
import com.xc.nestedchest.world.ConnectedChestFinder;
import com.xc.nestedchest.world.ConnectedChestInventory;
import com.xc.nestedchest.world.ConnectedChestScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.block.ShapeContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin {
	@Inject(method = "onStateReplaced", at = @At("HEAD"))
	private void nestedchest$sanitizeChestDrops(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (world.isClient || state.isOf(newState.getBlock())) {
			return;
		}
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity instanceof Inventory inventory) {
			NestedChestMod.sanitizeChestDrops(world, pos, inventory);
		}
	}

	@Inject(method = "getOutlineShape", at = @At("RETURN"), cancellable = true)
	private void nestedchest$connectedOutline(BlockState state, BlockView world, BlockPos pos, ShapeContext context, CallbackInfoReturnable<VoxelShape> cir) {
		VoxelShape shape = cir.getReturnValue();
		for (Direction direction : Direction.values()) {
			if (ConnectedChestFinder.isSupportedChest(world.getBlockState(pos.offset(direction)))) {
				shape = VoxelShapes.union(shape, VoxelShapes.fullCube().offset(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ()));
			}
		}
		cir.setReturnValue(shape.simplify());
	}

	@Inject(method = "onUse", at = @At("HEAD"), cancellable = true)
	private void nestedchest$openConnectedChest(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
		if (world.isClient) {
			return;
		}

		ConnectedChestInventory inventory = ConnectedChestFinder.find(world, pos);
		if (inventory.chestCount() <= 1 || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return;
		}

		serverPlayer.openHandledScreen(new ConnectedChestScreenHandlerFactory(inventory));
		serverPlayer.incrementStat(Stats.OPEN_CHEST);
		cir.setReturnValue(ActionResult.CONSUME);
	}
}
