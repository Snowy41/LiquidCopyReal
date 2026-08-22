package dev.liquidcopy.client.service;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

public final class StorageTypes {
    private StorageTypes() {
    }

    public static boolean isStorage(BlockEntity blockEntity) {
        return label(blockEntity) != null;
    }

    public static String label(BlockEntity blockEntity) {
        if (blockEntity instanceof EnderChestBlockEntity) {
            return "Ender Chest";
        }
        if (blockEntity instanceof ChestBlockEntity) {
            return "Chest";
        }
        if (blockEntity instanceof BarrelBlockEntity) {
            return "Barrel";
        }
        if (blockEntity instanceof ShulkerBoxBlockEntity) {
            return "Shulker Box";
        }
        if (blockEntity instanceof HopperBlockEntity) {
            return "Hopper";
        }
        if (blockEntity instanceof DropperBlockEntity) {
            return "Dropper";
        }
        if (blockEntity instanceof DispenserBlockEntity) {
            return "Dispenser";
        }
        if (blockEntity instanceof AbstractFurnaceBlockEntity) {
            return "Furnace";
        }
        if (blockEntity instanceof CrafterBlockEntity) {
            return "Crafter";
        }
        if (blockEntity instanceof ShelfBlockEntity) {
            return "Shelf";
        }
        return null;
    }
}
