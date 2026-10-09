package dev.doctor4t.wathe.communication;

import dev.doctor4t.wathe.cca.DebugDisguiseComponent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SentMessage;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端聊天身份伪装处理器。
 *
 * <p>原版聊天消息的发送者来自真实 ServerPlayer。这里在 ALLOW_CHAT_MESSAGE 阶段取消原广播，
 * 再用目标 UUID 创建无签名消息并逐个发送；聊天内容仍保持原文，只有发送者身份被替换。
 * 这样客户端聊天栏、点击玩家名等显示都会收到目标身份，而不会修改真实玩家档案。</p>
 */
public final class DebugIdentityCommunication {
    private static boolean registered;

    private DebugIdentityCommunication() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(DebugIdentityCommunication::handleChatMessage);
    }

    private static boolean handleChatMessage(@NotNull SignedMessage message,
                                             @NotNull ServerPlayerEntity sender,
                                             @NotNull MessageType.Parameters parameters) {
        DebugDisguiseComponent component = DebugDisguiseComponent.KEY.get(sender.getServer().getScoreboard());
        DebugDisguiseComponent.DisguiseEntry identity = resolveIdentity(component, sender.getUuid());
        if (identity == null) {
            return true;
        }

        SignedMessage outgoingMessage = SignedMessage.ofUnsigned(identity.appearanceUuid(), message.getSignedContent());
        MessageType.Parameters outgoingParameters = MessageType.params(
                MessageType.CHAT,
                sender.getRegistryManager(),
                Text.literal(identity.appearanceName())
        );
        SentMessage outgoing = SentMessage.of(outgoingMessage);
        for (ServerPlayerEntity recipient : sender.getServer().getPlayerManager().getPlayerList()) {
            outgoing.send(recipient, sender.shouldFilterMessagesSentTo(recipient), outgoingParameters);
        }
        return false;
    }

    private static DebugDisguiseComponent.DisguiseEntry resolveIdentity(DebugDisguiseComponent component,
                                                                         java.util.UUID playerUuid) {
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry personal = component.getChatIdentityEntry(playerUuid);
        if (DebugDisguiseComponent.isActive(personal, now)) {
            return personal;
        }
        if (component.isChatIdentityExcludedFromGlobal(playerUuid)) {
            return null;
        }
        DebugDisguiseComponent.DisguiseEntry global = component.getChatIdentityGlobalEntry();
        return DebugDisguiseComponent.isActive(global, now) ? global : null;
    }
}
