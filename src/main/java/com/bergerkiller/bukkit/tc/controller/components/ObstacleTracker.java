package com.bergerkiller.bukkit.tc.controller.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatus;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatusProvider;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.signactions.mutex.MutexZone;
import com.bergerkiller.bukkit.tc.signactions.mutex.MutexZoneCacheWorld;
import com.bergerkiller.bukkit.tc.signactions.mutex.MutexZoneSlot;
import com.bergerkiller.bukkit.tc.utils.ForwardChunkArea;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;

/**
 * Checks the rails ahead of the train for any obstacles that exist there.
 * These can be stationary obstacles, like mutex zones and blocker signs,
 * but also moving obstacles like other trains.<br>
 * <br>
 * With this information it controls the maximum speed of the train to maintain
 * distance from these obstacles as configured. Uses the configured acceleration
 * and deceleration to slow down the train, or launch it again to the original
 * speed when the blockage up ahead clears.
 */
public class ObstacleTracker implements TrainStatusProvider {
    private final MinecartGroup group;
    private double waitDistanceLastSpeedLimit = Double.MAX_VALUE;
    private double waitDistanceLastTrainSpeed = Double.MAX_VALUE;
    private int waitRemainingTicks = Integer.MAX_VALUE;
    private ObstacleSpeedLimit lastObstacleSpeedLimit = ObstacleSpeedLimit.NONE;
    private List<MutexZone> enteredMutexZones = Collections.emptyList();

    public ObstacleTracker(MinecartGroup group) {
        this.group = group;
    }

    /**
     * Gets the speed limit imposed by this waiter, for the current sub-physics tick.
     * Returns MAX_VALUE if no limit is imposed.
     * This speed limit is in absolute speeds, no update speed factor is involved.
     *
     * @return speed limit, MAX_VALUE if none is imposed
     */
    public double getSpeedLimit() {
        return this.waitDistanceLastSpeedLimit;
    }

