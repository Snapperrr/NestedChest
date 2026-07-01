package com.xc.nestedchest.world;

import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectedChestInventory implements Inventory {
	private static final Map<String, Integer> OPEN_GROUPS = new ConcurrentHashMap<>();

	private final World world;
	private final List<ChestBlockEntity> chests;
	private final List<BlockPos> positions;
	private final String groupKey;
	private final Bounds bounds;

	public ConnectedChestInventory(World world, List<ChestBlockEntity> chests) {
		this.world = world;
		this.chests = new ArrayList<>(chests);
		// 固定排序决定“第几个箱子对应哪 27 格”，避免每次打开顺序变化导致物品看起来乱跳。
		this.chests.sort(Comparator
				.comparingInt((ChestBlockEntity chest) -> chest.getPos().getY())
				.thenComparingInt(chest -> chest.getPos().getZ())
				.thenComparingInt(chest -> chest.getPos().getX()));
		this.positions = this.chests.stream().map(ChestBlockEntity::getPos).toList();
		this.groupKey = createGroupKey(world, positions);
		this.bounds = Bounds.from(this.positions);
	}

	public int chestCount() {
		return chests.size();
	}

	public List<BlockPos> positions() {
		return positions;
	}

	@Override
	public int size() {
		return chests.size() * 27;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < size(); i++) {
			if (!getStack(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		ChestBlockEntity chest = chestForSlot(slot);
		return chest == null ? ItemStack.EMPTY : chest.getStack(slot % 27);
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ChestBlockEntity chest = chestForSlot(slot);
		return chest == null ? ItemStack.EMPTY : chest.removeStack(slot % 27, amount);
	}

	@Override
	public ItemStack removeStack(int slot) {
		ChestBlockEntity chest = chestForSlot(slot);
		return chest == null ? ItemStack.EMPTY : chest.removeStack(slot % 27);
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		ChestBlockEntity chest = chestForSlot(slot);
		if (chest != null) {
			chest.setStack(slot % 27, stack);
		}
	}

	@Override
	public void markDirty() {
		for (ChestBlockEntity chest : chests) {
			chest.markDirty();
			world.updateListeners(chest.getPos(), chest.getCachedState(), chest.getCachedState(), 3);
		}
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		for (ChestBlockEntity chest : chests) {
			if (world.getBlockEntity(chest.getPos()) != chest || !ConnectedChestFinder.isSupportedChest(world.getBlockState(chest.getPos()))) {
				return false;
			}
		}
		// 距离判定按整个组合体外框算，堆得很高或很长时不会因为离根箱太远而秒关 UI。
		return bounds.squaredDistanceTo(player.getX(), player.getY(), player.getZ()) <= 64.0D;
	}

	@Override
	public void onOpen(PlayerEntity player) {
		if (chests.isEmpty()) {
			return;
		}
		int viewers = OPEN_GROUPS.merge(groupKey, 1, Integer::sum);
		if (viewers == 1) {
			updateLids(1);
			playGroupSound(SoundEvents.BLOCK_CHEST_OPEN);
			world.emitGameEvent(player, GameEvent.CONTAINER_OPEN, centerPos());
		}
	}

	@Override
	public void onClose(PlayerEntity player) {
		if (chests.isEmpty()) {
			return;
		}
		final boolean[] closed = {false};
		OPEN_GROUPS.compute(groupKey, (key, value) -> {
			if (value == null || value <= 1) {
				closed[0] = true;
				return null;
			}
			return value - 1;
		});
		if (closed[0]) {
			updateLids(0);
			playGroupSound(SoundEvents.BLOCK_CHEST_CLOSE);
			world.emitGameEvent(player, GameEvent.CONTAINER_CLOSE, centerPos());
		}
	}

	@Override
	public boolean isValid(int slot, ItemStack stack) {
		ChestBlockEntity chest = chestForSlot(slot);
		return chest != null && chest.isValid(slot % 27, stack);
	}

	@Override
	public void clear() {
		for (ChestBlockEntity chest : chests) {
			chest.clear();
		}
	}

	private ChestBlockEntity chestForSlot(int slot) {
		if (slot < 0 || slot >= size()) {
			return null;
		}
		return chests.get(slot / 27);
	}

	private void updateLids(int viewerCount) {
		// 所有组成箱子一起收开盖事件，视觉上像一个整体。
		for (ChestBlockEntity chest : chests) {
			world.addSyncedBlockEvent(chest.getPos(), chest.getCachedState().getBlock(), 1, viewerCount);
		}
	}

	private void playGroupSound(net.minecraft.sound.SoundEvent sound) {
		BlockPos center = centerPos();
		world.playSound(null, center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D, sound, SoundCategory.BLOCKS, 0.5F, world.random.nextFloat() * 0.1F + 0.9F);
	}

	private BlockPos centerPos() {
		if (positions.isEmpty()) {
			return BlockPos.ORIGIN;
		}
		long x = 0L;
		long y = 0L;
		long z = 0L;
		for (BlockPos pos : positions) {
			x += pos.getX();
			y += pos.getY();
			z += pos.getZ();
		}
		return new BlockPos((int) (x / positions.size()), (int) (y / positions.size()), (int) (z / positions.size()));
	}

	private static String createGroupKey(World world, List<BlockPos> positions) {
		StringBuilder builder = new StringBuilder(world.getRegistryKey().getValue().toString());
		for (BlockPos pos : positions) {
			builder.append('|').append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
		}
		return builder.toString();
	}

	private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		private static Bounds from(List<BlockPos> positions) {
			if (positions.isEmpty()) {
				return new Bounds(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
			}

			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (BlockPos pos : positions) {
				minX = Math.min(minX, pos.getX());
				minY = Math.min(minY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX());
				maxY = Math.max(maxY, pos.getY());
				maxZ = Math.max(maxZ, pos.getZ());
			}
			return new Bounds(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
		}

		private double squaredDistanceTo(double x, double y, double z) {
			// 点在外框内部时该轴距离为 0，只计算玩家到组合体外表面的最短距离。
			double dx = distanceOutside(x, minX, maxX);
			double dy = distanceOutside(y, minY, maxY);
			double dz = distanceOutside(z, minZ, maxZ);
			return dx * dx + dy * dy + dz * dz;
		}

		private static double distanceOutside(double value, double min, double max) {
			if (value < min) {
				return min - value;
			}
			if (value > max) {
				return value - max;
			}
			return 0.0D;
		}
	}
}
