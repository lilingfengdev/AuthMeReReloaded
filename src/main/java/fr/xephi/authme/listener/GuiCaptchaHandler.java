package fr.xephi.authme.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import fr.xephi.authme.AuthMe;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.settings.properties.HooksSettings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import javax.inject.Inject;
import java.io.File;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static fr.xephi.authme.util.PlayerUtils.isNpc;
import static org.bukkit.Bukkit.getLogger;
import static org.bukkit.Bukkit.getServer;

public class GuiCaptchaHandler implements Listener {
    //define AuthMeApi
    private final AuthMeApi authmeApi = AuthMeApi.getInstance();
    @Inject
    private BukkitService bukkitService;
    @Inject
    private AuthMe plugin;
    @Inject
    private Messages messages;
    @Inject
    private CommonService service;

    private PacketAdapter chatPacketListener;
    private PacketAdapter windowPacketListener;
    //define timesLeft
    private int timesLeft = 3;
    //Use ConcurrentHashMap to store player and their close reason
    /* We used many async tasks so there is concurrent**/
    public static ConcurrentHashMap<Player, String> closeReasonMap = new ConcurrentHashMap<>();
    //define randomStringSet
    String randomSet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890abcdefghijklmnopqrstuvwxyz!@#%&*()_+";
    String randomString = "";
    Random randomItemSet = new Random();
    Random howManyRandom = new Random();


    int howLongIsRandomString = (howManyRandom.nextInt(3) + 1);

    public GuiCaptchaHandler() {
    }

    private boolean isBedrockPlayer(UUID uuid) {
        return AuthMe.settings.getProperty(HooksSettings.HOOK_FLOODGATE_PLAYER) && AuthMe.settings.getProperty(SecuritySettings.GUI_CAPTCHA_BE_COMPATIBILITY) && org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgateId(uuid) && getServer().getPluginManager().getPlugin("floodgate") != null;
    }

