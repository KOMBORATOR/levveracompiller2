# 🚀 LEVVERACOMPILLER2

**Создатель: КОМБОРАТОР КОО**

Аналог Buildozer для сборки Kivy-приложений в APK.
Простой, стабильный, полностью на русском языке.

[![Версия](https://img.shields.io/badge/версия-2.0.1-blue)]()
[![Платформа](https://img.shields.io/badge/платформа-Linux-green)]()
[![Автор](https://img.shields.io/badge/автор-КОМБОРАТОР_КОО-red)]()

---

## 📖 Оглавление

- [Возможности](#-возможности)
- [Требования](#-требования)
- [Установка](#-установка)
- [Быстрый старт](#-быстрый-старт)
- [Команды](#-команды)
- [Файл levvera.spec](#-файл-levveraspec)
- [Решение проблем](#-решение-проблем)
- [Лицензия](#-лицензия)

---

## ✨ Возможности

- ✅ Сборка APK одной командой: `levvera build`
- ✅ Автоустановка Android SDK и NDK: `levvera android`
- ✅ Все сообщения на русском языке
- ✅ Простой конфиг `levvera.spec`
- ✅ Поддержка Kivy, KivyMD, Plyer, Pyjnius, Requests и др.
- ✅ Debug и Release сборки
- ✅ Гибкая настройка разрешений, иконок, ориентации
- ✅ Изолированное окружение в `~/.levvera` — не засоряет систему

---

## 🖥 Требования

- **ОС:** Linux (Ubuntu 20.04+, Debian 11+, Mint 20+)
- **Python:** 3.9 – 3.12
- **Диск:** ≥ 8 ГБ свободного места (SDK + NDK + кэш)
- **ОЗУ:** ≥ 4 ГБ (рекомендуется 8 ГБ)
- **Интернет:** нужен при первой установке

---

## 🔧 Установка

### 1. Системные пакеты

```bash
sudo apt update
sudo apt install -y git zip unzip openjdk-17-jdk autoconf libtool \
    pkg-config zlib1g-dev libncurses5-dev libncursesw5-dev \
    libtinfo5 cmake libffi-dev libssl-dev python3-pip python3-venv \
    wget