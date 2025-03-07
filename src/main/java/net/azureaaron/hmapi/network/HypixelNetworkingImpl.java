package net.azureaaron.hmapi.network;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;
import net.azureaaron.hmapi.events.HypixelPacketEvents;
import net.azureaaron.hmapi.network.packet.c2s.HypixelC2SPacket;
import net.azureaaron.hmapi.network.packet.c2s.PartyInfoC2SPacket;
import net.azureaaron.hmapi.network.packet.c2s.PlayerInfoC2SPacket;
import net.azureaaron.hmapi.network.packet.c2s.RegisterC2SPacket;
import net.azureaaron.hmapi.network.packet.s2c.ErrorS2CPacket;
import net.azureaaron.hmapi.network.packet.s2c.HelloS2CPacket;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.LocationUpdateS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.PlayerInfoS2CPacket;
import net.azureaaron.hmapi.network.packet.v2.s2c.PartyInfoS2CPacket;
import net.azureaaron.hmapi.utils.PacketCodecUtils;
import net.azureaaron.hmapi.utils.PacketSendResult;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.ApiStatus;

import java.util.stream.Collectors;

@ApiStatus.Internal
public class HypixelNetworkingImpl {
	private static final long COOLDOWN = 1000L;
	private static final Object2LongMap<CustomPayload.Id<?>> COOLDOWNS = Object2LongMaps.synchronize(new Object2LongOpenHashMap<>());

	static <T extends HypixelC2SPacket> PacketSendResult sendPacket(T payload, boolean bypassCooldown) {
		if ((System.currentTimeMillis() + COOLDOWN > COOLDOWNS.computeIfAbsent(payload.getId(), _id -> 0L)) || bypassCooldown) {
			ClientPlayNetworking.send(payload);
			COOLDOWNS.put(payload.getId(), System.currentTimeMillis());

			return PacketSendResult.success();
		}

		return PacketSendResult.onCooldown(COOLDOWNS.getLong(payload.getId()) - System.currentTimeMillis());
	}

	private static void sendEventRegistrations() {
		if (!HypixelNetworking.REGISTERED_EVENTS.isEmpty()) {
			Object2IntMap<Identifier> packetsToRegisterFor = HypixelNetworking.REGISTERED_EVENTS.object2IntEntrySet().stream()
					.collect(Collectors.toMap(e -> e.getKey().id(), Object2IntMap.Entry::getIntValue, (a, b) -> a > b ? a : b, Object2IntOpenHashMap::new));

			sendPacket(new RegisterC2SPacket(1, packetsToRegisterFor), true);
		}
	}

	public static void bootstrap() {
		registerC2SPacket(RegisterC2SPacket.ID, RegisterC2SPacket.PACKET_CODEC);
		registerC2SPacket(PartyInfoC2SPacket.ID, RegisterC2SPacket.PACKET_CODEC);
		registerC2SPacket(PlayerInfoC2SPacket.ID, PlayerInfoC2SPacket.PACKET_CODEC);
		registerS2CPacket(
				HelloS2CPacket.ID,
				HypixelPacketEvents.HELLO,
				PacketCodecUtils.dispatchSafely(
						HelloS2CPacket.PACKET_CODEC,
						ErrorS2CPacket.PACKET_CODEC.apply(HelloS2CPacket.ID)
				)
		);
		registerS2CPacket(
				PartyInfoS2CPacket.ID,
				HypixelPacketEvents.PARTY_INFO,
				PacketCodecUtils.dispatchHypixel(
						Util.make(
								new Int2ObjectOpenHashMap<>(),
								map -> map.put(2, PartyInfoS2CPacket.PACKET_CODEC)
						),
						ErrorS2CPacket.PACKET_CODEC.apply(PartyInfoS2CPacket.ID)
				)
		);
		registerS2CPacket(
				PlayerInfoS2CPacket.ID,
				HypixelPacketEvents.PLAYER_INFO		,
				PacketCodecUtils.dispatchHypixel(
						Util.make(
								new Int2ObjectOpenHashMap<>(),
								map -> map.put(1, PlayerInfoS2CPacket.PACKET_CODEC)
						),
						ErrorS2CPacket.PACKET_CODEC.apply(PlayerInfoS2CPacket.ID)
				)
		);
		registerS2CPacket(
				LocationUpdateS2CPacket.ID,
				HypixelPacketEvents.LOCATION_UPDATE,
				PacketCodecUtils.dispatchHypixel(
						Util.make(
								new Int2ObjectOpenHashMap<>(),
								map -> map.put(1, LocationUpdateS2CPacket.PACKET_CODEC)
						),
						ErrorS2CPacket.PACKET_CODEC.apply(LocationUpdateS2CPacket.ID)
				)
		);
		// Send initial edvent registration
		HypixelPacketEvents.HELLO.register(p -> sendEventRegistrations());
	}

	@SuppressWarnings("unchecked")
    private static void registerS2CPacket(
			CustomPayload.Id<HypixelS2CPacket> id,
			Event<HypixelPacketEvents.PacketCallback> event,
			PacketCodec<RegistryByteBuf, ? extends HypixelS2CPacket> codec
	) {
		PayloadTypeRegistry.playS2C().register(
				id,
				(PacketCodec<RegistryByteBuf, HypixelS2CPacket>) codec
		);
		ClientPlayNetworking.registerGlobalReceiver(id, (packet, context) ->
				context.client().execute(() -> event.invoker().onPacket(packet)));
	}

	@SuppressWarnings("unchecked")
	private static void registerC2SPacket(
			CustomPayload.Id<HypixelC2SPacket> id,
			PacketCodec<RegistryByteBuf, ? extends HypixelC2SPacket> codec
	) {
		PayloadTypeRegistry.playC2S().register(
				id,
				(PacketCodec<RegistryByteBuf, HypixelC2SPacket>) codec
		);

		// TODO: if you want functionality here to allow events for serverbound packets,
		//  we need to make a breaking change to the api. However, this is left as a
		//  demonstration that you can add this functionality if wanted/needed.
		//ServerPlayNetworking.registerGlobalReceiver(id, (payload, context) ->
		//		context.server().execute(() -> event.invoker().onPacket(payload)));
	}
}
