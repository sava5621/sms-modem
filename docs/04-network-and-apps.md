# USB сеть и установка приложений

## Адрес компьютера Windows

USB-сеть нужна только для доступа к странице. На компьютере задайте USB RNDIS-адаптеру `192.168.100.2`, маску `255.255.255.0` (`/24`), оставьте шлюз и DNS пустыми. Основной интернет компьютера продолжает использовать его обычный адаптер.

Сначала найдите нужный адаптер по отключению и подключению именно этого модема:

```powershell
Get-NetAdapter | Format-Table ifIndex, Name, InterfaceDescription, Status
Get-NetIPConfiguration
```

На проверенном компьютере он назывался `Ethernet 3`; имя на другом сервере может отличаться. В свойствах IPv4 нужного адаптера можно ввести параметры вручную. Для нового адаптера без другого статического адреса эквивалентные команды в PowerShell администратора:

```powershell
$modemInterface = 17 # замените на проверенный ifIndex USB RNDIS
Get-NetIPAddress -InterfaceIndex $modemInterface -AddressFamily IPv4
Set-NetIPInterface -InterfaceIndex $modemInterface -AddressFamily IPv4 -Dhcp Disabled
New-NetIPAddress -InterfaceIndex $modemInterface -IPAddress 192.168.100.2 -PrefixLength 24
Set-DnsClientServerAddress -InterfaceIndex $modemInterface -ResetServerAddresses
Get-NetIPConfiguration -InterfaceIndex $modemInterface
Get-NetRoute -InterfaceIndex $modemInterface -AddressFamily IPv4
```

Если адрес уже задан, не выполняйте `New-NetIPAddress` повторно. При существующей другой статической конфигурации сначала сохраните её и измените только выбранный USB-адаптер вручную. Проверьте отсутствие `0.0.0.0/0` и DNS на нём. Не создавайте сетевой мост и не включайте Windows Internet Connection Sharing. Если эта подсеть уже используется другой сетью, устраните конфликт маршрутов до настройки модема.

## Linux сервер

Найдите USB-интерфейс через `ip -br link` и `udevadm info /sys/class/net/<интерфейс>`. Пример временной настройки для проверенного интерфейса `enx...`:

```sh
sudo ip link set dev enxYOURMODEM up
sudo ip addr add 192.168.100.2/24 dev enxYOURMODEM
ip route get 192.168.100.1
```

Для постоянного адреса добавьте эту конфигурацию в используемый сервером менеджер сети, с привязкой к конкретному USB-интерфейсу/MAC и без gateway/DNS/DHCP. Не применяйте одновременно NetworkManager, netplan и systemd-networkd к одному интерфейсу. Имя интерфейса в примере нужно заменить; настройки Linux на исходном Windows-компьютере не испытывались.

## Установка готовых приложений

```powershell
.\scripts\Install-Apps.ps1 -Serial $serial -Adb $adb
```

Устанавливаются подписанные `artifacts/sms-modem-4.1.apk` и `artifacts/wifi-off-1.0.apk`. Используется `adb install -r`, затем запускаются `local.modem.sms/.MainActivity` и `local.modem.wifioff/.EnableActivity`. Данные приложения не очищаются. Скрипт сначала проверяет прошивку и контрольные суммы поставки.

При `INSTALL_FAILED_UPDATE_INCOMPATIBLE` остановитесь: установлен APK с другой подписью. Сохраните данные и используйте исходный ключ подписи для обновления. Удаление приложения ради обхода этой ошибки удалит его локальный архив; установщик этого не делает.

На Android 4.4 разрешения выдаются при установке, отдельного современного окна runtime permissions нет. Root для работы SMS-службы не нужен. APK `wifi-off` реагирует на загрузку и события включения Wi-Fi/AP, не держит постоянную фоновую службу.

До завершения этапа 5 адрес на `rndis0` может ещё не существовать. После установки политики и перезагрузки откройте [локальную страницу](http://192.168.100.1:8088/). Автоматический bind SMS-службы повторяется раз в 3 секунды, пока USB-адрес не появился. Первая загрузка с сетью может занять около 40 секунд.

Следующий этап: [режим только СМС](05-sms-only.md).