    /**
     * Main update tick function. Checks if the train should slow down, or use altered speeds,
     * and if so, returns a new max speed value the train should use. This operates in the
     * speed-factor applied to domain. Meaning this update() function is called multiple
     * times per tick at high speeds.
     * 
     * @param trainSpeed Current true speed of the train (update speed factor accounted for)
     * @return speed the train should limit itself to.
     */
    public void update(double trainSpeed) {
        TrainProperties properties = group.getProperties();

        // Calculate the amount of distance ahead of the train we have to look for other
        // trains or mutex zones or any other type of obstacle. This is calculated based
        // on the maximum projected movement the train will have this tick. This is the
        // full train speed, limited by the applied speed limit right now
        double searchAheadDistance = 1.0; // Little extra to avoid sign block obstacle jitter
        if (properties.getWaitDeceleration() > 0.0) {
            double speedLimitLastTick = (this.waitDistanceLastSpeedLimit == Double.MAX_VALUE) ?
                    properties.getSpeedLimit() : this.waitDistanceLastSpeedLimit;
            double maxProjectedSpeed = Math.min(trainSpeed, speedLimitLastTick);

            // At the current speed, how much extra distance does it take to slow the train down to 0?
            // Look for obstacles this much extra distance ahead, to allow for stopping in time.
            // As this train slows down due to a reduced speed limit, we have to check less and less
            // distance up a head, since it takes less long to slow to a complete stop.

            // Based on this formula: (v^2 - u^2) / (2s) = a where v=0
            //                        (2s) = -(v^2) / a
            //                        s = -(v^2) / a / 2
            // Where: a = acceleration, v=final speed (0), u=start speed, s=distance
            // This computes the rough distance traveled de-accellerating
            searchAheadDistance += 0.5 * (maxProjectedSpeed * maxProjectedSpeed) / properties.getWaitDeceleration();
        }

        // Update the last speed the train actually had. This forms the basis for the
        // slowdown calculations. The current speed has not yet resulted in
        // a position update, so we are safe in overriding that in this current tick. But
        // last tick's speed resulted in movement, so that cannot be dramatically deviated
        // from.
        double baseSpeedLimitThisTick;
        {
            double speedLimitLastTick = (this.waitDistanceLastSpeedLimit == Double.MAX_VALUE) ?
                    properties.getSpeedLimit() : this.waitDistanceLastSpeedLimit;
            double trainSpeedLastTick = (this.waitDistanceLastTrainSpeed == Double.MAX_VALUE) ?
                    trainSpeed : this.waitDistanceLastTrainSpeed;
            baseSpeedLimitThisTick = Math.min(speedLimitLastTick, trainSpeedLastTick);

            this.waitDistanceLastTrainSpeed = trainSpeed;
        }

        // At the current speed, how much extra distance does it take to slow the train down to 0?
        // Look for obstacles this much extra distance ahead, to allow for stopping in time.
        // As this train slows down due to a reduced speed limit, we have to check less and less
        // distance up a head, since it takes less long to slow to a complete stop.
        boolean checkTrains = (properties.getWaitDistance() > 0.0);

        ObstacleSpeedLimit newDesiredSpeed = getDesiredSpeedLimit(searchAheadDistance,
                properties.getWaitDeceleration(), checkTrains, true, properties.getWaitDistance());

        // Every time the speed drops to 0 consistently, reset the wait tick timer to 0
        // This causes it to wait until the remaining ticks reaches the configured delay
        if (this.waitDistanceLastSpeedLimit <= 1e-6 && newDesiredSpeed.speed <= 1e-6) {
            this.waitRemainingTicks = 0;
            this.waitDistanceLastSpeedLimit = newDesiredSpeed.speed;
            return;
        }

        // Until the configured delay is reached, keep the speed on 0
        if (this.waitRemainingTicks != Integer.MAX_VALUE) {
            double delay = properties.getWaitDelay();
            if (delay <= 0.0) {
                this.waitRemainingTicks = Integer.MAX_VALUE; // No delay
            } else {
                if (++this.waitRemainingTicks >= MathUtil.ceil(delay*20.0)) {
                    this.waitRemainingTicks = Integer.MAX_VALUE; // Delay elapsed
                }
                this.waitDistanceLastSpeedLimit = 0.0;
                return;
            }
        }

        // Unlimited speed, speed up the train and stop once speed limit is reached
        // Speed up based on configured acceleration
        if (newDesiredSpeed.speed >= properties.getSpeedLimit()) {
            if (this.waitDistanceLastSpeedLimit >= newDesiredSpeed.speed) {
                this.waitDistanceLastSpeedLimit = Double.MAX_VALUE;
            }
            if (this.waitDistanceLastSpeedLimit != Double.MAX_VALUE) {
                double acceleration = properties.getWaitAcceleration();
                if (acceleration > 0.0) {
                    this.waitDistanceLastSpeedLimit += acceleration;
                    if (this.waitDistanceLastSpeedLimit >= properties.getSpeedLimit()) {
                        this.waitDistanceLastSpeedLimit = Double.MAX_VALUE;
                    }
                } else {
                    this.waitDistanceLastSpeedLimit = Double.MAX_VALUE;
                }
            }
            return;
        }

        // If no speed limit was imposed before, set one right now
        if (this.waitDistanceLastSpeedLimit == Double.MAX_VALUE) {
            this.waitDistanceLastSpeedLimit = properties.getSpeedLimit();
        }

        double speedDiff = (newDesiredSpeed.speed - this.waitDistanceLastSpeedLimit);
        if (speedDiff >= 0.0) {
            // Speed up
            double acceleration = properties.getWaitAcceleration();
            if (acceleration <= 0.0 || acceleration >= speedDiff) {
                this.waitDistanceLastSpeedLimit = newDesiredSpeed.speed;
            } else {
                this.waitDistanceLastSpeedLimit += acceleration;
            }
        } else {
            double deceleration = properties.getWaitDeceleration();
            if (deceleration <= 0.0 || deceleration >= (-speedDiff) || newDesiredSpeed.instant) {
                // Slow down to the new speed instantly
                this.waitDistanceLastSpeedLimit = newDesiredSpeed.speed;
            } else if (newDesiredSpeed.speed > baseSpeedLimitThisTick) {
                // Use desired speed directly, as it's higher than the current minimum speed
                this.waitDistanceLastSpeedLimit = newDesiredSpeed.speed;
            } else {
                // Slow down gradually
                this.waitDistanceLastSpeedLimit = baseSpeedLimitThisTick - deceleration;
            }
        }
    }

