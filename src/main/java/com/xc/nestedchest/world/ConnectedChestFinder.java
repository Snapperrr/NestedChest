package com.xc.nestedchest.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public final class ConnectedChestFinder {
	public static final int MAX_CONNECTED_CHESTS = 4096;

	private ConnectedChestFinder() {
	}

	public static ConnectedChestInventory find(World world, BlockPos origin) {
		List<ChestBlockEntity> chests = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		Queue<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin.toImmutable());
		visited.add(origin.toImmutable());

		// 从被打开的箱子开始 BFS，六个方向相邻的箱子都属于同一个超大箱子组。
		while (!queue.isEmpty() && chests.size() < MAX_CONNECTED_CHESTS) {
			BlockPos current = queue.remove();
			BlockEntity blockEntity = world.getBlockEntity(current);
			if (!(blockEntity instanceof ChestBlockEntity chest) || !isSupportedChest(world.getBlockState(current))) {
				continue;
			}

			chests.add(chest);
			for (Direction direction : Direction.values()) {
				BlockPos next = current.offset(direction);
				if (visited.add(next.toImmutable()) && isSupportedChest(world.getBlockState(next))) {
					queue.add(next.toImmutable());
				}
			}
		}

		return new ConnectedChestInventory(world, chests);
	}

	public static boolean isSupportedChest(BlockState state) {
		return state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST);
	}
}
