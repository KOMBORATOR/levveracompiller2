#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LEVVERACOMPILLER2 — сборщик APK для Kivy-приложений.
Аналог Buildozer, но проще, стабильнее и на русском.
"""

import os
import sys
import shutil
import subprocess
import configparser
import argparse
from pathlib import Path

VERSION = "2.0.1"
RED = "\033[91m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
BLUE = "\033[94m"
RESET = "\033[0m"


def log(msg, color=BLUE):
    print(f"{color}[LEVVERA]{RESET} {msg}")


def error(msg):
    print(f"{RED}[ОШИБКА]{RESET} {msg}")
    sys.exit(1)


def warn(msg):
    print(f"{YELLOW}[ВНИМАНИЕ]{RESET} {msg}")


# ---------- Проверка окружения ----------
def check_system():
    log("Проверка системы...", BLUE)
    if sys.platform != "linux":
        error("LEVVERACOMPILLER2 работает только на Linux.")

    # Проверка прав
    if os.geteuid() == 0:
        warn("Запуск от root может сломать сборку. Рекомендуется обычный пользователь.")

    # Проверка пакетов
    required = ["git", "zip", "unzip", "openjdk-17-jdk", "autoconf", "libtool",
                "pkg-config", "zlib1g-dev", "libncurses5-dev", "libncursesw5-dev",
                "libtinfo5", "cmake", "libffi-dev", "libssl-dev", "python3-pip"]
    missing = []
    for pkg in required:
        r = subprocess.run(["dpkg", "-s", pkg], capture_output=True)
        if r.returncode != 0:
            missing.append(pkg)

    if missing:
        warn(f"Отсутствуют пакеты: {', '.join(missing)}")
        log("Установите командой:")
        print(f"  sudo apt install -y {' '.join(missing)}")
        ans = input("Продолжить всё равно? [y/N]: ")
        if ans.lower() != "y":
            sys.exit(0)

    log("Система в порядке ✔", GREEN)


# ---------- Работа с конфигом ----------
DEFAULT_SPEC = """[app]
title = МоёПриложение
package.name = myapp
package.domain = org.test
source.dir = .
source.include_exts = py,png,jpg,jpeg,kv,atlas,ttf,otf
version = 0.1
requirements = python3,kivy
orientation = portrait
fullscreen = 0
android.archs = arm64-v8a, armeabi-v7a
android.api = 31
android.minapi = 21
android.ndk = 25b
android.allow_backup = True
android.permissions = INTERNET
icon.filename = %(source.dir)s/icon.png
presplash.filename = %(source.dir)s/presplash.png
"""


def create_spec(path="levvera.spec"):
    if os.path.exists(path):
        warn(f"{path} уже существует. Пропускаю.")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(DEFAULT_SPEC)
    log(f"Создан файл конфигурации: {path}", GREEN)


def load_spec(path="levvera.spec"):
    if not os.path.exists(path):
        error(f"Файл {path} не найден. Запустите: levvera init")
    cfg = configparser.ConfigParser()
    cfg.read(path, encoding="utf-8")
    if "app" not in cfg:
        error("В levvera.spec отсутствует секция [app]")
    return cfg


# ---------- Скачивание python-for-android ----------
P4A_DIR = Path.home() / ".levvera" / "python-for-android"
P4A_REPO = "https://github.com/kivy/python-for-android.git"


def ensure_p4a(branch="develop"):
    P4A_DIR.parent.mkdir(parents=True, exist_ok=True)
    if P4A_DIR.exists():
        log("python-for-android уже установлен ✔", GREEN)
        return P4A_DIR
    log("Клонирую python-for-android (это может занять время)...")
    r = subprocess.run(["git", "clone", "--depth", "1", "-b", branch,
                        P4A_REPO, str(P4A_DIR)])
    if r.returncode != 0:
        error("Не удалось склонировать python-for-android.")
    log("python-for-android установлен ✔", GREEN)
    return P4A_DIR


# ---------- Сборка ----------
def build(clean=False):
    check_system()
    cfg = load_spec()
    app = cfg["app"]

    if clean:
        log("Очистка предыдущих сборок...")
        for d in [".buildozer", "bin", ".levvera_build"]:
            if os.path.exists(d):
                shutil.rmtree(d, ignore_errors=True)
        log("Очищено ✔", GREEN)

    p4a = ensure_p4a()
    p4a_bin = p4a / "pythonforandroid" / "toolchain.py"
    if not p4a_bin.exists():
        error("toolchain.py не найден. Переустановите p4a.")

    title = app.get("title", "MyApp")
    pkg_name = app.get("package.name", "myapp")
    pkg_domain = app.get("package.domain", "org.test")
    version = app.get("version", "0.1")
    reqs = app.get("requirements", "python3,kivy")
    src = app.get("source.dir", ".")
    orientation = app.get("orientation", "portrait")
    api = app.get("android.api", "31")
    minapi = app.get("android.minapi", "21")
    ndk = app.get("android.ndk", "25b")
    archs = app.get("android.archs", "arm64-v8a")
    perms = app.get("android.permissions", "INTERNET")
    icon = app.get("icon.filename", "")
    presplash = app.get("presplash.filename", "")

    out_dir = Path("bin").resolve()
    out_dir.mkdir(exist_ok=True)

    cmd = [
        sys.executable, str(p4a_bin), "apk",
        "--private", str(Path.home() / ".levvera" / "p4a_build"),
        "--name", title.replace(" ", ""),
        "--package", f"{pkg_domain}.{pkg_name}",
        "--version", version,
        "--requirements", reqs,
        "--orientation", orientation,
        "--arch", archs.replace(" ", ""),
        "--api", api,
        "--minapi", minapi,
        "--ndk", ndk,
        "--dist-name", f"levvera_{pkg_name}",
        "--bootstrap", "sdl2",
        "--copy-libs",
        "--android-entrypoint", "org.kivy.android.PythonActivity",
        "--permission", perms.replace(" ", ""),
        "--debug",
    ]

    if icon and os.path.exists(icon):
        cmd += ["--icon", icon]
    if presplash and os.path.exists(presplash):
        cmd += ["--presplash", presplash]

    cmd.append(src)

    log("Запуск сборки APK...", YELLOW)
    log("Команда: " + " ".join(cmd), BLUE)

    env = os.environ.copy()
    env["ANDROID_HOME"] = str(Path.home() / ".levvera" / "android-sdk")
    env["ANDROID_NDK_HOME"] = str(Path.home() / ".levvera" / "android-ndk")

    r = subprocess.run(cmd, env=env)
    if r.returncode != 0:
        error("Сборка провалилась. Смотрите лог выше.")

    # Копируем APK в bin/
    for apk in Path(src).glob("*.apk"):
        shutil.move(str(apk), str(out_dir / apk.name))
    for apk in Path.cwd().glob("*.apk"):
        if apk.parent != out_dir:
            shutil.move(str(apk), str(out_dir / apk.name))

    log(f"Готово! APK находится в: {out_dir}", GREEN)


# ---------- Android SDK/NDK ----------
SDK_URL = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
NDK_URL = "https://dl.google.com/android/repository/android-ndk-r25b-linux.zip"


def install_android():
    base = Path.home() / ".levvera"
    base.mkdir(exist_ok=True)
    sdk = base / "android-sdk"
    ndk = base / "android-ndk"

    if not sdk.exists():
        log("Скачиваю Android SDK...")
        zip_path = base / "cmdline-tools.zip"
        subprocess.run(["wget", "-O", str(zip_path), SDK_URL], check=True)
        sdk.mkdir(exist_ok=True)
        subprocess.run(["unzip", "-q", str(zip_path), "-d", str(sdk)], check=True)
        zip_path.unlink()
        log("Android SDK установлен ✔", GREEN)
    else:
        log("Android SDK уже есть ✔", GREEN)

    if not ndk.exists():
        log("Скачиваю Android NDK r25b (это ~1 ГБ)...")
        zip_path = base / "ndk.zip"
        subprocess.run(["wget", "-O", str(zip_path), NDK_URL], check=True)
        subprocess.run(["unzip", "-q", str(zip_path), "-d", str(base)], check=True)
        zip_path.unlink()
        # Переименовываем
        extracted = base / "android-ndk-r25b"
        if extracted.exists() and not ndk.exists():
            extracted.rename(ndk)
        log("Android NDK установлен ✔", GREEN)
    else:
        log("Android NDK уже есть ✔", GREEN)


# ---------- CLI ----------
def main():
    parser = argparse.ArgumentParser(
        prog="levvera",
        description=f"LEVVERACOMPILLER2 v{VERSION} — сборщик APK для Kivy",
    )
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("init", help="Создать levvera.spec")
    p_build = sub.add_parser("build", help="Собрать APK")
    p_build.add_argument("--clean", action="store_true", help="Очистить перед сборкой")
    sub.add_parser("android", help="Установить Android SDK + NDK")
    sub.add_parser("version", help="Показать версию")

    args = parser.parse_args()

    if args.cmd == "init":
        create_spec()
    elif args.cmd == "build":
        build(clean=args.clean)
    elif args.cmd == "android":
        install_android()
    elif args.cmd == "version":
        print(f"LEVVERACOMPILLER2 v{VERSION}")
    else:
        parser.print_help()


if __name__ == "__main__":
    main()