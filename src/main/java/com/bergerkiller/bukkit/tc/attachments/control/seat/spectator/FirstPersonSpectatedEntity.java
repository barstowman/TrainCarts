package com.bergerkiller.bukkit.tc.attachments.control.seat.spectator;

import org.bukkit.entity.Player;

import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.tc.attachments.control.CartAttachmentSeat;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutCameraHandle;

/**
 * A type of entity that can be spectated, that has a particular appearance
 * when the player views himself in third-person (F5) view.
 */
public abstract class FirstPersonSpectatedEntity {
    protected final CartAttachmentSeat seat;
    protected final Player player;

    public FirstPersonSpectatedEntity(CartAttachmentSeat seat, Player player) {
        this.seat = seat;
        this.player = player;
    }

    /**
     * Spawns whatever entity needs to be spectated, and starts spectating that entity
     */
    public abstract void start();

    /**
     * Stops spectating and despawns the entity/others used for spectating
     */
    public abstract void stop();

    public abstract void updatePosition(Matrix4x4 transform);

    public abstract void syncPosition(boolean absolute);

    /**
     * Makes the player spectate a certain entity
     *
     * @param entityId ID of the entity to spectate, -1 to stop spectating
     */
    protected void spectate(int entityId) {
        PacketPlayOutCameraHandle packet = PacketPlayOutCameraHandle.T.newHandleNull();
        packet.setEntityId((entityId == -1) ? player.getEntityId() : entityId);
        PacketUtil.sendPacket(player, packet, false);
    }
}
