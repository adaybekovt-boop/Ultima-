package dev.ultima.command;

/**
 * Operator gate for {@code /ultima profile} expressed as Minecraft 26.2 command level ids
 * so unit tests can cover it without bootstrapping the permission registry.
 *
 * <p>{@code 2} is {@code PermissionLevel.GAMEMASTERS} (operator). The live command still uses
 * {@code Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)}.
 */
public final class UltimaProfilePermissions {
    public static final int OPERATOR_LEVEL_ID = 2;

    private UltimaProfilePermissions() {
    }

    public static boolean mayProfile(final int permissionLevelId) {
        return permissionLevelId >= OPERATOR_LEVEL_ID;
    }
}
