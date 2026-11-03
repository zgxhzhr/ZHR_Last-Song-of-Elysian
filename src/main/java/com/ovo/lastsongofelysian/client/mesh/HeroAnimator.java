package com.ovo.lastsongofelysian.client.mesh;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * 程序化动画：不依赖 VMD 文件，直接按实体状态驱动骨骼。
 *
 * <p>之所以先做这个而不是等 VMD：
 * <ul>
 *   <li>NPC 需要的是「站着会呼吸、走起来腿会摆」，不需要复杂的舞蹈动作</li>
 *   <li>程序化动画没有任何资源依赖，改一个系数就能调手感</li>
 *   <li>VMD 播放器可以后加，作为「玩家自备动作」的可选通道</li>
 * </ul>
 *
 * <p>骨骼名取自梅比乌斯模型的实际命名（日文）。找不到的骨骼会静默跳过，
 * 所以换成别的英桀模型也不会崩——只是少几段动画。
 */
public final class HeroAnimator {

    /**
     * 基础站姿：把模型自带的 A-pose 手臂收下来。
     *
     * <p>MMD 骨骼命名里「右腕」是<b>上臂</b>（右肩=肩 / 右腕=上臂 / 右ひじ=前臂 /
     * 右手首=手），不是手腕。模型的绑定姿势手臂外张约 35°，直接站着看很僵，
     * 这里绕 Z 轴把上臂往身侧转。
     *
     * <p>正负号：模型面朝 -Z，右手在 -X 侧。右臂朝 -X 延伸，绕 Z 轴正向旋转会
     * 把它压向 -Y（下垂）；左臂镜像，取负。
     */
    private static final float ARM_REST_Z = 0.42F;

    /** 视线追踪半径（格）。 */
    private static final double LOOK_RANGE = 10.0;

    /** 头部最多能转多少度，超出部分交给眼球。 */
    private static final float HEAD_YAW_LIMIT = 55.0F;
    private static final float HEAD_PITCH_LIMIT = 22.0F;

    /**
 * 眼球最多补多少度。
 *
 * <p>MMD 的眼球骨骼（両目/右目/左目）本意只是「视线微微一偏」，转十几度
 * 会让瞳孔跑到眼角外，看起来像斜视。头部已经能转 55°，眼球只留很小余量。
 */
    private static final float EYE_YAW_LIMIT = 6.0F;
    private static final float EYE_PITCH_LIMIT = 3.0F;

    /** 头部/眼球的平滑速度（每 tick 收敛比例，越小越迟缓）。 */
    private static final float LOOK_SMOOTH = 0.35F;

    private HeroAnimator() {
    }

