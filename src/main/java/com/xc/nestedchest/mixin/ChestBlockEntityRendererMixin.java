package com.xc.nestedchest.mixin;

import com.xc.nestedchest.world.ConnectedChestFinder;
import com.xc.nestedchest.world.ConnectedChestInventory;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LidOpenable;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.ChestBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ChestBlockEntityRenderer.class)
public abstract class ChestBlockEntityRendererMixin {
	private static final float HALF_PI = (float) Math.PI / 2.0F;
	private static final ModelPart GROUP_BODY = createCuboidPart("group_bottom", 0, 19, 1.0F, 0.0F, 1.0F, 14.0F, 10.0F, 14.0F, EnumSet.allOf(Direction.class), ModelTransform.NONE);
	private static final ModelPart GROUP_LID = createCuboidPart("group_lid", 0, 0, 1.0F, 0.0F, 0.0F, 14.0F, 5.0F, 14.0F, EnumSet.allOf(Direction.class), ModelTransform.NONE);
	private static final ModelPart GROUP_LATCH = createCuboidPart("group_latch", 0, 0, 7.0F, -2.0F, 14.0F, 2.0F, 4.0F, 1.0F, EnumSet.allOf(Direction.class), ModelTransform.NONE);
	private static final Map<CellModelKey, CellModels> CELL_MODELS = new ConcurrentHashMap<>();
	private static final Map<VisualBoxModelKey, CellModels> VISUAL_BOX_MODELS = new ConcurrentHashMap<>();

	@Shadow
	@Final
	private ModelPart singleChestLatch;

	@Shadow
	private boolean christmas;

	@Inject(
			method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;II)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void nestedchest$renderConnectedChestBody(BlockEntity blockEntity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay, CallbackInfo ci) {
		World world = blockEntity.getWorld();
		BlockState state = blockEntity.getCachedState();
		if (world == null || !ConnectedChestFinder.isSupportedChest(state)) {
			return;
		}

		ConnectedChestInventory inventory = ConnectedChestFinder.find(world, blockEntity.getPos());
		if (!shouldRenderAsConnectedGroup(world, inventory)) {
			return;
		}

		List<BlockPos> positions = inventory.positions();
		BlockPos root = findRoot(positions, blockEntity.getPos());
		if (!blockEntity.getPos().equals(root)) {
			ci.cancel();
			return;
		}

		renderGroup(blockEntity, world, state, positions, matrices, vertexConsumers, light, overlay, tickDelta);
		ci.cancel();
	}

