package me.neznamy.tab.platforms.bungeecord;

import java.util.*;
import java.util.stream.Collectors;

import me.neznamy.tab.api.ProtocolVersion;
import me.neznamy.tab.api.chat.EnumChatFormat;
import me.neznamy.tab.api.chat.IChatBaseComponent;
import me.neznamy.tab.api.protocol.*;
import me.neznamy.tab.api.protocol.PacketPlayOutPlayerInfo.EnumGamemode;
import me.neznamy.tab.api.protocol.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import me.neznamy.tab.api.protocol.PacketPlayOutPlayerInfo.PlayerInfoData;
import net.md_5.bungee.protocol.PlayerPublicKey;
import net.md_5.bungee.protocol.Property;
import net.md_5.bungee.protocol.packet.*;
import net.md_5.bungee.protocol.packet.PlayerListItem.Item;
import net.md_5.bungee.protocol.packet.ScoreboardObjective.HealthDisplay;

/**
 * Packet builder for BungeeCord platform
 */
public class BungeePacketBuilder extends PacketBuilder {

    @Override
    public Object build(PacketPlayOutPlayerInfo packet, ProtocolVersion clientVersion) {
        if (clientVersion.getNetworkId() >= ProtocolVersion.V1_19_3.getNetworkId() &&
                packet.getActions().contains(EnumPlayerInfoAction.REMOVE_PLAYER)) {
            PlayerListItemRemove remove = new PlayerListItemRemove();
            remove.setUuids(packet.getEntries().stream().map(PlayerInfoData::getUniqueId).toArray(UUID[]::new));
            return remove;
        }
        List<Item> items = new ArrayList<>();
        for (PlayerInfoData data : packet.getEntries()) {
            Item item = new Item();
            if (data.getDisplayName() != null) {
                if (clientVersion.getMinorVersion() >= 8) {
                    item.setDisplayName(data.getDisplayName().toString(clientVersion));
                } else {
                    item.setDisplayName(data.getDisplayName().toLegacyText());
                }
            } else if (clientVersion.getMinorVersion() < 8) {
                item.setDisplayName(String.valueOf(data.getName())); //avoiding NPE, 1.7 client requires this, 1.8 added a leading boolean
            }
            if (data.getGameMode() != null) item.setGamemode(data.getGameMode().ordinal() - 1);
            item.setListed(data.isListed());
            item.setPing(data.getLatency());
            if (data.getSkin() != null) {
                item.setProperties(new Property[]{new Property("textures", data.getSkin().getValue(), data.getSkin().getSignature())});
            } else {
                item.setProperties(new Property[0]);
            }
            item.setUsername(data.getName());
            item.setUuid(data.getUniqueId());
            item.setChatSessionId(data.getChatSessionId());
            item.setPublicKey((PlayerPublicKey) data.getProfilePublicKey());
            items.add(item);
        }
        if (clientVersion.getNetworkId() >= ProtocolVersion.V1_19_3.getNetworkId()) {
            PlayerListItemUpdate bungeePacket = new PlayerListItemUpdate();
            PlayerListItemUpdate.Action[] array = packet.getActions().stream().map(action -> PlayerListItemUpdate.Action.valueOf(
                    action.toString().replace("GAME_MODE", "GAMEMODE"))).toArray(PlayerListItemUpdate.Action[]::new);
            bungeePacket.setActions(EnumSet.of(array[0], array));
            bungeePacket.setItems(items.toArray(new Item[0]));
            return bungeePacket;
        } else {
            PlayerListItem bungeePacket = new PlayerListItem();
            EnumPlayerInfoAction action = packet.getActions().contains(EnumPlayerInfoAction.ADD_PLAYER) ?
                    EnumPlayerInfoAction.ADD_PLAYER : packet.getActions().iterator().next();
            bungeePacket.setAction(PlayerListItem.Action.valueOf(action.toString().replace("GAME_MODE", "GAMEMODE")));
            bungeePacket.setItems(items.toArray(new Item[0]));
            return bungeePacket;
        }
    }

    @Override
    public Object build(PacketPlayOutScoreboardDisplayObjective packet, ProtocolVersion clientVersion) {
        return new ScoreboardDisplay((byte)packet.getSlot(), packet.getObjectiveName());
    }

