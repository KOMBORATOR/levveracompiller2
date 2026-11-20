# LEVVER Utility — Android

Версия 0.7.0 Alpha • Kotlin • Jetpack Compose • sing-box libbox 1.14.4

## Реализовано в текущей Alpha
- Главный экран, список профилей и настройки.
- Импорт профилей из буфера обмена, QR-кода, Android Share и deep links.
- Локальное хранение профилей с AES-GCM; ключ AES хранится в Android Keystore.
- Android VpnService и интеграция с libbox CommandServer.
- Преобразование ссылок VLESS (TLS/Reality, TCP/WS/gRPC/HTTPUpgrade) и Hysteria2 (включая salamander obfs) в sing-box конфигурацию.
- Импорт пользовательского sing-box JSON с TUN inbound.
- GitHub Actions unit-test и debug APK workflow.

## Ограничения текущей Alpha
- Автоматическая конвертация реализована пока только для VLESS и Hysteria2. Остальные схемы распознаются парсером и сохраняются, но конвертер для них ещё не готов.
- Поддержка отдельных параметров и вариантов URI ограничена; неподдерживаемый транспорт или параметр должен приводить к понятной ошибке, а не к ложному статусу подключения.
- Успешная компиляция не подтверждает реальное подключение. Для проверки туннеля требуется тест на Android-устройстве с тестовой конфигурацией сервера, которым пользователь владеет или имеет разрешение пользоваться.
- Перед выпуском нужно проверить сетевые маршруты, DNS, остановку/перезапуск сервиса, отзыв VPN-разрешения, переходы между Wi-Fi и мобильной сетью, а также обработку ошибок.

## Лицензии
libbox распространяется по GPL-3.0-or-later. До публикации необходимо включить соответствующие уведомления и выполнить требования GPL, включая предоставление соответствующего исходного кода производной сборки.

## Конфиденциальность
- Не публикуй ссылки, UUID, пароли и конфигурации в Issues или публичных логах.
- Профили шифруются на устройстве через Android Keystore.
- Потеря ключа Android Keystore может сделать сохранённые профили нечитаемыми.


## 0.8.0 Alpha — диагностика и Quick Settings
- Добавлен TCP connect latency probe для профилей с доменом/IP и TCP-портом.
- Кнопка «Пинг / рейтинг» сравнивает TCP-доступность профилей и показывает самый быстрый ответивший endpoint.
- Важно: это не ICMP ping и не тест скорости/качества VPN-туннеля. UDP-протоколы (включая Hysteria2) нельзя честно ранжировать этим TCP-тестом; для них требуется протокольная проверка/handshake через движок.
- Добавлена плитка LEVVER VPN в Android Quick Settings. После первого предоставления системного VPN-разрешения плитка может запускать сохранённую последнюю конфигурацию или отключать активный VPN.
- Сервис синхронизирует состояние плитки и поддерживает постоянное системное уведомление во время работы VPN.
- Уведомления Android 13+ могут требовать отдельного разрешения POST_NOTIFICATIONS; пользователь может отключить их в настройках Android.

## Известные ограничения
- TCP latency — только предварительная оценка доступности порта; лучший результат не гарантирует лучшую пропускную способность, стабильность или географически лучший сервер.
- Для честного сравнения Hysteria2/UDP и проверки VPN end-to-end нужно добавить тест через sing-box URLTest или отдельную диагностическую операцию движка.


## 0.8.1 Beta Pre-release
- Android 13+: added a system prompt to add the LEVVER VPN Quick Settings tile from Settings.
- Fixed libbox StringIterator length reporting and guarded connection-owner lookup.
- Added share-link conversion for VMess, Trojan, Shadowsocks (modern URI form), SOCKS5, TUIC and AnyTLS alongside VLESS and Hysteria2.
- Import recognition does not guarantee successful connection; server-specific transport options still need validation on a real device.
- CI success confirms compilation/tests only. Real tunnel operation and tile appearance must be checked on Android hardware.