    /**
     * 按实体当前状态设置骨骼姿势。调用前 {@link MeshSkeleton#reset()} 由本方法负责。
     *
     * @param partialTick 插值用，避免低帧率下动作一跳一跳
     */
    public static void pose(MeshSkeleton skeleton, LivingEntity entity, float partialTick) {
        skeleton.reset();

        // --- 基础站姿：先收手臂，后面所有动画都在这个基础上叠加 ---
        int restArmR = firstBone(skeleton, "右腕");
        int restArmL = firstBone(skeleton, "左腕");
        if (restArmR >= 0) {
            skeleton.setRotation(restArmR, 0.0F, 0.0F, ARM_REST_Z);
        }
        if (restArmL >= 0) {
            skeleton.setRotation(restArmL, 0.0F, 0.0F, -ARM_REST_Z);
        }
        // 前臂轻微内收，避免胳膊太直像假人
        int restForeR = firstBone(skeleton, "右ひじ");
        int restForeL = firstBone(skeleton, "左ひじ");
        if (restForeR >= 0) {
            skeleton.setRotation(restForeR, 0.0F, 0.0F, 0.12F);
        }
        if (restForeL >= 0) {
            skeleton.setRotation(restForeL, 0.0F, 0.0F, -0.12F);
        }

        float time = entity.tickCount + partialTick;
        float walkPhase = entity.walkAnimation.position(partialTick);
        float walkSpeed = entity.walkAnimation.speed(partialTick);

        // 移动量 0~1：静止时 0，全速走时接近 1
        float move = Mth.clamp(walkSpeed, 0.0F, 1.0F);

        // 攻击时上半身前倾
        boolean attacking = entity.swinging;
        float swing = entity.getAttackAnim(partialTick);

        // --- 呼吸：上半身轻微起伏，幅度随移动减弱 ---
        float breath = Mth.sin(time * 0.08F) * (0.008F * (1.0F - move));
        int upper = skeleton.findBone("上半身");
        if (upper >= 0) {
            skeleton.setRotation(upper, breath, 0.0F, 0.0F);
        }
        int upper2 = skeleton.findBone("上半身2");
        if (upper2 >= 0) {
            skeleton.setRotation(upper2, breath * 0.6F, 0.0F, 0.0F);
        }

        // --- 头部与眼球：看向附近玩家，没有目标时轻微环顾 ---
        int head = firstBone(skeleton, "頭");
        int bothEyes = firstBone(skeleton, "両目");
        int eyeR = firstBone(skeleton, "右目");
        int eyeL = firstBone(skeleton, "左目");

        net.minecraft.world.entity.player.Player lookTarget =
                entity.level().getNearestPlayer(entity, LOOK_RANGE);

        float headYawDeg;
        float headPitchDeg;
        float eyeYawDeg;
        float eyePitchDeg;

        if (lookTarget != null && !lookTarget.isSpectator()) {
            // 目标方向（MC 的 yaw：0 = +Z，90 = -X）
            double dx = lookTarget.getX() - entity.getX();
            double dz = lookTarget.getZ() - entity.getZ();
            // 视线落点取玩家眼睛高度，实体眼睛约在 1.62 格（MMD 头部高度换算后）
            double eyeHeight = entity.getBbHeight() * 0.90;
            double dy = lookTarget.getEyeY() - (entity.getY() + eyeHeight);

            float targetWorldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // 坐标系换算：MC 的 yaw 从 +Z 向 -X **顺时针**增长，而 MMD 骨骼的
            // Y 轴旋转是右手系（绕 +Y **逆时针**）。两者方向相反，所以送进骨骼前
            // 先取负——否则玩家站左边、头会往右转。
            // 头部和眼球共用这一个值，翻一次两边就都对了。
            float deltaYaw = -Mth.wrapDegrees(targetWorldYaw - entity.getYRot());
            // 俯仰同理：dy > 0 表示目标在头顶以上，此时骨骼 X 轴要正转才是抬头。
            // 之前这里取了负号，表现就是玩家站高处、她低头往下看。
            float deltaPitch = (float) Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

            // 头部分担大头，超出的给眼球；走路时头转幅度收一点，看起来更自然。
            // 用软限幅（tanh 形状）代替硬 clamp，目标转到极限附近时是渐进减速而不是
            // 突然卡住，这样"转头到边就丢目标"的突兀感会小很多。
            float yawLimit = HEAD_YAW_LIMIT * (1.0F - 0.25F * move);
            headYawDeg = softLimit(deltaYaw, yawLimit);
            headPitchDeg = softLimit(deltaPitch, HEAD_PITCH_LIMIT);
            eyeYawDeg = softLimit(deltaYaw - headYawDeg, EYE_YAW_LIMIT);
            eyePitchDeg = softLimit(deltaPitch - headPitchDeg, EYE_PITCH_LIMIT);
        } else {
            // 没人可看：原来的环顾动作
            headYawDeg = (float) Math.toDegrees(Mth.sin(time * 0.045F) * 0.09F * (1.0F - move));
            headPitchDeg = (float) Math.toDegrees(Mth.sin(time * 0.031F) * 0.04F * (1.0F - move));
            eyeYawDeg = 0.0F;
            eyePitchDeg = 0.0F;
        }

        // 平滑：用 tickCount 的世界时间做一阶低通，避免玩家快速横移时头猛甩
        float smoothHeadYaw = smooth(headYawDeg, entity, 0);
        float smoothHeadPitch = smooth(headPitchDeg, entity, 1);
        float smoothEyeYaw = smooth(eyeYawDeg, entity, 2);
        float smoothEyePitch = smooth(eyePitchDeg, entity, 3);

        if (head >= 0) {
            skeleton.setRotation(head,
                    (float) Math.toRadians(smoothHeadPitch),
                    (float) Math.toRadians(smoothHeadYaw),
                    0.0F);
        }
        if (bothEyes >= 0) {
            skeleton.setRotation(bothEyes,
                    (float) Math.toRadians(smoothEyePitch),
                    (float) Math.toRadians(smoothEyeYaw),
                    0.0F);
        } else {
            if (eyeR >= 0) {
                skeleton.setRotation(eyeR,
                        (float) Math.toRadians(smoothEyePitch),
                        (float) Math.toRadians(smoothEyeYaw), 0.0F);
            }
            if (eyeL >= 0) {
                skeleton.setRotation(eyeL,
                        (float) Math.toRadians(smoothEyePitch),
                        (float) Math.toRadians(smoothEyeYaw), 0.0F);
            }
        }

        // --- 走路：腿摆动 + 手臂反向摆 ---
        if (move > 0.01F) {
            float swingAngle = 0.55F * move;

            // 注意：MMD 里「右足」是 IK 目标、不带顶点权重，真正驱动网格的是「右足D」。
            // 这组 D 骨骼才是需要旋转的（右足D 1051 顶点 / 右ひざD 364 / 右足首D 155）。
            int rLeg = firstBone(skeleton, "右足D", "右足");
            int lLeg = firstBone(skeleton, "左足D", "左足");
            if (rLeg >= 0) {
                // X 轴正/负：MMD 里通常向前摆是 +X
                skeleton.setRotation(rLeg, Mth.sin(walkPhase) * swingAngle, 0.0F, 0.0F);
            }
            if (lLeg >= 0) {
                skeleton.setRotation(lLeg, Mth.sin(walkPhase + (float) Math.PI) * swingAngle, 0.0F, 0.0F);
            }

            int rKnee = firstBone(skeleton, "右ひざD", "右ひざ", "右膝");
            int lKnee = firstBone(skeleton, "左ひざD", "左ひざ", "左膝");
            if (rKnee >= 0) {
                float bend = Math.max(0.0F, Mth.sin(walkPhase + (float) Math.PI) * 0.6F) * move;
                skeleton.setRotation(rKnee, -bend, 0.0F, 0.0F);
            }
            if (lKnee >= 0) {
                float bend = Math.max(0.0F, Mth.sin(walkPhase) * 0.6F) * move;
                skeleton.setRotation(lKnee, -bend, 0.0F, 0.0F);
            }

            // --- 手臂摆动 ---
            // 关键：Z 轴必须保留 ARM_REST_Z，不能写 0。
            // 基础站姿是靠「上臂 Z 轴 ±ARM_REST_Z」把 A-pose 收下来的；
            // 走路时如果这里把 Z 清零，等于撤销站姿，手臂会瞬间弹回模型自带的
            // 外张姿势——看起来就是「一走动就变回默认动画」。
            int rArm = firstBone(skeleton, "右腕");
            int lArm = firstBone(skeleton, "左腕");
            if (rArm >= 0) {
                skeleton.setRotation(rArm,
                        Mth.sin(walkPhase + (float) Math.PI) * swingAngle * 0.9F,
                        0.0F,
                        ARM_REST_Z);
            }
            if (lArm >= 0) {
                skeleton.setRotation(lArm,
                        Mth.sin(walkPhase) * swingAngle * 0.9F,
                        0.0F,
                        -ARM_REST_Z);
            }

            // 前臂（右ひじ/左ひじ）跟随后摆，避免手臂像一根直棍。
            // Z 保持基础站姿的 ±0.12 不变，只加 X 方向的滞后摆动。
            int rFore = firstBone(skeleton, "右ひじ");
            int lFore = firstBone(skeleton, "左ひじ");
            if (rFore >= 0) {
                float trailR = Math.max(0.0F, Mth.sin(walkPhase + (float) Math.PI)) * 0.55F * move;
                skeleton.setRotation(rFore, trailR, 0.0F, 0.12F);
            }
            if (lFore >= 0) {
                float trailL = Math.max(0.0F, Mth.sin(walkPhase)) * 0.55F * move;
                skeleton.setRotation(lFore, trailL, 0.0F, -0.12F);
            }

            // 肩膀轻微参与，让摆臂从肩关节发起而不是整条手臂平移
            int rShoulder = firstBone(skeleton, "右肩");
            int lShoulder = firstBone(skeleton, "左肩");
            if (rShoulder >= 0) {
                skeleton.setRotation(rShoulder,
                        Mth.sin(walkPhase + (float) Math.PI) * swingAngle * 0.25F, 0.0F, 0.0F);
            }
            if (lShoulder >= 0) {
                skeleton.setRotation(lShoulder,
                        Mth.sin(walkPhase) * swingAngle * 0.25F, 0.0F, 0.0F);
            }

            // 走路时身体上下轻微起伏
            int center = skeleton.findBone("センター");
            if (center >= 0) {
                skeleton.setOffset(center, 0.0F, Mth.abs(Mth.sin(walkPhase)) * 0.35F * move, 0.0F);
            }
        }

        // --- 挥击：右臂快速前摆 ---
        if (attacking) {
            int rArm = firstBone(skeleton, "右腕");
            if (rArm >= 0) {
                float arc = Mth.sin(swing * (float) Math.PI) * 1.4F;
                skeleton.setRotation(rArm, arc, 0.0F, 0.0F);
            }
            int rShoulder = firstBone(skeleton, "右肩");
            if (rShoulder >= 0) {
                skeleton.setRotation(rShoulder, Mth.sin(swing * (float) Math.PI) * 0.35F, 0.0F, 0.0F);
            }
            if (upper >= 0) {
                skeleton.setRotation(upper, 0.20F * Mth.sin(swing * (float) Math.PI), 0.0F, 0.0F);
            }
        }

        // --- 受伤/死亡：身体后仰 ---
        if (entity.isDeadOrDying()) {
            int center = skeleton.findBone("センター");
            if (center >= 0) {
                skeleton.setOffset(center, 0.0F, -8.0F, 0.0F);
                skeleton.setRotation(center, -0.35F, 0.0F, 0.0F);
            }
        }
    }


    /**
     * 一阶低通平滑。状态存在实体的 persistentData 里，键按 slot 区分，
     * 这样不同实体互不干扰、也不需要在渲染器里额外维护缓存。
     */

    /**
     * 软限幅：|v| 远小于 limit 时近似线性通过，接近 limit 时平滑饱和到 limit。
     * 用 tanh 形状，避免硬 clamp 在边界处的突然停顿。
     */
    private static float softLimit(float v, float limit) {
        if (limit <= 0.0F) {
            return 0.0F;
        }
        return limit * (float) Math.tanh(v / limit);
    }

    private static float smooth(float target, LivingEntity entity, int slot) {
        var data = entity.getPersistentData();
        String key = "lse_look_" + slot;
        float prev = data.getFloat(key);
        // 首帧或长时间未更新时直接吸附，避免从 0 慢慢爬
        if (!data.contains(key)) {
            data.putFloat(key, target);
            return target;
        }
        float next = prev + (target - prev) * LOOK_SMOOTH;
        data.putFloat(key, next);
        return next;
    }

    private static int firstBone(MeshSkeleton skeleton, String... names) {
        for (String n : names) {
            int i = skeleton.findBone(n);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }
}
