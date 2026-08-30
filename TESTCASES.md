# 本地 M3U 文件支持 — 测试用例 (Local M3U file feature — test cases)

Issue: https://github.com/vibe4free/LiteTV/issues/4（希望支持本地 m3u 文件，U 盘 / 本地导入）

## 范围 (Scope)

设置界面「选择 M3U 文件」→ 系统文件选择器（SAF）→ 拷贝到应用私有目录 →
`ChannelRepository` 优先读取本地文件（其次才是 URL）→ 可移除（本机设置 / Web 页面）→
播放器主界面返回设置后自动重新加载。

## 自动化测试（已在 `app/src/test` 中实现并运行）

| 编号 | 用例 | 自动化测试 | 验证点 |
|------|------|-----------|--------|
| U-01 | M3U 解析：分组 / 台标 / tvg-id / UA / Referer | `M3uParserTest.parse_m3uWithAttributes_extractsChannelFields` | 字段齐全 |
| U-02 | 频道未写 group-title 时继承上一频道分组 | `parse_channelWithoutGroupTitle_inheritsPreviousGroup` | 分组继承 |
| U-03 | 空 / null / 纯注释输入 | `parse_emptyOrNull_returnsEmpty` | 不崩溃，返回空 |
| U-04 | 非 http(s) 地址（rtmp 等）被丢弃 | `parse_urlWithoutScheme_isDropped` | 不产生频道 |
| U-05 | catchup 属性（频道级覆盖播放列表级默认） | `parse_catchupAttributes_captured` | 回看字段 |
| U-06 | #EXTVLCOPT 行 UA/Referer | `parse_vlcOptLines_setHeaders` | 请求头 |
| U-07 | url\|User-Agent=...&Referer=... 行内头 | `parse_inlineHeadersAfterUrlPipe_areExtracted` | 请求头 |
| U-08 | TXT（#genre# + 多源 url1#url2），含逗号/无逗号两种写法 | `parse_txtGenreFormat_multiSource_supported` | 多源 failover |
| U-09 | M3U 内置 EPG（x-tvg-url，空格/|分隔，相对路径对本地文件丢弃） | `parseEpgUrls_explicitHttp_kept_relativeDroppedForLocalFile` | EPG 回退 |
| U-10 | 相对 EPG 路径仅相对 http 播放列表解析 | `parseEpgUrls_relativeResolvedAgainstHttpPlaylist` | 安全解析 |
| U-11 | EPG URL 去重 / 上限 5 个 | `parseEpgUrls_duplicates_removed` / `parseEpgUrls_capped` | 兜底 |
| C-01 | 本地文件路径经 Hawk 持久化（设置 → 存储 → 读取） | `AppConfigTest.m3uFilePath_roundTrips` | 配置层 |
| C-02 | M3U URL / EPG 优先级默认值与回退 | `AppConfigTest.*` | 配置层 |
| R-01 | 本地文件作为唯一播放源：解析频道 + 内置 EPG 记录 + 顺序/索引 | `ChannelRepositoryLocalTest.load_fromLocalFile_parsesChannelsAndEmbeddedEpg` | 核心功能 |
| R-02 | 本地文件优先于 URL（URL 指向死端口也不影响） | `load_localFileTakesPriorityOverUrl` | 优先级 |
| R-03 | UTF-8 BOM + CRLF 文件（Windows 记事本） | `load_bomAndCrLf_stillParses` | 兼容性 |
| R-04 | 损坏文件：报告失败、无频道、不崩溃 | `load_garbageFile_reportsFailureWithoutChannels` | 失败处理 |
| R-05 | 文件路径指向不存在的文件且无 URL：静默、无频道、不崩溃 | `load_missingFileAndNoUrl_reportsNothingAndNoCrash` | 失败处理 |
| R-06 | 移除本地文件后（路径清空）无源：不崩溃 | `load_clearedFileConfig_staysEmpty` | 失败处理 |
| R-07 | 本地 TXT 播放列表（name,url#url）同样支持 | `load_localTxtFile_parses` | 兼容性 |
| W-01 | Web `/config/get` 返回 `m3u_file_path` | `ConfigWebServerTest.getConfig_reportsLocalFilePath` | Web 配置 |
| W-02 | Web `POST /config/m3u-file-clear`：清除配置 + 删除拷贝 + 通知 TV 端 | `clearRemovesFileSettingAndNotifies` | Web 配置 |
| W-03 | 跨站 POST 被拒（Origin 校验）403 | `crossSitePost_rejected403` | 安全 |
| W-04 | 同源 POST 放行 200 | `sameOriginPost_allowed` | 安全 |
| W-05 | 非表单 Content-Type 被拒 415 | `wrongContentType_rejected415` | 安全 |
| W-06 | 未知路径 404 | `unknownPath_404` | 路由 |
| S-01 | 「选择 M3U 文件」按钮发起 ACTION_OPEN_DOCUMENT（*/*、可读权限） | `SettingsActivityTest.pickButton_opensDocumentPicker` | UI 入口 |
| S-02 | 设置页显示当前本地文件（存在） | `configuredFile_isShownInSettings` | UI 状态 |
| S-03 | 未配置本地文件时显示「未使用本地文件」 | `noFile_showsNoneState` | UI 状态 |
| S-04 | 「移除本地文件」清空配置并刷新界面 | `clearButton_removesConfiguredFile` | UI 操作 |
| S-05 | 新增控件不破坏设置页其他区块 | `activity_survivesOnCreate_withAllSections` | 回归 |

## 测试中发现并修复的问题

- **TXT `#genre#` 行无逗号变体**（`央视频道#genre#`）：原实现 `line.split(",")[0]` 会把 `#genre#` 留在分组名里。已改为先去除行尾 `#genre#`、再去除尾部逗号，两种写法均正确（`M3uParser.parseTxt`）。测试 U-08 同时覆盖两种写法。

## 需要真机/实物验证的手动用例（Robolectric 无法覆盖）

| 编号 | 步骤 | 预期 |
|------|------|------|
| M-01（核心） | U 盘插入电视盒 → 设置 → 播放源 → 选择 M3U 文件 → 浏览到 U 盘 root 下 `list.m3u` → 选中 | 提示「本地 M3U 文件已保存」，界面显示「正在使用本地文件：list.m3u」；返回播放页后频道列表自动加载 U 盘里的频道并开始播放 |
| M-02 | 拔出 U 盘 → 播放 / 换台 | 频道仍可播放（已拷贝到应用私有目录，不依赖 U 盘） |
| M-03 | 设置页「移除本地文件」 | 提示已移除，界面回到「未使用本地文件」；如果同时配置了 URL，返回播放页后切回 URL 源 |
| M-04 | 文件 > 8 MB | 提示「文件过大（最大 8 MB）」，配置不变 |
| M-05 | 选择非 m3u 的乱码文件 | 返回播放页后提示无法加载播放列表，不崩溃 |
| M-06 | 手机上：浏览器打开 `http://电视IP:8899` → M3U 区块显示「本地 M3U 文件生效：…」→ 点「清除本地文件」 | 提示成功，TV 端自动重新加载为 URL 源；电视上播放源恢复 |
| M-07 | 无 DocumentsUI 的廉价盒子点「选择 M3U 文件」 | 提示「此设备无法打开文件选择器」，不崩溃 |
| M-08 | 本地文件 + URL 同时配置 | 本地文件生效（优先级），Web 页面 /config/get 的 `m3u_file_path` 非空 |

## 运行方式

```bash
# 需要 JDK 17 + Android SDK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:testDebugUnitTest          # 全部单测（Robolectric）
./gradlew :app:testDebugUnitTest --tests "*ChannelRepositoryLocalTest"
./gradlew :app:testDebugUnitTest --tests "*ConfigWebServerTest"
```

## 测试报告位置

```
app/build/reports/tests/testDebugUnitTest/index.html
```