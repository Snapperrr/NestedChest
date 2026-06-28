package com.xc.nestedchest.network;

import com.xc.nestedchest.NestedChestMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record NestedChestClickPayload(List<Integer> path, int nestedSlot, int button, SlotActionType actionType) implements CustomPayload {
	public static final Id<NestedChestClickPayload> ID = new Id<>(Identifier.of(NestedChestMod.MOD_ID, "nested_click"));
	public static final PacketCodec<ByteBuf, List<Integer>> PATH_CODEC = PacketCodecs.VAR_INT
			.collect(PacketCodecs.toList())
			.xmap(List::copyOf, ArrayList::new);
	public static final PacketCodec<ByteBuf, SlotActionType> ACTION_TYPE_CODEC = PacketCodecs.indexed(
			index -> SlotActionType.values()[index],
			SlotActionType::ordinal
	);
	public static final PacketCodec<RegistryByteBuf, NestedChestClickPayload> CODEC = PacketCodec.tuple(
			PATH_CODEC, NestedChestClickPayload::path,
			PacketCodecs.VAR_INT, NestedChestClickPayload::nestedSlot,
			PacketCodecs.VAR_INT, NestedChestClickPayload::button,
			ACTION_TYPE_CODEC, NestedChestClickPayload::actionType,
			NestedChestClickPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
