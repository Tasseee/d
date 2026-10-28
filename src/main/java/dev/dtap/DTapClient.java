package dev.dtap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class DTapClient implements ClientModInitializer {
    public static boolean enabled = false;   // DTap standardmaessig aus
    static KeyBinding toggleKey;
    static int popTick = -1, delay;
    static PlayerEntity target;
    static BlockPos placed;
    static final Random rng = new Random();
    static final float SAFETY = 1.15f;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dtap.toggle", GLFW.GLFW_KEY_H, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(DTapClient::tick);

        // Explosions-Check: immer aktiv
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient() || !(entity instanceof EndCrystalEntity)) return ActionResult.PASS;
            float dmg = DamageCalculator.crystalDamage(player,
                new Vec3d(entity.getX(), entity.getY(), entity.getZ()), world);
            float hp = player.getHealth() + player.getAbsorptionAmount();
            // Sicherheitsmarge, falls Client-Rechnung (Ping, Schwierigkeit) leicht abweicht
            boolean lethal = dmg * SAFETY >= hp;
            if (lethal && !hasTotem(player)) {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Crystal blockiert! Schaden ca. " + Math.round(dmg) + " / Leben " + Math.round(hp)), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    public static void onPop(Entity e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mc.player == null || !(e instanceof PlayerEntity p) || p == mc.player) return;
        if (mc.player.distanceTo(p) > 8) return;
        target = p;
        popTick = 0;
        placed = null;
        delay = 2 + rng.nextInt(4);
    }

    static void tick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            reset();
            if (mc.inGameHud != null)
                mc.inGameHud.setOverlayMessage(Text.literal(enabled ? "DTap: AN" : "DTap: AUS"), false);
        }
        // Anzeige solange DTap aktiv ist
        if (enabled && mc.player != null && mc.inGameHud != null && mc.player.age % 20 == 0)
            mc.inGameHud.setOverlayMessage(Text.literal("DTap: AN"), false);
        if (!enabled || mc.player == null || mc.world == null || mc.interactionManager == null || popTick < 0) return;
        popTick++;
        if (popTick > 40 || target == null || target.isRemoved()) { reset(); return; }

        if (placed == null) {
            if (popTick < delay) return;
            if (!mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) { reset(); return; }
            float hp = 9f;   // 1 HP + Absorption II
            BlockPos best = null;
            float bestDmg = 0;
            BlockPos c = target.getBlockPos();
            for (BlockPos p : BlockPos.iterateOutwards(c.down(), 3, 1, 3)) {
                if (!isValid(mc.world, p)) continue;
                if (mc.player.squaredDistanceTo(Vec3d.ofCenter(p)) > 20) continue;
                Vec3d pos = Vec3d.of(p).add(0.5, 1, 0.5);
                float dmg = DamageCalculator.crystalDamage(target, pos, mc.world);
                if (dmg <= bestDmg) continue;
                float self = DamageCalculator.crystalDamage(mc.player, pos, mc.world);
                if (selfDies(mc.player, self)) continue;
                bestDmg = dmg;
                best = p.toImmutable();
            }
            if (best != null && bestDmg >= hp && !hasTotem(target)) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(best).add(0, 0.5, 0), Direction.UP, best, false));
                placed = best;
            } else reset();
        } else {
            Box area = new Box(placed.up()).expand(0.5);
            for (EndCrystalEntity cr : mc.world.getEntitiesByClass(EndCrystalEntity.class, area, e -> true)) {
                mc.interactionManager.attackEntity(mc.player, cr);
                mc.player.swingHand(Hand.MAIN_HAND);
                reset();
                break;
            }
        }
    }

    static boolean isValid(World w, BlockPos p) {
        var b = w.getBlockState(p).getBlock();
        return (b == Blocks.OBSIDIAN || b == Blocks.BEDROCK) && w.isAir(p.up()) && w.isAir(p.up(2));
    }

    static boolean hasTotem(PlayerEntity p) {
        return p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    static boolean selfDies(PlayerEntity me, float dmg) {
        return dmg >= me.getHealth() + me.getAbsorptionAmount() && !hasTotem(me);
    }

    static void reset() { popTick = -1; placed = null; target = null; }
}