    @Override
    public List<TrainStatus> getStatusInfo() {
        if (!this.lastObstacleSpeedLimit.hasLimit() && this.enteredMutexZones.isEmpty()) {
            return Collections.emptyList();
        }

        List<TrainStatus> statuses = new ArrayList<>();

        for (MutexZone zone : this.enteredMutexZones) {
            statuses.add(new TrainStatus.EnteredMutexZone(zone));
        }

        if (this.lastObstacleSpeedLimit.hasLimit()) {
            statuses.add(this.lastObstacleSpeedLimit.getStatus());
        } else if (this.waitRemainingTicks != Integer.MAX_VALUE) {
            double remaining = this.group.getProperties().getWaitDelay() - (double) this.waitRemainingTicks * 0.05;
            statuses.add(new TrainStatus.WaitingForDelay(remaining));
        }

        return statuses;
    }

    /**
     * Calculates the desired speed limit the train should ideally have right now.
     * Based on acceleration/deceleration, the actual speed limit is adjusted to reach
     * this speed.
     * 
     * @param searchAheadDistance How much distance ahead of the train to look for obstacles
     *                            that would alter the maximum desired speed.
     * @param deceleration The de-acceleration the train has to stop for the obstacle. 0 for instant.
     *                     This controls the speed limit found, assuming the train can stop at this rate
     *                     to avoid collision in the future.
     * @param checkTrains Whether to check for trains ahead blocking the track
     * @param checkRailObstacles Whether to check for rail obstacles, like mutex zones
     * @param trainDistance How much extra distance should be kept between this train and any
     *                      other trains ahead
     * @return desired speed limit
     */
    private ObstacleSpeedLimit getDesiredSpeedLimit(double searchAheadDistance, double deceleration,
            boolean checkTrains, boolean checkRailObstacles, double trainDistance
    ) {
        // Find obstacles. Update the mutex zone found (train status)
        ObstacleFinder finder = new ObstacleFinder(Math.min(2000.0, searchAheadDistance),
                                                   checkTrains, checkRailObstacles, trainDistance);
        List<Obstacle> obstacles = finder.search();
        this.enteredMutexZones = finder.enteredMutexZones;
        return this.lastObstacleSpeedLimit = minimumSpeedLimit(obstacles, deceleration);
    }

    /**
     * Looks up ahead on the track for obstacles. These can be other trains, or mutex
     * signs that disallow movement further.
     * 
     * @param distance Distance in blocks to check ahead of the train
     * @param checkTrains Whether to look for trains or only for mutex signs
     * @param checkRailObstacles Whether to check for rail obstacles, like mutex zones
     * @param trainDistance If checkTrains true, what distance to subtract from obstacles
     *                      distance to maintain a safety distance from them.
     * @return obstacle that was detected, null if there is no obstacle
     */
    public List<Obstacle> findObstaclesAhead(double distance, boolean checkTrains, boolean checkRailObstacles, double trainDistance) {
        return (new ObstacleFinder(distance, checkTrains, checkRailObstacles, trainDistance)).search();
    }

    /**
     * Finds the minimum speed limit in a Collection of obstacles. If the collection
     * is empty, returns {@link #NONE}
     *
     * @param obstacles Obstacles
     * @param deceleration Maximum rate of deceleration
     * @return Minimum speed limit to avoid the nearest obstacle
     */
    public static ObstacleSpeedLimit minimumSpeedLimit(Iterable<Obstacle> obstacles, double deceleration) {
        ObstacleSpeedLimit min = ObstacleSpeedLimit.NONE;
        for (Obstacle obstacle : obstacles) {
            ObstacleSpeedLimit limit = obstacle.findSpeedLimit(deceleration);
            if (limit.speed < min.speed) {
                min = limit;
            }
        }
        return min;
    }

