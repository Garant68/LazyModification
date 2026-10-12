/*
 * LazyModification - signature verification bypass engine (injected helper).
 *
 * Встраивается в целевое приложение отдельным dex и подключается через
 * подмену суперкласса класса Application. Цель: проверки подписи через
 * PackageManager (signatures / signingInfo.getApkContentsSigners()) должны
 * возвращать ОРИГИНАЛЬНЫЙ сертификат приложения.
 *
 * Техника: обёртка над PackageInfo.CREATOR (подставляет оригинальный
 * сертификат при распаковке PackageInfo), очистка кэшей Parcel и
 * PackageManager. Для доступа к скрытым полям используется HiddenApiBypass
 * (Apache-2.0, LSPosed) — см. NOTICE.md рядом с этим файлом.
 */
package lazy.sign;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public class KillerApplication extends Application {

    // Плейсхолдеры подменяются на этапе патчинга (dexlib2) на реальные значения.
    private static final String CERT_B64 = "__LM_CERT_B64__";
    private static final String TARGET_PKG = "__LM_TARGET_PKG__";

    static {
        try {
            install();
        } catch (Throwable ignored) {
        }
    }

    private static void install() throws Exception {
        // ВАЖНО: не добавлять сюда compile-time проверки вида CERT_B64.startsWith("__LM_") —
        // оптимизатор d8 свернёт константу и удалит всё тело метода как мёртвый код
        // (сборка без --release обязательна).
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app", "Landroid/os/Parcel", "Landroid/content/pm");
        } catch (Throwable ignored) {
        }

        Signature origin = new Signature(Base64.decode(CERT_B64, Base64.DEFAULT));

        // 1) Подмена PackageInfo.CREATOR: все PackageInfo, попадающие в процесс,
        //    будут содержать оригинальный сертификат для целевого пакета.
        try {
            Field f = findField(PackageInfo.class, "CREATOR");
            Object orig = f.get(null);
            if (orig instanceof Parcelable.Creator) {
                f.set(null, new CreatorWrapper((Parcelable.Creator<?>) orig, TARGET_PKG, origin));
            }
        } catch (Throwable ignored) {
        }

        // 2) Чистим кэши, где могли остаться старые CREATOR / PackageInfo.
        try {
            clearMap(findField(Parcel.class, "mCreators"));
            clearMap(findField(Parcel.class, "sPairedCreators"));
        } catch (Throwable ignored) {
        }
        try {
            Object cache = findField(PackageManager.class, "sPackageInfoCache").get(null);
            if (cache != null) {
                Method clear = cache.getClass().getMethod("clear");
                clear.invoke(cache);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void clearMap(Field f) throws Exception {
        Object v = f.get(null);
        if (v instanceof Map) {
            ((Map<?, ?>) v).clear();
        }
    }

    public static final class CreatorWrapper implements Parcelable.Creator<PackageInfo> {
        private final Parcelable.Creator<?> orig;
        private final String pkg;
        private final Signature origin;

        CreatorWrapper(Parcelable.Creator<?> orig, String pkg, Signature origin) {
            this.orig = orig;
            this.pkg = pkg;
            this.origin = origin;
        }

        @Override
        public PackageInfo createFromParcel(Parcel in) {
            Object o = orig.createFromParcel(in);
            if (o instanceof PackageInfo) {
                PackageInfo pi = (PackageInfo) o;
                try {
                    if (pkg == null || pkg.equals(pi.packageName)) {
                        if (pi.signatures != null && pi.signatures.length > 0) {
                            pi.signatures[0] = origin;
                        }
                        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                            Signature[] arr = pi.signingInfo.getApkContentsSigners();
                            if (arr != null && arr.length > 0) {
                                arr[0] = origin;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return (PackageInfo) o;
        }

        @Override
        public PackageInfo[] newArray(int size) {
            return (PackageInfo[]) orig.newArray(size);
        }
    }
}