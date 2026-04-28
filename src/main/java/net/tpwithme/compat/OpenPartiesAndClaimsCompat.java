package net.tpwithme.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.tpwithme.TpWithMe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class OpenPartiesAndClaimsCompat {

    public enum TeleportType {
        GENERAL,
        ENDER_PEARL,
        CHORUS_FRUIT
    }

    private static final String SERVER_API_CLASS = "xaero.pac.common.server.api.OpenPACServerAPI";
    private static final String PLAYER_CONFIG_OPTIONS_V2_CLASS =
            "xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions";
    private static final String PLAYER_CONFIG_OPTIONS_V1_CLASS =
            "xaero.pac.common.server.player.config.api.PlayerConfigOptions";
    private static final String CHORUS_EXCEPTION_FIELD = "CLAIM_EXCEPTION_CHORUS_FRUIT";
    private static final String CHORUS_PROTECTION_FIELD = "PROTECT_CLAIMED_CHUNKS_CHORUS_FRUIT";
    private static final String ENDER_PEARL_BARRIER_OPTION_ID =
            "claims.protection.exceptions.groups.entity.barrier.Ender_Pearls";

    private static boolean loggedFailure;

    private OpenPartiesAndClaimsCompat() {
    }

    public static boolean canTeleportInto(ServerLevel world, BlockPos pos, ServerPlayer player, TeleportType teleportType) {
        try {
            Class<?> serverApiClass = Class.forName(SERVER_API_CLASS);
            Object api = invokeStaticByNameAndArgCount(serverApiClass, "get", 1, world.getServer());
            if (api == null) {
                return true;
            }

            Object claimsManager = api.getClass().getMethod("getServerClaimsManager").invoke(api);
            Object claim = invokeChunkClaimLookup(claimsManager, world.dimension().identifier(), pos);
            if (claim == null) {
                return true;
            }

            Object protection = api.getClass().getMethod("getChunkProtection").invoke(api);
            Object claimConfig = getClaimConfig(protection, claim);
            if (hasChunkAccess(protection, claimConfig, player)) {
                return true;
            }

            return switch (teleportType) {
                case CHORUS_FRUIT -> allowsChorusFruit(protection, claimConfig, player);
                case ENDER_PEARL -> allowsEnderPearl(api, protection, claimConfig, player);
                case GENERAL -> false;
            };
        } catch (ClassNotFoundException e) {
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!loggedFailure) {
                loggedFailure = true;
                TpWithMe.LOGGER.warn("{} Open Parties and Claims protection checks failed. TpWithMe will not use OPAC claim rules until this is fixed.",
                        TpWithMe.prefix(), e);
            }
            return true;
        }
    }

    private static boolean allowsChorusFruit(Object protection, Object claimConfig, ServerPlayer player)
            throws ReflectiveOperationException {
        Object v2Option = getStaticFieldIfPresent(PLAYER_CONFIG_OPTIONS_V2_CLASS, CHORUS_EXCEPTION_FIELD);
        if (v2Option != null) {
            return checkPlayerGroupExceptionOption(protection, v2Option, claimConfig, player);
        }

        Object v1Option = getStaticFieldIfPresent(PLAYER_CONFIG_OPTIONS_V1_CLASS, CHORUS_PROTECTION_FIELD);
        if (v1Option == null) {
            return true;
        }

        boolean protectedAgainst = invokeBooleanByNameAndArgCount(
                protection, "checkProtectionLeveledOption", 3, v1Option, claimConfig, player);
        return !protectedAgainst || invokeBooleanByNameAndArgCount(
                protection, "checkExceptionLeveledOption", 3, v1Option, claimConfig, player);
    }

    private static boolean allowsEnderPearl(Object api, Object protection, Object claimConfig, ServerPlayer player)
            throws ReflectiveOperationException {
        Object playerConfigManager = api.getClass().getMethod("getPlayerConfigManager").invoke(api);
        Object option = invokeByNameAndArgCount(playerConfigManager, "getOptionForId", 1, ENDER_PEARL_BARRIER_OPTION_ID);
        if (option == null) {
            return true;
        }
        return checkPlayerGroupExceptionOption(protection, option, claimConfig, player);
    }

    private static boolean checkPlayerGroupExceptionOption(
            Object protection,
            Object option,
            Object claimConfig,
            ServerPlayer player
    ) throws ReflectiveOperationException {
        return invokeBooleanByNameAndArgCount(protection, "checkPlayerGroupExceptionOption", 3, option, claimConfig, player);
    }

    private static Object getClaimConfig(Object protection, Object claim) throws ReflectiveOperationException {
        try {
            return invokeByNameAndArgCount(protection, "getConfig", 1, claim);
        } catch (NoSuchMethodException e) {
            return invokeByNameAndArgCount(protection, "getClaimConfig", 1, claim);
        }
    }

    private static boolean hasChunkAccess(Object protection, Object claimConfig, ServerPlayer player)
            throws ReflectiveOperationException {
        return invokeBooleanByNameAndArgCount(protection, "hasChunkAccess", 2, claimConfig, player);
    }

    private static Object invokeChunkClaimLookup(Object claimsManager, Object dimensionId, BlockPos pos)
            throws ReflectiveOperationException {
        for (Method method : claimsManager.getClass().getMethods()) {
            if (method.getName().equals("get") && canAccept(method, dimensionId, pos)) {
                return method.invoke(claimsManager, dimensionId, pos);
            }
        }
        throw new NoSuchMethodException("No OPAC claims manager get(dimension, pos) method found.");
    }

    private static Object invokeByNameAndArgCount(Object target, String name, int argCount, Object... args)
            throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == argCount && canAccept(method, args)) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException("No OPAC method found: " + name);
    }

    private static boolean invokeBooleanByNameAndArgCount(Object target, String name, int argCount, Object... args)
            throws ReflectiveOperationException {
        Object result = invokeByNameAndArgCount(target, name, argCount, args);
        return Boolean.TRUE.equals(result);
    }

    private static Object invokeStaticByNameAndArgCount(Class<?> targetClass, String name, int argCount, Object... args)
            throws ReflectiveOperationException {
        for (Method method : targetClass.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == argCount && canAccept(method, args)) {
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException("No OPAC static method found: " + name);
    }

    private static boolean canAccept(Method method, Object... args) {
        if (method.getParameterCount() != args.length) {
            return false;
        }

        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] != null && !parameterTypes[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Object getStaticFieldIfPresent(String className, String fieldName) throws ReflectiveOperationException {
        try {
            Field field = Class.forName(className).getField(fieldName);
            return field.get(null);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            return null;
        }
    }
}
