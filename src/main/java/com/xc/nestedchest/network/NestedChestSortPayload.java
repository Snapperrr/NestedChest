package com.xc.nestedchest.network;

import com.xc.nestedchest.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record NestedChestSortPayload(List<Integer> path, int mode, boolean ascending) implements CustomPayload {
	public static final Id<NestedChestSortPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "nested_sort"));
	public static final PacketCodec<ByteBuf, List<Integer>> PATH_CODEC = PacketCodecs.VAR_INT
			.collect(PacketCodecs.toList())
			.xmap(List::copyOf, ArrayList::new);
	public static final PacketCodec<RegistryByteBuf, NestedChestSortPayload> CODEC = PacketCodec.tuple(
			PATH_CODEC, NestedChestSortPayload::path,
			PacketCodecs.VAR_INT, NestedChestSortPayload::mode,
			PacketCodecs.BOOL, NestedChestSortPayload::ascending,
			NestedChestSortPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