    /**
     * Finds the minimum speed limit in a Collection of speed limits. If the collection
     * is empty, returns {@link #NONE}
     *
     * @param limits Limits
     * @return Minimum
     */
    public static ObstacleSpeedLimit minimumSpeedLimit(Iterable<ObstacleSpeedLimit> limits) {
        ObstacleSpeedLimit min = ObstacleSpeedLimit.NONE;
        for (ObstacleSpeedLimit limit : limits) {
            if (limit.speed < min.speed) {
                min = limit;
            }
        }
        return min;
    }

    /**
     * Searches for obstacles up ahead on the track. One instance of this class represents
     * a single search operation and cannot be re-used.
     */
    private class ObstacleFinder {
        final double distance;
        final boolean checkTrains;
        final boolean checkRailObstacles;
        final double trainDistance;

        // Take into account that the head minecart has a length also, so we count distance from the edge (half length)
        // TODO: This does not take into account wheel offset!!!
        final double selfCartOffset;

        // The actual minimum distance allowed from the walking point position to any minecarts discovered
        // This takes into account that the start position is halfway the length of the Minecart
        // When distance is not greater than 0, we don't check for other trains at all.
        double waitDistance;

        // Two blocks are used to slow down the train, to make it match up to speed with the train up ahead
        // Check for any mutex zones ~2 blocks ahead, and stop before we enter them
        // If a wait distance is set, also check for trains there
        final double mutexHardDistance;
        final double mutexSoftDistance;
        final double checkDistance;

        // If true, encountered a rail obstacle that will put the train to a complete stop
        // Rail obstacles don't have to be checked anymore if so, but trains do (train distance)
        boolean foundHardRailObstacle = false;

        // Last-encountered speed limit imposed by a rail obstacle
        double lastRailSpeedLimit = Double.MAX_VALUE;

        // Tracks the current mutex zone the train is inside of while navigating the track
        MutexZone currentMutex = null;
        MutexZoneSlot.EnteredGroup currentMutexGroup = null;
        double currentMutexDistance = Double.NaN;
        double lastAddedSoftMutexObstacleDistance = Double.MAX_VALUE;
        boolean handledNonSmartMutex = false;
        boolean currentMutexHard = false;

        // Mutex zones that have been (soft-) entered
        public List<MutexZone> enteredMutexZones = Collections.emptyList();

        // Resulting obstacles
        List<Obstacle> obstacles = new ArrayList<>();

        public ObstacleFinder(double distance, boolean checkTrains, boolean checkRailObstacles, double trainDistance) {
            this.distance = distance;
            this.checkTrains = checkTrains;
            this.checkRailObstacles = checkRailObstacles;
            this.trainDistance = trainDistance;
            this.selfCartOffset = (0.5 * group.head().getEntity().getWidth());
            this.waitDistance = distance + trainDistance;
            this.mutexHardDistance = 0.0;
            this.mutexSoftDistance = 2.0 + distance;
            this.checkDistance = selfCartOffset + Math.max(mutexSoftDistance, waitDistance) + 1.0;
        }

