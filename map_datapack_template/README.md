# Wathe map datapack template

这个目录是地图投票数据包模板，不属于 mod 源码。使用时可以把整个目录复制到世界存档的 `datapacks` 目录下，然后按自己的地图改名和改坐标。

## 必要文件

- `pack.mcmeta`：Minecraft 数据包声明。1.21.1 使用 `pack_format: 48`。
- `data/wathe/dimension/<map_id>.json`：注册新维度，例如 `wathe:template_map`。
- `data/wathe/dimension_type/<type_id>.json`：可选。如果维度直接使用 `minecraft:overworld` 类型，可以不需要；模板保留一份自定义类型方便复制。
- `data/wathe/maps/<map_id>.json`：Wathe 地图投票和地图增强配置。当前 Wathe 只读取 `data/wathe/maps/*.json`，所以这里的命名空间必须是 `wathe`。

## 额外注意

数据包只负责注册维度和规则，不会自动生成建筑。真实地图方块需要存在于世界存档对应维度目录里，例如 `dimensions/wathe/template_map`。

新维度第一次使用前，还需要站在该维度里用 `/wathe:mapVariables set ...` 配置大厅出生点、旁观出生点、准备区、游玩区域、重置模板区域等 Wathe 维度变量。
