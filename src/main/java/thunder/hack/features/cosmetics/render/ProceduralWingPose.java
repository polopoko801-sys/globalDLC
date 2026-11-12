package thunder.hack.features.cosmetics.render;

import java.util.Locale;

/** Authored procedural wing motion, expressed as additive Bedrock degrees and pixels. */
public final class ProceduralWingPose {
    private ProceduralWingPose() {}

    /**
     * Flight weights are smoothed by the caller once per player render. Ground has the remaining
     * weight; previews use zero for both. Root scale is applied only to bones without a parent.
     */
    public static BedrockAnimation.Pose sample(String modelKey, String bone, float seconds,
                                              boolean root, float flightWeight, float elytraWeight) {
        String key = modelKey == null ? "" : modelKey.toLowerCase(Locale.ROOT);
        int slash = key.lastIndexOf('/');
        if (slash >= 0) key = key.substring(slash + 1);
        if (key.endsWith(".geo.json")) key = key.substring(0, key.length() - 9);
        float time = seconds % 360f;
        BedrockAnimation.Pose pose = switch (key) {
            case "dragon", "dragon_wings", "baby_dragon_white" -> dragon(bone, time, flightWeight, elytraWeight);
            case "angel", "angel_wings", "angel_white" -> angel(bone, time * 200f);
            case "demon", "demon_wings", "demon_red", "demon_black" -> demon(bone, time * 200f);
            default -> BedrockAnimation.Pose.IDENTITY;
        };
        if (!root) return pose;
        float scale = switch (key) {
            case "dragon", "dragon_wings", "baby_dragon_white" -> .9f + .1f * (flightWeight + elytraWeight);
            case "angel", "angel_wings", "angel_white", "demon", "demon_wings", "demon_red", "demon_black" -> .6f;
            default -> 1f;
        };
        return new BedrockAnimation.Pose(pose.position(), pose.rotation(),
            new float[]{pose.scale()[0] * scale, pose.scale()[1] * scale, pose.scale()[2] * scale});
    }

    private static BedrockAnimation.Pose dragon(String bone, float t, float flight, float elytra) {
        float[] main, airborne, gliding;
        float[] scale = {1, 1, 1};
        switch (bone) {
            case "left_wing" -> {
                main = v(cos(-60 + t * 300) * 10, -30 + cos(t * 300) * 20, 0);
                airborne = v(7.8279f + cos(-60 + t * 600) * 10, -27.5839f + cos(t * 600) * 20, -12.46225f);
                gliding = v(cos(t * 2200) * -2, cos(t * 120) * -5, cos(30 + t * 120) * -5);
            }
            case "right_wing" -> {
                main = v(cos(-60 + t * 300) * 10, 30 - cos(t * 300) * 20, 0);
                airborne = v(7.8279f + cos(-60 + t * 600) * 10, 27.5839f - cos(t * 600) * 20, 12.46225f);
                gliding = v(cos(30 + t * 2500) * -2, cos(t * 120) * 5, cos(30 + t * 120) * 5);
            }
            case "left_tip" -> {
                main = v(0, cos(90 + t * 300) * -20, 0);
                airborne = v(0, cos(90 + t * 600) * -20, 0);
                gliding = v(0, cos(-90 + t * 120) * -10, 0);
            }
            case "right_tip" -> {
                main = v(0, cos(90 + t * 300) * 20, 0);
                airborne = v(0, cos(90 + t * 600) * 20, 0);
                gliding = v(0, cos(t * 120) * 10, 0);
            }
            default -> { return BedrockAnimation.Pose.IDENTITY; }
        }
        if (bone.equals("left_wing") || bone.equals("right_wing")) {
            scale = blend(v(1.2f + cos(90 + t * 300) * .2f, 1, 1.1f),
                v(1.2f + cos(90 + t * 600) * .2f, 1, 1.1f), v(1.4f, 1, 1.1f), flight, elytra);
        }
        return new BedrockAnimation.Pose(v(0, 0, 0), blend(main, airborne, gliding, flight, elytra), scale);
    }

