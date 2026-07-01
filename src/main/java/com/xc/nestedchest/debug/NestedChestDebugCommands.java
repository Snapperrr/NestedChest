package com.xc.nestedchest.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xc.nestedchest.NestedChestMod;
import com.xc.nestedchest.storage.NestedChestStorage;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class NestedChestDebugCommands {
	private static final int DEFAULT_PAGES = WritableBookContentComponent.MAX_PAGE_COUNT;
	private static final int DEFAULT_CHARS_PER_PAGE = WritableBookContentComponent.MAX_PAGE_LENGTH;
	private static final int DEFAULT_CHAIN_DEPTH = 32;
	private static final int DEFAULT_BOOKS_PER_CHEST = 4;
	private static final int DEFAULT_INSPECT_DEPTH = 512;
	private static final int MAX_CHAIN_DEPTH = 512;

	private NestedChestDebugCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("nestedchestdebug")
				.requires(source -> source.hasPermissionLevel(2))
				.then(literal("give_full_book")
						.executes(context -> giveFullBook(context.getSource(), DEFAULT_PAGES, DEFAULT_CHARS_PER_PAGE, 1))
						.then(argument("pages", IntegerArgumentType.integer(1, WritableBookContentComponent.MAX_PAGE_COUNT))
								.executes(context -> giveFullBook(
										context.getSource(),
										IntegerArgumentType.getInteger(context, "pages"),
										DEFAULT_CHARS_PER_PAGE,
										1
								))
								.then(argument("charsPerPage", IntegerArgumentType.integer(1, WritableBookContentComponent.MAX_PAGE_LENGTH))
										.executes(context -> giveFullBook(
												context.getSource(),
												IntegerArgumentType.getInteger(context, "pages"),
												IntegerArgumentType.getInteger(context, "charsPerPage"),
												1
										))
										.then(argument("count", IntegerArgumentType.integer(1, 64))
												.executes(context -> giveFullBook(
														context.getSource(),
														IntegerArgumentType.getInteger(context, "pages"),
														IntegerArgumentType.getInteger(context, "charsPerPage"),
														IntegerArgumentType.getInteger(context, "count")
												))))))
				.then(literal("make_chain")
						.executes(context -> makeChain(
								context.getSource(),
								DEFAULT_CHAIN_DEPTH,
								DEFAULT_BOOKS_PER_CHEST,
								20,
								512
						))
						.then(argument("depth", IntegerArgumentType.integer(1, MAX_CHAIN_DEPTH))
								.executes(context -> makeChain(
										context.getSource(),
										IntegerArgumentType.getInteger(context, "depth"),
										DEFAULT_BOOKS_PER_CHEST,
										20,
										512
								))
								.then(argument("booksPerChest", IntegerArgumentType.integer(0, NestedChestMod.NESTED_CHEST_SIZE))
										.executes(context -> makeChain(
												context.getSource(),
												IntegerArgumentType.getInteger(context, "depth"),
												IntegerArgumentType.getInteger(context, "booksPerChest"),
												20,
												512
										))
										.then(argument("pages", IntegerArgumentType.integer(1, WritableBookContentComponent.MAX_PAGE_COUNT))
												.executes(context -> makeChain(
														context.getSource(),
														IntegerArgumentType.getInteger(context, "depth"),
														IntegerArgumentType.getInteger(context, "booksPerChest"),
														IntegerArgumentType.getInteger(context, "pages"),
														512
												))
												.then(argument("charsPerPage", IntegerArgumentType.integer(1, WritableBookContentComponent.MAX_PAGE_LENGTH))
														.executes(context -> makeChain(
																context.getSource(),
																IntegerArgumentType.getInteger(context, "depth"),
																IntegerArgumentType.getInteger(context, "booksPerChest"),
																IntegerArgumentType.getInteger(context, "pages"),
																IntegerArgumentType.getInteger(context, "charsPerPage")
														)))))))
				.then(literal("inspect_hand")
						.executes(context -> inspectHand(context.getSource(), DEFAULT_INSPECT_DEPTH))
						.then(argument("maxDepth", IntegerArgumentType.integer(1, 4096))
								.executes(context -> inspectHand(
										context.getSource(),
										IntegerArgumentType.getInteger(context, "maxDepth")
								))))
				.then(literal("checkpoint")
						.executes(context -> checkpoint(context.getSource()))));
	}

	private static int giveFullBook(ServerCommandSource source, int pages, int charsPerPage, int count) throws CommandSyntaxException {
		ServerPlayerEntity player = source.getPlayerOrThrow();
		for (int i = 0; i < count; i++) {
			player.getInventory().offerOrDrop(createFullBook(pages, charsPerPage, "NBT压力书 " + (i + 1)));
		}

		source.sendFeedback(() -> Text.literal("已生成 " + count + " 本书与笔，每本 " + pages + " 页，每页 " + charsPerPage + " 字符。"), false);
		return count;
	}

	private static int makeChain(ServerCommandSource source, int depth, int booksPerChest, int pages, int charsPerPage) throws CommandSyntaxException {
		ServerPlayerEntity player = source.getPlayerOrThrow();
		NestedChestStorage storage = NestedChestStorage.get(source.getServer());

		ItemStack child = ItemStack.EMPTY;
		int createdBooks = 0;
		for (int layer = depth; layer >= 1; layer--) {
			DefaultedList<ItemStack> stacks = DefaultedList.ofSize(NestedChestMod.NESTED_CHEST_SIZE, ItemStack.EMPTY);
			int startSlot = 0;
			if (!child.isEmpty()) {
				stacks.set(0, child);
				startSlot = 1;
			}

			int booksThisLayer = 0;
			for (int slot = startSlot; slot < stacks.size() && booksThisLayer < booksPerChest; slot++) {
				stacks.set(slot, createFullBook(pages, charsPerPage, "NBT压力书 L" + layer + "-" + booksThisLayer));
				booksThisLayer++;
				createdBooks++;
			}

			ItemStack chest = new ItemStack(Items.CHEST);
			chest.set(DataComponentTypes.CUSTOM_NAME, Text.literal("NBT压力目录箱 第" + layer + "层"));
			storage.setStacks(chest, stacks);
			child = chest;
		}

		player.getInventory().offerOrDrop(child);
		int topBytes = encodedBytes(child, source);
		String id = storage.getStorageId(child);
		NestedChestStorage.DebugTreeStats stats = storage.inspectTree(child, DEFAULT_INSPECT_DEPTH);
		final int totalBooks = createdBooks;
		source.sendFeedback(() -> Text.literal("已生成 " + depth + " 层箱中箱链，内含压力书 " + totalBooks + " 本。"), false);
		source.sendFeedback(() -> Text.literal("顶层箱子编码大小约 " + topBytes + " bytes，nestedchest_id=" + shortId(id) + "。"), false);
		source.sendFeedback(() -> Text.literal("SQLite 扫描：目录页 " + stats.chestPages() + " 个，非空槽位行 " + stats.storedSlots() + " 行，最大深度 " + stats.maxDepth() + "。"), false);
		source.sendFeedback(() -> Text.literal("如果顶层大小仍然只有几百字节且 CONTAINER 为否，就说明没有走递归 NBT 存储。"), false);
		return depth;
	}

	private static int inspectHand(ServerCommandSource source, int maxDepth) throws CommandSyntaxException {
		ServerPlayerEntity player = source.getPlayerOrThrow();
		ItemStack stack = player.getMainHandStack();
		if (stack.isEmpty()) {
			source.sendError(Text.literal("主手是空的，请先拿着要检查的箱子物品。"));
			return 0;
		}

		NestedChestStorage storage = NestedChestStorage.get(source.getServer());
		String id = storage.getStorageId(stack);
		int encodedBytes = encodedBytes(stack, source);
		boolean hasLegacyContainer = stack.contains(DataComponentTypes.CONTAINER);
		boolean hasCustomData = stack.contains(DataComponentTypes.CUSTOM_DATA);
		NestedChestStorage.DebugTreeStats stats = storage.inspectTree(stack, maxDepth);

		source.sendFeedback(() -> Text.literal("主手物品：" + stack.getName().getString()), false);
		source.sendFeedback(() -> Text.literal("顶层 ItemStack 编码大小约 " + encodedBytes + " bytes。"), false);
		source.sendFeedback(() -> Text.literal("顶层 CONTAINER 组件：" + yesNo(hasLegacyContainer) + "；CUSTOM_DATA：" + yesNo(hasCustomData) + "；nestedchest_id=" + shortId(id) + "。"), false);
		source.sendFeedback(() -> Text.literal("SQLite 数据库：" + storage.getDatabasePath()), false);
		source.sendFeedback(() -> Text.literal("SQLite 文件大小约 " + storage.getDatabaseSizeBytes() + " bytes，总槽位行 " + storage.getTotalStoredSlotRows() + " 行。"), false);
		source.sendFeedback(() -> Text.literal("从主手箱子递归扫描：目录页 " + stats.chestPages() + " 个，非空槽位行 " + stats.storedSlots() + " 行，最大深度 " + stats.maxDepth() + "。"), false);
		source.sendFeedback(() -> Text.literal("递归扫描到的旧 CONTAINER 残留：" + stats.legacyContainerStacks() + " 个" + (stats.truncated() ? "，结果已被 maxDepth 截断。" : "。")), false);

		if (NestedChestMod.isChestItem(stack) && !id.isBlank() && !hasLegacyContainer && stats.legacyContainerStacks() == 0) {
			source.sendFeedback(() -> Text.literal("结论：这个箱中箱走的是 SQLite ID 存储路径，没有发现递归 CONTAINER NBT 残留。"), false);
		} else if (hasLegacyContainer || stats.legacyContainerStacks() > 0) {
			source.sendError(Text.literal("警告：发现旧 CONTAINER 数据，建议打开一次该目录箱子触发迁移后再检查。"));
		} else {
			source.sendFeedback(() -> Text.literal("结论：主手物品不是已绑定 SQLite 的箱中箱，或还没有写入过目录内容。"), false);
		}
		return stats.chestPages();
	}

	private static int checkpoint(ServerCommandSource source) {
		NestedChestStorage.checkpointAllTruncate();
		source.sendFeedback(() -> Text.literal("已手动 checkpoint 所有 Nested Chest SQLite 数据库。"), false);
		return 1;
	}

	private static ItemStack createFullBook(int pages, int charsPerPage, String name) {
		List<RawFilteredPair<String>> bookPages = new ArrayList<>(pages);
		for (int page = 0; page < pages; page++) {
			bookPages.add(RawFilteredPair.of(createPageText(page, charsPerPage)));
		}

		ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
		stack.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(bookPages));
		return stack;
	}

	private static String createPageText(int page, int charsPerPage) {
		String pattern = "NBT_STRESS_PAGE_" + page + "_";
		StringBuilder builder = new StringBuilder(charsPerPage);
		while (builder.length() < charsPerPage) {
			builder.append(pattern);
		}
		return builder.substring(0, charsPerPage);
	}

	private static int encodedBytes(ItemStack stack, ServerCommandSource source) {
		NbtElement encoded = stack.encodeAllowEmpty(source.getServer().getRegistryManager());
		return encoded.toString().getBytes(StandardCharsets.UTF_8).length;
	}

	private static String yesNo(boolean value) {
		return value ? "是" : "否";
	}

	private static String shortId(String id) {
		if (id == null || id.isBlank()) {
			return "无";
		}
		return id.length() <= 8 ? id : id.substring(0, 8) + "...";
	}
}
