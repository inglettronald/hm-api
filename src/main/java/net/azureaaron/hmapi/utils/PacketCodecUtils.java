package net.azureaaron.hmapi.utils;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * Utilities class for {@link PacketCodec PacketCodecs}.
 */
@ApiStatus.Internal
public class PacketCodecUtils {

	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * @return A codec that decodes the {@link PacketByteBuf} in line with the packet format of Hypixel's Mod API and the requirements of my implementation.
	 */
	public static <B extends RegistryByteBuf> PacketCodec<B, ? extends HypixelS2CPacket> dispatchHypixel(
			Int2ObjectMap<PacketCodec<B, ? extends HypixelS2CPacket>> primaryPacketCodecs,
			PacketCodec<B, ? extends HypixelS2CPacket> errorCodec
	) {
		return new PacketCodec<B, HypixelS2CPacket>() {

			@Override
			public HypixelS2CPacket decode(B buf) {
				Boolean success = buf.readBoolean();
				int version = success ? buf.readVarInt() : -1;

				return switch (success) {
					case Boolean s when s && primaryPacketCodecs != null && primaryPacketCodecs.containsKey(version) -> decodeInternal(buf, primaryPacketCodecs.get(version));
					case Boolean s when !s -> errorCodec.decode(buf);

					default -> {
						// When Migrating HypixelCustomPayloadCodecs to a fabric based implementation in
						// HypixelNetworkingImpl, I accidentally had some versions swapped around. However, this had
						// the unintended effect of surfacing a bug! the same byte reading issue presents itself when
						// providing this packet if we don't read anything. So, we read and discard the bytes.
						//  - Dulkir
						readAllBytes(buf, false);
						yield HypixelS2CPacket.NOP;
					}
				};
			}

			private HypixelS2CPacket decodeInternal(B buf, PacketCodec<B, ? extends HypixelS2CPacket> packetCodec) {
				HypixelS2CPacket packet = packetCodec.decode(buf);

				//There was a bug where Hypixel would send a bunch of extra bytes with a value of 0 so this was a work around to avoid being kicked from the server
				//for not fully reading the packet
				//Its also good practice if a breaking change is added
				readAllBytes(buf, true);

				return packet;
			}

			//Since this is only used for S2C packets we don't really need to care about what it does
			//so we will just throw an exception
			@Override
			public void encode(B buf, HypixelS2CPacket value) {
				throw new UnsupportedOperationException();
			}
		};
	}

	public static <B extends RegistryByteBuf, T> PacketCodec<B, T> dispatchConditionally(PacketCodec<B, T> ifTrue, PacketCodec<B, T> ifFalse) {
		return new PacketCodec<B, T>() {

			@Override
			public T decode(B buf) {
				return buf.readBoolean() ? ifTrue.decode(buf) : ifFalse.decode(buf);
			}

			//Only used for S2C
			@Override
			public void encode(B buf, T value) {
				throw new UnsupportedOperationException();
			}
		};
	}

	public static <B extends RegistryByteBuf> PacketCodec<B, ? extends HypixelS2CPacket> dispatchSafely(PacketCodec<B, ? extends HypixelS2CPacket> packetCodec, PacketCodec<B, ? extends HypixelS2CPacket> errorCodec) {
		return new PacketCodec<B, HypixelS2CPacket>() {

			@Override
			public HypixelS2CPacket decode(B buf) {
				try {
					Boolean success = buf.readBoolean();

					return switch (success) {
						case Boolean s when s -> {
							HypixelS2CPacket packet = packetCodec.decode(buf);

							//This is used only for the HelloS2CPacket right now, which can return extra bytes if necessary in the future
							readAllBytes(buf, false);

							yield packet;
						}

						case Boolean s when !s -> errorCodec.decode(buf);

						default ->  {
							readAllBytes(buf, false);
							yield HypixelS2CPacket.NOP;
						}
					};
				} catch (Throwable t) {
					LOGGER.error("[HM API] Encountered an unexpected exception while decoding a packet!", t);
				}

				return HypixelS2CPacket.NOP;
			}

			//Only used for S2C
			@Override
			public void encode(B buf, HypixelS2CPacket value) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private static void readAllBytes(PacketByteBuf buf, boolean logWarning) {
		buf.readerIndex(buf.writerIndex());
		if (logWarning) {
			LOGGER.info("[HM API] Discarding extra bytes for a payload.");
		}
	}
}
