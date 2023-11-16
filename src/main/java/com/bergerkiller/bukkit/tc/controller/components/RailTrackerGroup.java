package com.bergerkiller.bukkit.tc.controller.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.Util;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.RailPath.Position;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.rails.type.RailType;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;

/**
 * Uses a track iterator to keep track of the rails a train is driving on.
 * This information is then used to update minecart rails information,
 * handle the detection of signs, update minecart movement directions and
 * detect splitting of trains
 */
public class RailTrackerGroup extends RailTracker {
    private final MinecartGroup owner;
    private final ArrayList<TrackedRail> prevRails = new ArrayList<TrackedRail>();
    private final ArrayList<TrackedRail> rails = new ArrayList<TrackedRail>();

    public RailTrackerGroup(MinecartGroup owner) {
        this.owner = owner;
    }

    /**
     * Called when the group unloads, and this group and all its Minecarts need
     * to be unregistered from any caches.
     */
    public void unload() {
        this.rails.forEach(TrackedRail::handleMemberRemove);
        this.rails.clear();
        this.prevRails.clear();
    }

    /**
     * Removes all the tracked rails belonging to a particular minecart
     * 
     * @param member to remove all rails for
     */
    public void removeMemberRails(MinecartMember<?> member) {
        removeMemberRails(prevRails, member);
        removeMemberRails(rails, member);
    }

    private static void removeMemberRails(List<TrackedRail> rails, MinecartMember<?> member) {
        Iterator<TrackedRail> iter = rails.iterator();
        while (iter.hasNext()) {
            TrackedRail rail = iter.next();
            if (rail.member == member) {
                if (rail.memberAddedToRailPiece) {
                    rail.handleMemberRemove();
                }
                iter.remove();
            }
        }
    }

    /**
     * When the train reverses direction, this method modifies
     * the cached rail data to reflect that. A full re-calculation
     * is needed later.
     */
    public void reverseRailData() {
        Collections.reverse(this.rails);

        // Invert motion direction on the rails
        for (TrackedRail rail : this.rails) {
            rail.state.position().invertMotion();
            rail.state.initEnterDirection();
        }
    }

    /**
     * Gets a list of all rails blocks that the train occupies.
     * Each item contains information about the rails and the minecart that is 'on' it.
     * 
     * @return List of rails block information
     */
    public List<TrackedRail> getRailInformation() {
        return this.rails;
    }

    @Override
    public boolean isOnRails(Block railsBlock) {
        return getMemberFromRails(railsBlock) != null;
    }

    /**
     * Gets the Minecart Member part of this Group that is traveling on the
     * rails block specified
     *
     * @param railsBlock to get the Minecart Member for
     * @return the Minecart Member, or null if not found
     */
    public MinecartMember<?> getMemberFromRails(Block railsBlock) {
        if (railsBlock.getWorld() != owner.getWorld()) {
            return null;
        }
        return getMemberFromRails(new IntVector3(railsBlock));
    }

    /**
     * Gets the Minecart Member part of this Group that is traveling on the
     * rails block specified
     *
     * @param railsBlockPosition to get the Minecart Member for
     * @return the Minecart Member, or null if not found
     */
    public MinecartMember<?> getMemberFromRails(IntVector3 railsBlockPosition) {
        //TODO: Is keeping a hashmap up to date a good idea? This loop works just fine, too.
        for (TrackedRail info : rails) {
            if (railsBlockPosition.equals(info.state.railPiece().blockPosition())) {
                return info.member;
            }
        }
        return null;
    }

