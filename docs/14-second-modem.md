# Второй модем UFI — отдельный профиль прошивки

Статус проверки 17 октября 2026: сайт, архив, WebSocket, индикация и ограничения сети настроены. Доставка настоящих СМС пока не подтверждена: и прежняя SIM MegaFon, и рабочая SIM MTS во втором модеме оставались без регистрации для обычных СМС. Проверку расположения и длительной работы остановило физическое отключение модемов владельцем. [Результаты](../reports/ufi-4.2/README.md).

## 1. Отличия и идентификация

| Параметр | Первый модем | Второй модем |
| --- | --- | --- |
| USB ADB serial на этой машине | `86e3aa1e` | `0` |
| USB ID | `05C6:90B4` | `05C6:9024` |
| Fingerprint | `qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys` | `qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.richal.20251104:user/test-keys` |
| Android / SoC | 4.4.4 / MSM8916 | 4.4.4 / MSM8916 |
| Адрес сайта | `http://192.168.100.1:8088/` | `http://192.168.101.1:8088/` |
| Windows USB IPv4 | `192.168.100.2/24` | `192.168.101.2/24` |
| Приложение | `local.modem.sms` 4.1 | `local.modem.hub` 4.2, versionCode 6 |
| Доступ для политики | Временный root и boot hook | Имеющийся системный UID 1000, без root |
| Цветовые каналы | `4G_L2`, `WIFI_S`, `4G_S` | `green`, `blue`, `red` |

Второй модем уже имел приложение Modem Hub 1.4, системный UID и отдельную USB-подсеть из предыдущей настройки. Сохранение package и подписи позволяет обновить его архив `inbox.db` на месте. Совпадение SoC не означает совместимость установщика первого модема. На втором нет BusyBox, `adb root` запрещён production-прошивкой; старый shell-установщик не запускается и его fingerprint-проверка не снимается.

## 2. USB-сеть Windows

Оба USB-адаптера должны иметь статический IPv4 в разных подсетях, без шлюза и DNS. На этой машине второй — `Ethernet 4`, GUID `{67620759-3EB6-4B94-99D8-F6F05B26CB51}`, USB parent `USB\VID_05C6&PID_9024\0`; первый — `Ethernet 3`. Эти имена и GUID не переносятся на другой компьютер без сверки PnP-привязки.

```powershell
& $adb devices -l
& $adb -s 0 shell getprop ro.build.fingerprint
Get-NetAdapter | Select-Object ifIndex,Name,InterfaceDescription,InterfaceGuid
Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceIndex,IPAddress,PrefixLength
```

Нужный адрес второго адаптера — `192.168.101.2/24`. Изменять первый адаптер или добавлять шлюз через SIM не требуется. Служба второго модема восстанавливает `rndis0` с адресом `192.168.101.1/24` после загрузки или пересоздания USB-интерфейса.

## 3. Установка без потери архива

Для проверенного экземпляра с уже установленным `local.modem.hub`:

```powershell
.\scripts\Install-Ufi.ps1 -Serial '0' -Adb $adb
```

Устанавливается `artifacts/sms-modem-ufi-4.2.apk` с `adb install -r`. Схема базы остаётся 2, прежние сообщения и tombstones сохраняются. Старые дополнительные файлы приложения не удаляются. Не используйте `uninstall`, `pm clear`, установку APK первого модема или перенос его приватной базы во второй.

Подпись второго APK соответствует уже установленному системному приложению: SHA-256 сертификата `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`. Это другая подпись, чем у первого APK. Ключи подписи и сохранённые пароли старой панели в Git не входят.

Новый сайт работает на 8088 с тем же доступом к просмотру, что у первого модема: без пароля чтения, удаление — с прежним кодом. Порты старой панели 80/8088 и её обработчики звонков заменены службой СМС; файлы прежних данных сохраняются. Защита Host/Origin ограничена локальным адресом второго модема; домен reverse proxy первого устройства не разрешён. Подробнее о модели доступа — [HTTP API](11-service-and-api.md).

## 4. Режим «только СМС»

`UfiPolicy` использует штатный `INetworkManagementService` с существующим UID 1000:

- отключает mobile data, data roaming, Wi-Fi, AP и фоновый Wi-Fi scan;
- отключает USB tethering и IPv4/IPv6 forwarding;
- включает системный firewall KitKat для IPv4/IPv6: INPUT по умолчанию DROP, OUTPUT/FORWARD — REJECT; разрешает только интерфейсы `lo` и `rndis0`, без разрешений для `rmnet*` или отдельных UID;
- сохраняет отключённым отдельное приложение панели `com.youdo.server`, уже отключённое в предыдущей настройке;
- восстанавливает только USB LAN; не меняет baseband, IMEI, NV, загрузчик, sysfs SIM-переключатели или системные APK.

