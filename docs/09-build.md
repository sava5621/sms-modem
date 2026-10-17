# Сборка из исходников

Для настройки достаточно готовых файлов из `artifacts/`. Сборка нужна при изменении интерфейса, кода удаления, Host allowlist или логики приложений. Исходники приложений и контрольного JAR сохранены в состоянии рабочей установки; документация и переносимые сценарии сборки добавлены отдельно.

## Инструменты сборки

Проверенная комбинация: Java 8, Eclipse ECJ 3.21.0, Android API 19 и Android Build Tools 28.0.3. Компиляция использует Java source/target 1.7, DEX min-sdk 19; Gradle и Python не требуются.

| Зависимость | Нужный файл |
| --- | --- |
| Java 8 | `java.exe`; `keytool.exe` для создания нового ключа |
| Android SDK Platform API 19 | `platforms/android-19/android.jar` |
| Android Build Tools 28.0.3 для Windows | `aapt.exe`, `lib/dx.jar`, `lib/apksigner.jar` и штатные соседние DLL |
| Eclipse ECJ 3.21.0 | Batch compiler `ecj-4.15.jar`, можно назвать `ecj.jar` |

SDK загружайте через [официальные инструменты Android SDK](https://developer.android.com/tools/sdkmanager). Для существующей установки command-line tools с `sdkmanager.bat` пакеты задаются так:

```powershell
& 'C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat' 'platforms;android-19' 'build-tools;28.0.3'
```

Лицензии SDK принимаются владельцем при установке. Современные инструменты загрузки SDK могут требовать другую Java; сам сценарий сборки запускайте с Java 8, указав её полный путь. ECJ 3.21.0 доступен как JDT Core Batch Compiler в [архиве Eclipse 4.15](https://archive.eclipse.org/eclipse/downloads/drops4/R-4.15-202003050155/).

В исходной рабочей папке Build Tools 28.0.3 были распакованы под именем `android-9`, а API 19 — под `android-4.4.2`. Это исторические имена каталогов, не версии инструментария. Новый сценарий принимает пути явно и не зависит от этих имён или пользовательских папок.

## Подпись

В Git нет исходного `signing.jks`. Для обновления уже установленного приложения нужен именно ключ его подписи; с новым ключом `adb install -r` выдаст несовпадение сертификата. Сначала сохраните архив СМС, не удаляйте приложение для обхода ошибки.

Для отдельного нового устройства можно создать свой ключ в исключённом каталоге:

```powershell
New-Item -ItemType Directory -Force .\.local\keys
& 'C:\Program Files\Java\jre-1.8\bin\keytool.exe' -genkeypair `
  -keystore .\.local\keys\signing.jks -alias modem -keyalg RSA -keysize 2048 `
  -validity 3650 -dname 'CN=Local SMS Modem'
```

`keytool` запросит пароли интерактивно. Сохраните ключ и пароли отдельно от репозитория. Сценарий сборки не генерирует ключ автоматически и не перезаписывает готовые файлы в `artifacts/`.

## Команда сборки

Пароли передаются apksigner через переменные окружения текущего процесса, без литерала пароля в командной строке. Пример:

```powershell
$storeSecret = Read-Host 'Keystore password' -AsSecureString
$keySecret = Read-Host 'Key password' -AsSecureString
$env:SMS_MODEM_STORE_PASSWORD = [Net.NetworkCredential]::new('', $storeSecret).Password
$env:SMS_MODEM_KEY_PASSWORD = [Net.NetworkCredential]::new('', $keySecret).Password
try {
    .\scripts\Build.ps1 `
      -AndroidJar 'C:\Android\Sdk\platforms\android-19\android.jar' `
      -BuildToolsDirectory 'C:\Android\Sdk\build-tools\28.0.3' `
      -EcjJar 'C:\tools\ecj.jar' `
      -Java 'C:\Program Files\Java\jre-1.8\bin\java.exe' `
      -KeyStore .\.local\keys\signing.jks -KeyAlias modem -IncludeTests
} finally {
    Remove-Item Env:SMS_MODEM_STORE_PASSWORD, Env:SMS_MODEM_KEY_PASSWORD -ErrorAction SilentlyContinue
}
```

На выходе: `build/sms-modem.apk`, `build/wifi-off.apk`, `build/sms-only-control.jar`; с `-IncludeTests` также `build/sms-tests.apk`. APK проверяются `apksigner verify`, включая совместимую с Android 4.4 подпись v1. JAR содержит Android `classes.dex` для `app_process`, а не обычные Java class-файлы для компьютера. `build/` исключён из Git.

При подготовке репозитория сборка выполнена в PowerShell 7 и Windows PowerShell 5.1 с временным тестовым ключом. DEX обоих приложений и контроллера побайтно совпал с DEX готовых рабочих файлов. APK целиком отличаются из-за перепаковки и другой подписи; тестовые сборки на действующий модем не устанавливались. SHA-256 сертификата готовых APK: `c25eeda64643a9cd9e335b4ac37634fc5364ac8ba55feebc75ecccaa22f51682`.

Сборка не подключается к устройству. Не заменяйте установленный контрольный JAR новым без проверки и резервной копии. При подготовке новой поставки замените выбранные готовые файлы осознанно и пересчитайте соответствующие записи `artifacts/manifest.json` и `artifacts/SHA256SUMS`.

## Тесты приложения

`app/tests/src/local/modem/sms/tests/InboxTest.java` проверяет сборку multipart Unicode, подавление дубликатов, группировку, фильтр/пагинацию, HTTP JSON, защиту удаления, tombstones и rate limit. Он создаёт синтетические записи и удаляет их в `finally`. Это тест с записью в базу, поэтому запускайте его на отдельном тестовом модеме с резервной копией, подходящими адресами и тестовым APK, подписанным тем же ключом, что и целевое приложение.

```powershell
& $adb -s $serial install -r .\build\sms-tests.apk
& $adb -s $serial shell am instrument -w local.modem.sms.tests/.InboxTest
```

Ожидается `status=passed` и сохранность всех прежних ID. В тесте используется штатный код удаления `3412`; при смене кода обновите и тестовые ожидания. Тест не отправляет настоящую SMS через сеть оператора, поэтому проверку реального приёма из [этапа 6](06-verification.md) он не заменяет.