        public List<Obstacle> search() {
            // Not sure if fixed, but skip if this train is empty
            if (group.isEmpty()) {
                return Collections.emptyList();
            }

            MutexZoneCacheWorld.MovingPoint mutexZones = group.head().railLookup().getMutexZones()
                    .track(group.head().getEntity().loc.block());

            // If no wait distance is set and no mutex zones are anywhere close, skip these expensive calculations
            if (distance <= 0.0 && trainDistance <= 0.0 && (!checkRailObstacles || !mutexZones.isNear())) {
                return Collections.emptyList();
            }

            TrackWalkingPoint iter = new TrackWalkingPoint(group.head().discoverRail());
            if (group.getProperties().isWaitPredicted()) {
                iter.setFollowPredictedPath(group.head());
            }

            ForwardChunkArea forwardChunks = null;
            if (group.getProperties().isKeepingChunksLoaded()) {
                forwardChunks = group.getChunkArea().getForwardChunkArea();
                forwardChunks.begin();
            }

            while (iter.movedTotal <= checkDistance && iter.moveFull()) {
                // The distance traveled from the physical front of the cart
                // The first iteration will likely have a negative distance
                double distanceFromFront = iter.movedTotal - selfCartOffset;

                // Refresh that we've visited this rail/position block, keeping the area loaded for this tick
                if (forwardChunks != null) {
                    forwardChunks.addBlock(iter.state.railBlock());
                }

                if (checkRailObstacles) {
                    // Check last smart mutex still valid for the current rail
                    if (currentMutex != null && !currentMutex.containsBlock(iter.state.positionOfflineBlock().getPosition())) {
                        // Exited the mutex zone
                        currentMutex = null;
                    }

                    if (!foundHardRailObstacle) {
                        // If the current rail block imposes a speed limit, set that right now
                        // Ignore successive equal speed limits (speed traps), that would cause too many obstacles
                        {
                            double railSpeedLimit = iter.getPredictedSpeedLimit();
                            if (railSpeedLimit < lastRailSpeedLimit) {
                                lastRailSpeedLimit = railSpeedLimit;
                                obstacles.add(new RailObstacle(distanceFromFront, railSpeedLimit, iter.state.railPiece()));
                                if (railSpeedLimit <= 0.0) {
                                    foundHardRailObstacle = true; // No need to check further
                                }
                            }
                        }

                        // Check for mutex zones the next block. If one is found that is occupied, stop right away
                        if (currentMutex == null && distanceFromFront < mutexSoftDistance) {
                            currentMutex = mutexZones.get(iter.state.positionOfflineBlock().getPosition());
                            if (currentMutex != null) {
                                currentMutexGroup = currentMutex.slot.track(group);
                                currentMutexDistance = distanceFromFront;
                                currentMutexHard = distanceFromFront <= mutexHardDistance;
                                handledNonSmartMutex = false;
                            }
                        }
                    }

                    // Refresh smart mutex zones' occupied rail blocks.
                    if (currentMutex != null) {
                        updateCurrentMutex(iter);
                    }
                }

                // Only check for trains on the rails when a wait distance is set
                if (!checkTrains) {
                    continue;
                }

                // Check all other minecarts on the same rails to see if they are too close
                Location state_position = null;
                Location member_position = null;
                MinecartMember<?> minMemberAhead = null;
                double minSpeedAhead = Double.MAX_VALUE;
                double minDistanceAhead = 0.0;
                for (MinecartMember<?> member : iter.state.railPiece().members()) {
                    if (member.getGroup() == group) {
                        continue;
                    }

                    // Retrieve & re-use (readonly)
                    if (state_position == null) {
                        state_position = iter.state.positionLocation();
                    }

                    // Member center position & re-use (readonly)
                    if (member_position == null) {
                        member_position = member.getEntity().getLocation();
                    } else {
                        member.getEntity().getLocation(member_position);
                    }

                    // Is the minecart 'in front' of the current position on the rails, or behind us?
                    // This is important when iterating over the first track only, because then this is not guaranteed
                    if (iter.movedTotal == 0.0) {
                        Vector delta = new Vector(member_position.getX() - state_position.getX(),
                                                  member_position.getY() - state_position.getY(),
                                                  member_position.getZ() - state_position.getZ());
                        if (delta.dot(iter.state.motionVector()) < 0.0) {
                            continue;
                        }
                    }

                    // Compute distance from the current rail position to the 'edge' of the minecart.
                    // This is basically the distance to center, with half the length of the minecart subtracted.
                    double distanceToMember = member_position.distance(state_position) -
                                              (double) member.getEntity().getWidth() * 0.5;

                    // Find the distance we can still move from our current position
                    if ((distanceFromFront + distanceToMember) > waitDistance) {
                        continue;
                    }

                    // Movement speed of the minecart, taking maximum speed into account
                    Vector member_velocity = member.getEntity().getVelocity();
                    double speedAhead = MathUtil.clamp(member_velocity.length(), member.getEntity().getMaxSpeed());

                    // If moving towards me, stop right away! When barely moving, ignore this check.
                    if (speedAhead > 1e-6 && iter.state.position().motDot(member_velocity) < 0.0) {
                        obstacles.add(new TrainObstacle(distanceFromFront + distanceToMember, trainDistance, 0.0, member));
                        continue;
                    }

                    // Too close, match the speed of the Minecart ahead. For the overshoot, slow ourselves down.
                    if (speedAhead < 0.0) {
                        speedAhead = 0.0;
                    }
                    if (speedAhead < minSpeedAhead) {
                        minMemberAhead = member;
                        minSpeedAhead = speedAhead;
                        minDistanceAhead = distanceFromFront + distanceToMember;
                    }
                }
                if (minSpeedAhead != Double.MAX_VALUE) {
                    obstacles.add(new TrainObstacle(minDistanceAhead, trainDistance, minSpeedAhead, minMemberAhead));
                }
            }

            // While we are still updating mutex information, navigate the track until we exit the zone
            // This is only important for smart mutexes
            // This might cause a new obstacle to be inserted from when the train reached the start of the zone
            if (currentMutex != null && currentMutex.smart) {
                // Exceeding 64 blocks we enable the loop filter, as we probably reached an infinite loop of sorts...
                double enabledLoopFilterLimit = iter.movedTotal + 64.0;
                while (iter.moveFull()) {
                    if (iter.movedTotal >= enabledLoopFilterLimit) {
                        enabledLoopFilterLimit = Double.MAX_VALUE;
                        iter.setLoopFilter(true);
                    }

                    // Check still within mutex. If not, abort.
                    if (!currentMutex.containsBlock(iter.state.positionOfflineBlock().getPosition())) {
                        break;
                    }

                    // Update
                    if (!updateCurrentMutex(iter)) {
                        break;
                    }
                }
            }

            return obstacles;
        }

