package dev.falix.studiomobile;

final class RobloxAnimations {
    static final class Pose {
        float bobY;
        float torsoPitch, torsoYaw;
        float headYaw, headPitch;
        float lUpperArmX, lUpperArmZ, lLowerArmX;
        float rUpperArmX, rUpperArmZ, rLowerArmX;
        float lUpperLegX, lLowerLegX;
        float rUpperLegX, rLowerLegX;
    }

    static Pose evaluate(boolean isR15, boolean onGround, double speed, double walkSpeed,
                         double walkPhase, double idleTime, double landTimer, double velY) {
        Pose p = new Pose();
        float speedFrac = (float) Math.min(1.25, speed / Math.max(1.0, walkSpeed));
        float moveBlend = Math.min(1.0f, speedFrac * 1.4f);

        if (!onGround) {
            if (isR15) {
                boolean rising = velY > 2.0;
                p.torsoPitch = rising ? -0.08f : 0.06f;
                p.lUpperArmX = rising ? -2.35f : -2.65f;
                p.rUpperArmX = rising ? -2.35f : -2.65f;
                p.lUpperArmZ = -0.28f;
                p.rUpperArmZ = 0.28f;
                p.lLowerArmX = -0.45f;
                p.rLowerArmX = -0.45f;
                p.lUpperLegX = rising ? -0.45f : -0.25f;
                p.lLowerLegX = rising ? 0.78f : 0.48f;
                p.rUpperLegX = rising ? 0.22f : 0.12f;
                p.rLowerLegX = rising ? 0.42f : 0.30f;
            } else {
                // Classic Roblox R6 Jump / Fall: iconic arms straight up overhead
                p.lUpperArmX = velY > 0 ? -3.05f : -2.75f;
                p.rUpperArmX = velY > 0 ? -3.05f : -2.75f;
                p.lUpperLegX = 0.16f;
                p.rUpperLegX = -0.16f;
            }
            return p;
        }

        // Idle keyframe loop (Roblox default Animate idle + occasional head look-around)
        float idleBreath = (float) Math.sin(idleTime * 2.1);
        float lookCycle = (float) (idleTime % 7.5);
        float idleHeadYaw = 0f;
        if (lookCycle > 4.5f && lookCycle < 6.5f) {
            idleHeadYaw = (float) Math.sin((lookCycle - 4.5f) * Math.PI * 0.5) * 0.32f;
        }

        float landSquash = landTimer > 0 ? (float) Math.sin(landTimer * Math.PI / 0.18) * 0.22f : 0f;

        if (!isR15) {
            // Official Roblox R6 Walk / Run / Idle cycle
            float swing = (float) Math.sin(walkPhase) * 0.92f * Math.min(1.0f, speedFrac);
            float idleArm = idleBreath * 0.07f * (1f - moveBlend);
            p.bobY = Math.abs((float) Math.sin(walkPhase)) * 0.10f * moveBlend - landSquash * 0.6f;
            p.headYaw = idleHeadYaw * (1f - moveBlend);
            p.lUpperArmX = -swing + idleArm;
            p.rUpperArmX = swing - idleArm;
            p.lUpperLegX = swing;
            p.rUpperLegX = -swing;
            return p;
        }

        // Official Roblox R15 Walk / Run / Idle keyframe synthesis
        float runBlend = Math.max(0f, Math.min(1f, (speedFrac - 0.55f) / 0.45f));
        float phase = (float) walkPhase;
        float s = (float) Math.sin(phase);
        float c = (float) Math.cos(phase);

        float ampLeg = (0.58f + 0.32f * runBlend) * moveBlend;
        float ampArm = (0.52f + 0.38f * runBlend) * moveBlend;

        p.bobY = (Math.abs(s) * (0.12f + 0.08f * runBlend) * moveBlend) + idleBreath * 0.025f * (1f - moveBlend) - landSquash;
        p.torsoPitch = (0.06f + 0.14f * runBlend) * moveBlend;
        p.torsoYaw = s * (0.10f + 0.06f * runBlend) * moveBlend;
        p.headYaw = idleHeadYaw * (1f - moveBlend) - p.torsoYaw * 0.5f;

        p.lUpperArmX = -s * ampArm + idleBreath * 0.04f * (1f - moveBlend);
        p.rUpperArmX = s * ampArm - idleBreath * 0.04f * (1f - moveBlend);
        p.lLowerArmX = -(0.14f + Math.max(0f, -p.lUpperArmX) * (0.55f + 0.45f * runBlend) + 0.25f * runBlend * moveBlend);
        p.rLowerArmX = -(0.14f + Math.max(0f, -p.rUpperArmX) * (0.55f + 0.45f * runBlend) + 0.25f * runBlend * moveBlend);

        p.lUpperLegX = s * ampLeg;
        p.rUpperLegX = -s * ampLeg;
        float kneeL = Math.max(0f, -c) * (0.55f + 0.45f * runBlend) * moveBlend + landSquash * 1.4f;
        float kneeR = Math.max(0f, c) * (0.55f + 0.45f * runBlend) * moveBlend + landSquash * 1.4f;
        p.lLowerLegX = kneeL;
        p.rLowerLegX = kneeR;
        return p;
    }
}