Проверка повторяется каждые 10 секунд внутри основной службы. Раз в минуту заново создаются только три штатные firewall-цепочки netd с теми же правилами: это восстанавливает USB-разрешения после возможного перезапуска netd и не накапливает дубликаты. Мобильные данные выключаются до настройки правил. Прежний Hub принудительно включал mobile data каждые 5 секунд; этого поведения в новой версии нет. Отключение data — обязательное условие: служба не включает интернет как обход проблемы регистрации. Сотовое радио остаётся включённым для СМС.

На этой прошивке нет выявленного таймера `persist.ufi.nosignal.reboot` первого устройства. Его workaround и `testConnect=false` к другому vendor-пакету не применяются. BootReceiver восстанавливает службу после BOOT_COMPLETED/замены APK/USB_STATE. Сохранён HOME-компонент, уже выбранный в прежней настройке.

Источники системных API: [NetworkManagementService Android 4.4.4](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-4.4.4_r2/services/java/com/android/server/NetworkManagementService.java), [FirewallController Android 4.4.4](https://android.googlesource.com/platform/system/netd/+/android-4.4.4_r2/FirewallController.cpp). Разрешение по USB-интерфейсу в этой реализации шире, чем правило одного порта первого профиля; SMS HTTP проверяет peer/Host/Origin, а других сетевых служб новый APK не запускает.

## 5. Проверка и индикация

```powershell
.\scripts\Verify-Ufi.ps1 -Serial '0' -Adb $adb -OutputFile '.local\ufi-validation.json'
```

Сценарий не сохраняет тексты/отправителей СМС. Он разделяет `ConfigurationPassed` и `RadioRegistered`: успешная настройка сети/сайта не доказывает доставку СМС оператором. `CarrierSmsReceiptVerified` этим сценарием не подтверждается; для этого отправьте обычное СМС с другого телефона и проверьте появление записи.

Светодиод: готовая SIM и регистрация — темно в простое, открытие HTML — зелёный 5 секунд, отсутствие готовой SIM или регистрации — постоянный синий с приоритетом. Кнопка обновления и WebSocket индикатор не продлевают. Логика общая с [первым модемом](13-led-indicator.md), пути sysfs заменены на `red/green/blue`; GPIO SIM не записываются.

При READY и `OUT_OF_SERVICE` проверьте SIM в заведомо рабочем устройстве и дайте модему время на регистрацию. Приложение не скрывает этот статус и не подменяет его наличием LTE-сигнала/пакетной регистрации. Не копируйте radio properties/NV другого модема.

Для ограниченной диагностики с USB ADB доступна Activity с разрешением DUMP: `am start -n local.modem.hub/.UfiRadioActivity --ei mode <0|1|2|9|12>`. Она использует уже проверенный Binder Qualcomm FeatureService, меняет только стандартный preferred network mode и сохраняет исходное значение в настройках приложения. Проверяйте `settings get global preferred_network_mode`; после неудачного теста возвращайте исходный режим 12. Режимы: 0 GSM/WCDMA auto, 1 GSM-only, 2 WCDMA-only, 9 LTE/GSM/WCDMA, 12 LTE/WCDMA. Произвольных команд и изменения NV у неё нет.

## 6. Сборка и проверка из общих исходников

```powershell
.\scripts\Build-Ufi.ps1 -AndroidJar '<SDK>\android.jar' -BuildToolsDirectory '<build-tools>' `
  -EcjJar '<ECJ>\ecj.jar' -PlatformKey '<вне Git>\platform.pk8' `
  -PlatformCertificate '<вне Git>\platform.x509.pem' -Java '<Java>\bin\java.exe' -IncludeTests
```

Сборка генерирует вариант из `app/src/local/modem/sms` и общего `app/assets/index.html`: заменяет package/IP/пути LED и добавляет ограниченную `UfiPolicy`. Проверяемые текстовые якоря останавливают сборку при несовместимом изменении общей службы. Дополнения лежат в `devices/ufi-20251104/`. APK первого модема и его исходники не переписываются. Выход — `build/sms-modem-ufi-4.2.apk`, тестовый APK — `build/sms-ufi-tests.apk`.

Instrumentation `local.modem.hub.tests/.InboxTest` проверяет общий тракт хранения/HTTP/WebSocket на синтетических данных, очищает только собственные тестовые сообщения и проверяет сохранность существующих ID. Перед тестированием реального архива нужна разрешённая владельцем резервная копия; в исходной проверке второй архив был пуст. После instrumentation заново запустите `local.modem.hub/.MainActivity`, затем удалите только пакет тестов.
