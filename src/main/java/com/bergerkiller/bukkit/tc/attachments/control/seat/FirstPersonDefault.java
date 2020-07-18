package com.bergerkiller.bukkit.tc.attachments.control.seat;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.math.Quaternion;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.common.utils.PlayerUtil;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.attachments.VirtualEntity;
import com.bergerkiller.bukkit.tc.attachments.VirtualEntity.SyncMode;
import com.bergerkiller.bukkit.tc.attachments.control.CartAttachmentSeat;
import com.bergerkiller.generated.net.minecraft.server.EntityHandle;
import com.bergerkiller.generated.net.minecraft.server.EntityLivingHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutUpdateAttributesHandle;

/**
 * Synchronizes the seat to the player sitting in the seat
 */
public class FirstPersonDefault {
    private final CartAttachmentSeat seat;
    private Player _player;
    private Mode _mode = Mode.FROM_ENTITY;
    private boolean _useSmoothCoasters = false;
    private VirtualEntity _fakeCameraMount = null;

    public FirstPersonDefault(CartAttachmentSeat seat) {
        this.seat = seat;
    }

    public void makeVisible(Player viewer) {
        _player = viewer;

        if (this.useSmoothCoasters()) {
            Quaternion rotation = seat.getTransform().getRotation();
            TrainCarts.plugin.getSmoothCoastersAPI().setRotation(
                    viewer,
                    (float) rotation.getX(),
                    (float) rotation.getY(),
                    (float) rotation.getZ(),
                    (float) rotation.getW(),
                    (byte) 0 // Set instantly
            );
        }

        if (this._mode.isVirtual()) {
            if (this._fakeCameraMount == null) {
                this._fakeCameraMount = new VirtualEntity(seat.getManager());

                this._fakeCameraMount.setEntityType(EntityType.CHICKEN);
                this._fakeCameraMount.setPosition(new Vector(0.0, this._mode.getVirtualOffset(), 0.0));
                this._fakeCameraMount.setRelativeOffset(0.0, VirtualEntity.PLAYER_SIT_CHICKEN_OFFSET, 0.0);
                this._fakeCameraMount.setSyncMode(SyncMode.SEAT);

                // When synchronizing passenger to himself, we put him on a fake mount to alter where the camera is at
                this._fakeCameraMount.updatePosition(seat.getTransform());
                this._fakeCameraMount.syncPosition(true);
                this._fakeCameraMount.getMetaData().set(EntityHandle.DATA_FLAGS, (byte) (EntityHandle.DATA_FLAG_INVISIBLE));
                this._fakeCameraMount.getMetaData().set(EntityLivingHandle.DATA_HEALTH, 10.0F);
                this._fakeCameraMount.spawn(viewer, seat.calcMotion());
                this._fakeCameraMount.syncPosition(true);

                // Also send zero-max-health
                PacketUtil.sendPacket(viewer, PacketPlayOutUpdateAttributesHandle.createZeroMaxHealth(this._fakeCameraMount.getEntityId()));
            }

            PlayerUtil.getVehicleMountController(viewer).mount(this._fakeCameraMount.getEntityId(), viewer.getEntityId());
        }

        seat.seated.makeVisible(seat, viewer, this._mode.hasFakePlayer());
    }

    public void makeHidden(Player viewer) {
        if (TrainCarts.plugin.isEnabled()) {
            // Cannot send plugin messages while the plugin is being disabled
            TrainCarts.plugin.getSmoothCoastersAPI().resetRotation(viewer);
        }

        if (this._mode.isVirtual() && this._fakeCameraMount != null) {
            PlayerUtil.getVehicleMountController(viewer).unmount(this._fakeCameraMount.getEntityId(), viewer.getEntityId());
            this._fakeCameraMount.destroy(viewer);
            this._fakeCameraMount = null;
        }

        seat.seated.makeHidden(viewer, this._mode.hasFakePlayer());
    }

    public void onMove(boolean absolute) {
        if (this.useSmoothCoasters()) {
            Quaternion rotation = seat.getTransform().getRotation();
            TrainCarts.plugin.getSmoothCoastersAPI().setRotation(
                    _player,
                    (float) rotation.getX(),
                    (float) rotation.getY(),
                    (float) rotation.getZ(),
                    (float) rotation.getW(),
                    (byte) 3 // TODO 5 for minecarts
            );
        }

        if (this._fakeCameraMount != null && this._mode.isVirtual()) {
            this._fakeCameraMount.updatePosition(seat.getTransform());
            this._fakeCameraMount.syncPosition(absolute);
        }
    }

    public boolean useSmoothCoasters() {
        return _useSmoothCoasters;
    }

    public void setUseSmoothCoasters(boolean use) {
        this._useSmoothCoasters = use;
    }

    /**
     * Gets the view mode used in first-person. This mode alters how the player perceives
     * himself in the seat.
     * 
     * @return mode
     */
    public Mode getMode() {
        return this._mode;
    }

    /**
     * Sets the view mode in first-person. See: {@link #getMode()}
     * 
     * @param mode
     */
    public void setMode(Mode mode) {
        this._mode = mode;
    }

    /**
     * First-player view mode
     */
    public static enum Mode {
        /**
         * The visible entity itself is used to 'look' from
         */
        FROM_ENTITY(Double.NaN, false),
        /**
         * The camera hovers in third person near to the head
         */
        THIRD_PERSON(1.4, true),
        /**
         * The player does not see himself, but looks exactly
         * from where the head is that other players can see
         */
        INVISIBLE(0.0, false);

        private final double _cameraOffset;
        private final boolean _fakePlayer;

        private Mode(double cameraOffset, boolean fakePlayer) {
            this._cameraOffset = cameraOffset;
            this._fakePlayer = fakePlayer;
        }

        /**
         * Gets whether for this mode a virtual entity is used to move
         * the player around
         * 
         * @return True if virtual
         */
        public boolean isVirtual() {
            return !Double.isNaN(this._cameraOffset);
        }

        /**
         * Gets the Y-offset of the camera
         * 
         * @return virtual camera offset
         */
        public double getVirtualOffset() {
            return this._cameraOffset;
        }

        /**
         * Gets whether a different Player entity is spawned to represent
         * the player in first-person
         * 
         * @return True if a fake player is used
         */
        public boolean hasFakePlayer() {
            return this._fakePlayer;
        }
    }
}