    @Override
    public Object build(PacketPlayOutScoreboardObjective packet, ProtocolVersion clientVersion) {
        return new ScoreboardObjective(packet.getObjectiveName(), jsonOrCut(packet.getDisplayName(), clientVersion, 32), packet.getRenderType() == null ? null : HealthDisplay.valueOf(packet.getRenderType().toString()), (byte)packet.getAction());
    }

    @Override
    public Object build(PacketPlayOutScoreboardScore packet, ProtocolVersion clientVersion) {
        return new ScoreboardScore(packet.getPlayer(), (byte) packet.getAction().ordinal(), packet.getObjectiveName(), packet.getScore());
    }

    @Override
    public Object build(PacketPlayOutScoreboardTeam packet, ProtocolVersion clientVersion) {
        int color = 0;
        if (clientVersion.getMinorVersion() >= 13) {
            color = (packet.getColor() != null ? packet.getColor() : EnumChatFormat.lastColorsOf(packet.getPlayerPrefix())).ordinal();
        }
        return new Team(packet.getName(), (byte)packet.getAction(), jsonOrCut(packet.getName(), clientVersion, 16), jsonOrCut(packet.getPlayerPrefix(), clientVersion, 16), jsonOrCut(packet.getPlayerSuffix(), clientVersion, 16),
                packet.getNameTagVisibility(), packet.getCollisionRule(), color, (byte)packet.getOptions(), packet.getPlayers().toArray(new String[0]));
    }
    
    @Override
    public PacketPlayOutPlayerInfo readPlayerInfo(Object bungeePacket, ProtocolVersion clientVersion) {
        if (bungeePacket instanceof PlayerListItemRemove) {
            return new PacketPlayOutPlayerInfo(
                    EnumPlayerInfoAction.REMOVE_PLAYER,
                    Arrays.stream(((PlayerListItemRemove) bungeePacket).getUuids()).map(PlayerInfoData::new).collect(Collectors.toList())
            );
        }
        Item[] items = bungeePacket instanceof PlayerListItemUpdate ? ((PlayerListItemUpdate) bungeePacket).getItems() : ((PlayerListItem) bungeePacket).getItems();
        List<PlayerInfoData> listData = new ArrayList<>();
        for (Item i : items) {
            Skin skin = i.getProperties() == null || i.getProperties().length == 0 ? null : new Skin(i.getProperties()[0].getValue(), i.getProperties()[0].getSignature());
            listData.add(new PlayerInfoData(
                    i.getUsername(),
                    i.getUuid(),
                    skin,
                    Boolean.TRUE.equals(i.getListed()),
                    i.getPing() == null ? 0 : i.getPing(),
                    i.getGamemode() == null ? null : EnumGamemode.VALUES[i.getGamemode()+1],
                    IChatBaseComponent.deserialize(i.getDisplayName()),
                    i.getChatSessionId(),
                    i.getPublicKey()));
        }
        if (bungeePacket instanceof PlayerListItemUpdate) {
            EnumPlayerInfoAction[] array = ((PlayerListItemUpdate) bungeePacket).getActions().stream().map(this::toCustomAction).toArray(EnumPlayerInfoAction[]::new);
            return new PacketPlayOutPlayerInfo(EnumSet.of(array[0], array), listData);
        } else {
            return new PacketPlayOutPlayerInfo(toCustomAction(((PlayerListItem) bungeePacket).getAction()), listData);
        }
    }

    private EnumPlayerInfoAction toCustomAction(Object action) {
        return EnumPlayerInfoAction.valueOf(action.toString().replace("GAMEMODE", "GAME_MODE"));
    }

    @Override
    public PacketPlayOutScoreboardObjective readObjective(Object bungeePacket) {
        return new PacketPlayOutScoreboardObjective(((ScoreboardObjective) bungeePacket).getAction(), ((ScoreboardObjective) bungeePacket).getName(),
                null, PacketPlayOutScoreboardObjective.EnumScoreboardHealthDisplay.INTEGER);
    }

    @Override
    public PacketPlayOutScoreboardDisplayObjective readDisplayObjective(Object bungeePacket) {
        return new PacketPlayOutScoreboardDisplayObjective(((ScoreboardDisplay) bungeePacket).getPosition(), ((ScoreboardDisplay) bungeePacket).getName());
    }
}