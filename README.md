# Xray Client

Личный VPN-клиент для Android на Kotlin + Jetpack Compose с ядром Xray (VLESS / Reality, VMess, Trojan, Shadowsocks).

## Что умеет

- **Подписки** — ссылка на подписку (base64 или список ссылок), обновление вручную и при запуске, если данные старше 6 часов. Показывает трафик и срок из заголовка `subscription-userinfo`.
- **Режимы выбора сервера**
  - *Быстрый* — проверяет все серверы и берёт с наименьшей задержкой. Переключается, если текущий упал или другой заметно быстрее (на 30% и больше 50 мс).
  - *Приоритет* — берёт первый рабочий сервер по вашему порядку (кнопки ↑ ↓ на вкладке «Серверы»).
  - *Вручную* — только выбранный сервер.
- **Автопереключение** во время работы: проверка каждые 15–120 с без пересоздания VPN-интерфейса.
- **Обновление из приложения**: при каждом пуше в `main` GitHub Actions собирает APK и публикует релиз `v1.0.<номер сборки>`. Приложение проверяет последний релиз и предлагает установить его.

## Устройство

```
app/src/main/java/com/mkred/xrayclient/
├── core/        LinkParser (ссылки), XrayConfig (JSON для Xray), XrayCore (libv2ray), ServerSelector (режимы)
├── data/        Models, AppStore (состояние в data.json), Subscriptions
├── service/     XrayVpnService (VpnService + TUN → Xray), VpnState
├── update/      Updater (GitHub Releases)
└── ui/          Compose-экраны: Главная, Серверы, Подписки, Настройки
```

Ядро — [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite). CI скачивает `libv2ray.aar` из его релизов, версия задаётся в `LIBXRAY_TAG` в `.github/workflows/build.yml`. Трафик из TUN передаётся прямо во встроенный `tun`-вход Xray, tun2socks не нужен. Само приложение исключено из туннеля, поэтому проверки серверов и загрузка подписок идут напрямую.

## Секреты репозитория для подписи

| Секрет | Что это |
|---|---|
| `KEYSTORE_BASE64` | keystore в base64 |
| `KEYSTORE_PASSWORD` | пароль keystore |
| `KEY_ALIAS` | алиас ключа |
| `KEY_PASSWORD` | пароль ключа |

Ключ должен оставаться одним и тем же, иначе Android не даст поставить обновление поверх установленной версии.
