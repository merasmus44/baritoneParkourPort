package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
// Move into MovementHelper
public class MovementPrediction {
    private MovementPrediction() {
    }

    /**
     * Mutable
     */
    public static class PredictionResult {
        Player player;




        private final Map<MobEffect, MobEffectInstance> activePotionsMap;



        public int tick = 0; // ticks from the present
        /**
         * Currently does not take into account blocks that prevent/negate fall damage (Slime, etc.)
         */
        float damageTaken = 0;
        /** Tick (from present) when we last jumped */
        private int lastJump = -10; // assume jump is allowed at initialization
        public boolean collided = false;
        public boolean collidedVertically = false;
        public boolean collidedHorizontally = false;
        public double rotationYaw;
        boolean isJumping = false;
        public boolean isAirBorne;
        boolean isSneaking = false; // changed in update()
        public boolean onGround;
        double motionX;
        double motionY;
        double motionZ;
        AABB boundingBox;
      
        private final ArrayList<Vec3> positionCache = new ArrayList<>();
      
        public double posX;
        public double posY;
        public double posZ;
        float fallDistance;

        public PredictionResult(Player p) {
            player = p;
            //activePotionsMap = p.getActiveEffects();
            activePotionsMap = new HashMap<>();
	    for (MobEffectInstance effectInstance : p.getActiveEffects()) {
		   activePotionsMap.put(effectInstance.getEffect(), effectInstance);
	    }
            posX = p.getX();
            posY = p.getY();
            posZ = p.getZ();
            rotationYaw = p.getYRot();

	    Vec3 pVelocity = p.getDeltaMovement();

            motionX = pVelocity.x;
            motionY = pVelocity.y;
            motionZ = pVelocity.z;
            isAirBorne = p.isFallFlying();
            onGround = p.onGround();
            double playerWidth = 0.3; // 0.3 in each direction
            double playerHeight = 1.8; // modified while sneaking?
            boundingBox = new AABB(posX - playerWidth, posY, posZ - playerWidth, posX + playerWidth, posY + playerHeight, posZ + playerWidth);
            positionCache.add(new Vec3(posX, posY, posZ)); // prevent null pointers
        }

        public void resetPositionToBB() {
            this.posX = (boundingBox.minX + boundingBox.maxX) / 2.0D;
            this.posY = boundingBox.minY;
            this.posZ = (boundingBox.minZ + boundingBox.maxZ) / 2.0D;
        }

        public void updateFallState(double y, boolean onGroundIn, BlockState iblockstate) {
            if (onGroundIn) {
                // Change fall damage if block negates it? HayBale?
                // if iblockstate.getBlock() instanceof Block... fallDistance = 0 etc..
                // Fall damage
                float f = getPotionAmplifier(MobEffects.JUMP);
                int i = Mth.ceil((fallDistance - 3.0F - f));
                damageTaken += i;
                this.fallDistance = 0.0F;
            } else if (y < 0.0D) {
                this.fallDistance = (float) ((double) this.fallDistance - y);
            }
        }

        public Vec3 getPosition() {
            return positionCache.get(tick);
        }

        public Vec3 getPosition(int tick) {
            return positionCache.get(tick);
        }

        public boolean canJump() {
            return tick - lastJump >= 10 && onGround;
        }

        /**
         * returns the Amplifier of the potion if present, 0 otherwise.
         * returns 0 if considerPotionEffects setting is false.
         * Amplifier starts at 1
         */
        public int getPotionAmplifier(MobEffect potionIn) {
            if (Baritone.settings().considerPotionEffects.value && isPotionActive(potionIn)) {
                return activePotionsMap.get(potionIn).getAmplifier() + 1;
            }
            return 0;
        }

        public boolean isPotionActive(MobEffect potionIn) {
            MobEffectInstance effect = activePotionsMap.get(potionIn);
            if (effect == null) {
                return false;
            }
            if (effect.getDuration() < tick)             {
                activePotionsMap.remove(potionIn);
                return false;
            }
            return true;
        }

        /**
         * Calculates the next tick.
         * Updates all variables to match the predicted next tick.
         */
        public void update(MovementState state) {
            isSneaking = state.getInputStates().getOrDefault(Input.SNEAK, false);
            isJumping = canJump() && state.getInputStates().getOrDefault(Input.JUMP, false);
            if (isJumping) lastJump = tick;
            rotationYaw = state.getTarget().rotation.getYaw();
            onLivingUpdate(this, state);
            positionCache.add(new Vec3(posX, posY, posZ));
            tick++;
        }