	@ModifyVariable(
			method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;II)V",
			at = @At(value = "STORE", ordinal = 0),
			ordinal = 0
	)
	private BlockState nestedchest$renderSimpleConnectedAsVanillaDoubleChest(BlockState state, BlockEntity blockEntity) {
		World world = blockEntity.getWorld();
		if (world == null || !ConnectedChestFinder.isSupportedChest(state) || !state.contains(ChestBlock.CHEST_TYPE) || state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
			return state;
		}
		ConnectedChestInventory inventory = ConnectedChestFinder.find(world, blockEntity.getPos());
		if (inventory.chestCount() != 2 || hasVerticalNeighbor(world, blockEntity.getPos())) {
			return state;
		}

		BlockPos pos = blockEntity.getPos();
		Direction facing = state.get(ChestBlock.FACING);
		Direction rightDirection = facing.rotateYCounterclockwise();
		Direction leftDirection = facing.rotateYClockwise();
		if (isSameFacingSupportedChest(world, pos.offset(rightDirection), facing)) {
			return state.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT);
		}
		if (isSameFacingSupportedChest(world, pos.offset(leftDirection), facing)) {
			return state.with(ChestBlock.CHEST_TYPE, ChestType.LEFT);
		}
		return state;
	}

	private void renderGroup(BlockEntity rootEntity, World world, BlockState rootState, List<BlockPos> positions, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay, float tickDelta) {
		BlockPos root = rootEntity.getPos();
		Direction facing = getFacing(rootState, Direction.SOUTH);
		float openProgress = getGroupOpenProgress(world, positions, tickDelta);
		if (!isFilledCuboid(positions, root, facing)) {
			LayeredFootprint footprint = LayeredFootprint.from(positions, root, facing);
			if (footprint != null) {
				renderLayeredFootprintGroup(rootEntity, world, root, facing, footprint, matrices, vertexConsumers, light, overlay, openProgress);
				return;
			}
			renderSparseGroup(rootEntity, world, positions, root, facing, matrices, vertexConsumers, light, overlay, openProgress);
			return;
		}

		GroupHull hull = GroupHull.from(positions, root, facing);
		BlockEntity textureEntity = world.getBlockEntity(hull.texturePos());
		VertexConsumer consumer = getChestConsumer(textureEntity == null ? rootEntity : textureEntity, ChestType.SINGLE, vertexConsumers);
		int groupLight = WorldRenderer.getLightmapCoordinates(world, hull.texturePos());

		matrices.push();
		applyChestTransform(matrices, hull.offsetX(), hull.offsetY(), hull.offsetZ(), facing);
		renderGroupBody(matrices, consumer, hull, groupLight == 0 ? light : groupLight, overlay);
		renderGroupLid(matrices, consumer, hull, openProgress, groupLight == 0 ? light : groupLight, overlay);
		renderGroupLatch(matrices, consumer, hull, openProgress, groupLight == 0 ? light : groupLight, overlay);
		matrices.pop();
	}

	private void renderLayeredFootprintGroup(BlockEntity rootEntity, World world, BlockPos root, Direction facing, LayeredFootprint footprint, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay, float openProgress) {
		matrices.push();
		VisualBox latchBox = null;
		for (FootprintBox footprintBox : footprint.boxes()) {
			VisualBox box = VisualBox.footprintBox(root, facing, footprint, footprintBox);
			renderVisualBox(rootEntity, world, box, matrices, vertexConsumers, light, overlay, openProgress);
			if (latchBox == null || box.latchSpan() > latchBox.latchSpan()) {
				latchBox = box;
			}
		}

		if (latchBox != null) {
			renderVisualBoxLatch(rootEntity, world, latchBox, matrices, vertexConsumers, light, overlay, openProgress);
		}
		matrices.pop();
	}

	private void renderSparseGroup(BlockEntity rootEntity, World world, List<BlockPos> positions, BlockPos root, Direction facing, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay, float openProgress) {
		Set<BlockPos> connected = new HashSet<>(positions);
		matrices.push();
		for (BlockPos pos : positions) {
			BlockEntity cellEntity = world.getBlockEntity(pos);
			CellConnection connection = CellConnection.from(connected, pos, facing);
			VertexConsumer consumer = getChestConsumer(cellEntity == null ? rootEntity : cellEntity, connection.textureType(), vertexConsumers);
			int cellLight = WorldRenderer.getLightmapCoordinates(world, pos);
			renderSparseCell(matrices, consumer, connection, pos, root, facing, openProgress, cellLight == 0 ? light : cellLight, overlay);
		}

		LockOrigin lockOrigin = LockOrigin.from(connected, root, facing);
		BlockEntity lockEntity = world.getBlockEntity(lockOrigin.texturePos());
		VertexConsumer lockConsumer = getChestConsumer(lockEntity == null ? rootEntity : lockEntity, ChestType.SINGLE, vertexConsumers);
		int lockLight = WorldRenderer.getLightmapCoordinates(world, lockOrigin.texturePos());
		renderSparseLatch(matrices, lockConsumer, lockOrigin.x(), lockOrigin.y(), lockOrigin.z(), facing, openProgress, lockLight == 0 ? light : lockLight, overlay);
		matrices.pop();
	}

	private void renderVisualBox(BlockEntity rootEntity, World world, VisualBox box, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay, float openProgress) {
		BlockEntity textureEntity = world.getBlockEntity(box.texturePos());
		VertexConsumer consumer = getChestConsumer(textureEntity == null ? rootEntity : textureEntity, ChestType.SINGLE, vertexConsumers);
		int boxLight = WorldRenderer.getLightmapCoordinates(world, box.texturePos());
		CellModels models = VISUAL_BOX_MODELS.computeIfAbsent(box.modelKey(), ChestBlockEntityRendererMixin::createVisualBoxModels);

		matrices.push();
		applyChestTransform(matrices, box.anchor().getX() - rootEntity.getPos().getX(), box.anchor().getY() - rootEntity.getPos().getY(), box.anchor().getZ() - rootEntity.getPos().getZ(), box.facing());
		renderVisualBoxBody(matrices, consumer, box, models.body(), boxLight == 0 ? light : boxLight, overlay);
		renderVisualBoxLid(matrices, consumer, box, models.lid(), openProgress, boxLight == 0 ? light : boxLight, overlay);
		matrices.pop();
	}

	private void renderVisualBoxLatch(BlockEntity rootEntity, World world, VisualBox box, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay, float openProgress) {
		BlockEntity textureEntity = world.getBlockEntity(box.texturePos());
		VertexConsumer consumer = getChestConsumer(textureEntity == null ? rootEntity : textureEntity, ChestType.SINGLE, vertexConsumers);
		int boxLight = WorldRenderer.getLightmapCoordinates(world, box.texturePos());

		matrices.push();
		applyChestTransform(matrices, box.anchor().getX() - rootEntity.getPos().getX(), box.anchor().getY() - rootEntity.getPos().getY(), box.anchor().getZ() - rootEntity.getPos().getZ(), box.facing());
		renderLidBoundLatch(matrices, consumer, box.latchTranslateX(), box.latchTranslateZ(), box.lidPivotY(), box.minZ(), openProgress, boxLight == 0 ? light : boxLight, overlay);
		matrices.pop();
	}

	private static void renderVisualBoxBody(MatrixStack matrices, VertexConsumer consumer, VisualBox box, ModelPart body, int light, int overlay) {
		float scaleX = box.width() / 14.0F;
		float scaleY = box.bodyHeight() / 10.0F;
		float scaleZ = box.depth() / 14.0F;
		float translateX = box.minX() - scaleX;
		float translateZ = box.minZ() - scaleZ;

		matrices.push();
		matrices.translate(translateX / 16.0F, 0.0F, translateZ / 16.0F);
		matrices.scale(scaleX, scaleY, scaleZ);
		body.render(matrices, consumer, light, overlay);
		matrices.pop();
	}

	private static void renderVisualBoxLid(MatrixStack matrices, VertexConsumer consumer, VisualBox box, ModelPart lid, float openProgress, int light, int overlay) {
		float scaleX = box.width() / 14.0F;
		float scaleZ = box.depth() / 14.0F;
		float translateX = box.minX() - scaleX;

		matrices.push();
		matrices.translate(0.0F, box.lidPivotY() / 16.0F, box.minZ() / 16.0F);
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(-openProgress * HALF_PI));
		matrices.translate(translateX / 16.0F, 0.0F, 0.0F);
		matrices.scale(scaleX, 1.0F, scaleZ);
		lid.render(matrices, consumer, light, overlay);
		matrices.pop();
	}

	private VertexConsumer getChestConsumer(BlockEntity blockEntity, ChestType chestType, VertexConsumerProvider vertexConsumers) {
		return TexturedRenderLayers.getChestTextureId(blockEntity, chestType, this.christmas)
				.getVertexConsumer(vertexConsumers, RenderLayer::getEntityCutout);
	}

	private void renderSparseCell(MatrixStack matrices, VertexConsumer consumer, CellConnection connection, BlockPos pos, BlockPos root, Direction facing, float openProgress, int light, int overlay) {
		CellModels models = CELL_MODELS.computeIfAbsent(connection.modelKey(), ChestBlockEntityRendererMixin::createCellModels);
		CellTransform transform = CellTransform.from(connection);

		matrices.push();
		applyChestTransform(matrices, pos.getX() - root.getX(), pos.getY() - root.getY(), pos.getZ() - root.getZ(), facing);
		matrices.push();
		applyCellTransform(matrices, transform, true);
		models.body().render(matrices, consumer, light, overlay);
		matrices.pop();
		if (models.lid() != null) {
			matrices.push();
			applyCellTransform(matrices, transform, false);
			models.lid().pitch = -openProgress * HALF_PI;
			models.lid().render(matrices, consumer, light, overlay);
			matrices.pop();
		}
		matrices.pop();
	}

	private static void renderGroupBody(MatrixStack matrices, VertexConsumer consumer, GroupHull hull, int light, int overlay) {
		float scaleX = hull.visualWidth() / 14.0F;
		float scaleY = hull.bodyHeight() / 10.0F;
		float scaleZ = hull.visualDepth() / 14.0F;
		float translateX = 1.0F - scaleX;
		float translateZ = 1.0F - scaleZ;

		matrices.push();
		matrices.translate(translateX / 16.0F, 0.0F, translateZ / 16.0F);
		matrices.scale(scaleX, scaleY, scaleZ);
		GROUP_BODY.render(matrices, consumer, light, overlay);
		matrices.pop();
	}

	private static void renderGroupLid(MatrixStack matrices, VertexConsumer consumer, GroupHull hull, float openProgress, int light, int overlay) {
		float scaleX = hull.visualWidth() / 14.0F;
		float scaleZ = hull.visualDepth() / 14.0F;
		float translateX = 1.0F - scaleX;

		matrices.push();
		matrices.translate(0.0F, hull.lidPivotY() / 16.0F, 1.0F / 16.0F);
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(-openProgress * HALF_PI));
		matrices.translate(translateX / 16.0F, 0.0F, 0.0F);
		matrices.scale(scaleX, 1.0F, scaleZ);
		GROUP_LID.render(matrices, consumer, light, overlay);
		matrices.pop();
	}

	private void renderGroupLatch(MatrixStack matrices, VertexConsumer consumer, GroupHull hull, float openProgress, int light, int overlay) {
		renderLidBoundLatch(matrices, consumer, hull.latchTranslateX(), hull.latchTranslateZ() + 1.0F, hull.lidPivotY(), 1.0F, openProgress, light, overlay);
	}

	private void renderSparseLatch(MatrixStack matrices, VertexConsumer consumer, double x, double y, double z, Direction facing, float openProgress, int light, int overlay) {
		matrices.push();
		applyChestTransform(matrices, x, y, z, facing);
		this.singleChestLatch.pitch = -openProgress * HALF_PI;
		this.singleChestLatch.render(matrices, consumer, light, overlay);
		matrices.pop();
	}

	private static void renderLidBoundLatch(MatrixStack matrices, VertexConsumer consumer, float latchX, float latchZ, float lidPivotY, float lidPivotZ, float openProgress, int light, int overlay) {
		matrices.push();
		matrices.translate(0.0F, lidPivotY / 16.0F, lidPivotZ / 16.0F);
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(-openProgress * HALF_PI));
		matrices.translate(latchX / 16.0F, 0.0F, (latchZ - lidPivotZ) / 16.0F);
		GROUP_LATCH.render(matrices, consumer, light, overlay);
		matrices.pop();
	}

	private static void applyChestTransform(MatrixStack matrices, double x, double y, double z, Direction facing) {
		matrices.translate(x + 0.5D, y + 0.5D, z + 0.5D);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
		matrices.translate(-0.5D, -0.5D, -0.5D);
	}

	private static void applyCellTransform(MatrixStack matrices, CellTransform transform, boolean body) {
		matrices.translate(transform.translateX() / 16.0F, 0.0F, (body ? transform.bodyTranslateZ() : transform.lidTranslateZ()) / 16.0F);
		matrices.scale(transform.scaleX(), body ? transform.bodyScaleY() : 1.0F, body ? transform.bodyScaleZ() : transform.lidScaleZ());
	}

	private static float getGroupOpenProgress(World world, List<BlockPos> positions, float tickDelta) {
		float progress = 0.0F;
		for (BlockPos pos : positions) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof LidOpenable lidOpenable) {
				progress = Math.max(progress, lidOpenable.getAnimationProgress(tickDelta));
			}
		}
		progress = 1.0F - progress;
		return 1.0F - progress * progress * progress;
	}

	private static ModelPart createCuboidPart(String name, int textureX, int textureY, float x, float y, float z, float width, float height, float depth, Set<Direction> directions, ModelTransform transform) {
		ModelData data = new ModelData();
		ModelPartData root = data.getRoot();
		root.addChild(name, ModelPartBuilder.create().uv(textureX, textureY).cuboid(x, y, z, width, height, depth, directions), transform);
		return TexturedModelData.of(data, 64, 64).createModel().getChild(name);
	}

	private static CellModels createCellModels(CellModelKey key) {
		float minX = key.profile().modelMinX;
		float width = key.profile().modelWidth;

		ModelPart body = createCuboidPart(
				"bottom",
				0,
				19,
				minX,
				0.0F,
				1.0F,
				width,
				10.0F,
				14.0F,
				visibleDirections(key),
				ModelTransform.NONE
		);

		ModelPart lid = null;
		if (!key.localUp()) {
			lid = createCuboidPart(
					"lid",
					0,
					0,
					minX,
					0.0F,
					0.0F,
					width,
					5.0F,
					14.0F,
					visibleLidDirections(key),
					ModelTransform.pivot(0.0F, 9.0F, 1.0F)
			);
		}

		return new CellModels(body, lid);
	}

	private static CellModels createVisualBoxModels(VisualBoxModelKey key) {
		ModelPart body = createCuboidPart(
				"corner_arm_body",
				0,
				19,
				1.0F,
				0.0F,
				1.0F,
				14.0F,
				10.0F,
				14.0F,
				key.bodyDirections(),
				ModelTransform.NONE
		);

		ModelPart lid = createCuboidPart(
				"corner_arm_lid",
				0,
				0,
				1.0F,
				0.0F,
				0.0F,
				14.0F,
				5.0F,
				14.0F,
				key.lidDirections(),
				ModelTransform.NONE
		);
		return new CellModels(body, lid);
	}

	private static Set<Direction> visibleDirections(CellModelKey key) {
		EnumSet<Direction> directions = visibleHorizontalDirections(key);
		if (key.localDown()) {
			directions.remove(Direction.DOWN);
		}
		if (key.localUp()) {
			directions.remove(Direction.UP);
		}
		return directions;
	}

	private static Set<Direction> visibleLidDirections(CellModelKey key) {
		return visibleHorizontalDirections(key);
	}

	private static EnumSet<Direction> visibleHorizontalDirections(CellModelKey key) {
		EnumSet<Direction> directions = EnumSet.allOf(Direction.class);
		if (key.localWest()) {
			directions.remove(Direction.WEST);
		}
		if (key.localEast()) {
			directions.remove(Direction.EAST);
		}
		if (key.localNorth()) {
			directions.remove(Direction.NORTH);
		}
		if (key.localSouth()) {
			directions.remove(Direction.SOUTH);
		}
		return directions;
	}

	private static boolean shouldRenderAsConnectedGroup(World world, ConnectedChestInventory inventory) {
		if (inventory.chestCount() <= 1) {
			return false;
		}
		return !isVanillaSideBySidePair(world, inventory.positions());
	}

	private static boolean isVanillaSideBySidePair(World world, List<BlockPos> positions) {
		if (positions.size() != 2) {
			return false;
		}
		BlockPos first = positions.get(0);
		BlockPos second = positions.get(1);
		BlockState firstState = world.getBlockState(first);
		BlockState secondState = world.getBlockState(second);
		if (!firstState.contains(ChestBlock.FACING) || !secondState.contains(ChestBlock.FACING)) {
			return false;
		}
		Direction facing = firstState.get(ChestBlock.FACING);
		if (secondState.get(ChestBlock.FACING) != facing) {
			return false;
		}
		Direction neighborDirection = getDirectionBetween(first, second);
		return neighborDirection == facing.rotateYClockwise() || neighborDirection == facing.rotateYCounterclockwise();
	}

	private static Direction getDirectionBetween(BlockPos from, BlockPos to) {
		for (Direction direction : Direction.values()) {
			if (from.offset(direction).equals(to)) {
				return direction;
			}
		}
		return null;
	}

	private static BlockPos findRoot(List<BlockPos> positions, BlockPos fallback) {
		return positions.stream()
				.min(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX))
				.orElse(fallback);
	}

	private static Direction getFacing(BlockState state, Direction fallback) {
		return state.contains(ChestBlock.FACING) ? state.get(ChestBlock.FACING) : fallback;
	}

	private static boolean hasVerticalNeighbor(World world, BlockPos pos) {
		return ConnectedChestFinder.isSupportedChest(world.getBlockState(pos.up()))
				|| ConnectedChestFinder.isSupportedChest(world.getBlockState(pos.down()));
	}

	private static boolean isSameFacingSupportedChest(World world, BlockPos pos, Direction facing) {
		BlockState state = world.getBlockState(pos);
		return ConnectedChestFinder.isSupportedChest(state)
				&& state.contains(ChestBlock.FACING)
				&& state.get(ChestBlock.FACING) == facing;
	}

	private static boolean isFilledCuboid(List<BlockPos> positions, BlockPos root, Direction facing) {
		LocalBounds bounds = LocalBounds.from(positions, root, facing);
		long expectedSize = (long) (bounds.maxX() - bounds.minX() + 1)
				* (bounds.maxY() - bounds.minY() + 1)
				* (bounds.maxZ() - bounds.minZ() + 1);
		if (expectedSize != positions.size()) {
			return false;
		}

		Set<BlockPos> connected = new HashSet<>(positions);
		for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
			for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
				for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
					if (!connected.contains(localToWorld(root, x, y, z, facing))) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private record LockOrigin(double x, double y, double z, BlockPos texturePos) {
		private static LockOrigin from(Set<BlockPos> positions, BlockPos root, Direction facing) {
			Bounds bounds = Bounds.from(positions);
			double centerX = (bounds.minX + bounds.maxX + 1.0D) * 0.5D - root.getX() - 0.5D;
			double centerZ = (bounds.minZ + bounds.maxZ + 1.0D) * 0.5D - root.getZ() - 0.5D;
			double x = centerX;
			double z = centerZ;
			int textureX = clampToBounds((int) Math.floor(centerX + root.getX() + 0.5D), bounds.minX, bounds.maxX);
			int textureY = bounds.maxY;
			int textureZ = clampToBounds((int) Math.floor(centerZ + root.getZ() + 0.5D), bounds.minZ, bounds.maxZ);

			switch (facing) {
				case NORTH -> {
					z = bounds.minZ - root.getZ();
					textureZ = bounds.minZ;
				}
				case SOUTH -> {
					z = bounds.maxZ - root.getZ();
					textureZ = bounds.maxZ;
				}
				case WEST -> {
					x = bounds.minX - root.getX();
					textureX = bounds.minX;
				}
				case EAST -> {
					x = bounds.maxX - root.getX();
					textureX = bounds.maxX;
				}
				default -> {
				}
			}

			BlockPos intendedPos = new BlockPos(textureX, textureY, textureZ);
			BlockPos texturePos = positions.contains(intendedPos) ? intendedPos : closestFrontPosition(positions, bounds, intendedPos, facing);
			if (!positions.contains(intendedPos)) {
				x = texturePos.getX() - root.getX();
				z = texturePos.getZ() - root.getZ();
			}
			return new LockOrigin(x, textureY - root.getY(), z, texturePos);
		}

		private static int clampToBounds(int value, int min, int max) {
			return Math.max(min, Math.min(max, value));
		}

		private static BlockPos closestFrontPosition(Set<BlockPos> positions, Bounds bounds, BlockPos target, Direction facing) {
			List<BlockPos> frontPositions = positions.stream()
					.filter(pos -> pos.getY() == bounds.maxY)
					.filter(pos -> switch (facing) {
						case NORTH -> pos.getZ() == bounds.minZ;
						case SOUTH -> pos.getZ() == bounds.maxZ;
						case WEST -> pos.getX() == bounds.minX;
						case EAST -> pos.getX() == bounds.maxX;
						default -> false;
					})
					.toList();
			if (frontPositions.isEmpty()) {
				return closestExistingPosition(positions, target);
			}
			return frontPositions.stream()
					.min(Comparator
							.comparingInt((BlockPos pos) -> manhattanDistance(pos, target))
							.thenComparingInt(BlockPos::getZ)
							.thenComparingInt(BlockPos::getX))
					.orElse(target);
		}
	}

	private record CellConnection(boolean localWest, boolean localEast, boolean localNorth, boolean localSouth, boolean localUp, boolean localDown) {
		private static CellConnection from(Set<BlockPos> positions, BlockPos pos, Direction facing) {
			return new CellConnection(
					positions.contains(pos.offset(facing.rotateYClockwise())),
					positions.contains(pos.offset(facing.rotateYCounterclockwise())),
					positions.contains(pos.offset(facing.getOpposite())),
					positions.contains(pos.offset(facing)),
					positions.contains(pos.up()),
					positions.contains(pos.down())
			);
		}

		private CellModelKey modelKey() {
			return new CellModelKey(localWest, localEast, localNorth, localSouth, localUp, localDown);
		}

		private ChestType textureType() {
			return modelKey().profile().textureType;
		}
	}

	private record CellModelKey(boolean localWest, boolean localEast, boolean localNorth, boolean localSouth, boolean localUp, boolean localDown) {
		private CellProfile profile() {
			if (localWest && !localEast) {
				return CellProfile.LEFT;
			}
			if (!localWest && localEast) {
				return CellProfile.RIGHT;
			}
			return CellProfile.SINGLE;
		}
	}

	private enum CellProfile {
		SINGLE(1.0F, 14.0F, ChestType.SINGLE),
		LEFT(0.0F, 15.0F, ChestType.LEFT),
		RIGHT(1.0F, 15.0F, ChestType.RIGHT);

		private final float modelMinX;
		private final float modelWidth;
		private final ChestType textureType;

		CellProfile(float modelMinX, float modelWidth, ChestType textureType) {
			this.modelMinX = modelMinX;
			this.modelWidth = modelWidth;
			this.textureType = textureType;
		}
	}

	private record CellTransform(float translateX, float scaleX, float bodyTranslateZ, float bodyScaleZ, float lidTranslateZ, float lidScaleZ, float bodyScaleY) {
		private static CellTransform from(CellConnection connection) {
			CellProfile profile = connection.modelKey().profile();
			float modelMinX = profile.modelMinX;
			float modelMaxX = profile.modelMinX + profile.modelWidth;
			float targetMinX = connection.localWest ? 0.0F : 1.0F;
			float targetMaxX = connection.localEast ? 16.0F : 15.0F;
			float scaleX = (targetMaxX - targetMinX) / (modelMaxX - modelMinX);
			float translateX = targetMinX - scaleX * modelMinX;

			float bodyTargetMinZ = connection.localNorth ? 0.0F : 1.0F;
			float bodyTargetMaxZ = connection.localSouth ? 16.0F : 15.0F;
			float bodyScaleZ = (bodyTargetMaxZ - bodyTargetMinZ) / 14.0F;
			float bodyTranslateZ = bodyTargetMinZ - bodyScaleZ;

			float lidTargetMaxZ = connection.localSouth ? 16.0F : 14.0F;
			float lidScaleZ = lidTargetMaxZ / 14.0F;
			float lidTranslateZ = 0.0F;
			float bodyScaleY = connection.localUp ? 1.6F : 1.0F;
			return new CellTransform(translateX, scaleX, bodyTranslateZ, bodyScaleZ, lidTranslateZ, lidScaleZ, bodyScaleY);
		}
	}

	private record CellModels(ModelPart body, ModelPart lid) {
	}

	private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		private static Bounds from(Set<BlockPos> positions) {
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
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
		}
	}

	private record LayeredFootprint(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Set<LocalCell> footprint, List<FootprintBox> boxes) {
		private static LayeredFootprint from(List<BlockPos> positions, BlockPos root, Direction facing) {
			LocalBounds bounds = LocalBounds.from(positions, root, facing);

			Map<Integer, Set<LocalCell>> layers = new HashMap<>();
			for (BlockPos pos : positions) {
				int y = pos.getY() - root.getY();
				layers.computeIfAbsent(y, ignored -> new HashSet<>()).add(new LocalCell(localX(pos, root, facing), localZ(pos, root, facing)));
			}
			if (layers.size() != bounds.maxY() - bounds.minY() + 1) {
				return null;
			}

			Set<LocalCell> footprint = layers.get(bounds.minY());
			if (footprint == null || footprint.size() <= 1) {
				return null;
			}
			for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
				Set<LocalCell> layer = layers.get(y);
				if (layer == null || !layer.equals(footprint)) {
					return null;
				}
			}

			if (isFilledRectangle(footprint, bounds)) {
				return null;
			}
			List<FootprintBox> boxes = buildBoxes(footprint, bounds);
			return new LayeredFootprint(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), footprint, boxes);
		}

		private static boolean isFilledRectangle(Set<LocalCell> footprint, LocalBounds bounds) {
			long expectedSize = (long) (bounds.maxX() - bounds.minX() + 1) * (bounds.maxZ() - bounds.minZ() + 1);
			if (expectedSize != footprint.size()) {
				return false;
			}
			for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
				for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
					if (!footprint.contains(new LocalCell(x, z))) {
						return false;
					}
				}
			}
			return true;
		}

		private static List<FootprintBox> buildBoxes(Set<LocalCell> footprint, LocalBounds bounds) {
			Set<LocalCell> remaining = new HashSet<>(footprint);
			java.util.ArrayList<FootprintBox> boxes = new java.util.ArrayList<>();
			for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
				int startZ = Integer.MIN_VALUE;
				int previousZ = Integer.MIN_VALUE;
				for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
					LocalCell cell = new LocalCell(x, z);
					if (remaining.contains(cell)) {
						if (startZ == Integer.MIN_VALUE) {
							startZ = z;
						}
						previousZ = z;
					} else if (startZ != Integer.MIN_VALUE) {
						addVerticalBox(boxes, remaining, x, startZ, previousZ);
						startZ = Integer.MIN_VALUE;
					}
				}
				if (startZ != Integer.MIN_VALUE) {
					addVerticalBox(boxes, remaining, x, startZ, previousZ);
				}
			}

			for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
				int startX = Integer.MIN_VALUE;
				int previousX = Integer.MIN_VALUE;
				for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
					LocalCell cell = new LocalCell(x, z);
					if (remaining.contains(cell)) {
						if (startX == Integer.MIN_VALUE) {
							startX = x;
						}
						previousX = x;
					} else if (startX != Integer.MIN_VALUE) {
						addHorizontalBox(boxes, remaining, startX, previousX, z);
						startX = Integer.MIN_VALUE;
					}
				}
				if (startX != Integer.MIN_VALUE) {
					addHorizontalBox(boxes, remaining, startX, previousX, z);
				}
			}
			return boxes;
		}

		private static void addVerticalBox(List<FootprintBox> boxes, Set<LocalCell> remaining, int x, int startZ, int endZ) {
			if (endZ <= startZ) {
				return;
			}
			for (int z = startZ; z <= endZ; z++) {
				remaining.remove(new LocalCell(x, z));
			}
			boxes.add(new FootprintBox(x, x, startZ, endZ));
		}

		private static void addHorizontalBox(List<FootprintBox> boxes, Set<LocalCell> remaining, int startX, int endX, int z) {
			for (int x = startX; x <= endX; x++) {
				remaining.remove(new LocalCell(x, z));
			}
			boxes.add(new FootprintBox(startX, endX, z, z));
		}

		private Set<BlockPos> worldPositions(BlockPos root, Direction facing) {
			Set<BlockPos> positions = new HashSet<>();
			for (int y = minY; y <= maxY; y++) {
				for (LocalCell cell : footprint) {
					positions.add(localToWorld(root, cell.x(), y, cell.z(), facing));
				}
			}
			return positions;
		}
	}

	private record LocalCell(int x, int z) {
	}

	private record FootprintBox(int minX, int maxX, int minZ, int maxZ) {
	}

	private record VisualBox(BlockPos anchor, Direction facing, float minX, float minZ, float width, float depth, float bodyHeight, float lidPivotY, BlockPos texturePos, Set<Direction> bodyDirections, Set<Direction> lidDirections) {
		private static VisualBox footprintBox(BlockPos root, Direction facing, LayeredFootprint footprint, FootprintBox box) {
			boolean westNeighbor = hasNeighborAlongZ(footprint.footprint(), box.minX() - 1, box.minZ(), box.maxZ());
			boolean eastNeighbor = hasNeighborAlongZ(footprint.footprint(), box.maxX() + 1, box.minZ(), box.maxZ());
			boolean northNeighbor = hasNeighborAlongX(footprint.footprint(), box.minX(), box.maxX(), box.minZ() - 1);
			boolean southNeighbor = hasNeighborAlongX(footprint.footprint(), box.minX(), box.maxX(), box.maxZ() + 1);
			float minX = westNeighbor ? 0.0F : 1.0F;
			float minZ = northNeighbor ? 0.0F : 1.0F;
			float width = (box.maxX() - box.minX() + 1) * 16.0F - minX - (eastNeighbor ? 0.0F : 1.0F);
			float depth = (box.maxZ() - box.minZ() + 1) * 16.0F - minZ - (southNeighbor ? 0.0F : 1.0F);
			float bodyHeight = (footprint.maxY() - footprint.minY() + 1) * 16.0F - 6.0F;
			EnumSet<Direction> hiddenDirections = EnumSet.noneOf(Direction.class);
			return create(
					root,
					facing,
					box.minX(),
					footprint.minY(),
					box.minZ(),
					minX,
					minZ,
					width,
					depth,
					bodyHeight,
					bodyHeight - 1.0F,
					localToWorld(root, box.minX(), footprint.maxY(), box.minZ(), facing),
					hiddenDirections
			);
		}

		private static boolean hasNeighborAlongZ(Set<LocalCell> footprint, int x, int minZ, int maxZ) {
			for (int z = minZ; z <= maxZ; z++) {
				if (footprint.contains(new LocalCell(x, z))) {
					return true;
				}
			}
			return false;
		}

		private static boolean hasNeighborAlongX(Set<LocalCell> footprint, int minX, int maxX, int z) {
			for (int x = minX; x <= maxX; x++) {
				if (footprint.contains(new LocalCell(x, z))) {
					return true;
				}
			}
			return false;
		}

		private static VisualBox create(BlockPos root, Direction facing, int minLocalX, int minLocalY, int minLocalZ, float minX, float minZ, float width, float depth, float bodyHeight, float lidPivotY, BlockPos texturePos, Set<Direction> hiddenDirections) {
			Direction localEast = facing.rotateYCounterclockwise();
			BlockPos anchor = root.add(
					localEast.getOffsetX() * minLocalX + facing.getOffsetX() * minLocalZ,
					minLocalY,
					localEast.getOffsetZ() * minLocalX + facing.getOffsetZ() * minLocalZ
			);
			EnumSet<Direction> bodyDirections = EnumSet.allOf(Direction.class);
			EnumSet<Direction> lidDirections = EnumSet.allOf(Direction.class);
			if (hiddenDirections != null) {
				bodyDirections.removeAll(hiddenDirections);
				lidDirections.removeAll(hiddenDirections);
			}
			return new VisualBox(anchor, facing, minX, minZ, width, depth, bodyHeight, lidPivotY, texturePos, bodyDirections, lidDirections);
		}

		private VisualBoxModelKey modelKey() {
			return new VisualBoxModelKey(bodyDirections, lidDirections);
		}

		private float latchSpan() {
			return Math.max(width, depth);
		}

		private float latchTranslateX() {
			return minX + width * 0.5F - 8.0F;
		}

		private float latchTranslateZ() {
			return minZ * 2.0F + depth - 15.0F;
		}
	}

	private record VisualBoxModelKey(Set<Direction> bodyDirections, Set<Direction> lidDirections) {
	}

	private record GroupHull(double offsetX, double offsetY, double offsetZ, float visualWidth, float visualDepth, float bodyHeight, float lidPivotY, float latchTranslateX, float latchTranslateZ, BlockPos texturePos) {
		private static GroupHull from(List<BlockPos> positions, BlockPos root, Direction facing) {
			LocalBounds bounds = LocalBounds.from(positions, root, facing);
			Direction localEast = facing.rotateYCounterclockwise();
			BlockPos anchor = root.add(
					localEast.getOffsetX() * bounds.minX() + facing.getOffsetX() * bounds.minZ(),
					bounds.minY(),
					localEast.getOffsetZ() * bounds.minX() + facing.getOffsetZ() * bounds.minZ()
			);

			int widthBlocks = bounds.maxX() - bounds.minX() + 1;
			int heightBlocks = bounds.maxY() - bounds.minY() + 1;
			int depthBlocks = bounds.maxZ() - bounds.minZ() + 1;
			float visualWidth = widthBlocks * 16.0F - 2.0F;
			float visualDepth = depthBlocks * 16.0F - 2.0F;
			float bodyHeight = heightBlocks * 16.0F - 6.0F;
			float lidPivotY = bodyHeight - 1.0F;
			float latchTranslateX = (visualWidth - 14.0F) * 0.5F;
			float latchTranslateZ = (depthBlocks - 1) * 16.0F;
			BlockPos texturePos = closestExistingPosition(positions, localToWorld(root, (bounds.minX() + bounds.maxX()) / 2, bounds.maxY(), bounds.maxZ(), facing));

			return new GroupHull(
					anchor.getX() - root.getX(),
					anchor.getY() - root.getY(),
					anchor.getZ() - root.getZ(),
					visualWidth,
					visualDepth,
					bodyHeight,
					lidPivotY,
					latchTranslateX,
					latchTranslateZ,
					texturePos
			);
		}
	}

	private record LocalBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		private static LocalBounds from(List<BlockPos> positions, BlockPos root, Direction facing) {
			if (positions.isEmpty()) {
				return new LocalBounds(0, 0, 0, 0, 0, 0);
			}

			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (BlockPos pos : positions) {
				int localX = localX(pos, root, facing);
				int localZ = localZ(pos, root, facing);
				minX = Math.min(minX, localX);
				minY = Math.min(minY, pos.getY() - root.getY());
				minZ = Math.min(minZ, localZ);
				maxX = Math.max(maxX, localX);
				maxY = Math.max(maxY, pos.getY() - root.getY());
				maxZ = Math.max(maxZ, localZ);
			}
			return new LocalBounds(minX, minY, minZ, maxX, maxY, maxZ);
		}
	}

	private static int localX(BlockPos pos, BlockPos root, Direction facing) {
		Direction localEast = facing.rotateYCounterclockwise();
		int dx = pos.getX() - root.getX();
		int dz = pos.getZ() - root.getZ();
		return dx * localEast.getOffsetX() + dz * localEast.getOffsetZ();
	}

	private static int localZ(BlockPos pos, BlockPos root, Direction facing) {
		int dx = pos.getX() - root.getX();
		int dz = pos.getZ() - root.getZ();
		return dx * facing.getOffsetX() + dz * facing.getOffsetZ();
	}

	private static BlockPos localToWorld(BlockPos root, int localX, int localY, int localZ, Direction facing) {
		Direction localEast = facing.rotateYCounterclockwise();
		return root.add(
				localEast.getOffsetX() * localX + facing.getOffsetX() * localZ,
				localY,
				localEast.getOffsetZ() * localX + facing.getOffsetZ() * localZ
		);
	}

	private static BlockPos closestExistingPosition(Collection<BlockPos> positions, BlockPos target) {
		return positions.stream()
				.min(Comparator
						.comparingInt((BlockPos pos) -> manhattanDistance(pos, target))
						.thenComparingInt(BlockPos::getY)
						.thenComparingInt(BlockPos::getZ)
						.thenComparingInt(BlockPos::getX))
				.orElse(target);
	}

	private static int manhattanDistance(BlockPos first, BlockPos second) {
		return Math.abs(first.getX() - second.getX()) + Math.abs(first.getY() - second.getY()) + Math.abs(first.getZ() - second.getZ());
	}
}
