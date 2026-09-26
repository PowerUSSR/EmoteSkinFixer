package me.levon.emoteskinfixer;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EmoteSkinFixer v1.3
 *
 * 1. PLAYER_INFO patch — заменяет текстуру в TAB-листе на SR-скин
 * 2. ENTITY_EQUIPMENT patch — при /emote заменяет PLAYER_HEAD в экипировке NPC
 * 3. Диагностика — логирует пакеты + слоты экипировки во время эмоута
 */
public class EmoteSkinFixer extends JavaPlugin implements Listener {

    private final Map<UUID, WrappedSignedProperty> cachedTextures = new ConcurrentHashMap<>();
    // UUID → URL скина (декодируется из base64 value SR-скина)
    private final Map<UUID, String> cachedSkinUrls = new ConcurrentHashMap<>();
    // UUID игроков у которых сейчас активен эмоут (окно перехвата)
    private final Set<UUID> diagActive = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // имя (ловеркейс) → SR-текстура (для поиска, когда UUID не совпадает)
    private final Map<String, WrappedSignedProperty> cachedTexturesByName = new ConcurrentHashMap<>();
    private final Map<String, String> cachedSkinUrlsByName = new ConcurrentHashMap<>();

    private File srPlayersDir;
    private File srSkinsDir;

