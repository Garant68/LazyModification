# Сборка killer.dex (инжектируемый dex-движок)

ВАЖНО:
1) Держать статический инициализатор/install() БЕЗ compile-time константных
   проверок вида `CERT_B64.startsWith("__LM_")`: оптимизатор d8 сворачивает
   константу и удаляет тело метода как мёртвый код.
2) Собирать d8 БЕЗ флага --release (иначе тело install() будет удалено).

Команды (пример для Windows, Corretto 17 + Android SDK build-tools 37.0.0):

  javac -encoding UTF-8 -source 8 -target 8 ^
    -cp "hiddenapibypass-classes.jar;%ANDROID_SDK%\platforms\android-34\android.jar" ^
    -d build2\classes src\lazy\sign\KillerApplication.java

  jar cf build2\killer.jar -C build2\classes .

  d8 --min-api 21 --lib "%ANDROID_SDK%\platforms\android-34\android.jar" ^
    --output build2\dex build2\killer.jar hiddenapibypass-classes.jar

Проверка: в build2\dex\classes.dex метод Llazy/sign/KillerApplication;->install()
должен содержать ~60 инструкций (НЕ один return-void).

Затем скопировать build2\dex\classes.dex в app\src\main\assets\killer\killer.dex.