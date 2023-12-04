package me.neznamy.tab.platforms.bukkit.nms;

import lombok.SneakyThrows;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.util.ReflectionUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PingRetriever {

    private static Method getHandle;
    private static Field PING;

    public static void tryLoad() {
        try {
            if (BukkitReflection.getMinorVersion() < 17) {
                getHandle = Class.forName("org.bukkit.craftbukkit." + BukkitReflection.getServerPackage() + ".entity.CraftPlayer").getMethod("getHandle");
                Class<?> EntityPlayer = BukkitReflection.getClass("server.level.ServerPlayer", "server.level.EntityPlayer", "EntityPlayer");
                PING = ReflectionUtils.getField(EntityPlayer, "ping", "field_71138_i"); // 1.5.2 - 1.16.5, 1.7.10 Thermos
            }
        } catch (Exception e) {
            Bukkit.getConsoleSender().sendMessage(EnumChatFormat.RED.getFormat() + "[TAB] Failed to initialize NMS fields for " +
                    "getting player's ping due to a compatibility error. This will " +
                    "result in ping showing \"-1\". " +
                    "Please update the plugin a to version with native support for your server version to properly show ping.");
        }
    }

    @SneakyThrows
    public static int getPing(@NotNull Player player) {
        if (BukkitReflection.getMinorVersion() >= 17) {
            return player.getPing();
        }
        if (PING == null) return -1;
        return PING.getInt(getHandle.invoke(player));
    }
}