        /**
         * Updates the current mutex being tracked. Returns whether to continue feeding it more track.
         *
         * @param iter
         * @return True if more track is requested
         */
        private boolean updateCurrentMutex(TrackWalkingPoint iter) {
            MutexZoneSlot.EnterResult result;
            if (currentMutex.smart) {
                // Track every rail block visited while navigating within the mutex zone
                result = currentMutexGroup.enterRail(currentMutexHard,
                                                     iter.state.railPiece().blockPosition());
            } else if (!handledNonSmartMutex) {
                // Only handle non-smart mutexes once. No need to check them often, as they won't change result.
                result = currentMutexGroup.enterZone(currentMutexHard);
                handledNonSmartMutex = true;
            } else {
                // Skip.
                result = MutexZoneSlot.EnterResult.IGNORED;
            }
            if (result == MutexZoneSlot.EnterResult.OCCUPIED_HARD) {
                // At this point the train is guaranteed stopped. Don't check for more mutex zones now.
                // This is a hard stop, so we slow down to speed 0
                foundHardRailObstacle = true;
                obstacles.add(new MutexZoneObstacle(currentMutexDistance, 0.0, currentMutex));
                currentMutex = null; // stop checking
                return false;
            } else if (result == MutexZoneSlot.EnterResult.OCCUPIED_SOFT && currentMutexDistance < lastAddedSoftMutexObstacleDistance) {
                // At this point the train is guaranteed stopped. Don't check for more mutex zones now.
                // This is a soft stop, so we slow down to a crawl, but still moving
                // We might find out later on that this changes from soft to hard...
                lastAddedSoftMutexObstacleDistance = currentMutexDistance;
                obstacles.add(new MutexZoneObstacle(currentMutexDistance, 0.01, currentMutex));
            } else if (result == MutexZoneSlot.EnterResult.SUCCESS) {
                // Track mutex zones we have entered (train status!)
                if (!enteredMutexZones.contains(currentMutex)) {
                    if (enteredMutexZones.isEmpty()) {
                        enteredMutexZones = new ArrayList<MutexZone>();
                    }
                    enteredMutexZones.add(currentMutex);
                }
            } else if (result == MutexZoneSlot.EnterResult.IGNORED) {
                // Break out, no need to check this.
                return false;
            }

            return true;
        }
    }

