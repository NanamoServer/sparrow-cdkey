package top.catnies.sparrow.cdkey;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.catnies.sparrow.cdkey.command.CdkeyCommand;
import top.catnies.sparrow.cdkey.command.RedeemCommand;
import top.catnies.sparrow.cdkey.database.SqliteCdkeyStore;
import top.catnies.sparrow.cdkey.dialog.DialogService;
import top.catnies.sparrow.cdkey.dialog.Messages;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * 连接 Paper 生命周期, 命令入口和本地 CDKEY 存储.
 * 启用时先准备配置和数据库, 停用时先结算已受理的操作再关闭数据库.
 */
public final class SparrowCdkeyPlugin extends JavaPlugin {
    public static final String VIEW_PERMISSION = "sparrow.command.cdkey.view";     // 打开管理入口和读取管理数据
    public static final String REDEEM_PERMISSION = "sparrow.command.redeem";      // 打开兑换入口和提交兑换
    public static final String REDEEM_OTHER_PERMISSION = REDEEM_PERMISSION + ".other"; // 为其他本服玩家打开兑换入口
    private static SparrowCdkeyPlugin instance; // 由 Paper 在加载阶段发布的插件入口

    private @Nullable DialogService dialogs;
    private CdkeyConfig settings;             // 启用时读取的管理显示参数
    private @Nullable SqliteCdkeyStore store; // 当前启用轮次的数据库, 初始化成功后才赋值
    private @Nullable CdkeyService service;   // 存储就绪后建立的业务入口

    @Override
    public void onLoad() {
        instance = this;
    }

    /**
     * 准备本服配置和 SQLite, 然后把两个命令注册到 Paper 命令树.
     *
     * @throws IllegalStateException 配置或存储无法初始化时抛出
     */
    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.settings = this.loadSettings();
        File language = new File(this.getDataFolder(), "lang.yml");
        if (!language.exists()) this.saveResource("lang.yml", false);
        Messages.load(language);
        try {
            this.store = new SqliteCdkeyStore(this.getDataFolder().toPath().resolve("cdkey.db"));
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize cdkey.db", exception);
        }
        this.service = new CdkeyService();
        this.dialogs = new DialogService();
        Bukkit.getPluginManager().registerEvents(this.dialogs, this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(new CdkeyCommand().build(), Messages.MANAGE_DESCRIPTION.plain());
            event.registrar().register(new RedeemCommand().build(), Messages.REDEEM_DESCRIPTION.plain());
        });
    }

    /**
     * 读取管理配置. 分页大小用于后台查询, 时区供管理页面处理绝对时间.
     *
     * @return 校验通过的完整配置
     * @throws IllegalStateException 配置无法读取, 解析或校验时抛出
     */
    @NotNull
    private CdkeyConfig loadSettings() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(this.getDataFolder(), "config.yml"));
            int pageSize = yaml.getInt("page-size", 16);
            if (pageSize < 1 || pageSize > 16) throw new IllegalStateException("page-size must be between 1 and 16");
            ZoneId timeZone = ZoneId.of(yaml.getString("time-zone", "Asia/Shanghai"));
            return new CdkeyConfig(timeZone, pageSize);
        } catch (IOException | InvalidConfigurationException | DateTimeException exception) {
            throw new IllegalStateException("Could not read CDKEY config.yml", exception);
        }
    }

    /**
     * 停止受理请求并取消待派发动作, 让后台事务结束后再关闭数据库.
     */
    @Override
    public void onDisable() {
        if (this.dialogs != null) this.dialogs.close();
        // Paper 在主线程停用插件, 停用时必须先结算等待这个线程的派发动作.
        if (this.service != null) {
            this.service.stop().join();
        }
        if (this.store != null) {
            this.store.close();
        }
    }

    /**
     * 返回 Paper 已加载的插件实例, 供内部服务获取当前轮次的依赖.
     *
     * @return 已加载的插件入口
     */
    @NotNull
    public static SparrowCdkeyPlugin instance() {
        return instance;
    }

    /**
     * 提供启动时读取的管理参数, 同一次查询使用同一分页大小.
     *
     * @return 当前启用轮次的配置
     */
    @NotNull
    public CdkeyConfig settings() {
        return this.settings;
    }

    /**
     * 提供受理管理和兑换的业务入口.
     *
     * @return 当前服务, <strong>在插件启用后调用</strong>
     */
    @NotNull
    public CdkeyService service() {
        return this.service;
    }

    @NotNull
    public DialogService dialogs() {
        return this.dialogs;
    }

    /**
     * 提供同一插件轮次的存储队列, 供业务服务提交读写.
     *
     * @return 已初始化的存储, <strong>在插件启用后调用</strong>
     */
    @NotNull
    SqliteCdkeyStore store() {
        return this.store;
    }
}