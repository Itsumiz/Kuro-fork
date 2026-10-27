package dev.kuro.client.echo.event;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Event fired when a player right-clicks with an item. */
public abstract class EventStartUseItem extends Event {
    private final Player player;
    private final InteractionHand hand;
    private final ItemStack stack;
    private final HitResult hitResult;

    public EventStartUseItem(Event.Stage stage, Player player, InteractionHand hand, ItemStack stack, HitResult hitResult) {
        super(stage);
        this.player = player;
        this.hand = hand;
        this.stack = stack;
        this.hitResult = hitResult;
    }

    public Player getPlayer() { return player; }
    public InteractionHand getHand() { return hand; }
    public ItemStack getStack() { return stack; }
    public HitResult getHitResult() { return hitResult; }
    public boolean isTargetingBlock() { return hitResult instanceof BlockHitResult; }
    public boolean isTargetingEntity() { return hitResult instanceof EntityHitResult; }
    public BlockHitResult getBlockHitResult() { return isTargetingBlock() ? (BlockHitResult) hitResult : null; }
    public EntityHitResult getEntityHitResult() { return isTargetingEntity() ? (EntityHitResult) hitResult : null; }

    /** Called before the item use is processed. */
    public static class Pre extends EventStartUseItem {
        public Pre(Player player, InteractionHand hand, ItemStack stack, HitResult hitResult) {
            super(Event.Stage.PRE, player, hand, stack, hitResult);
        }
    }

    /** Called after the item use is processed. */
    public static class Post extends EventStartUseItem {
        public Post(Player player, InteractionHand hand, ItemStack stack, HitResult hitResult) {
            super(Event.Stage.POST, player, hand, stack, hitResult);
        }
    }
}