    /**
     * A detected obstacle ahead of the train. Includes information about how far away the obstacle is,
     * and the speed the obstacle is moving away from the train. To calculate a safe speed for
     * the train to avoid it, use {@link Obstacle#findSpeedLimit(double)}
     */
    public static abstract class Obstacle {
        public final double distance;
        public final double speed;

        public Obstacle(double distance, double speed) {
            this.distance = Math.max(0.0, distance); // Avoid pain
            this.speed = speed;
        }

        /**
         * Creates a suitable train status for this Obstacle
         *
         * @param speedLimit Speed limit calculated using {@link #findSpeedLimit(double)}
         * @return Train Status
         */
        protected abstract TrainStatus createStatus(ObstacleSpeedLimit speedLimit);

        /**
         * Gets the maximum speed the original train that found this obstacle can have without
         * colliding with it at the train's wait deceleration rate.
         *
         * @param deceleration The rate of deceleration the train can have at most. Use 0.0 or
         *                     {@link Double#MAX_VALUE} for instantaneous.
         * @return speed of the train to stay clear of the obstacle
         */
        public ObstacleSpeedLimit findSpeedLimit(double deceleration) {
            // If obstacle is closer than it is allowed to ever be, emergency stop
            // This also ignores the vehicle's actual speed, because we're too close to
            // it already, so we need to stop and wait for the distance to go above
            // the safe wait distance threshold again.
            if (distance <= 0.0) {
                return new ObstacleSpeedLimit(this, Math.max(0.0, speed), true);
            }

            // If no wait deceleration is used, just keep on going at the speed following
            // this train ahead, plus the max distance we can move extra this tick.
            if (deceleration <= 0.0 || deceleration == Double.MAX_VALUE) {
                return new ObstacleSpeedLimit(this, Math.max(0.0, speed + distance), true);
            }

            // Based on this formula: a = (v^2 - u^2) / (2s) where v=0
            //                        (-u^2) / (2s) = a
            //                        (u^2) = -2*s*a
            //                        u = sqrt(-2*s*a)
            // Where: a = acceleration, v=final speed (0), u=start speed, s=distance
            // This computes the rough start speed (u)
            double startSpeed = Math.sqrt(2.0 * deceleration * this.distance);

            // The above is for linear time, not discrete time. So it's not completely accurate
            // We divide start speed by deceleration to get the number of 1/20 seconds periods
            //                        t = sqrt(2*s*d) / d where d=deceleration
            // This is ceiled, to get the number of discrete ticks we got to slow down to 0
            int numSlowdownTicks = MathUtil.ceil(startSpeed / deceleration);

            // Reduce the number of ticks of deceleration we got until traveled distance <= threshold
            // It's not pretty, but this while will probably only loop once or twice.
            while ((((numSlowdownTicks+1) * numSlowdownTicks) * 0.5 * deceleration) > this.distance) {
                numSlowdownTicks--;
            }

            if (numSlowdownTicks == 0) {
                // If number of ticks is 0, then there is a less than deceleration rate distance remaining
                // Move this tick at most the full obstacle distance remaining
                startSpeed = this.distance + this.speed;
            } else {
                // Knowing how many ticks of deceleration it takes, we can turn it into an approximate start speed
                startSpeed = numSlowdownTicks * deceleration + this.speed;
            }

            return new ObstacleSpeedLimit(this, Math.max(0.0, startSpeed), false);
        }
    }

