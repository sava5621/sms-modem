# Файлы проекта и внешние зависимости

## Готовая поставка

| Файл | Назначение | Куда устанавливается |
| --- | --- | --- |
| `artifacts/sms-modem-3.0.apk` | Подписанное приложение приёма, архива и HTTP UI | Android package `local.modem.sms` |
| `artifacts/wifi-off-1.0.apk` | Событийное отключение Wi-Fi и AP | Android package `local.modem.wifioff` |
| `artifacts/sms-only-control.jar` | DEX-контроллер системных сетевых API | `/system/etc/sms-only-control.jar` |
| `modem/modem-usb-lan.sh` | Восстановление USB IP и запуск политики | `/system/etc/modem-usb-lan.sh`, root:root, 0644 |
| `modem/sms-only-policy.sh` | Firewall, настройки, журнал здоровья | `/system/etc/sms-only-policy.sh`, root:root, 0644 |
| `modem/install-sms-only.sh` | Установщик с резервной копией | Временно `/data/local/tmp/sms-only-install/` |
| `modem/backup-app.sh` | Согласованный архив данных SMS-приложения | Временно `/data/local/tmp/sms-backup-app.sh` |
| `artifacts/manifest.json`, `artifacts/SHA256SUMS` | SHA-256 бинарников и устанавливаемых сценариев | Проверяются перед установкой |

Эти файлы достаточны для настройки при наличии USB ADB, драйверов и подходящей прошивки. Самостоятельно прошивать bootloader, modem/baseband или полный Android image для проекта не требуется. Полные дампы заводских APK и init не поставляются; установщик сохраняет исходные файлы конкретного устройства и добавляет только свой boot hook.

## Исходники

| Каталог | Содержание |
| --- | --- |
| `app/src/local/modem/sms/` | `SmsService`, `SmsReceiver`, `SmsStore`, `BootReceiver`, `MainActivity`, `DeleteGuard` |
| `app/assets/index.html` | Веб-интерфейс без внешних CDN |
| `app/AndroidManifest.xml` | Package, версия 3.0, API 19, SMS/boot/network permissions |
| `app/tests/` | Instrumentation manifest и синтетический интеграционный тест |
| `wifi-off/src/` и `wifi-off/AndroidManifest.xml` | Receiver и Activity отключения Wi-Fi, версия 1.0 |
| `modem/src/SmsOnlyControl.java` | Исходник DEX-контроллера |

Исходники и готовые APK перенесены из рабочей версии сервиса без изменения его поведения. Наследуемый `PUBLIC_HOST` в `SmsService.java` описан в [API](11-service-and-api.md). Это разрешённый входной Host для прежнего reverse proxy, а не адрес исходящей отправки сообщений.

## Сценарии для компьютера

| Файл | Действие |
| --- | --- |
| `scripts/Modem.psm1` | Общие ADB-функции, проверка прошивки, временный root, проверка checksum |
| `scripts/Install-Apps.ps1` | Установка APK с `-r`, запуск приложений |
| `scripts/Install-SmsOnly.ps1` | Загрузка файлов и установка политики, возврат обычного ADB |
| `scripts/Backup-Sms.ps1` | Закрытый архив данных и проверка SHA-256 |
| `scripts/Verify-Modem.ps1` | Базовая проверка состояния без изменения модема |
| `scripts/Measure-Stability.ps1` | Ограниченное по времени наблюдение HTTP и boot_id |
| `scripts/Build.ps1` | Сборка двух приложений, контроллера и необязательного test APK |

Сценарии записаны для PowerShell 5.1+ на Windows, сборка использует Windows `aapt.exe`. Shell-файлы для Android должны оставаться UTF-8 без BOM с окончаниями LF. `.gitattributes` фиксирует LF для `*.sh`.

## Материалы

`docs/01–09` — последовательные этапы настройки и обслуживания, `docs/11-service-and-api.md` — справочник приложения. Два исходных фото лежат в `docs/images/`. В `reports/2026-10-17/` сохранены измерения и обезличенные результаты проверки, в том числе неудачный начальный тест. Номеров и текстов реальных сообщений там нет.

## Что хранится отдельно

Platform Tools/SDK/Java/ECJ устанавливаются отдельно из источников в [этапе 2](02-host-and-adb.md) и [этапе 9](09-build.md). Их бинарники, SDK-лицензии и USB-драйверы не копируются из пользовательской машины в Git.

Ключ подписи, пароли, SQLite, архивы, pcap и полные диагностические журналы также не входят в поставку. `.local/`, `build/`, IDE-файлы и соответствующие расширения исключены через `.gitignore`. Наличие `.gitignore` не удаляет данные из истории, если кто-либо уже добавил их раньше; перед собственным commit проверяйте `git diff --cached`.
