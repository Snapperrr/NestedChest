package com.xc.nestedchest.storage;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.xc.nestedchest.NestedChestMod;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.collection.DefaultedList;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NestedChestStorage {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String CUSTOM_ID_KEY = "nestedchest_id";
	private static final int STORAGE_VERSION = 1;
	private static final Map<Path, NestedChestStorage> INSTANCES = new HashMap<>();

	private final Path databasePath;
	private final Connection connection;
	private final RegistryWrapper.WrapperLookup registryLookup;
	// 热路径缓存最近读写的页面，真实权威数据仍在 SQLite 中。
	private final Map<String, DefaultedList<ItemStack>> stackCache = new HashMap<>();

	private NestedChestStorage(Path databasePath, Connection connection, RegistryWrapper.WrapperLookup registryLookup) {
		this.databasePath = databasePath;
		this.connection = connection;
		this.registryLookup = registryLookup;
	}

	public static synchronized NestedChestStorage get(MinecraftServer server) {
		Path database = server.getSavePath(WorldSavePath.ROOT).resolve(NestedChestMod.MOD_ID).resolve("nested_chests.sqlite");
		Path normalized = database.toAbsolutePath().normalize();
		return INSTANCES.computeIfAbsent(normalized, path -> open(path, server.getRegistryManager()));
	}

	public static synchronized void closeAll() {
		for (NestedChestStorage storage : INSTANCES.values()) {
			storage.close();
		}
		INSTANCES.clear();
	}

	public static synchronized void checkpointAll() {
		for (NestedChestStorage storage : INSTANCES.values()) {
			storage.checkpoint(false);
		}
	}

	public static synchronized void checkpointAllTruncate() {
		for (NestedChestStorage storage : INSTANCES.values()) {
			storage.checkpoint(true);
		}
	}

	private static NestedChestStorage open(Path database, RegistryWrapper.WrapperLookup registryLookup) {
		try {
			Files.createDirectories(database.getParent());
			Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
			NestedChestStorage storage = new NestedChestStorage(database, connection, registryLookup);
			storage.initialize();
			return storage;
		} catch (Exception e) {
			throw new IllegalStateException("Unable to open nested chest database at " + database, e);
		}
	}

	private void initialize() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			// WAL + FULL 同步牺牲一点写入速度，换取崩溃时更可靠的恢复能力。
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("PRAGMA synchronous=FULL");
			statement.execute("PRAGMA foreign_keys=ON");
			statement.execute("PRAGMA busy_timeout=5000");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS metadata (
						key TEXT PRIMARY KEY,
						value TEXT NOT NULL
					)
					""");
			statement.execute("""
					CREATE TABLE IF NOT EXISTS nested_chest_slots (
						chest_id TEXT NOT NULL,
						slot INTEGER NOT NULL,
						stack_nbt TEXT NOT NULL,
						updated_at INTEGER NOT NULL,
						PRIMARY KEY (chest_id, slot)
					)
					""");
			// PRIMARY KEY(chest_id, slot) 就是本模组的分页索引：按箱子 ID 精确读取 27 个槽位。
			statement.execute("INSERT OR REPLACE INTO metadata(key, value) VALUES ('schema_version', '" + STORAGE_VERSION + "')");
		}
	}

	public synchronized DefaultedList<ItemStack> getStacks(ItemStack stack) {
		// 旧版本写在物品 NBT 里的容器内容，在第一次读取时迁移进数据库。
		migrateLegacyContainer(stack);
		String id = getOrCreateId(stack);
		return loadStacks(id);
	}

	public synchronized void setStacks(ItemStack stack, DefaultedList<ItemStack> stacks) {
		String id = getOrCreateId(stack);
		saveStacks(id, stacks);
		stack.remove(DataComponentTypes.CONTAINER);
	}

	public synchronized String getStorageId(ItemStack stack) {
		return getId(stack);
	}

	public synchronized Path getDatabasePath() {
		return databasePath;
	}

	public synchronized long getDatabaseSizeBytes() {
		try {
			return Files.exists(databasePath) ? Files.size(databasePath) : 0L;
		} catch (Exception e) {
			LOGGER.warn("Unable to read nested chest database size", e);
			return -1L;
		}
	}

	public synchronized int getStoredSlotCount(String id) {
		if (id.isBlank()) {
			return 0;
		}
		try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM nested_chest_slots WHERE chest_id = ?")) {
			statement.setString(1, id);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getInt(1) : 0;
			}
		} catch (SQLException e) {
			LOGGER.warn("Unable to count slots for nested chest {}", id, e);
			return -1;
		}
	}

	public synchronized int getTotalStoredSlotRows() {
		try (Statement statement = connection.createStatement();
			 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM nested_chest_slots")) {
			return resultSet.next() ? resultSet.getInt(1) : 0;
		} catch (SQLException e) {
			LOGGER.warn("Unable to count nested chest slot rows", e);
			return -1;
		}
	}

	public synchronized DebugTreeStats inspectTree(ItemStack stack, int maxDepth) {
		String id = getId(stack);
		if (id.isBlank()) {
			return DebugTreeStats.empty();
		}
		return inspectTree(id, 1, Math.max(1, maxDepth), new HashSet<>());
	}

	public synchronized void removeStorageId(ItemStack stack) {
		NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (customData == null || !customData.contains(CUSTOM_ID_KEY)) {
			return;
		}

		NbtCompound nbt = customData.copyNbt();
		nbt.remove(CUSTOM_ID_KEY);
		if (nbt.isEmpty()) {
			stack.remove(DataComponentTypes.CUSTOM_DATA);
		} else {
			stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
		}
	}

	public synchronized void checkpoint() {
		checkpoint(true);
	}

	private synchronized void checkpoint(boolean truncate) {
		try (Statement statement = connection.createStatement()) {
			// TRUNCATE 用在关闭世界时收尾，PASSIVE 用在运行中减少卡顿。
			statement.execute("PRAGMA wal_checkpoint(" + (truncate ? "TRUNCATE" : "PASSIVE") + ")");
		} catch (SQLException e) {
			LOGGER.warn("Unable to checkpoint nested chest database", e);
		}
	}

	private synchronized void close() {
		checkpoint(true);
		try {
			connection.close();
		} catch (SQLException e) {
			LOGGER.warn("Unable to close nested chest database", e);
		}
	}

	private void migrateLegacyContainer(ItemStack stack) {
		ContainerComponent component = stack.get(DataComponentTypes.CONTAINER);
		if (component == null || component == ContainerComponent.DEFAULT) {
			return;
		}

		// 迁移后立刻删除原版 CONTAINER 组件，避免同一份内容同时存在于 NBT 和 SQLite。
		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY);
		component.copyTo(stacks);
		saveStacks(getOrCreateId(stack), stacks);
		stack.remove(DataComponentTypes.CONTAINER);
	}

	private DefaultedList<ItemStack> loadStacks(String id) {
		DefaultedList<ItemStack> cached = stackCache.get(id);
		if (cached != null) {
			// 返回副本，避免调用方直接改掉缓存里的权威视图。
			return copyStacks(cached, NestedChestMod.NESTED_CHEST_SIZE);
		}

		DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY);
		try (PreparedStatement statement = connection.prepareStatement("SELECT slot, stack_nbt FROM nested_chest_slots WHERE chest_id = ? ORDER BY slot")) {
			statement.setString(1, id);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					int slot = resultSet.getInt("slot");
					if (slot < 0 || slot >= stacks.size()) {
						continue;
					}
					stacks.set(slot, decodeStack(resultSet.getString("stack_nbt")));
				}
			}
		} catch (SQLException e) {
			LOGGER.error("Unable to load nested chest {}", id, e);
		}
		stackCache.put(id, copyStacks(stacks, NestedChestMod.NESTED_CHEST_SIZE));
		return copyStacks(stacks, NestedChestMod.NESTED_CHEST_SIZE);
	}

	private void saveStacks(String id, DefaultedList<ItemStack> stacks) {
		savePreparedStacks(id, prepareStoredStacks(stacks));
	}

	private DefaultedList<ItemStack> prepareStoredStacks(DefaultedList<ItemStack> stacks) {
		DefaultedList<ItemStack> prepared = DefaultedList.ofSize(Math.min(stacks.size(), NestedChestMod.NESTED_CHEST_SIZE), ItemStack.EMPTY);
		Set<String> seenChestIds = new HashSet<>();
		for (int slot = 0; slot < prepared.size(); slot++) {
			prepared.set(slot, prepareStoredStack(stacks.get(slot), seenChestIds));
		}
		return prepared;
	}

	private void savePreparedStacks(String id, DefaultedList<ItemStack> stacks) {
		try {
			try (Statement transaction = connection.createStatement();
				 PreparedStatement delete = connection.prepareStatement("DELETE FROM nested_chest_slots WHERE chest_id = ?");
				 PreparedStatement insert = connection.prepareStatement("INSERT INTO nested_chest_slots(chest_id, slot, stack_nbt, updated_at) VALUES (?, ?, ?, ?)")) {
				// 删除旧页再批量写入新页必须在一个事务里完成，否则崩溃可能留下半页数据。
				transaction.execute("BEGIN IMMEDIATE");
				delete.setString(1, id);
				delete.executeUpdate();

				long now = System.currentTimeMillis();
				for (int slot = 0; slot < Math.min(stacks.size(), NestedChestMod.NESTED_CHEST_SIZE); slot++) {
					ItemStack stack = stacks.get(slot);
					if (stack.isEmpty()) {
						continue;
					}
					insert.setString(1, id);
					insert.setInt(2, slot);
					insert.setString(3, encodeStack(stack));
					insert.setLong(4, now);
					insert.addBatch();
				}
				insert.executeBatch();
				transaction.execute("COMMIT");
				stackCache.put(id, copyStacks(stacks, NestedChestMod.NESTED_CHEST_SIZE));
			} catch (Exception e) {
				try (Statement rollback = connection.createStatement()) {
					rollback.execute("ROLLBACK");
				} catch (SQLException rollbackError) {
					e.addSuppressed(rollbackError);
				}
				throw e;
			}
		} catch (Exception e) {
			LOGGER.error("Unable to save nested chest {}", id, e);
		}
	}

	private ItemStack prepareStoredStack(ItemStack stack, Set<String> seenChestIds) {
		if (!NestedChestMod.isChestItem(stack)) {
			return stack.copy();
		}

		ItemStack copy = stack.copy();
		if (copy.getCount() != 1) {
			// 只有单个箱子能作为目录箱；成堆箱子不应该携带目录 ID。
			removeStorageId(copy);
			copy.remove(DataComponentTypes.CONTAINER);
			return copy;
		}

		String existingId = getId(copy);
		if (!existingId.isBlank() && !seenChestIds.add(existingId)) {
			// 同一页里不能出现两个指向同一数据库页面的箱子，否则移动一个会像复制另一个。
			removeStorageId(copy);
			copy.remove(DataComponentTypes.CONTAINER);
			return copy;
		}

		migrateLegacyContainer(copy);
		String id = getId(copy);
		if (existingId.isBlank() && !id.isBlank() && !seenChestIds.add(id)) {
			// 旧 NBT 迁移后也要重新查重，防止两个旧箱子迁移到同一个页面引用。
			removeStorageId(copy);
		}
		copy.remove(DataComponentTypes.CONTAINER);
		return copy;
	}

	private DefaultedList<ItemStack> copyStacks(DefaultedList<ItemStack> stacks, int size) {
		DefaultedList<ItemStack> copy = DefaultedList.ofSize(size, ItemStack.EMPTY);
		for (int slot = 0; slot < size && slot < stacks.size(); slot++) {
			copy.set(slot, stacks.get(slot).copy());
		}
		return copy;
	}

	private String getOrCreateId(ItemStack stack) {
		String existing = getId(stack);
		if (!existing.isBlank()) {
			return existing;
		}
		// ItemStack 上只保存很小的 UUID，真正的槽位内容存进 SQLite，避免 NBT 爆炸。
		String created = UUID.randomUUID().toString();
		setId(stack, created);
		return created;
	}

	private void setId(ItemStack stack, String id) {
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putString(CUSTOM_ID_KEY, id));
	}

	private String getId(ItemStack stack) {
		NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (customData == null || !customData.contains(CUSTOM_ID_KEY)) {
			return "";
		}
		return customData.copyNbt().getString(CUSTOM_ID_KEY);
	}

	private DebugTreeStats inspectTree(String id, int depth, int maxDepth, Set<String> visited) {
		if (id.isBlank() || !visited.add(id)) {
			return DebugTreeStats.empty();
		}

		// 调试命令用这个方法估算压力树规模，同时用 visited 防止循环引用把检查卡死。
		int slots = getStoredSlotCount(id);
		DebugTreeStats stats = new DebugTreeStats(1, Math.max(0, slots), depth, 0, depth >= maxDepth);
		if (depth >= maxDepth) {
			return stats;
		}

		DefaultedList<ItemStack> stacks = loadStacks(id);
		for (ItemStack stack : stacks) {
			if (stack.contains(DataComponentTypes.CONTAINER)) {
				stats = stats.withAdditionalLegacyContainer();
			}
			if (!NestedChestMod.isChestItem(stack)) {
				continue;
			}
			String childId = getId(stack);
			if (!childId.isBlank()) {
				stats = stats.plus(inspectTree(childId, depth + 1, maxDepth, visited));
			}
		}
		return stats;
	}

	private String encodeStack(ItemStack stack) {
		NbtElement encoded = stack.encodeAllowEmpty(registryLookup);
		return encoded.toString();
	}

	private ItemStack decodeStack(String encoded) {
		try {
			NbtCompound nbt = StringNbtReader.parse(encoded);
			return ItemStack.fromNbtOrEmpty(registryLookup, nbt);
		} catch (CommandSyntaxException e) {
			LOGGER.warn("Unable to parse stored nested chest stack: {}", encoded, e);
			return ItemStack.EMPTY;
		}
	}

	public record DebugTreeStats(int chestPages, int storedSlots, int maxDepth, int legacyContainerStacks, boolean truncated) {
		private static DebugTreeStats empty() {
			return new DebugTreeStats(0, 0, 0, 0, false);
		}

		private DebugTreeStats plus(DebugTreeStats other) {
			return new DebugTreeStats(
					chestPages + other.chestPages,
					storedSlots + other.storedSlots,
					Math.max(maxDepth, other.maxDepth),
					legacyContainerStacks + other.legacyContainerStacks,
					truncated || other.truncated
			);
		}

		private DebugTreeStats withAdditionalLegacyContainer() {
			return new DebugTreeStats(chestPages, storedSlots, maxDepth, legacyContainerStacks + 1, truncated);
		}
	}
}
