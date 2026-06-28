package com.xc.nestedchest.network;

import com.xc.nestedchest.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.List;

public record NestedChestSyncPayload(List<Integer> path, DefaultedList<ItemStack> stacks) implements CustomPayload {
	public static final Id<NestedChestSyncPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "nested_sync"));
	public static final PacketCodec<ByteBuf, List<Integer>> PATH_CODEC = PacketCodecs.VAR_INT
			.collect(PacketCodecs.toList())
			.xmap(List::copyOf, ArrayList::new);
	public static final PacketCodec<RegistryByteBuf, DefaultedList<ItemStack>> STACKS_CODEC = ItemStack.OPTIONAL_PACKET_CODEC
			.collect(PacketCodecs.toList(NestedChestMod.MAX_NESTED_CHEST_SIZE))
			.xmap(
					list -> {
						DefaultedList<ItemStack> stacks = DefaultedList.ofSize(Math.min(list.size(), NestedChestMod.MAX_NESTED_CHEST_SIZE), ItemStack.EMPTY);
						for (int i = 0; i < Math.min(list.size(), stacks.size()); i++) {
							stacks.set(i, list.get(i));
						}
						return stacks;
					},
					stacks -> stacks
			);
	public static final PacketCodec<RegistryByteBuf, NestedChestSyncPayload> CODEC = PacketCodec.tuple(
			PATH_CODEC, NestedChestSyncPayload::path,
			STACKS_CODEC, NestedChestSyncPayload::stacks,
			NestedChestSyncPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