    private static BedrockAnimation.Pose angel(String bone, float t) {
        return switch (bone) {
            case "Left_Wing" -> pose(-18.2468f + cos(10 + t) * 10, -36.3088f + sin(10 + t) * 15, 18.01613f,
                -.0288f, .20524f, 1.18197f);
            case "Right_Wing" -> pose(-18.2468f + cos(10 + t) * 10, 36.3088f - sin(10 + t) * 15, -18.01609f,
                -.0288f, .2052f, 1.18197f);
            case "bone6" -> pose(1.1552f - cos(10 + t) * 10, -.543f - cos(t) * 25, 3.16854f,
                -.90088f, .04987f, -.43119f);
            case "bone4" -> pose(1.1552f - cos(10 + t) * 10, .543f + cos(t) * 25, -3.1685f,
                .9009f, .04987f, -.43119f);
            case "bone7" -> pose(cos(10 + t) * 5, -sin(10 + t) * 15, -sin(10 + t) * 15);
            case "bone5" -> pose(cos(10 + t) * 5, sin(10 + t) * 15, sin(10 + t) * 15);
            case "bone3" -> pose(-.0082f + cos(t) * 10, 12.49978f, -7.57475f, -.87628f, 1.75491f, .39042f);
            case "bone8" -> pose(-.0082f + cos(t) * 10, -12.4998f, 7.5748f, .8763f, 1.75491f, .39042f);
            case "bone2" -> pose(6.4724f - cos(60 + t) * 5, 5.1344f + sin(60 + t) * 5, -12.5338f);
            case "bone9" -> pose(6.4724f - cos(60 + t) * 5, -5.1344f - sin(60 + t) * 5, 12.5338f);
            case "bone10" -> pose(6.4876f + cos(200 + t) * 5, -.97f + sin(300 + t) * 5, -6.9044f - sin(220 + t) * 7,
                -.89933f, -.02767f, -.01524f);
            case "bone11" -> pose(6.4876f + cos(200 + t) * 5, .97f - sin(300 + t) * 5, 6.9044f + sin(220 + t) * 7,
                .8993f, -.0277f, -.0152f);
            default -> BedrockAnimation.Pose.IDENTITY;
        };
    }

    private static BedrockAnimation.Pose demon(String bone, float t) {
        return switch (bone) {
            case "Wing3" -> pose(cos(t) * 15 + 10, 45 - cos(t) * 15, 0);
            case "Wing2" -> pose(cos(t) * 15 + 10, -45 + cos(t) * 15, 0);
            case "bone8" -> pose(0, -cos(50 - t) * 20, 0);
            case "bone2" -> pose(0, cos(50 - t) * 20, 0);
            case "bone9" -> pose(0, -cos(80 - t) * 25, 0);
            case "bone3" -> pose(0, cos(80 - t) * 25, 0);
            default -> BedrockAnimation.Pose.IDENTITY;
        };
    }

    private static BedrockAnimation.Pose pose(float x, float y, float z) {
        return pose(x, y, z, 0, 0, 0);
    }
    private static BedrockAnimation.Pose pose(float x, float y, float z, float px, float py, float pz) {
        return new BedrockAnimation.Pose(v(px, py, pz), v(x, y, z), v(1, 1, 1));
    }
    private static float[] blend(float[] main, float[] flight, float[] elytra, float fw, float ew) {
        float ground = 1 - fw - ew;
        return v(main[0] * ground + flight[0] * fw + elytra[0] * ew,
            main[1] * ground + flight[1] * fw + elytra[1] * ew,
            main[2] * ground + flight[2] * fw + elytra[2] * ew);
    }
    private static float[] v(float x, float y, float z) { return new float[]{x, y, z}; }
    private static float cos(float degrees) { return (float) Math.cos(Math.toRadians(degrees)); }
    private static float sin(float degrees) { return (float) Math.sin(Math.toRadians(degrees)); }
}
