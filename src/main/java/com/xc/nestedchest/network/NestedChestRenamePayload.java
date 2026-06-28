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

public record NestedChestRenamePayload(List<Integer> path, String name) implements CustomPayload {
	public static final Id<NestedChestRenamePayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "nested_rename"));
	public static final PacketCodec<ByteBuf, List<Integer>> PATH_CODEC = PacketCodecs.VAR_INT
			.collect(PacketCodecs.toList())
			.xmap(List::copyOf, ArrayList::new);
	public static final PacketCodec<RegistryByteBuf, NestedChestRenamePayload> CODEC = PacketCodec.tuple(
			PATH_CODEC, NestedChestRenamePayload::path,
			PacketCodecs.string(NestedChestMod.MAX_CHEST_NAME_LENGTH), NestedChestRenamePayload::name,
			NestedChestRenamePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