    @Override
    public void onEnable() {
        getLogger().info("EmoteSkinFixer v1.5 starting...");
        srPlayersDir = new File(getDataFolder().getParentFile(), "SkinsRestorer/players");
        srSkinsDir   = new File(getDataFolder().getParentFile(), "SkinsRestorer/skins");

        for (Player p : Bukkit.getOnlinePlayers()) loadSrTexture(p.getUniqueId(), p.getName());

        Bukkit.getPluginManager().registerEvents(this, this);

        // Patch 1: PLAYER_INFO — заменяем текстуру в tab-листе
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.PLAYER_INFO) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    PacketContainer debugPkt = event.getPacket();
                    var rawMod = debugPkt.getModifier();
                    StringBuilder sb2 = new StringBuilder("[ESF-PI] PLAYER_INFO rx=" + event.getPlayer().getName());
                    sb2.append(" rawFields=" + rawMod.size() + ":[");
                    for (int i = 0; i < rawMod.size(); i++) {
                        Object v = rawMod.readSafely(i);
                        sb2.append(i).append("=").append(v == null ? "null" : v.getClass().getSimpleName()).append(";");
                    }
                    sb2.append("] piListSize=").append(debugPkt.getPlayerInfoDataLists().size());
                    var piList = debugPkt.getPlayerInfoDataLists().readSafely(0);
                    sb2.append(" piListVal=").append(piList == null ? "null" : piList.size());
                    getLogger().info(sb2.toString());
                    try { patchPlayerInfo(event); } catch (Throwable e) {
                        getLogger().log(Level.WARNING, "[ESF-PI] patch error", e);
                    }
                }
            }
        );

        // Patch 2: ENTITY_EQUIPMENT — заменяем PLAYER_HEAD череп на NPC эмоута
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.HIGH, PacketType.Play.Server.ENTITY_EQUIPMENT) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    try { patchEquipment(event); } catch (Exception e) {
                        getLogger().log(Level.WARNING, "[ESF] Equipment patch error", e);
                    }
                }
            }
        );

        // Diag: ALL outbound packets for emote players
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.MONITOR,
                PacketType.Play.Server.NAMED_ENTITY_SPAWN,
                PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.ENTITY_EQUIPMENT,
                PacketType.Play.Server.PLAYER_INFO,
                PacketType.Play.Server.PLAYER_INFO_REMOVE,
                PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.TILE_ENTITY_DATA,
                PacketType.Play.Server.BUNDLE
            ) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    if (!diagActive.contains(event.getPlayer().getUniqueId())) return;
                    try { logDiagPacket(event); }
                    catch (Exception ignored) {}
                }
            }
        );

        getLogger().info("EmoteSkinFixer v1.5 enabled. SR dirs: players=" + srPlayersDir.exists() + " skins=" + srSkinsDir.exists());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        String name = e.getPlayer().getName();
        Bukkit.getScheduler().runTaskLater(this, () -> loadSrTexture(uid, name), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cachedTextures.remove(e.getPlayer().getUniqueId());
        cachedSkinUrls.remove(e.getPlayer().getUniqueId());
        cachedTexturesByName.remove(e.getPlayer().getName().toLowerCase());
        cachedSkinUrlsByName.remove(e.getPlayer().getName().toLowerCase());
        diagActive.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!e.getMessage().toLowerCase().startsWith("/emote ")) return;
        UUID uid = e.getPlayer().getUniqueId();
        final String playerName = e.getPlayer().getName();
        getLogger().info("[ESF-DIAG] Emote detected from " + playerName + ": " + e.getMessage());
        getLogger().info("[ESF-DIAG] Cached SR texture present: " + cachedTextures.containsKey(uid)
            + " byName: " + cachedTexturesByName.containsKey(playerName.toLowerCase()));
        // Активируем диагностику на 10 секунд
        diagActive.add(uid);
        // Инжектируем свежий PLAYER_INFO через 2 тика после эмоута (череп аппарицию FALLING_BLOCK)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p != null) injectSrPlayerInfo(p);
        }, 2L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            diagActive.remove(uid);
            getLogger().info("[ESF-DIAG] Diag window closed for " + playerName);
        }, 200L);
    }

    private void patchPlayerInfo(PacketEvent event) {
        // ProtocolLib 5.4.0 / 1.20.1: getPlayerInfoDataLists() returns all-null entries.
        // Strategy: read raw NMS list, recreate entries via reflection ctor (avoids final-field mutation).
        PacketContainer packet = event.getPacket();
        Player receiver = event.getPlayer();

        Object rawObj;
        try { rawObj = packet.getModifier().readSafely(1); }
        catch (Throwable e) { getLogger().warning("[ESF-PI] raw[1] read: " + e.getMessage()); return; }

        if (!(rawObj instanceof List<?>)) {
            getLogger().info("[ESF-PI] raw[1]=" + (rawObj == null ? "null" : rawObj.getClass().getSimpleName()));
            return;
        }
        List<?> nmsEntries = (List<?>) rawObj;
        if (nmsEntries.isEmpty()) return;
        getLogger().info("[ESF-PI] " + nmsEntries.size() + " entries rx=" + receiver.getName());

        List<Object> newList = new ArrayList<>();
        boolean anyPatched = false;

        for (int i = 0; i < nmsEntries.size(); i++) {
            Object nmsEntry = nmsEntries.get(i);
            if (nmsEntry == null) { newList.add(null); continue; }

            // Reflect: scan all fields of the NMS Entry, find GameProfile and profileId UUID
            java.lang.reflect.Field[] allFields = nmsEntry.getClass().getDeclaredFields();
            java.lang.reflect.Field gpField = null;
            com.mojang.authlib.GameProfile nmsProfile = null;
            UUID entryId = null;

            try {
                for (java.lang.reflect.Field f : allFields) {
                    f.setAccessible(true);
                    Object val = f.get(nmsEntry);
                    if (val instanceof com.mojang.authlib.GameProfile gp && gpField == null) {
                        nmsProfile = gp; gpField = f;
                    } else if (val instanceof UUID uid && entryId == null) {
                        entryId = uid;
                    }
                }
            } catch (Throwable e) {
                getLogger().warning("[ESF-PI] reflect e[" + i + "]: " + e.getClass().getSimpleName());
                newList.add(nmsEntry); continue;
            }

            if (nmsProfile == null) {
                getLogger().info("[ESF-PI] e[" + i + "] profileId=" + entryId + " profile=null");
                newList.add(nmsEntry); continue;
            }

            UUID uuid = nmsProfile.getId();
            String name = nmsProfile.getName();

            WrappedSignedProperty srTex = uuid != null ? cachedTextures.get(uuid) : null;
            if (srTex == null && name != null) srTex = cachedTexturesByName.get(name.toLowerCase());

            if (srTex == null) {
                getLogger().info("[ESF-PI] e[" + i + "] uuid=" + uuid + " name=" + name + " noSR");
                newList.add(nmsEntry); continue;
            }

            // Build SR GameProfile with correct texture
            try {
                com.mojang.authlib.GameProfile srProfile = new com.mojang.authlib.GameProfile(uuid, name);
                srProfile.getProperties().put("textures",
                    new com.mojang.authlib.properties.Property("textures", srTex.getValue(), srTex.getSignature()));

                // Recreate NMS Entry via its constructor — bypass final-field mutation.
                // Read all field values; replace GameProfile field with SR version.
                Object[] fieldVals = new Object[allFields.length];
                for (int j = 0; j < allFields.length; j++) {
                    allFields[j].setAccessible(true);
                    fieldVals[j] = (allFields[j] == gpField) ? srProfile : allFields[j].get(nmsEntry);
                }
                // Find constructor whose param count matches field count
                java.lang.reflect.Constructor<?> ctor = null;
                for (java.lang.reflect.Constructor<?> c : nmsEntry.getClass().getDeclaredConstructors()) {
                    if (c.getParameterCount() == allFields.length) { ctor = c; break; }
                }
                if (ctor == null) {
                    getLogger().warning("[ESF-PI] no ctor match fields=" + allFields.length
                        + " ctors=" + Arrays.toString(nmsEntry.getClass().getDeclaredConstructors()));
                    newList.add(nmsEntry); continue;
                }
                ctor.setAccessible(true);
                Object newEntry = ctor.newInstance(fieldVals);
                newList.add(newEntry);
                anyPatched = true;
                getLogger().info("[ESF-PATCH] e[" + i + "] " + name + " rx=" + receiver.getName());
            } catch (Throwable e) {
                getLogger().warning("[ESF-PI] patch e[" + i + "]: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                newList.add(nmsEntry);
            }
        }

        if (anyPatched) {
            try {
                packet.getModifier().write(1, newList);
                getLogger().info("[ESF-PI] wrote patched list size=" + newList.size());
            } catch (Throwable e) {
                getLogger().warning("[ESF-PI] write list: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void logDiagPacket(PacketEvent event) {
        PacketContainer pkt = event.getPacket();
        PacketType type = pkt.getType();
        StringBuilder sb = new StringBuilder();
        sb.append("[ESF-DIAG] PKT=").append(type.name());
        sb.append(" rx=").append(event.getPlayer().getName());

        if (type == PacketType.Play.Server.PLAYER_INFO) {
            try {
                List<PlayerInfoData> entries = pkt.getPlayerInfoDataLists().read(0);
                if (entries != null) {
                    for (PlayerInfoData e : entries) {
                        if (e == null) { sb.append(" [NULL_ENTRY]"); continue; }
                        WrappedGameProfile p = e.getProfile();
                        if (p == null) { sb.append(" [NULL_PROFILE]"); continue; }
                        sb.append(" UUID=").append(p.getUUID());
                        sb.append(" name=").append(p.getName());
                        Collection<WrappedSignedProperty> tex = p.getProperties().get("textures");
                        sb.append(" textures=").append(tex.isEmpty() ? "NONE" : tex.iterator().next().getValue().substring(0, 20) + "...");
                    }
                }
            } catch (Exception ignored) {}
        } else if (type == PacketType.Play.Server.SPAWN_ENTITY || type == PacketType.Play.Server.NAMED_ENTITY_SPAWN) {
            try {
                sb.append(" entityId=").append(pkt.getIntegers().readSafely(0));
                sb.append(" entityUUID=").append(pkt.getUUIDs().readSafely(0));
                sb.append(" entityType=").append(pkt.getEntityTypeModifier().readSafely(0));
                // Dump ALL integers to find block state ID / ObjectData
                sb.append(" ints[");
                for (int j = 0; j < pkt.getIntegers().size(); j++) {
                    sb.append(j).append("=").append(pkt.getIntegers().readSafely(j)).append(";");
                }
                sb.append("]");
                sb.append(" shorts[");
                for (int j = 0; j < pkt.getShorts().size(); j++) {
                    sb.append(j).append("=").append(pkt.getShorts().readSafely(j)).append(";");
                }
                sb.append("]");
            } catch (Exception ignored) {}
        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            try {
                sb.append(" entityId=").append(pkt.getIntegers().readSafely(0));
                // Log all metadata value types
                var vals = pkt.getDataValueCollectionModifier().readSafely(0);
                if (vals != null) {
                    sb.append(" metaCount=").append(vals.size());
                    vals.forEach(v -> sb.append(" [idx=").append(v.getIndex()).append(" ser=").append(v.getSerializer()).append(" val=").append(v.getValue()).append("]"));
                }
            } catch (Exception ignored) {}
        } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            try {
                sb.append(" entityId=").append(pkt.getIntegers().readSafely(0));
                var equipPairs = pkt.getSlotStackPairLists().readSafely(0);
                if (equipPairs != null) {
                    equipPairs.forEach(pr -> sb.append(" [slot=").append(pr.getFirst()).append(" item=").append(pr.getSecond() != null ? pr.getSecond().getType() : "NULL").append("]"));
                }
            } catch (Exception ignored) {}
        } else if (type == PacketType.Play.Server.BUNDLE) {
            sb.append(" [BUNDLE]");
        } else if (type == PacketType.Play.Server.TILE_ENTITY_DATA) {
            try {
                sb.append(" pos=").append(pkt.getBlockPositionModifier().readSafely(0));
                sb.append(" teType=").append(pkt.getIntegers().readSafely(0));
                var nbt = pkt.getNbtModifier().readSafely(0);
                sb.append(" nbt=").append(nbt != null ? nbt.toString().substring(0, Math.min(nbt.toString().length(), 200)) : "null");
            } catch (Exception ignored) {}
        }

        getLogger().info(sb.toString());
    }

    private void loadSrTexture(UUID uuid, String name) {
        try {
            File pf = new File(srPlayersDir, uuid + ".player");
            if (!pf.exists()) { getLogger().warning("[ESF] No SR player file for " + name); return; }
            String pJson = new String(Files.readAllBytes(pf.toPath()));
            Pattern hp = Pattern.compile("\"identifier\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = hp.matcher(pJson);
            String lastId = null; while (m.find()) lastId = m.group(1);
            if (lastId == null) { getLogger().warning("[ESF] No skin ID for " + name); return; }
            File sf = new File(srSkinsDir, lastId + ".playerskin");
            if (!sf.exists()) { getLogger().warning("[ESF] No skin file " + lastId + " for " + name); return; }
            String sJson = new String(Files.readAllBytes(sf.toPath()));
            String val = extract(sJson, "value"), sig = extract(sJson, "signature");
            if (val == null) { getLogger().warning("[ESF] No value in skin for " + name); return; }
            cachedTextures.put(uuid, new WrappedSignedProperty("textures", val, sig));
            cachedTexturesByName.put(name.toLowerCase(), new WrappedSignedProperty("textures", val, sig));
            // Декодируем base64 → JSON → URL скина
            try {
                String decoded = new String(Base64.getDecoder().decode(val));
                Matcher mu = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(decoded);
                if (mu.find()) {
                    cachedSkinUrls.put(uuid, mu.group(1));
                    cachedSkinUrlsByName.put(name.toLowerCase(), mu.group(1));
                    getLogger().info("[ESF] Loaded SR skin for " + name + " id=" + lastId.substring(0,8) + " url=..." + mu.group(1).substring(mu.group(1).lastIndexOf('/') + 1, Math.min(mu.group(1).lastIndexOf('/') + 9, mu.group(1).length())));
                } else {
                    getLogger().info("[ESF] Loaded SR skin for " + name + " id=" + lastId.substring(0,8) + " (no url in decoded)");
                }
            } catch (Exception ex) {
                getLogger().info("[ESF] Loaded SR skin for " + name + " id=" + lastId.substring(0,8) + " (url decode failed: " + ex.getMessage() + ")");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "[ESF] Error loading skin for " + name, e);
        }
    }

    private void patchEquipment(PacketEvent event) {
        if (!diagActive.contains(event.getPlayer().getUniqueId())) return;
        PacketContainer pkt = event.getPacket();
        int entityId = pkt.getIntegers().readSafely(0);

        UUID playerUUID = event.getPlayer().getUniqueId();
        String skinUrl = cachedSkinUrls.get(playerUUID);
        if (skinUrl == null) {
            getLogger().warning("[ESF] No cached skin URL for " + event.getPlayer().getName() + ", can't patch equipment");
            return;
        }

        List<Pair<EnumWrappers.ItemSlot, ItemStack>> pairs = pkt.getSlotStackPairLists().readSafely(0);
        if (pairs == null || pairs.isEmpty()) return;

        boolean changed = false;
        for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : pairs) {
            ItemStack item = pair.getSecond();
            if (item == null || item.getType() != Material.PLAYER_HEAD) continue;

            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof SkullMeta skull)) continue;

            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "EmoteSkin");
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(new URL(skinUrl));
                profile.setTextures(textures);
                skull.setOwnerProfile(profile);
                item.setItemMeta(skull);
                changed = true;
                getLogger().info("[ESF] Patched PLAYER_HEAD skull entity=" + entityId
                    + " slot=" + pair.getFirst()
                    + " rx=" + event.getPlayer().getName());
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "[ESF] Skull patch failed for entity " + entityId, ex);
            }
        }

        if (changed) {
            pkt.getSlotStackPairLists().write(0, pairs);
        }
    }

    private void injectSrPlayerInfo(Player player) {
        // Trigger a full player info re-send via Bukkit hide/show cycle.
        // This causes the server to re-send PLAYER_INFO (with SR skin) to the observer.
        try {
            for (Player obs : Bukkit.getOnlinePlayers()) {
                obs.hidePlayer(this, player);
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player obs : Bukkit.getOnlinePlayers()) {
                    obs.showPlayer(this, player);
                }
                getLogger().info("[ESF-INJ] hide/show done for " + player.getName());
            }, 1L);
            getLogger().info("[ESF-INJ] Triggered hide/show for " + player.getName());
        } catch (Throwable e) {
            getLogger().log(Level.WARNING, "[ESF-INJ] hide/show failed", e);
        }
    }

    private String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}