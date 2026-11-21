package com.wandou.betterarmortrim.mixin;

import com.wandou.betterarmortrim.BetterArmorTrim;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 让「我们的 header」和别的 tab 类插件（TabTPS 等）共存：
 * 不自己发包，而是在任何 tab 包出站时把 header 换成我们的、footer 原样保留。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class TabListMixin {

    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> betterarmortrim$rewriteTabList(Packet<?> packet) {
        if (packet == null || packet.getClass() != ClientboundTabListPacket.class) {
            return packet;
        }
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return packet;
        }
        return BetterArmorTrim.rewriteTabPacket(listener.player.getUUID(),
                (ClientboundTabListPacket) packet);
    }
}
