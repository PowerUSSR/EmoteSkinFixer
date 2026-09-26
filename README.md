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

## Лицензия

MIT — см. [LICENSE](LICENSE).
