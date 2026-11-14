# SparrowCdkey

面向 **Paper 1.21.11+** 的单服礼包码插件，不支持 Folia。通过原生 Dialog 创建、编辑、生成和兑换礼包，并查询兑换历史。

## 命令

| 命令 | 使用者 | 功能 | 所需权限 |
| --- | --- | --- | --- |
| `/redeem` | 玩家 | 打开自己的兑换窗口 | `sparrow.command.redeem` |
| `/redeem <在线玩家>` | 玩家、控制台 | 为指定在线玩家打开兑换窗口 | `sparrow.command.redeem` + `sparrow.command.redeem.other` |
| `/sparrow-cdkey` | 玩家 | 打开管理首页 | `sparrow.command.cdkey.view` |
| `/sparrow-cdkey history <玩家名或UUID>` | 玩家 | 查询指定玩家的兑换记录 | `sparrow.command.cdkey.view` |

`/redeem <在线玩家>` 只代开窗口，兑换者始终是实际提交表单的玩家。该玩家仍须具有基础兑换权限，并满足礼包配置中的额外权限、时间和次数限制。

历史查询按 UUID 精确查找；输入名字时优先匹配当前在线玩家，否则查询本插件记录过的兑换时姓名。历史名字对应多个 UUID 时，会显示选择页。离线玩家改名后的新名字若尚无兑换记录，需使用 UUID 或历史名字查询。

## 权限

以下权限的默认值均为 **OP**。普通玩家需要由权限插件授予 `sparrow.command.redeem` 才能兑换。

| 权限 | 作用 | 组合要求 |
| --- | --- | --- |
| `sparrow.command.redeem` | 打开兑换窗口、提交兑换 | 还须满足礼包额外权限与规则 |
| `sparrow.command.redeem.other` | 为其他在线玩家打开兑换窗口 | 同时需要 `sparrow.command.redeem` |
| `sparrow.command.cdkey.view` | 管理首页、列表、详情和兑换历史 | 所有管理操作的基础权限 |
| `sparrow.command.cdkey.manage` | 创建、编辑定义和生成 CDKEY | 同时需要 `view`；生成还需要 `copy` |
| `sparrow.command.cdkey.copy` | 查看和复制完整 CDKEY，包括历史快照中的完整码 | 同时需要 `view` |
| `sparrow.command.cdkey.delete` | 查看删除预览并确认永久删除 | 同时需要 `view` |

表中的 `view`、`manage`、`copy`、`delete` 均指完整的 `sparrow.command.cdkey.*` 权限节点。插件不定义通配权限节点，按所用权限插件的规则授权。

## 管理流程

- **礼包码**：管理员直接填写可兑换文本，区分大小写；每名玩家最多兑换一次，可设置总次数上限。
- **礼品**：先定义奖励规则，再每批生成 **1～16** 个一次性 CDKEY；可设置每名玩家对该礼品的兑换次数上限。
- **复制**：支持单码、整批和未用码列表整页复制。列表只展示查询时的状态，不预占兑换码。
- **历史**：可按定义或玩家查询，快照保留兑换时的规则、命令模板和实际派发命令。
- **删除**：确认后永久删除定义及其生成批次、兑换码、玩家用量和兑换历史，不能恢复。确认页数量可能随兑换变化，结果显示实际删除数量。

创建和编辑页面的字段标签旁带有简短说明，悬浮在标签上可查看完整帮助。输入无效时保留草稿；配置版本发生冲突时，需要重新读取规则后确认。

创建表单的开始、结束时间都预填打开表单时的当前时间。提交前应把结束时间改到开始时间之后，或清空结束时间表示不限期。开始时间包含边界，结束时间不包含边界；开始留空表示提交时立即生效。

## 奖励命令

每行一条控制台命令，支持 `<player>`（玩家名）和 `<uuid>`（玩家 UUID），行首 `/` 可省略：

```text
give <player> minecraft:diamond 3
say <player> 兑换了礼包
```

命令按顺序尝试派发，派发即视为成功。插件不判断外部命令的业务结果，不接入 PlaceholderAPI 或在线时长限制。

## 配置与语言

首次启动后，插件目录 `plugins/SparrowCdkey/` 中会生成：

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 管理页面时区和分页条数 |
| `lang.yml` | Dialog 标题、字段标签、帮助、按钮、命令描述和反馈文案 |
| `cdkey.db` | 本服 SQLite 数据；使用 WAL + FULL，读取与写入分别排队 |

`config.yml` 的默认配置：

```yaml
time-zone: Asia/Shanghai
page-size: 16
```

`page-size` 范围为 1～16。数据库时间以绝对时间保存，`time-zone` 用于表单输入和页面显示。

`lang.yml` 使用 **MiniMessage**，可修改文字、颜色和符号。各动态消息上方注明可用的命名占位符，例如：

```yaml
page: '<gray>第 <page> / <pages> 页 · 共 <total> 条</gray>'
redeem-success: '<#A5DFB2>✓ 兑换成功。</#A5DFB2>'
form-end-label: '<#9DD9CF>结束时间 <dark_gray>· 留空不限期 ⓘ</dark_gray></#9DD9CF>'
```

保留语言文件的键名与占位符名称。输入框标签应保持简短单行；完整说明位于对应的 `*-hint` 项，通过悬浮提示显示。若要在语言文案中原样展示奖励占位符，使用 MiniMessage 转义，如 `\<player>`。奖励命令输入框中的 `<player>` 则无需转义。

修改配置或语言文件后重启服务器生效，当前没有插件重载命令。

## 构建

使用 Java 21 编译：

```shell
./gradlew build
```

Windows 使用 `gradlew.bat build`。构建产物位于 `build/libs/`；服务器运行时的 Java 版本按对应 Paper 版本要求选择。