    private void removePacketListeners() {
        ProtocolLibrary.getProtocolManager().removePacketListener(windowPacketListener);
        ProtocolLibrary.getProtocolManager().removePacketListener(chatPacketListener);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            ItemStack currentItem = event.getCurrentItem();
            if (!authmeApi.isRegistered(player.getName()) && !closeReasonMap.containsKey(player)) {
                if (isBedrockPlayer(player.getUniqueId())) {
                    return;
                }
                if (currentItem != null && currentItem.getType().equals(Material.REDSTONE_BLOCK)) {
                    event.setCancelled(true);
                    closeReasonMap.put(player, "verified");
                    player.closeInventory();
                    messages.send(player, MessageKey.GUI_CAPTCHA_VERIFIED);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        randomString = "";
        Player playerunreg = event.getPlayer();
        String name = playerunreg.getName();
        if (!authmeApi.isRegistered(name) && !isNpc(playerunreg)) {
            if (isBedrockPlayer(playerunreg.getUniqueId())) {
                closeReasonMap.put(playerunreg, "verified");
                messages.send(playerunreg, MessageKey.GUI_CAPTCHA_VERIFIED_AUTO_BEDROCK);
                return;
            }
            bukkitService.runTaskAsynchronously(() -> {
                StringBuilder sb = new StringBuilder();
                howLongIsRandomString = (howManyRandom.nextInt(3) + 1);
                for (int i = 0; i < howLongIsRandomString; i++) {
                    //生成随机索引号
                    int index = randomItemSet.nextInt(randomSet.length());

                    // 从字符串中获取由索引 index 指定的字符
                    char randomChar = randomSet.charAt(index);

                    // 将字符追加到字符串生成器
                    sb.append(randomChar);
                }

                bukkitService.runTask(() -> {
                    randomString = sb.toString();
                    Random random_blockpos = new Random();
                    AtomicInteger random_num = new AtomicInteger(random_blockpos.nextInt(26));
                    Inventory menu = Bukkit.createInventory(playerunreg, 27, messages.retrieveSingle(playerunreg, MessageKey.GUI_CAPTCHA_WINDOW_NAME, randomString));
                    ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
                    ItemMeta meta = item.getItemMeta();
                    try {
                        if (meta != null) {
                            meta.setDisplayName(messages.retrieveSingle(playerunreg, MessageKey.GUI_CAPTCHA_CLICKABLE_NAME, randomString));
                            item.setItemMeta(meta);
                        }
                    } catch (NullPointerException e) {
                        getLogger().log(Level.WARNING, "Unexpected error occurred while setting item meta.");
                    }
                    menu.setItem(random_num.get(), item);
                    playerunreg.openInventory(menu);
                    if (AuthMe.settings.getProperty(SecuritySettings.GUI_CAPTCHA_TIMEOUT) > 0) {
                        long timeOut = AuthMe.settings.getProperty(SecuritySettings.GUI_CAPTCHA_TIMEOUT);
                        if (AuthMe.settings.getProperty(SecuritySettings.GUI_CAPTCHA_TIMEOUT) > AuthMe.settings.getProperty(RestrictionSettings.TIMEOUT)) {
                            bukkitService.runTask(() -> {
                                getLogger().warning("AuthMe detected that your GUI captcha timeout seconds(" + AuthMe.settings.getProperty(SecuritySettings.GUI_CAPTCHA_TIMEOUT) + ") is bigger than the Login timeout seconds(" +
                                    AuthMe.settings.getProperty(RestrictionSettings.TIMEOUT) + "). To prevent issues, we will let the GUI captcha follow the Login timeout seconds, please check and modify your config.");
                            });
                            timeOut = AuthMe.settings.getProperty(RestrictionSettings.TIMEOUT);
                        }
                        long finalTimeOut = timeOut;
                        bukkitService.runTask(() -> {
                            bukkitService.runTaskLater(() -> {
                                if (!closeReasonMap.containsKey(playerunreg) && !authmeApi.isRegistered(playerunreg.getName())) {
                                    playerunreg.kickPlayer(service.retrieveSingleMessage(playerunreg, MessageKey.GUI_CAPTCHA_KICK_TIMEOUT));
                                    timesLeft = 3; // Reset the attempt counter
                                }
                            }, finalTimeOut * 20L);
                        });
                    }

                    bukkitService.runTask(() -> {
                        windowPacketListener = new PacketAdapter(this.plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.CLOSE_WINDOW) {
                            @Override
                            public void onPacketReceiving(PacketEvent event) {
                                if (event.getPlayer() == playerunreg && !closeReasonMap.containsKey(playerunreg) && !authmeApi.isRegistered(playerunreg.getName())) {
                                    if (timesLeft <= 0) {
                                        bukkitService.runTask(() -> {
                                            playerunreg.kickPlayer(service.retrieveSingleMessage(playerunreg, MessageKey.GUI_CAPTCHA_KICK_FAILED));
                                        });
                                        timesLeft = 3;
                                    } else {
                                        --timesLeft;
                                        if (timesLeft <= 0) {
                                            bukkitService.runTask(() -> {
                                                playerunreg.kickPlayer(service.retrieveSingleMessage(playerunreg, MessageKey.GUI_CAPTCHA_KICK_FAILED));
                                            });
                                            timesLeft = 3;
                                            return;
                                        }
                                        messages.send(playerunreg, MessageKey.GUI_CAPTCHA_RETRY_MESSAGE, String.valueOf(timesLeft));
                                        event.setCancelled(true);
                                        random_num.set(random_blockpos.nextInt(26));
                                        bukkitService.runTask(() -> {
                                            menu.clear();
                                            menu.setItem(random_num.get(), item);
                                            playerunreg.openInventory(menu);
                                        });
                                    }
                                }
                            }
                        };
                        ProtocolLibrary.getProtocolManager().addPacketListener(windowPacketListener);
                    });
                    bukkitService.runTask(() -> {
                        chatPacketListener = new PacketAdapter(this.plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.CHAT) {
                            @Override
                            public void onPacketReceiving(PacketEvent event) {
                                if (event.getPlayer() == playerunreg && !closeReasonMap.containsKey(playerunreg) && !authmeApi.isRegistered(playerunreg.getName())) {
                                    messages.send(playerunreg, MessageKey.GUI_CAPTCHA_DENIED_MESSAGE);
                                    event.setCancelled(true);
                                }
                            }
                        };
                        ProtocolLibrary.getProtocolManager().addPacketListener(chatPacketListener);
                    });
                });
            });
        }
    }

    private void deletePlayerData(UUID playerUUID) {
        // 获取服务器的存储文件夹路径
        File serverFolder = Bukkit.getServer().getWorldContainer();
        String worldFolderName = AuthMe.settings.getProperty(SecuritySettings.DELETE_PLAYER_DATA_WORLD);
        // 构建playerdata文件夹路径
        File playerDataFolder = new File(serverFolder, File.separator + worldFolderName + File.separator + "playerdata");

        // 构建玩家数据文件路径
        File playerDataFile = new File(playerDataFolder, File.separator + playerUUID + ".dat");

        // 删除玩家数据文件
        if (playerDataFile.exists()) {
            playerDataFile.delete();
        }
    }
    private void deletePlayerStats(UUID playerUUID) {
        // 获取服务器的存储文件夹路径
        File serverFolder = Bukkit.getServer().getWorldContainer();
        String worldFolderName = AuthMe.settings.getProperty(SecuritySettings.DELETE_PLAYER_DATA_WORLD);
        // 构建stats文件夹路径
        File statsFolder = new File(serverFolder, File.separator + worldFolderName + File.separator + "stats");
        // 构建玩家统计数据文件路径
        File statsFile = new File(statsFolder, File.separator + playerUUID + ".json");
        // 删除玩家统计数据文件
        if (statsFile.exists()) {
            statsFile.delete();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        UUID playerUUID = event.getPlayer().getUniqueId();
        if (!authmeApi.isRegistered(name)) {
            if (AuthMe.settings.getProperty(SecuritySettings.DELETE_UNVERIFIED_PLAYER_DATA) && !closeReasonMap.containsKey(player)) {
                closeReasonMap.remove(player);
                bukkitService.runTaskLater(() -> {
                    if (!player.isOnline()) {
                        deletePlayerData(playerUUID);
                        deletePlayerStats(playerUUID);
                    }
                }, 100L);
                removePacketListeners();
                return;
            }
            removePacketListeners();
            closeReasonMap.remove(player);
        }
    }
}




