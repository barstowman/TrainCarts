package com.bergerkiller.bukkit.tc.signactions;

import com.bergerkiller.bukkit.tc.Direction;
import com.bergerkiller.bukkit.tc.Permission;
import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.Util;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.utils.FormattedSpeed;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import com.bergerkiller.bukkit.tc.utils.SignBuildOptions;

import org.bukkit.block.BlockFace;

public class SignActionLauncher extends SignAction {

    @Override
    public boolean match(SignActionEvent info) {
        return info.isType("launch");
    }

    @Override
    public void execute(SignActionEvent info) {
        if (!info.isAction(SignActionType.GROUP_ENTER, SignActionType.REDSTONE_ON) || !info.isPowered()) {
            return;
        }

        // Parse the launch speed (if starts with + or -, it is relative)
        FormattedSpeed velocity = FormattedSpeed.parse(info.getLine(2), FormattedSpeed.of(TCConfig.launchForce));

        // Parse the launch distance
        int launchEndIdx = info.getLine(1).indexOf(' ');
        String launchConfigStr = (launchEndIdx == -1) ? "" : info.getLine(1).substring(launchEndIdx + 1);
        LauncherConfig launchConfig = LauncherConfig.parse(launchConfigStr);

        if (info.isRCSign()) {

            Direction direction = Direction.parse(info.getLine(3));
            // Launch all groups
            for (MinecartGroup group : info.getRCTrainGroups()) {
                double launchVelocity = velocity.getValue();

                // When prefixed with + or - the speed should be added on top of the current speed of the train
                if (velocity.isRelative()) {
                    launchVelocity += group.head().getRealSpeed();
                }

                BlockFace cartDirection = group.head().getDirection();
                BlockFace directionFace = direction.getDirectionLegacy(cartDirection, cartDirection);
                group.getActions().clear();
                group.head().getActions().addActionLaunch(directionFace, launchConfig, launchVelocity);
            }
        } else if (info.hasRailedMember()) {
            // Parse the direction to launch into
            BlockFace direction = Direction.parse(info.getLine(3)).getDirectionLegacy(info.getFacing(), info.getCartEnterFace());

            // Calculate the launch distance if left empty
            if (!launchConfig.isValid()) {
                launchConfig.setDistance(Util.calculateStraightLength(info.getRails(), direction));
            }

            double launchVelocity = velocity.getValue();

            // When prefixed with + or - the speed should be added on top of the current speed of the train
            if (velocity.isRelative()) {
                launchVelocity += info.getMember().getRealSpeed();
            }

            // Launch
            info.getGroup().getActions().clear();
            info.getMember().getActions().addActionLaunch(direction, launchConfig, launchVelocity);
        }
    }

    public void execute(MinecartGroup group) {

    }

    @Override
    public boolean canSupportRC() {
        return true;
    }

    @Override
    public boolean build(SignChangeActionEvent event) {
        return SignBuildOptions.create()
                .setPermission(Permission.BUILD_LAUNCHER)
                .setName("launcher")
                .setDescription("launch (or brake) trains at a desired speed")
                .setTraincartsWIKIHelp("TrainCarts/Signs/Launcher")
                .handle(event.getPlayer());
    }
}
