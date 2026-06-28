# Nested Chest

Minecraft 1.21 Fabric mod using Yarn mappings.

## 中文说明

Nested Chest 是一个适用于 Minecraft 1.21 Fabric 的箱中箱模组。它允许玩家在箱子界面中打开箱子物品，并以可拖拽的子窗口形式继续管理嵌套箱子的内容。

### 构建

在 `Chest` 文件夹中运行：

```powershell
.\gradlew.bat build
```

可游玩的模组 jar 会生成在：

```text
build/libs/nested-chest-1.0.0.jar
```

请将它与 Minecraft 1.21 对应的 Fabric Loader 和 Fabric API 一起安装使用。

### 使用方式

- 打开普通箱子。
- 将普通箱子或陷阱箱物品放入箱子的任意格子。
- 在箱子 UI 打开时，右键点击箱子物品。
- 子箱子窗口会显示在当前箱子 UI 的上层，并且可以拖动。
- 父级箱子 UI 会保持打开。
- 放入子箱子窗口中的物品会保存到箱子物品自身的 Minecraft 1.21 `CONTAINER` 数据组件中。

### 作者

- Codex
- xc

## Build

Run this from the `Chest` folder:

```powershell
.\gradlew.bat build
```

The playable mod jar is created at:

```text
build/libs/nested-chest-1.0.0.jar
```

Install it together with Fabric Loader and Fabric API for Minecraft 1.21.

## How It Works

- Open a normal chest.
- Put a single normal chest or trapped chest item into any chest slot.
- Right-click that chest item while the chest UI is open.
- A draggable child chest window appears on top of the existing chest UI.
- The original chest UI stays open.
- Items placed in the child window are saved into the chest item itself using Minecraft 1.21's `CONTAINER` data component.

This first version supports left/right mouse clicks inside child windows and dragging the child window by its title bar. Shift-click and hotbar-number shortcuts inside child windows are intentionally not implemented yet.