        /**
         * Calculates up to the given tick
         * (does not go backwards)
         * May be inaccurate with large state changes
         *
         * @param state The key presses for the duration of this calculation
         * @param tick  The tick to calculate up to
         */
        public void setTick(MovementState state, int tick) {
            for (int i = this.tick; i < tick; i++) {
                update(state);
            }
        }

        public PredictionResult recalculate(MovementState state) {
            return getFutureLocation(player, state, tick);
        }
    }

    public static PredictionResult getFutureLocation(Player p, MovementState state, int ticksInTheFuture) {
        PredictionResult r = new PredictionResult(p);
        for (int tick = 0; tick < ticksInTheFuture; tick++) {
            r.update(state);
        }
        return r;
    }

    /**
     * Checks the if movement collides with blocks (no stair steps, no sneak till edge)
     *
     * @param r The player parameters to update
     */
    public static void moveAndCheckCollisions(PredictionResult r) {
        double x = r.motionX;
        double y = r.motionY;
        double z = r.motionZ;

        // save initial values
        double initX = x;
        double initY = y;
        double initZ = z;

        // Calculate block collisions
        //List<AABB> nearbyBBs = r.player.level().getCollisionBoxes(r.player, r.boundingBox.inflate(x, y, z));
	List<AABB> nearbyBBs = new ArrayList<>();
	for (VoxelShape shape : r.player.level().getCollisions(r.player, r.boundingBox.inflate(x, y, z))) {
	    nearbyBBs.add(shape.bounds());
	}

        if (y != 0) {
            int i = 0;
            for (int listSize = nearbyBBs.size(); i < listSize; ++i) {
                //y = nearbyBBs.get(i).calculateYOffset(r.boundingBox, y);
		AABB nearbyBB = nearbyBBs.get(i);
   		double minYOffset = r.boundingBox.minY - nearbyBB.maxY;
   		double maxYOffset = nearbyBB.minY - r.boundingBox.maxY;
   		y = Math.min(y, Math.max(minYOffset, maxYOffset));
     	    }
            r.boundingBox = r.boundingBox.move(0, y, 0);
        }

        if (x != 0) {
            int i = 0;
            for (int listSize = nearbyBBs.size(); i < listSize; ++i) {
                //x = nearbyBBs.get(i).calculateXOffset(r.boundingBox, x);
		AABB nearbyBB = nearbyBBs.get(i);
 		double minXOffset = r.boundingBox.minX - nearbyBB.maxX;
		double maxXOffset = nearbyBB.minX - r.boundingBox.maxX;
    		x = Math.min(x, Math.max(minXOffset, maxXOffset));
            }
            if (x != 0) {
                r.boundingBox = r.boundingBox.move(x, 0, 0);
            }
        }

        if (z != 0) {
            int i = 0;
            for (int listSize = nearbyBBs.size(); i < listSize; ++i) {
                //z = nearbyBBs.get(i).calculateZOffset(r.boundingBox, z);
		AABB nearbyBB = nearbyBBs.get(i);
  	  	double minZOffset = r.boundingBox.minZ - nearbyBB.maxZ;
  	 	double maxZOffset = nearbyBB.minZ - r.boundingBox.maxZ;
  		z = Math.min(x, Math.max(minZOffset, maxZOffset));
            }
            if (z != 0) {
                r.boundingBox = r.boundingBox.move(0, 0, z);
            }
        }


        // Set position
        r.resetPositionToBB();

        // update some movement related variables
        r.collidedHorizontally = initX != x || initZ != z;
        r.collidedVertically = initY != y;
        r.collided = r.collidedHorizontally || r.collidedVertically;
        r.onGround = r.collidedVertically && initY < 0.0D; // collided vertically in the downwards direction

        // Check block underneath for fences/etc. that could cause fall damage early
        int blockX = Mth.floor(r.posX);
        int blockYdown = Mth.floor(r.posY - 0.20000000298023224D);
        int blockZ = Mth.floor(r.posZ);
        BlockPos blockpos = new BlockPos(blockX, blockYdown, blockZ);
        BlockState landingBlockState = r.player.level().getBlockState(blockpos);
        if (landingBlockState.isAir()) {
            BlockState posbFenceState = r.player.level().getBlockState(blockpos.below());
            Block blockBelow = posbFenceState.getBlock();

            if (blockBelow instanceof FenceBlock || blockBelow instanceof WallBlock || blockBelow instanceof FenceGateBlock) {
                landingBlockState = posbFenceState;
            }
        }

        // fall damage
        r.updateFallState(y, r.onGround, landingBlockState);

        // Set motion to 0 if collision occurs
        if (initX != x) {
            r.motionX = 0.0D;
        }
        if (initZ != z) {
            r.motionZ = 0.0D;
        }

        // Calculate landing collisions
        Block landingBlock = landingBlockState.getBlock();
        // replaced landingBlock.onLanded()
        if (r.collidedVertically) {
            if (landingBlock instanceof SlimeBlock && !r.isSneaking) {
                if (r.motionY < 0.0D) {
                    r.motionY = -r.motionY;
                }
            } else if (landingBlock instanceof BedBlock && !r.isSneaking) {
                if (r.motionY < 0.0D) {
                    r.motionY = -r.motionY * 0.6600000262260437D;
                }
            } else {
                r.motionY = 0;
            }
        }
    }