    /**
     * Refreshes rail information, recalculating rail positions, directions and disconnect states
     */
    public void refresh() {
        /* Timings: refreshRails  (Train Physics, Rail Tracker) */
        {
            this.prevRails.clear();
            this.prevRails.addAll(this.rails);
            this.rails.clear();
            refreshFrom(this.owner.size() - 1, false);

            if (TCConfig.railTrackerDebugEnabled) {
                List<TrackedRail> behindRails = new ArrayList<TrackedRail>();
                List<TrackedRail> midRails = new ArrayList<TrackedRail>(this.rails);
                List<TrackedRail> aheadRails = new ArrayList<TrackedRail>();

                calcWheelTracks();

                boolean gotToAhead = false;
                for (TrackedRail rail : this.rails) {
                    if (midRails.contains(rail)) {
                        gotToAhead = true;
                    } else if (gotToAhead) {
                        aheadRails.add(rail);
                    } else {
                        behindRails.add(rail);
                    }
                }

                // Red: Behind tracks
                for (int i = 0; i < behindRails.size(); i++) {
                    Location loc = behindRails.get(i).state.positionLocation();
                    double theta =  (double) i / (double) (behindRails.size() - 1);

                    Util.spawnDustParticle(loc, 0.5 * theta + 0.5, 0.0, 0.0);
                }
                // Red-Green with blueish: Middle tracks
                for (int i = 0; i < midRails.size(); i++) {
                    Location loc = midRails.get(i).state.positionLocation();
                    double theta = (double) i / (double) (midRails.size() - 1);

                    Util.spawnDustParticle(loc, 0.5 * (1.0 - theta), 0.5 * theta, 1.0);
                }
                // Green: Ahead tracks
                for (int i = 0; i < aheadRails.size(); i++) {
                    Location loc = aheadRails.get(i).state.positionLocation();
                    double theta = (double) i / (double) (aheadRails.size() - 1);

                    Util.spawnDustParticle(loc, 0.0, 0.5 * (1.0 - theta) + 0.5, 0.0);
                }

            } else {
                calcWheelTracks();
            }

            Collections.reverse(this.rails);

            // Log the rail information
            /*
            String s = "";
            for (TrackedRail rail : this.rails) {
                s += "[" + rail.member.getIndex() + " " + rail.position + "]";
            }
            this.owner.getPlugin().log(Level.INFO, s);
            */

            // TODO: Detect when the rails are changed
            // Compare rails with prevRails to do so
            owner.getSignTracker().updatePosition();
        }

        // Remove all previous rails from the rail member cache, and add the new rails
        /* Timings: cacheRailMembers  (Train Physics, Rail Tracker, Cache) */
        {
            if (this.prevRails.isEmpty() && !this.rails.isEmpty()) {
                // Spawning / entering rails for the first time
                // Remove all earlier storing of the member
                // This fixes a bug of ghost members in the rail member cache due to unloading
                for (MinecartMember<?> member : this.owner) {
                    RailLookup.removeMemberFromAll(member);
                }
                for (TrackedRail newRail : this.rails) {
                    newRail.handleMemberAdd();
                }
            } else {
                // Moving

                // Go by all previous rails, and check whether their rail block matches
                // a rail in the new list of rails. If so, skip the removal/re-add for that rail.
                // For all previous rails not matched, remove member from the list of members
                // of the rail piece. By handling removal first we avoid problems re-adding later.
                for (TrackedRail prevRail : this.prevRails) {
                    // This can happen because of the behind-rails logic
                    if (!prevRail.memberAddedToRailPiece) {
                        continue;
                    }

                    // Iterate the new rails until we encounter the same members as prevRail
                    // Once found, keep iterating while member matches, and check whether the rail
                    // piece is the same
                    // We can safely assume that all member's rails are in sequence, without gaps
                    MinecartMember<?> memberToFind = prevRail.member;
                    for (Iterator<TrackedRail> newRailIter = this.rails.iterator(); newRailIter.hasNext();) {
                        TrackedRail newRail = newRailIter.next();
                        if (newRail.member == memberToFind) {
                            while (true) {
                                // If RailPiece is the same, we can avoid a remove/add of the member for this piece
                                if (prevRail.state.isSameRails(newRail.state)) {
                                    // Unchanged! Skip removal of the rail
                                    prevRail.memberAddedToRailPiece = false;
                                    // Avoid assigning member of the new rail, too!
                                    newRail.memberAddedToRailPiece = true;
                                }

                                // Next rail. Stop when member is different / no more rails.
                                if (!newRailIter.hasNext() || (newRail = newRailIter.next()).member != memberToFind) {
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    // If memberAddedToRailPiece is still true, then we did not find a new rail to match
                    // Remove it now
                    if (prevRail.memberAddedToRailPiece) {
                        prevRail.handleMemberRemove();
                    }
                }

                // Add members to all rail pieces to which it has not yet been added
                for (TrackedRail newRail : this.rails) {
                    if (!newRail.memberAddedToRailPiece) {
                        newRail.handleMemberAdd();
                    }
                }
            }
        }
    }

    private final void calcWheelTracks() {
        // Error condition
        if (this.rails.isEmpty()) {
            return;
        }

        // Go by all the Minecarts and walk additional tracks when their wheels are potentially not found
        boolean hasPreviousMember = false;
        for (int i = 0; i < this.rails.size(); i++) {
            TrackedRail rail = this.rails.get(i);

            // Skip derailed rails
            // If a previous Minecart did exist, we must recalculate the rails after
            if (rail.state.railType() == RailType.NONE) {
                if (hasPreviousMember) {
                    calcWheelTracksAhead(i - 1);
                    hasPreviousMember = false;

                    // Make sure to continue iteration after these rails again
                    while (this.rails.get(i) != rail && i < this.rails.size()) {
                        i++;
                    }
                }
                continue;
            }

            // If we have no previous member, recalculate the tracks behind
            if (!hasPreviousMember || rail.disconnected) {
                calcWheelTracksBehind(i);
                hasPreviousMember = true;

                // Make sure to continue iteration after these rails again
                while (this.rails.get(i) != rail && i < this.rails.size()) {
                    i++;
                }
            }
        }

        // We must always calculate the tracks ahead for the last minecart
        calcWheelTracksAhead(this.rails.size() - 1);
    }

    private final void calcWheelTracksAhead(int railIndex) {
        TrackedRail startInfo = this.rails.get(railIndex);
        MinecartMember<?> tail = startInfo.member;
        if (startInfo.state.railType() == RailType.NONE) {
            return;
        }

        // Don't do anything if no wheel distance is set
        if (!tail.getWheels().hasWheelDistance()) {
            return;
        }

        // Retrieve start position
        Position position = startInfo.state.position().clone();

        // Find the forwards wheel distance
        double wheelDistance;
        if ((position.motDot(tail.getOrientationForward()) > 0.0)) {
            wheelDistance = tail.getWheels().front().getDistance();
        } else {
            wheelDistance = tail.getWheels().back().getDistance();
        }

        // Walk the distance from the current position (and rails) in the direction
        if (wheelDistance > WheelTrackerMember.MIN_WHEEL_DISTANCE) {
            TrackWalkingPoint p = new TrackWalkingPoint(startInfo.state);

            int limit = 1000;
            do {
                if (p.moveStep(wheelDistance - p.movedTotal) && --limit == 0) {
                    this.owner.getTrainCarts().log(Level.WARNING, "Reached maximum loops refreshing front wheel position (" +
                            "train=" + this.owner.getProperties().getTrainName() +
                            " x=" + tail.getEntity().loc.getX() +
                            " y=" + tail.getEntity().loc.getY() +
                            " z=" + tail.getEntity().loc.getZ() + ")");
                        break;
                }

                this.rails.add(++railIndex, new TrackedRail(tail, p, false));
            } while (p.failReason == TrackWalkingPoint.FailReason.NONE);

            //Location loc = position.toLocation(owner.getWorld());
            //Util.spawnParticle(loc, Particle.WATER_BUBBLE);

            /*
            Location loc = new Location(tail.getEntity().getWorld(), position.posX, position.posY, position.posZ);
            loc.setY(loc.getY() - 1.0);
            Util.spawnParticle(loc, Particle.WATER_BUBBLE);
            loc.add(new Vector(position.upX, position.upY, position.upZ).multiply(0.5));
            Util.spawnParticle(loc, Particle.WATER_BUBBLE);
            */
        }
    }

    private final void calcWheelTracksBehind(int railIndex) {
        TrackedRail startInfo = this.rails.get(railIndex);
        MinecartMember<?> tail = startInfo.member;
        if (startInfo.state.railType() == RailType.NONE) {
            return;
        }
        if (!tail.getWheels().hasWheelDistance()) {
            return;
        }

        // Calculate the actual direction in which the minecart moves
        // This is important when initializing the direction to move over the paths
        // Behind, so invert it (-1)
        Vector movementDirection = startInfo.state.motionVector();
        movementDirection.multiply(-1.0);

        // No next member, so we can't compute a direction from that
        // Simply walk the wheel distance forwards to find out that angle
        // Which wheel is in the direction we are going to be looking at?
        double wheelDistance;
        Vector ownDirection = tail.getOrientationForward();
        if (MathUtil.isHeadingTo(movementDirection, ownDirection)) {
            wheelDistance = tail.getWheels().front().getDistance();
        } else {
            wheelDistance = tail.getWheels().back().getDistance();
        }

        // Use known previous rail information to walk backwards to find the rails of the back wheel
        // Any distance remaining will have to be settled by walking the rails backwards, which might fail
        // First, find the index of the rails in prevRails from which we can start looking
        if (wheelDistance > WheelTrackerMember.MIN_WHEEL_DISTANCE) {

            // Position while discovering and walking along track
            Position position = Position.fromPosDir(tail.getEntity().loc.vector(), movementDirection);
            position.reverse = true;

            // Find a previous rail that exactly contains the position we are requesting
            int prevRailStartIndex = -1;
            for (int i = this.prevRails.size() - 1; i >= 0; --i) {
                if (this.prevRails.get(i).isSameTrack(startInfo)) {
                    prevRailStartIndex = i;
                    break;
                }
            }

            // If we failed to find a start rail, then we have a problem
            // Try and see if the first entry in this.prevRails leads to startInfo
            // If so, we can simply append startInfo as is after that one
            // This path is used when the wheel distance is very small, and no history exists
            if (prevRailStartIndex == -1 && !this.prevRails.isEmpty()) {
                for (int i = 0; i < this.prevRails.size(); i++) {
                    if (this.prevRails.get(i).member == startInfo.member) {
                        TrackedRail prev = this.prevRails.get(i);
                        TrackWalkingPoint p = new TrackWalkingPoint(prev.state);
                        p.skipFirst();
                        if (p.moveFull()) {
                            if (p.state.isSameRails(startInfo.state) && p.currentRailPath.equals(startInfo.getPath())) {
                                this.prevRails.add(i, startInfo.clone());
                                prevRailStartIndex = i;
                            }
                        }
                        break;
                    }
                }
            }

            // If previous rails are found, walk them first
            if (prevRailStartIndex != -1) {
                TrackedRail startRail = this.prevRails.get(prevRailStartIndex);

                // Move as much as possible over the current rail
                // This sets our position to the end-position of the current rail
                RailPath startPath = startRail.getPath();
                double startMoved = startPath.move(position, startRail.state.railBlock(), wheelDistance);
                wheelDistance -= startMoved;

                if (wheelDistance > 1e-10) {
                    // We need to walk more tracks. To do so, we must figure out whether we go +1 or -1.
                    // To find this out, we first obtain the movement direction over the start rails when forwards
                    // TODO: If a single path is very curvy this stuff will likely not work!
                    int order;
                    if (startRail.state.position().motDot(position) > 0.0) {
                        order = -1;
                    } else {
                        order = 1;
                    }

                    for (int prevRailIndex = prevRailStartIndex + order; prevRailIndex >= 0 && prevRailIndex < this.prevRails.size() && wheelDistance > 0.0001; prevRailIndex += order) {
                        TrackedRail rail = this.prevRails.get(prevRailIndex);
                        if (rail.isSameTrack(startInfo)) {
                            continue; //TODO: Still needed?
                        }

                        // Walk this rail backwards
                        RailPath path = rail.getPath();
                        double moved = path.move(position, rail.state.railBlock(), wheelDistance);
                        wheelDistance -= moved;

                        // Create a new version of the tracked rail with the correct member
                        // This also clones the rail - important to avoid weirdness with memberAddedToRailPiece
                        rail = rail.changeMember(startInfo.member);

                        // If the direction of the rail is wrong, fix it
                        if (order < 0) {
                            rail = rail.invertMotionVector();
                        }

                        rail.cachedPath = path;
                        this.rails.add(railIndex, rail);
                        startInfo = rail;
                    }
                }
            }

            // If more wheel distance remains, all we can do is walk the tracks in the opposite direction
            // This can actually be incorrect, for example when taking a junction
            // It will at least resolve correctly for straight rails
            if (wheelDistance > 0.0) {
                TrackWalkingPoint p;// = new TrackWalkingPoint(position.toLocation(startInfo.member.));
                {
                    RailState state = new RailState();
                    state.setPosition(position);
                    state.setMember(tail);
                    state.setRailPiece(startInfo.state.railPiece());
                    RailType.loadRailInformation(state);
                    p = new TrackWalkingPoint(state);
                }

                int limit = 1000;
                do {
                    if (p.moveStep(wheelDistance - p.movedTotal) && --limit == 1000) {
                        this.owner.getTrainCarts().log(Level.WARNING, "Reached maximum loops refreshing back wheel position (" +
                                "train=" + this.owner.getProperties().getTrainName() +
                                " x=" + tail.getEntity().loc.getX() +
                                " y=" + tail.getEntity().loc.getY() +
                                " z=" + tail.getEntity().loc.getZ() + ")");
                            break;
                    }

                    TrackedRail rail = new TrackedRail(tail, p, false);
                    rail = rail.invertMotionVector();
                    rail.cachedPath = p.currentRailPath;
                    this.rails.add(railIndex, rail);
                } while (p.failReason == TrackWalkingPoint.FailReason.NONE);
            }

            if (position != null) {
                //org.bukkit.Location loc = position.toLocation(owner.getWorld());
                //com.bergerkiller.bukkit.tc.Util.spawnParticle(loc, org.bukkit.Particle.WATER_BUBBLE);
                
                //Util.spawnParticle(owner.get(0).getEntity().getLocation(), Particle.REDSTONE);
            }
        }
    }

    private final void refreshFrom(int memberIndex, boolean disconnected) {
        // Helper object for doing this algorithm
        RailFinder finder = new RailFinder(memberIndex, disconnected);

        // No next member! Train stops here.
        if (finder.startIndex < 0) {
            finder.tail.getRailTracker().refresh(finder.startInfo);
            this.rails.add(finder.startInfo);
            return;
        }

        // If derailed, skip checking the tracks for this minecart
        if (finder.startInfo.state.railType() == RailType.NONE) {
            finder.tail.getRailTracker().refresh(finder.startInfo);
            this.rails.add(finder.startInfo);
            refreshFrom(finder.startIndex, false);
            return;
        }

        // Number of remaining carts to be found
        int remainingCnt = memberIndex;

        // First, test the startInfo, which is the direction in which the cart is moving
        // If this yields insufficient number of carts, try the opposite direction
        // Select the one with the most detected carts and use the rails contained
        // In the normal case, all carts will be found and no special logic is needed
        // We can fill the main rails list instantly, without using a temporary buffer
        // When not all carts can be found and multiple directions must be asked, use a buffer
        boolean isAbormal = false;
        RailFinderResult result;
        if (this.rails.isEmpty()) {
            result = finder.test(finder.startInfo, this.rails);
            if (result.numMembers < remainingCnt && !result.endIsDerailed) {
                isAbormal = true;

                // Restore back into a buffer
                result.rails = new ArrayList<TrackedRail>(result.rails);
                this.rails.clear();
            }
        } else {
            result = finder.test(finder.startInfo);
            isAbormal = true;
        }

        if (isAbormal) {
            // Try the opposite direction when not all carts could be found
            if (result.numMembers < remainingCnt && !result.endIsDerailed) {
                RailFinderResult alter = finder.test(finder.startInfo.invertMotionVector());
                if (alter.numMembers > result.numMembers) {
                    result = alter;
                }
            }

            // Add the rails result
            this.rails.addAll(result.rails);
        }

        // If debug logging is enabled, log when the search fails for some reason
        if (TCConfig.logTrainSplitting && result.status != RailFinderResult.Status.OK) {
            Logger logger = owner.getTrainCarts().getLogger();
            logger.warning("Train '" + owner.getProperties().getTrainName() + "' split apart because: " +
                    result.status.getReason());
            logger.warning("Search start: " + result.failSearchStart);
            logger.warning("Search end: " + result.failSearchEnd);
            if (result.nextMemberIndex >= 0) {
                MinecartMember<?> member = owner.get(result.nextMemberIndex);
                Location mloc = member.getEntity().getLocation();
                logger.warning("Cart that could not be reached: cart #" + (result.nextMemberIndex + 1) +
                        " of " + owner.size() + " at " +
                        "x=" + mloc.getX() + " y=" + mloc.getY() + " z=" + mloc.getZ());
            }
        }

        // Apply found rails to the members themselves
        // Use a somewhat complex iteration scheme to avoid get(index)
        // LinkedList does not like the use of indices
        {
            Iterator<TrackedRail> iter = result.rails.iterator();
            if (iter.hasNext()) {
                TrackedRail prev = iter.next();
                while (iter.hasNext()) {
                    TrackedRail next = iter.next();

                    // Refresh when the member bound to a rail changes
                    // The last rail iterated is for the member to use
                    if (prev.member != next.member) {
                        prev.member.getRailTracker().refresh(prev);
                    }

                    prev = next;
                }

                // Refresh last member rail in list
                prev.member.getRailTracker().refresh(prev);
            }
        }

        // If not all members are found, continue looking for more (= disconnected)
        // If there are more minecarts remaining in the chain, these could not be found using the iterator
        // We will have to disconnect these from the train later, and they have to be iterated by themselves
        // Mark disconnected when the next minecart isn't derailed, indicating the two carts are on different
        // tracks that can not reach each other.
        if (result.nextMemberIndex >= 0) {
            refreshFrom(result.nextMemberIndex, !result.endIsDerailed);
        }
    }

    private class RailFinder {
        private MinecartMember<?> tail;
        private final TrackedRail startInfo;
        private final int startIndex;

        public RailFinder(int index, boolean disconnected) {
            // Iterate the tracks from the minecart from the tail to the front
            // If we fail to find the next minecart in the chain within a limit
            // amount of blocks, assume the train has split at that minecart.
            this.tail = owner.get(index);
            this.startInfo = TrackedRail.create(tail, disconnected);
            this.startIndex = (index - 1);
        }

        public RailFinderResult test(TrackedRail moveInfo) {
            return test(moveInfo, new LinkedList<TrackedRail>());
        }

        public RailFinderResult test(TrackedRail moveInfo, List<TrackedRail> buffer) {
            RailFinderResult result = new RailFinderResult(this.startIndex, buffer);
            result.rails.add(moveInfo);

            MinecartMember<?> nextMember = owner.get(result.nextMemberIndex);

            RailState nextPos = nextMember.discoverRail();
            if (nextPos.railType() == RailType.NONE) {
                result.status = RailFinderResult.Status.DERAILED;
                result.failSearchStart = moveInfo.state;
                result.failSearchEnd = nextPos;
                result.endIsDerailed = true;
                return result;
            }

            int moveLimitCtr = 0;
            int maximumDistanceBlocks = tail.getMaximumBlockDistance(nextMember);

            TrackWalkingPoint p = new TrackWalkingPoint(moveInfo.state);
            if (p.moveFull()) {
                moveLimitCtr = 0;
                boolean isFirstBlock = true;
                int nrCachedRails = 0; // rails added without certainty of being correct
                while (true) {
                    if (p.state.isSameRails(nextPos)) {
                        // We can skip the slow movement steps when the current rail path has only one segment
                        // This helps performance a bit on vanilla rails.
                        boolean useFastMethod = (p.currentRailPath.getSegments().length <= 1);

                        TrackedRail currInfo;
                        if (useFastMethod) {
                            // Just do a simple dot-product test, which will break in curves. See below:
                            // TODO: Should we instead 'move' towards nextPos from p.state?
                            //       This would better handle curved paths
                            // This is what the not-fast method resolves.

                            if (p.state.position().motDot(nextPos.motionVector()) < 0.0) {
                                nextPos.position().invertMotion();
                            }

                            currInfo = new TrackedRail(nextMember, nextPos, false);
                        } else {
                            // Move the walking point small steps until no more significant movement occurs
                            // to close the distance between p.state.position() and nextPos.
                            final double ERR_EPSILON = 1e-8;
                            int cycle_limit = 10000;
                            double initial_distance = p.state.position().distance(nextPos.position());
                            double curr_distance = initial_distance;
                            do {
                                if (curr_distance <= ERR_EPSILON) {
                                    break; // Good enough
                                } else if (!p.move(curr_distance) || p.moved <= ERR_EPSILON) {
                                    curr_distance = p.state.position().distance(nextPos.position());
                                    break; // End of track reached
                                }
                                double new_distance = p.state.position().distance(nextPos.position());
                                if (new_distance >= curr_distance) {
                                    break;
                                }
                                curr_distance = new_distance;
                            } while (--cycle_limit > 0);

                            // If we find that the position is diverging away, then there is no connection
                            // with the other cart and we abort. The next test will try the other direction
                            // and hopefully correct this.
                            if (curr_distance > ERR_EPSILON && curr_distance > (0.5 * initial_distance)) {
                                result.status = RailFinderResult.Status.DIVERGING;
                                result.failSearchStart = p.state;
                                result.failSearchEnd = nextPos;
                                break;
                            }

                            // Update rail state, change rail piece of different
                            RailState currInfoState = p.state.clone();
                            currInfoState.setRailPiece(nextPos.railPiece());
                            currInfo = new TrackedRail(nextMember, currInfoState, false);
                        }

                        // If we found the next member for the first time, also update the starting minecart with the correct info
                        result.numMembers++;

                        // Refresh the next minecart with the information currently iterating at
                        nrCachedRails = 0;
                        result.rails.add(currInfo);

                        // Continue looking for more minecarts
                        if (--result.nextMemberIndex < 0) {
                            nextMember = null;
                            nextPos = null;
                            break; // we're done!
                        }
                        moveLimitCtr = 0;
                        nextMember = owner.get(result.nextMemberIndex);
                        nextPos = nextMember.discoverRail();
                        maximumDistanceBlocks = currInfo.member.getMaximumBlockDistance(nextMember);
                        isFirstBlock = true;
                        if (nextPos.railType() == RailType.NONE) {
                            result.status = RailFinderResult.Status.DERAILED;
                            result.endIsDerailed = true;
                            result.failSearchStart = p.state;
                            result.failSearchEnd = nextPos;
                            break; // member is derailed
                        }
                    } else {
                        if (isFirstBlock) {
                            isFirstBlock = false;
                        } else {
                            // Keep track of the Minecart we are trying to find for the in-between blocks
                            // This is important for the block space
                            result.rails.add(new TrackedRail(nextMember, p, false));
                            nrCachedRails++;
                        }
                        moveLimitCtr++;
                        if (moveLimitCtr > maximumDistanceBlocks || !p.moveFull()) {
                            // Remove all cached rails - rails iteration failed
                            while (nrCachedRails > 0) {
                                nrCachedRails--;
                                result.rails.remove(result.rails.size() - 1);
                            }

                            result.status = (moveLimitCtr > maximumDistanceBlocks)
                                    ? RailFinderResult.Status.LIMIT_REACHED : RailFinderResult.Status.END_OF_TRACK;
                            result.failSearchStart = result.rails.isEmpty()
                                    ? p.state : result.rails.get(result.rails.size() - 1).state;
                            result.failSearchEnd = p.state;

                            break; // out of track
                        }
                    }
                }
            }
            return result;
        }
    }

    public static class RailFinderResult {
        public Status status;
        public List<TrackedRail> rails;
        public int numMembers;
        public int nextMemberIndex;
        public boolean endIsDerailed;
        public RailState failSearchStart;
        public RailState failSearchEnd;

        public RailFinderResult(int nextMemberIndex, List<TrackedRail> buffer) {
            this.status = Status.OK;
            this.rails = buffer;
            this.numMembers = 0;
            this.nextMemberIndex = nextMemberIndex;
            this.endIsDerailed = false;
            this.failSearchStart = null;
            this.failSearchEnd = null;
        }

        public enum Status {
            /** All members were found on the rails */
            OK("OK"),
            /** Member could not be found because path is diverging away from the next member */
            DIVERGING("Path moving away from the next cart in the chain"),
            /** Member could not be found because it is derailed (not on rails) */
            DERAILED("Next cart is derailed"),
            /** Reached the rail iteration limit before finding the next member */
            LIMIT_REACHED("Maximum distance reached searching next cart"),
            /** Reached end of rail iteration, never encountering the rail the next member is on */
            END_OF_TRACK("End of the rails reached before finding next cart");

            private final String reason;

            Status(String reason) {
                this.reason = reason;
            }

            public String getReason() {
                return reason;
            }
        }
    }
}
