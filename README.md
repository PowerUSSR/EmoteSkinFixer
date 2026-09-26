# EmoteSkinFixer

Плагин-фикс для Paper/Spigot 1.20 на сервере **VortexiaPolit**. Перехватывает пакет `PlayerInfoUpdate` (через [ProtocolLib](https://github.com/dmulloy2/ProtocolLib)) и подставляет корректный скин из SkinsRestorer для NPC/эмоций, чтобы визуальные эффекты не сбрасывали скин игрока на скин по умолчанию.

## Зависимости

- **ProtocolLib** (обязательно)
- SkinsRestorer, ItemsAdder — опционально

## Сборка

Maven-проект:

```
mvn clean package
```

## Совместимость

- Minecraft **1.20.1**
- Ядро сервера: **Mohist 1.20.1** (плагин используется на сервере VortexiaPolit)
- Написан на Bukkit/Spigot API, поэтому может работать и на Paper/Spigot 1.20.x

## Сообщество

Discord сервера VortexiaPolit: https://discord.gg/3svAGgVtz

## Лицензия

MIT — см. [LICENSE](LICENSE).