    public static void onLivingUpdate(PredictionResult r, MovementState state) {
        double strafe = 0;
        if (state.getInputStates().getOrDefault(Input.MOVE_LEFT, false)) {
            strafe += 1;
        }
        if (state.getInputStates().getOrDefault(Input.MOVE_RIGHT, false)) {
            strafe -= 1;
        }

        double forward = 0;
        if (state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false)) {
            forward += 1;
        }
        if (state.getInputStates().getOrDefault(Input.MOVE_BACK, false)) {
            forward -= 1;
        }

        if (r.isSneaking) {
            forward *= 0.3;
            strafe *= 0.3;
        }

        strafe *= 0.98F;
        forward *= 0.98F;

        // inertia determines how much speed is conserved on the next tick
        double inertia = 0.91;
        if (r.onGround) {
            inertia = r.player.level().getBlockState(new BlockPos(Mth.floor(r.posX), Mth.floor(r.posY) - 1, Mth.floor(r.posZ))).getBlock().getFriction() * 0.91F; // -1 is 0.5 in 1.15+
        }

        // acceleration = (0.6*0.91)^3 / (slipperiness*0.91)^3) -> redundant calculations...
        double acceleration = 0.16277136F / (inertia * inertia * inertia);

        double moveMod;
        if (r.onGround) {
            moveMod = 0.1 * acceleration * (r.getPotionAmplifier(MobEffects.MOVEMENT_SPEED) * 0.2 - r.getPotionAmplifier(MobEffects.MOVEMENT_SLOWDOWN) * 0.15 + 1);
        } else {
            moveMod = 0.02F;
        }

        if (state.getInputStates().getOrDefault(Input.SPRINT, false)) {
            moveMod *= 1.3F;
        }

        double distance = strafe * strafe + forward * forward;
        if (distance >= 1.0E-4F) {
            distance = Math.sqrt(distance);

            if (distance < 1.0F)
                distance = 1.0F;

            distance = moveMod / distance;
            strafe = strafe * distance;
            forward = forward * distance;
            float sinYaw = Mth.sin((float) (r.rotationYaw * RotationUtils.DEG_TO_RAD));
            float cosYaw = Mth.cos((float) (r.rotationYaw * RotationUtils.DEG_TO_RAD));
            r.motionX += strafe * cosYaw - forward * sinYaw;
            r.motionZ += forward * cosYaw + strafe * sinYaw;
        }

        if (r.isJumping) {
            r.motionY = 0.42 + r.getPotionAmplifier(MobEffects.JUMP) * 0.1;
            if (state.getInputStates().getOrDefault(Input.SPRINT, false)) {
                double f = r.rotationYaw * RotationUtils.DEG_TO_RAD;
                r.motionX -= Math.sin(f) * 0.2;
                r.motionZ += Math.cos(f) * 0.2;
            }
            r.isAirBorne = true;
        }

        // new location
        moveAndCheckCollisions(r);

        // ending motion
        r.motionX *= inertia;
        r.motionZ *= inertia;
        r.motionY = (r.motionY - 0.08) * 0.98; // gravity and drag
    }
}