    /**
     * Another train obstacle
     */
    public static class TrainObstacle extends Obstacle {
        /** The full distance, which includes the distance to keep between the trains */
        public final double fullDistance;
        /** The first Member encountered of the train */
        public final MinecartMember<?> member;

        public TrainObstacle(double fullDistance, double spaceDistance, double speed, MinecartMember<?> member) {
            super(fullDistance - spaceDistance, speed);
            this.fullDistance = fullDistance;
            this.member = member;
        }

        @Override
        protected TrainStatus createStatus(ObstacleSpeedLimit speedLimit) {
            if (speedLimit.isStopped()) {
                return new TrainStatus.WaitingForTrain(member, fullDistance);
            } else {
                return new TrainStatus.FollowingTrain(member, fullDistance, speedLimit.speed);
            }
        }
    }

    /**
     * A mutex zone obstacle
     */
    public static class MutexZoneObstacle extends Obstacle {
        public final MutexZone zone;

        public MutexZoneObstacle(double distance, double speed, MutexZone zone) {
            super(distance, speed);
            this.zone = zone;
        }

        @Override
        protected TrainStatus createStatus(ObstacleSpeedLimit speedLimit) {
            if (speedLimit.isStopped()) {
                return new TrainStatus.WaitingForMutexZone(zone);
            } else {
                return new TrainStatus.ApproachingMutexZone(zone, distance, speed);
            }
        }
    }

    /**
     * A generic rail obstacle, like a blocker or speed trap
     */
    public static class RailObstacle extends Obstacle {
        public final RailPiece rail;

        public RailObstacle(double distance, double speed, RailPiece rail) {
            super(distance, speed);
            this.rail = rail;
        }

        @Override
        protected TrainStatus createStatus(ObstacleSpeedLimit speedLimit) {
            if (speedLimit.isStopped()) {
                return new TrainStatus.WaitingAtRailBlock(this.rail);
            } else {
                return new TrainStatus.ApproachingRailSpeedTrap(this.rail, this.distance, this.speed);
            }
        }
    }

    /**
     * A speed limit a train should stick to, to avoid hitting the obstacle.
     * Contains the speed to limit the train to, and whether the train should
     * adjust instantly to this speed or not.
     */
    public static class ObstacleSpeedLimit {
        /**
         * A constant speed limit that represents 'no' speed limit
         */
        public static final ObstacleSpeedLimit NONE = new ObstacleSpeedLimit(null, Double.MAX_VALUE, false);
        /**
         * The {@link Obstacle} that imposes this speed limit
         */
        public final Obstacle obstacle;
        /**
         * Speed to maintain. {@link Double#MAX_VALUE} if there is no speed limit.
         */
        public final double speed;
        /**
         * Whether the train should adjust it's speed instantly, or allow for a
         * gradual slowdown using wait deceleration.
         */
        public final boolean instant;

        public ObstacleSpeedLimit(Obstacle obstacle, double speed, boolean instant) {
            this.obstacle = obstacle;
            this.speed = speed;
            this.instant = instant;
        }

        /**
         * Creates a suitable train status for this speed limit and its obstacle
         *
         * @return Train Status
         */
        public TrainStatus getStatus() {
            return obstacle.createStatus(this);
        }

        /**
         * Gets whether a speed limit exists at all. If false, then there is
         * no obstacle ahead, or it is far enough away to not pose a threat.
         *
         * @return True if there is a speed limit
         */
        public boolean hasLimit() {
            return this.speed != Double.MAX_VALUE;
        }

        /**
         * Gets whether the train should be stopped completely
         *
         * @return True if the train is stopped completely
         */
        public boolean isStopped() {
            return this.instant && this.speed <= 0.0;
        }

        @Override
        public String toString() {
            if (this.obstacle == null) {
                return "{NONE}";
            }
            return "{speed=" + this.speed + ", instant=" + this.instant + ", obstacle=" +
                    this.obstacle.getClass().getSimpleName() + "}";
        }
    }
}
