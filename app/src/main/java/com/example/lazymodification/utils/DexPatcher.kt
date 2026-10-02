package com.example.lazymodification.utils

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.xml.XMLFactory
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.io.BlockReader
import com.reandroid.arsc.value.ResConfig
import com.reandroid.archive.ByteInputSource
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction11n
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction31c
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.io.File
import java.io.StringReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

data class PatchResult(
    val googlePlayPatched: Int = 0,
    val adsPatched: Int = 0,
    val adsBooleanPatched: Int = 0,
    val adsUrlPatched: Int = 0,
    val caAppPubPatched: Int = 0,
    val analyticsUrlPatched: Int = 0,
    val analyticsFieldPatched: Int = 0,
    val gpServicesPatched: Int = 0,
    val vpnPatched: Int = 0,
    val installerCheckPatched: Int = 0,
    val debugItemsRemoved: Int = 0,
    val manifestPatched: Int = 0,
    val signaturePatched: Int = 0,  // ✅ НОВОЕ
    val updateCheckPatched: Int = 0  // Play In-App Updates
) {
    val analyticsPatched: Int get() = analyticsUrlPatched + analyticsFieldPatched
}

object DexPatcher {

    private const val TAG = "DexPatcher"
    private const val BLOCKED_HOST = "https://127.0.0.1"
    private const val CA_APP_PUB_REPLACEMENT = "ca-app-pub-0000000000000000/0000000000"
    private const val ANALYTICS_REPLACEMENT = "http://127.0.0.1/source_code=@CawcaFr/"
    private const val MANIFEST_COMMENT = "<!-- @CawcaFr/-->"

    private val GP_SERVICE_CLASSES = setOf(
        "Lcom/google/android/gms/common/GoogleApiAvailability;",
        "Lcom/google/android/gms/common/GoogleApiAvailabilityLight;",
        "Lcom/google/android/gms/common/GooglePlayServicesUtil;"
    )

    private val AD_URL_REGEX: Regex? by lazy {
        try {
            Regex(
                """(http.*|//.*)(61\.145\.124\.238|/2mdn\.net|-ads\.|\.5rocks\.io|\.ad\.|\.adadapted|\.admitad\.|\.admost\.|\.ads\.|\.aerserv\.|\.airpush\.|\.batmobil\.|\.chartboost\.|\.cloudmobi\.|\.conviva\.|\.dov-e\.com|\.fyber\.|\.mng-ads|\.mydas\.|\.predic\.|\.talkingdata\.|\.tapdaq\.|\.tele\.fm|\.unity3d\.|\.unity\.|\.wapstart\.|\.xdrig\.|\.zapr\.|\/ad\.|\/ads|a4\.tl|accengage|ad4push|ad4screen|ad-mail|ad\..*_logging|ad\.api\.kaffnet\.|ad\.cauly\.co\.|adbuddiz|adc3-launch|adcolony|adfurikun|adincube|adinformation|adkmob|admax\.|admixer|admob|admost|ads\.mdotm\.|adsafeprotected|adservice|adsmogo|adsrvr|adswizz|adtag|adtech\.de|advert|adwhirl|adz\.wattpad\.|alimama\.|alta\.eqmob\.|amazon-.*ads|amazon\..*ads|amobee|analytics|anvato|appboy|appbrain|applovin|applvn|appmetrica|appnext|appodeal|appsdt|appsflyer|apsalar|avocarrot|axonix|banners-slb\.mobile\.yandex\.net|banners\.mobile\.yandex\.net|brightcove\.|burstly|cauly|cloudfront|cmcm\.|com\.google\.android\.gms\.ads\.identifier\.service\.START|comscore|contextual\.media\.net|crashlytics|crispwireless|criteo\.|dmtry\.|doubleclick|duapps|dummy|flurry|fwmrm|gad|getads|gimbal|glispa|google\.com\/dfp|googleAds|googleads|googleapis\..*\.ad-.*|googlesyndication|googletagmanager|greystripe|gstatic|heyzap|hyprmx|iasds01|inmobi|inneractive|instreamatic|integralads|jumptag|jwpcdn|jwpltx|jwpsrv|kochava|localytics|madnet|mapbox|mc\.yandex\.ru|media\.net|metrics\.|millennialmedia|mixpanel|mng-ads\.com|moat\.|moatads|mobclix|mobfox|mobpowertech|moodpresence|mopub|native_ads|nativex\.|nexage\.|ooyala|openx\.|pagead|pingstart|prebid|presage\.io|pubmatic|pubnative|rayjump|saspreview|scorecardresearch|smaato|smartadserver|sponsorpay|startappservice|startup\.mobile\.yandex\.net|statistics\.videofarm\.daum\.net|supersonicads|taboola|tapas|tapjoy|tapylitics|target\.my\.com|teads\.|umeng|unityads|vungle|zucks).*""",
                RegexOption.IGNORE_CASE
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ AD_URL_REGEX: ${e.message}"); null
        }
    }

    private val ANALYTICS_URL_REGEX: Regex? by lazy {
        try {
            Regex(
                """((https|http|www)(:\/\/)?(www(?:.)?)?)?(.*appsflyer(sdk)?\.com(.api)?(.*)?|audience.network(.dex)?|\..*\.facebook\.com.*audience_network.*server_side_reward|(.*)?\.facebook\.com.*adnw_logging.*|р\..*\.facebook\.com.*|\.googleapis\.com.*auth.*games_lite|\.googleapis\.com.*auth.*games(?:\.firstparty)?|app.measurement\.com(?:.*)?|firebaseinstallations.googleapis.com(.*)?|com\.google\.firebase\.analytics.*|.*\.moatads\.com.*|\.mopub\.com\/optout|(ads|analytics)\.mopub\.com.*|ad\.mail\.ru\/|(config|gateway|scar)\.unityads\.(unity3d\.com|unitychina\.cn).*|ads\.api\.vungle\.com\/|api\.vungle\.com\/|vungle\.com\/privacy\/|cdn.*vungle.com.*|\w\.crashlytics\.com\/spi.*events|settings\.crashlytics\.com\/spi.*platforms\/android\/apps\/.*\/settings|ssdk\.adkmob.*|cm\.adkmob\.com.*|.*doubleclick.net|(.*)?googleadservices(.*)?|googleads.*net.*|(.*)googlesyndication.com(.*)?|.*gstatic.*|app\.appsflyer\.com\/|.*(attr|adrevenue|conversions|launches|inapps|monitorsdk|validate|pia|gcdsdk|onelink|ars|viap|validate-and-log)..*/((install_data\/|shortlink-sdk\/)?(api/(remote-debug\/)?)?v.*|remote-debug\/exception-manager)|.*cdn-.*(test)?settings..*/android/v.*|\w\.amazon-adsystem\.com\/|(.*)?google-analytics\.com(.*)?|com\.google.android.gms.analytics(.*)?|.*googletagmanager\.com.*|cdn\d\.inner-active.*html|(.*)?fyber.com(.*)?|com\.google\.(firebase\.)?analytics(.*)?|help\.branch\.io.*|branch.app.link.*|mobile\.martadserver\.com|mediationsdk\.smartadserverapis.*|webview\.unityads.*|(.*)?ads\.vungle\.com(.*)?|privacy.vungle.com.|(cdn|api.*)\.branch\.io\/|adc3-launch-staging\.adcolony\.com.*|wd\.adcolony\.com\/logs|data\.flurry\.com.*|cfg\.flurry\.com\/sdk.*|fev\.fyber\.com\/event|googleapis\.com\/auth\/games|adservice\.google\.com.*|csi\.gstatic\.com\/csi|firebaseapp\.com|firebase-settings\.crashlytics\.com.*|marketplace-android-.*\.hyprmx\.com|(.*)?supersonicads\.com.*|.*\.tapjoy.*\.com\/|(io\.)?(opencensus.*|opentelemetry\.).*|(api.*\.)?.*amplitude\.com.*|prod\.cm.*|googlemobileadssdk.*|api.*adsdk.*|.*amazonaws\.com(.*)?|.*smartadserver\.com.*|adqualitysupport\@smaato.com|smaato.com(.*)?|.*openx\.com.*|(startup.)?mobile.yandex.*net(.*)?|com.yandex.metrica.IMetricaService.*|yandex.com.*html|YandexMetricaNativeModule|.*appmetrica.yandex.com.*|.*appnext\.com.*|(quantum4you|qsoftmobile)\.com.*|ad.api.kaffnet|(app|gdpr|subscription).*adjust.*|ssrv.adjust.*|adjust\.com.terms.*|certificate.mobile.yandex.net|zestadz|sb.scorecardresearch|revmob|r.my.com\/mobile|plus1.wapstart.ru|nexage|moolah|montexi|mobfox|boxdigital\/sdk\/ad|.*https.*startapp.*|(.*)?pubmatic.com(.*)?|herokuapp.appodeal.com(.*)?|.*chartboost.com(.*)?|(.*)?pubnative.net(.*)?|.*ads.com.click.*|(.*)?googleapis.*admob(.*)?|admob.*appspot.com(.*)?|admob.com|d.*cloudfront.net.*|sb.scorecardresearch.com(.*)?|ad.mail.ru\/.*|((med-api|afa(-api)?|resources).)?admost.(github.io|com)(.*)?|github.com.*AzureAd.*|firebaseappcheck.googleapis.com.*|(sdk|api).appbrain.com(.*)?|.*com.appbrain|analytics.us.tiktok.com.*|(firebase)((remoteconfig|installations|logging|inappmessaging)(.googleapis.com))|.*amazon-adsystem.com.*|api.onesignal.com.|config.inmobi.cn.*|schemas\.android.*inmobi\.ads|.*inmobicdn\.net\/sdk.*|.*config\.inmobi\.com\/config-server\/.*|unif-id\.ssp\.inmobi\.com\/fetch|\.inmobi\.com\/products\/sdk.*|.*inmobi.com.*|crash-metrics\.sdk\.inmobi\.com\/trace|telemetry\.sdk\.inmobi\.com\/metrics|ads.inmobi.*sdk|supply\.inmobicdn\.net.*|(assets)?.applovin\.com.*|assets(.)?applovin\.com.*|.*appl(o)?v(i)?n.com(.)?|(prod.*applovin\.com(.*)?|rt\.applovin\.com(.*)?|(dash|docs|sts)\.applovin\.com.*|ms\.(applvn|applovin)\.com(.*)?|prod.*analytics.*|compliance\.iabtechnologylab\.com.*APPLOVIN.*|developers\.applovin\.com.*|(?:.*)?vid\.applovin\.com(?:.*)?)|imasdk.googleapis.com(.*)?|mobile-data.onetrust.io.|mobile-data.|onetrust.io|zc.adswizz.com.*|sdk.*braze.com(.*)?|sondheim.braze.com(.*)?|..appbaqend.com.*|clarity.ms.*|api.mixpanel.com|.*tiktokpangle.*com.*|console.firebase.google.com|(gov-)?mobile-(collector|crash).*(nr-data.net|newrelic.com)|.*(prebid.mobile.android|logTelemetryevent.*function).*|wsmetrics.batch.com(.*)?|\<.*(script|function).*(console.log|celtra|googleAdsJsInterface|omsdk).*|.*(console.log|celtra|googleAdsJsInterface|omsdk|applovin).*(script|function|window|url).*)""",
                RegexOption.IGNORE_CASE
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ ANALYTICS_URL_REGEX: ${e.message}"); null
        }
    }

    private val ANALYTICS_FIELD_REGEX: Regex? by lazy {
        try {
            Regex(
                """((https|http|www|www.*)(:\/\/)?(www|www.*)?)?(api.*advertising.*com.*|adc3-launch\.adcolony\.com.*|audience_network(.dex)?|gamma.*advertising.*com.*|schemas\.applovin\.com\/android\/.*|\.applovin\.com\/privacy\/|\.facebook\.com\/adnw_logging\/|\.googleapis\.com\/auth\/games(.*)?|firebaseappcheck.googleapis.com(.*)?|firebase-settings\.crashlytics\.com(.*)?|com.google.firebase.analytics.FirebaseAnalytics|firebaseinstallations.googleapis.com(.*)?|marketplace-android-.*\.hyprmx\.com|.*inmobi\.com.*|\/config\/secure\.cfg|(.*)?supersonicads\.com(.*)?|.*tapjoy.*\.com\/|.*unityads\.unity3d\.com(.*)?|.*sdk.mediation.unity3d.com.*|(.*)?vungle\.com.*|.*pangle.*|.*mintegral.*|prod.*(advertising|analytics).*|(.*)?log.*inmobi.*|(api.*adsdk.*)|mobile\.smartadserver\.com|.*ads.*vungle.*|.*amazonaws.com.*|(app|gdpr|subscription|ssrv)(.*)?adjust.*|adqualitysupport.smaato.com|ad.mail.ru.*|mobile.yandexadexchange.net|startapp\.com.*|googlemobileadssdk.*|pagead2\.googlesyndication\.com.pagead.*|adservice.google.com(.*)?|(.*)?pubmatic.com(.*)?|(.*)?pubnative.net(.*)?|(.*)?admob.com(.*)?|sb.scorecardresearch.com(.*)?|cdn.appnext.com(?:.*)|admost.(github.io|com)(.*)?|.*amplitude.com.|(cdn|api|api2).branch.io.|.*amazon-adsystem.com.*|api.onesignal.com.|zc.adswizz.com.*|sdk.*braze.com|..appbaqend.com.*|wsmetrics.batch.com(.*)?|(gov-)?mobile-(collector|crash).*(nr-data.net|newrelic.com))""",
                RegexOption.IGNORE_CASE
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ ANALYTICS_FIELD_REGEX: ${e.message}"); null
        }
    }

    private val AD_PATTERN: Regex? by lazy {
        try {
            Regex(
                """invoke(?!.*(close|Deactiv|Destroy|Dismiss|Disabl|error|player|remov|expir|fail|hide|skip|stop|Throw)).*/(adcolony|admob|ads|adsdk|aerserv|appbrain|applovin|appodeal|appodealx|appsflyer|bytedance/sdk/openadsdk|chartboost|flurry|fyber|hyprmx|inmobi|ironsource|mbrg|mbridge|mintegral|moat|mobfox|mobilefuse|mopub|my/target|ogury|Omid|onesignal|presage|smaato|smartadserver|snap/adkit|snap/appadskit|startapp|taboola|tapjoy|tappx|vungle)/.*>(request.*|(.*(activat|Banner|build|Event|exec|header|html|initAd|initi|JavaScript|Interstitial|load|log|MetaData|metri|Native|onAd|propert|report|response|Rewarded|show|trac|url|(fetch|refresh|render|video)Ad).*)|.*Request)\(.*\)V""",
                RegexOption.IGNORE_CASE
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ AD_PATTERN: ${e.message}"); null
        }
    }

    private val AD_PATTERN_BOOLEAN: Regex? by lazy {
        try {
            Regex(
                """invoke(?!.*(close|Deactiv|Destroy|Dismiss|Disabl|error|player|remov|expir|fail|hide|skip|stop|Throw)).*/(adcolony|admob|ads|adsdk|aerserv|appbrain|applovin|appodeal|appodealx|appsflyer|bytedance/sdk/openadsdk|chartboost|flurry|fyber|hyprmx|inmobi|ironsource|mbrg|mbridge|mintegral|moat|mobfox|mobilefuse|mopub|my/target|ogury|Omid|onesignal|presage|smaato|smartadserver|snap/adkit|snap/appadskit|startapp|taboola|tapjoy|tappx|vungle)/.*>(request.*|(.*(activat|Banner|build|Event|exec|header|html|initAd|initi|JavaScript|Interstitial|load|log|MetaData|metri|Native|(can|get|is|has|was)Ad|propert|report|response|Rewarded|show|trac|url|(fetch|refresh|render|video)Ad).*)|.*Request)\(.*\)Z""",
                RegexOption.IGNORE_CASE
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ AD_PATTERN_BOOLEAN: ${e.message}"); null
        }
    }

    private val CA_APP_PUB_PATTERN: Regex? by lazy {
        try { Regex("""ca-app-pub-\d{16}/\d{10}""", RegexOption.IGNORE_CASE) }
        catch (e: Exception) { android.util.Log.e(TAG, "❌ CA_APP_PUB: ${e.message}"); null }
    }

    private val MANIFEST_PATTERN_1: Regex? by lazy {
        try {
            Regex("""((\<(activity(-alias)?|receiver|service|provider)[^<]*\s+android:name="(com.)?(yandex.metrica.*|yandex.mobile.ads.*|mbridge.msdk.*|io.appmetrica.analytics.*|bytedance.sdk.*|bytedance.(sdk|applog).*|ironsource.mediationsdk.*|huawei.(agconnect|hms).*|my.tracker.campaign.Campaign.*|appsflyer.*|google.android.gms.(analytics.*|TagManager(.*)?|measurement.*)|chartboost.sdk.*|io.presage.*|ogury.core.*|clevertap.android.*|com.taboola.android.*|optimizely.ab.android.*|google.android.datatransport.*|google.firebase.analytics.*|io.invertase.firebase.(crashlytics|messaging|app).ReactNativeFirebase.*|contentsquare.android.*|startapp.sdk.*|adjust.sdk.*|smartadserver.android.library.*|appnexus.opensdk.|tv.teads.sdk.*|yoc.visx.sdk.*|moengage.*|amazon.device.iap.*|amazon.device.ads.*|androidx.work.impl.diagnostics.*|inmobi.(commons|ads|androidsdk).*|org.altbeacon.*|com.adxcorp.ads.*|.*amazonaws.*|google.ads.*|tradplus.ads.*|anythink.(core|basead|expressad).*|org.acra.(sender.JobSenderService|sender.LegacySenderService|attachment.AcraContentProvider|receiver.*)|singular.sdk.SingularInstallReceiver|heytap.msp.push.service.(.*data.*)|sensorsdata.analytics.*|wandoujia.zendesk.*|com.appbrain.AppBrain.*|cleveradssolutions.internal.*|io.bidmachine.*|com.instabug.*|moloco.sdk.*|tp.adx.sdk.*|tradplus.crosspro.*|io.sentry.kotlin.multiplatform.SentryContextProvider|onesignal.notification(DismissReceiver|OpenedReceiver(.*)?)|onesignal(.notifications.(services|receivers))?.(FCMBroadcastReceiver|HmsMessageServiceOneSignal|FCMIntentService|NotificationOpenedActivityHMS|UpgradeReceiver|BootUpReceiver)|braze.(push|ui|braze).*|appodeal.(ads|consent).*|net.pubnative.*|tech.crackle.cracklertbsdk.vast.*|tech.crackle.core_sdk.ads.*|.*appnext\.core.*|jio.jioads.*)"[^>]*>(\s+\<(intent-filter|meta-data|action|data|category|property)\>?[^>]*>)+(\s+\<\/intent-filter\>\s+)?(\<(intent-filter|meta-data|action|data|category)[^>]*>)?(\s+)?(\<\/(reciever|service|provider)\>)?\<\/(activity(-alias)?|receiver|service|provider)\>)|(\<(meta-data|uses-library)\s+android:name="(com.)?(((google|firebase)_(performance|analytics|crashlytics)_(default_allow_(analytics_storage|ad_(personalization_signals|storage|user_data)?)|((adid_|ssaid_)?collection_|automatic_screen_reporting_|deferred_deep_link_)(enabled|deactivated)))|(in_app_messaging_auto_collection_enabled|firebase_inapp_messaging_auto_data_collection_enabled|firebase_crash_collection_enabled)|app_data_collection_default_enabled|unity3d.services.core.configuration.AdsSdkInitializer|google.android.gms.ads.*|android.ext.adservices|io.sentry.(auto-init|dsn|release|ndk.scope.*|proguard.*|traces.*|attach.*)|contentsquare.android.*|bugsnag.android.*|yandex.mobile.ads.*|bytedance.sdk.pangle.*|bytedance.(sdk|applog).*|google.android.play.billingclient.*|clevertap.*|(.*)?amazon.client.metrics.api(.*)?|facebook.sdk.(.*LogAppEvent.*|Advertiser.*|.*LogEnabled)|firebase_performance_logcat_enabled|delivery_metrics_exported_to_big_query_enabled|BaiduMobAd.*|sensorsdata.analytics.*|moloco.sdk.*|huawei.hms.(client.service.*|min_api_level.*)|io.sentry.gradle-plugin-integrations|onesignal.(BadgeCount|Notification.*)|mobilefuse.sdk.disable_auto_init|io.branch.sdk.(BranchKey|TestMode).*)"[^>]*>)|(\<intent\>\s*\<(package|action)\sandroid:name="(com.)?(google.android.apps.play.billing.*|android.vending.billing.*|applovin.*|appsflyer.*|com.singular.preinstall.*|huawei.hms.core(.*)?)\s*\<\/intent\>)|(\<package\sandroid:name="(com.)?(appnext.core|pubmatic.openwrapapp|huawei.(hms|hff|hwid.*))" \/\>))""", RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ MANIFEST_PATTERN_1: ${e.message}"); null
        }
    }

    private val MANIFEST_PATTERN_2: Regex? by lazy {
        try {
            Regex("""(\<(activity|provider|service|receiver|property|uses-permission)[^>]*\s+android:name="(com.)?((BIND_GET_INSTALL_REFERRER_SERVICE|android.billingclient.api.ProxyBillingActivity(V2)?|android.permission.(AD_ID|AD_SERVICES_CONFIG|ACCESS_ADSERVICES_(AD_ID|ATTRIBUTION|TOPICS))|google.android.(gms.oss.licenses.(OssLicensesMenuActivity|OssLicensesActivity)|ads.mediationtestsuite.activities.(HomeActivity|NetworkDetailActivity|ConfigurationItemDetailActivity|ConfigurationItemsSearchActivity)|gms.permission.AD_ID|gms.ads.(AdActivity|OutOfContextTestingActivity|NotificationHandlerActivity)|finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE|tv.ads.controls.FallbackImageActivity)|amazon.device.ads.*|facebook.ads.*|amazon.aps.ads.*|applovin.adview.(AppLovinInterstitialActivity|AppLovinFullscreenThemedActivity)|huawei.appmarket.service.commondata.permission.GET_COMMON_DATA|inmobi.(cmp|choice|signals|commons|ads|androidsdk|rendering).*|unity3d.(services.)?ads.(adunit|adplayer).(AdUnitActivity|FullScreenWebViewDisplay|AdUnitTransparentActivity|AdUnitTransparentSoftwareActivity|AdUnitSoftwareActivity)|vungle.ads.internal.*|io.bidmachine.*|ironsource.(sdk.)?(controller.)?(OpenUrlActivity|InterstitialActivity|mediationsdk.testSuite.TestSuiteActivity|controller.ControllerActivity)|pubmatic.((sdk.)?(common.browser.POBInternalBrowserActivity|webrendering.mraid.POBVideoPlayerActivity|webrendering.ui.POBFullScreenActivity))|fyber.ads.ofw.OfferWallActivity|fyber.inneractive.sdk.activities.(InneractiveInternalBrowserActivity|InneractiveFullscreenAdActivity|InneractiveRichMediaVideoPlayerActivityCore|InternalStoreWebpageActivity|FyberReportAdActivity)|mbridge.msdk.(newreward.player.MBRewardVideoActivity|interstitial.view.MBInterstitialActivity|out.LoadingActivity|activity.MBCommonActivity|reward.player.MBRewardVideoActivity)|adcolony.sdk.(AdColonyAdViewActivity|AdColonyInterstitialActivity)|tapjoy.(TJWebViewActivity|TJContentActivity|TJAdUnitActivity)|smaato.sdk.*|bytedance.(sdk|applog).*|startapp.sdk.ads(base.consent.ConsentActivity|.(interstitials.OverlayActivity|list3d.List3DActivity))|current.android.feature.ads.report.AdStackReportActivity|my.target.common.MyTargetActivity|kidoz.sdk.api.ui_views.interstitial.KidozAdActivity|chartboost.sdk.(view.)?(CBImpressionActivity|internal.clickthrough.EmbeddedBrowserActivity)|tv.superawesome.sdk.publisher.(SAInterstitialAd|SAVideoActivity|managed.SAManagedAdActivity)|ogury.cm.ConsentActivity|ogury.ad.interstitial.ui.Interstitial(Activity|Android8TransparentActivity|Android8RotableActivity)?|yandex.mobile.ads.*|yandex.metrica.*|braintreepayments.api.(threedsecure.ThreeDSecureWebViewActivity|GooglePaymentActivity|AndroidPayActivity|BraintreeBrowserSwitchActivity)|sns.payments.(offers.push.PaymentOfferPushActivity|google.recharge.GooglePurchaseCurrencyActivity)|mopub.*|criteo.publisher.*|io.adjoe.sdk.AdjoeActivity|mobilefuse.sdk.*|rendering.splashad.MobileFuseSplashAdActivity|wortise.ads.(appopen.AppOpenActivity|interstitial.InterstitialActivity)|facebook.ads.AudienceNetworkContentProvider|google.android.gms.measurement.AppMeasurement(Receiver|Service|JobService)|google.android.gms.(analytic.*|ads.*|TagManager(.*)?)|android.adservices.AD_SERVICES_CONFIG|loopme.views.activity.(BaseActivity|MraidVideoActivity)|io.didomi.sdk.(notice.ctv.TVNoticeDialogActivity|preferences.ctv.TVPreferencesDialogActivity)|adadapted.android.sdk.core.view.AaWebViewPopupActivity|applovin.sdk.AppLovinInitProvider|ironsource.lifecycle.*|PreloadInfoContentProvider)|yandex.mobile.ads.*|explorestack.iab.(mraid.Mraid(DialogActivity|Activity)|vast.activity.VastActivity)|cn.thinkingdata.analytics.utils.broadcast.TDReceiver|org.acra.(sender.JobSenderService|sender.LegacySenderService|attachment.AcraContentProvider|receiver.*)|io.sentry.android.core.(SentryInitProvider|SentryPerformanceProvider)|io.sentry.kotlin.multiplatform.SentryContextProvider|io.invertase.firebase.(crashlytics|messaging|app).ReactNativeFirebase.*|adjust.sdk.SystemLifecycleContentProvider|appnext.ads.*|my.target.common.MyTargetContentProvider|appnext.banners.BannerActivity|appnext.core.result.*|.*appnext\.(com|core).*|sg.bigo.ads.*|helpshift.(activities|unityproxy).*|anzu.sdk.*|huawei.(agconnect|hms).*|my.tracker.campaign.Campaign.*|vk.api.sdk.*|hyprmx.android.sdk.*|google.ads.*|clevertap.android.*|appsee.AppseeBackgroundUploader|bugsnag.android.internal.*|com.taboola.android.*|tappx.sdk.android.*|gomfactory.adpie.sdk.*|igaworks.ssp.part.*|kakao.adfit.*|mobon.sdk.*|.*admixer.*|coupang.ads.*|vungle.(warren|ads).*|net.pubnative.*|tech.crackle.cracklertbsdk.vast.*|tech.crackle.core_sdk.ads.*|applovin.*|microsoft.appcenter.loader.AppCenterLoader|google.android.datatransport.*|contentsquare.android.*|startapp.sdk.*|co.notix.interstitial.InterstitialActivity|io.presage.*|gameanalytics.*|five_corp.ad.*|smartadserver.android.library.*|admost.sdk.*|net.nend.android.internal.ui.activities.*|mngads.(sdk|service).*|yoc.visx.sdk.*|tv.teads.sdk.*|appnexus.opensdk.*|org.prebid.mobile.*|moengage.*|adswizz.interactivead.*|io.appmetrica.analytics.*|onesignal.notification(DismissReceiver|OpenedReceiver(.*)?)|onesignal.((notifications|core).(receivers|services|activities).)?(FCMBroadcastReceiver|FCMIntentService|NotificationDismissReceiver|NotificationOpenedActivity(.*)?)|feedad.*|rtb.sdk.*|org.altbeacon.*|com.adxcorp.ads.*|.*amazonaws.*|(.*)?amazon.*metrics(.*)?|miui.systemAdSolution.*|sina.weibo.sdk.component.WeiboSdkBrowser|tradplus.ads.*|anythink.(core|basead|expressad).*|benchmark.*|heytap.msp.push.service.(.*data.*)|wandoujia.zendesk.*|com.appbrain.AppBrain.*|cleveradssolutions.internal.*|com.instabug.*|moloco.sdk.*|tp.adx.sdk.*|tradplus.crosspro.*|uxcam.service.HttpPostService|braze.(push|ui|braze).*|appodeal.(ads|consent).*|tutelatechnologies.sdk.framework.TutelaSDKService|mbridge.msdk.*|jio.jioads.*)"[^>]*>)""", RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ MANIFEST_PATTERN_2: ${e.message}"); null
        }
    }

    private val DOMAIN_LIKE_REGEX = Regex("""^[a-z0-9.-]+\.[a-z]{2,}(/.*)?$""", RegexOption.IGNORE_CASE)

    // Быстрый предфильтр ключевых слов аналитики (дешёвый trie-матч перед тяжёлой регуляркой)
    private val ANALYTICS_KEYWORD_REGEX = Regex(
        """appsflyer|audience_network|adnw_logging|firebase|measurement|moatads|mopub|unityads|vungle|crashlytics|adkmob|doubleclick|googleadservices|googleads|googlesyndication|gstatic|amazon-adsystem|google-analytics|googletagmanager|inner-active|fyber|branch|martadserver|smartadserver|adcolony|flurry|adservice|firebaseapp|hyprmx|supersonicads|tapjoy|opencensus|opentelemetry|amplitude|googlemobileadssdk|adsdk|amazonaws|smaato|openx|metrica|yandex|appnext|adjust|scorecardresearch|startapp|pubmatic|appodeal|chartboost|pubnative|admob|cloudfront|admost|appbrain|tiktok|onesignal|inmobi|applovin|imasdk|onetrust|adswizz|braze|appbaqend|clarity|mixpanel|pangle|newrelic|prebid|batch|console.log|celtra|googleAdsJsInterface|omsdk|adrevenue|conversions|launches|inapps|monitorsdk|gcdsdk|onelink|viap|validate-and-log|kochava|localytics|clevertap|singular|taboola|moengage|sentry|instabug|gameanalytics|appmetrica|revmob|moolah|mobfox|nexage|zestadz|wapstart|montexi|quantum4you|qsoftmobile|kaffnet""",
        RegexOption.IGNORE_CASE
    )

    // Быстрый предфильтр классов рекламных SDK (перед buildSmaliInvokeString + AD_PATTERN)
    private val AD_CLASS_PREFILTER = Regex(
        """/adcolony/|/admob/|/ads/|/adsdk/|/aerserv/|/appbrain/|/applovin/|/appodeal/|/appsflyer/|/chartboost/|/flurry/|/fyber/|/hyprmx/|/inmobi/|/ironsource/|/mintegral/|/moat/|/mobfox/|/mobilefuse/|/mopub/|/ogury/|/onesignal/|/presage/|/smaato/|/smartadserver/|/startapp/|/taboola/|/tapjoy/|/tappx/|/vungle/|/mbridge/|/bytedance/|my/target|snap/adkit|/omid/""",
        RegexOption.IGNORE_CASE
    )

    private fun isUrlLike(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true) ||
            trimmed.startsWith("//")) return true
        return DOMAIN_LIKE_REGEX.matches(trimmed)
    }

    private fun isAnalyticsString(s: String): Boolean {
        if (!isUrlLike(s)) return false
        if (!ANALYTICS_KEYWORD_REGEX.containsMatchIn(s)) return false
        return ANALYTICS_URL_REGEX?.containsMatchIn(s) ?: false
    }

    private fun isAnalyticsFieldString(s: String): Boolean {
        if (!isUrlLike(s)) return false
        return ANALYTICS_FIELD_REGEX?.containsMatchIn(s) ?: false
    }

    private fun isSplitUrlFragment(s: String): Boolean = s.startsWith("hts/") || s.startsWith("tp:")

    private fun isAdUrlString(s: String): Boolean {
        if (!isUrlLike(s)) return false
        return AD_URL_REGEX?.containsMatchIn(s) ?: false
    }

    fun hasAdCalls(classDef: org.jf.dexlib2.iface.ClassDef): Boolean {
        for (method in classDef.directMethods + classDef.virtualMethods) {
            val impl = method.implementation ?: continue
            for (instr in impl.instructions) {
                if (instr.opcode.name.startsWith("invoke-")) {
                    val ref = (instr as? ReferenceInstruction)?.reference
                    if (ref is MethodReference && ref.returnType == "V") {
                        val sig = buildSmaliInvokeString(instr.opcode, ref)
                        if (AD_PATTERN != null && AD_PATTERN!!.containsMatchIn(sig)) return true
                    }
                } else if (isConstString(instr)) {
                    val ref = (instr as ReferenceInstruction).reference
                    if (ref is StringReference) {
                        val s = ref.string
                        if (CA_APP_PUB_PATTERN != null && CA_APP_PUB_PATTERN!!.containsMatchIn(s)) return true
                        if (isAdUrlString(s)) return true
                    }
                }
            }
        }
        return false
    }

    fun hasAnalyticsCalls(classDef: org.jf.dexlib2.iface.ClassDef): Boolean {
        for (field in classDef.staticFields) {
            val v = field.initialValue
            if (v is StringEncodedValue && isAnalyticsFieldString(v.value)) return true
        }
        for (method in classDef.directMethods + classDef.virtualMethods) {
            val impl = method.implementation ?: continue
            for (instr in impl.instructions) {
                if (isConstString(instr)) {
                    val ref = (instr as? ReferenceInstruction)?.reference
                    if (ref is StringReference && isAnalyticsString(ref.string)) return true
                }
            }
        }
        return false
    }

    private fun createConstZeroInstruction(register: Int): Instruction {
        return if (register <= 15) {
            ImmutableInstruction11n(Opcode.CONST_4, register, 0x0)
        } else {
            ImmutableInstruction31i(Opcode.CONST, register, 0x0)
        }
    }

    // ============================================================
    // ПАТЧИНГ DEX
    // ============================================================
    fun patchDex(
        inputDex: File, outputDex: File,
        patchGooglePlay: Boolean = false, patchRemoveAds: Boolean = false,
        patchRemoveAnalytics: Boolean = false, patchRemoveGPServices: Boolean = false,
        patchRemoveVpn: Boolean = false, patchRemoveInstallerCheck: Boolean = false, patchRemoveDebug: Boolean = false,
        patchRemoveSignature: Boolean = false,  // ✅ НОВОЕ
        patchRemoveUpdate: Boolean = false  // Play In-App Updates
    ): PatchResult {
        val opcodes = Opcodes.forApi(28)
        val dex = DexFileFactory.loadDexFile(inputDex, opcodes)

        var googlePlayCount = 0; var adsCount = 0; var adsBooleanCount = 0; var urlsCount = 0
        var caAppPubCount = 0; var analyticsUrlCount = 0; var analyticsFieldCount = 0
        var gpServicesCount = 0; var vpnCount = 0; var installerCheckCount = 0; var debugItemsCount = 0
        var signatureCount = 0  // ✅ НОВОЕ
        var updateCount = 0

        val startTime = System.currentTimeMillis()
        android.util.Log.d(TAG, "🔍 DEX: ${inputDex.name}, GP=$patchGooglePlay, Ads=$patchRemoveAds, Analytics=$patchRemoveAnalytics, GP_Svc=$patchRemoveGPServices, VPN=$patchRemoveVpn, Debug=$patchRemoveDebug, Sign=$patchRemoveSignature")

        try {
            val patchedClasses = dex.classes.map { classDef ->
                val patchedStaticFields = if (patchRemoveAnalytics) {
                    classDef.staticFields.map { field -> patchStaticField(field) { analyticsFieldCount++ } }
                } else classDef.staticFields

                val directMethods = classDef.directMethods.map { method ->
                    patchMethod(method, patchGooglePlay, patchRemoveAds, patchRemoveAnalytics, patchRemoveGPServices, patchRemoveVpn, patchRemoveInstallerCheck, patchRemoveDebug, patchRemoveSignature, patchRemoveUpdate,
                        { googlePlayCount++ }, { adsCount++ }, { adsBooleanCount++ }, { urlsCount++ }, { caAppPubCount++ },
                        { analyticsUrlCount++ }, { gpServicesCount++ }, { vpnCount++ }, { installerCheckCount++ }, { debugItemsCount++ }, { signatureCount++ }, { updateCount++ })
                }

                val virtualMethods = classDef.virtualMethods.map { method ->
                    patchMethod(method, patchGooglePlay, patchRemoveAds, patchRemoveAnalytics, patchRemoveGPServices, patchRemoveVpn, patchRemoveInstallerCheck, patchRemoveDebug, patchRemoveSignature, patchRemoveUpdate,
                        { googlePlayCount++ }, { adsCount++ }, { adsBooleanCount++ }, { urlsCount++ }, { caAppPubCount++ },
                        { analyticsUrlCount++ }, { gpServicesCount++ }, { vpnCount++ }, { installerCheckCount++ }, { debugItemsCount++ }, { signatureCount++ }, { updateCount++ })
                }

                ImmutableClassDef(classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile, classDef.annotations,
                    patchedStaticFields, classDef.instanceFields, directMethods, virtualMethods)
            }

            writeDexWithRepair(outputDex, opcodes, patchedClasses)
            android.util.Log.d(TAG, "📊 GP=$googlePlayCount, Ads(V)=$adsCount, Ads(Z)=$adsBooleanCount, URL=$urlsCount, ca-app-pub=$caAppPubCount, Analytics(URL)=$analyticsUrlCount, Analytics(Field)=$analyticsFieldCount, GP_Svc=$gpServicesCount, VPN=$vpnCount, Sign=$signatureCount, Debug=$debugItemsCount, Time=${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) { android.util.Log.e(TAG, "❌ Ошибка: ${e.message}", e); throw e }

        return PatchResult(googlePlayCount, adsCount + adsBooleanCount, adsBooleanCount, urlsCount, caAppPubCount,
            analyticsUrlCount, analyticsFieldCount, gpServicesCount, vpnCount, installerCheckCount, debugItemsCount, 0, signatureCount, updateCount)
    }

    // ============================================================
    // ✅ АВТО-ПОЧИНКА: методы, которые dexlib2 не может записать
    // («Exception occurred while writing code_item for method ...» —
    // например, кривая структура try/catch после R8/bundletool).
    // Повторяем запись, отбросив таблицу try/catch у проблемного метода.
    // ============================================================
    private fun writeDexWithRepair(outputDex: File, opcodes: Opcodes, classes: List<org.jf.dexlib2.iface.ClassDef>) {
        try {
            DexFileFactory.writeDexFile(outputDex.absolutePath, ImmutableDexFile(opcodes, classes))
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("code_item for method")) throw e
            val methodDesc = msg.substringAfter("code_item for method ").substringBefore(" ")
            // Починка 1: отбросить try/catch у проблемного метода
            val repaired = repairClassList(classes, methodDesc)
            if (repaired == null) throw e
            android.util.Log.w(TAG, "♻️ Починка метода $methodDesc: таблица try/catch отброшена")
            try {
                DexFileFactory.writeDexFile(outputDex.absolutePath, ImmutableDexFile(opcodes, repaired))
            } catch (e2: Exception) {
                val protoStr = findItemNotFound(e2)
                if (protoStr == null) throw e2
                // Починка 2: недостающий прото (например, прото invoke-polymorphic,
                // на который ссылается только эта инструкция) — добавляем
                // синтетический native-метод с таким прото в первый класс.
                val fixed = addProtoStub(repaired, protoStr)
                if (fixed == null) throw e2
                android.util.Log.w(TAG, "♻️ Добавлен заглушечный прото: $protoStr")
                DexFileFactory.writeDexFile(outputDex.absolutePath, ImmutableDexFile(opcodes, fixed))
            }
        }
    }

    private fun findItemNotFound(t: Throwable?): String? {
        var cur = t
        while (cur != null) {
            val m = cur.message ?: ""
            if (m.contains("Item not found.")) {
                return m.substringAfter("Item not found.: ").substringBefore(" ")
            }
            cur = cur.cause
        }
        return null
    }

    private fun addProtoStub(classes: List<org.jf.dexlib2.iface.ClassDef>, protoStr: String): List<org.jf.dexlib2.iface.ClassDef>? {
        val paren = protoStr.indexOf('(')
        val close = protoStr.indexOf(')', paren)
        if (paren < 0 || close < 0) return null
        val paramTypes = splitParamTypes(protoStr.substring(paren + 1, close))
        val returnType = protoStr.substring(close + 1)
        val first = classes.firstOrNull() ?: return null
        val stub = ImmutableMethod(
            first.type, "__lm_proto_fix",
            paramTypes.map { org.jf.dexlib2.immutable.ImmutableMethodParameter(it, mutableSetOf(), null) },
            returnType,
            0x8 or 0x100 or 0x1000, // static | native | synthetic
            null, null, null
        )
        val newFirst = ImmutableClassDef(
            first.type, first.accessFlags, first.superclass, first.interfaces,
            first.sourceFile, first.annotations, first.staticFields, first.instanceFields,
            first.directMethods + stub, first.virtualMethods
        )
        return classes.map { if (it === first) newFirst else it }
    }

    private fun repairClassList(classes: List<org.jf.dexlib2.iface.ClassDef>, methodDesc: String): List<org.jf.dexlib2.iface.ClassDef>? {
        val idx = methodDesc.indexOf(";->")
        if (idx < 0) return null
        val className = methodDesc.substring(0, idx) + ";"
        val rest = methodDesc.substring(idx + 3)
        val paren = rest.indexOf('(')
        val close = rest.indexOf(')', paren)
        if (paren < 0 || close < 0) return null
        val name = rest.substring(0, paren)
        val params = splitParamTypes(rest.substring(paren + 1, close))
        val returnType = rest.substring(close + 1)
        var found = false
        val newClasses = classes.map { c ->
            if (c.type != className) c
            else ImmutableClassDef(
                c.type, c.accessFlags, c.superclass, c.interfaces, c.sourceFile, c.annotations,
                c.staticFields, c.instanceFields,
                c.directMethods.map { m -> if (!found && matchesMethod(m, name, params, returnType)) { found = true; repairMethod(m) } else m },
                c.virtualMethods.map { m -> if (!found && matchesMethod(m, name, params, returnType)) { found = true; repairMethod(m) } else m }
            )
        }
        return if (found) newClasses else null
    }

    private fun matchesMethod(m: org.jf.dexlib2.iface.Method, name: String, params: List<String>, returnType: String): Boolean =
        m.name == name && m.returnType == returnType && m.parameterTypes.map { it.toString() } == params

    private fun repairMethod(m: org.jf.dexlib2.iface.Method): org.jf.dexlib2.iface.Method {
        val impl = m.implementation ?: return m
        val instructions = impl.instructions.toList()
        val newImpl = ImmutableMethodImplementation(impl.registerCount, instructions, emptyList(), impl.debugItems)
        return ImmutableMethod(m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags, m.annotations, m.hiddenApiRestrictions, newImpl)
    }

    private fun splitParamTypes(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            if (s[i] == '[') {
                while (i < s.length && s[i] == '[') i++
                if (i < s.length && s[i] == 'L') { while (i < s.length && s[i] != ';') i++ }
            } else if (s[i] == 'L') {
                while (i < s.length && s[i] != ';') i++
            }
            i++
            result.add(s.substring(start, i))
        }
        return result
    }

    // ============================================================
    // ✅ КЛОНИРОВАНИЕ: замена имени пакета в DEX (строки)
    // ============================================================
fun patchPackageName(inputDex: File, outputDex: File, oldPackage: String, newPackage: String): Int {
        val bytes = inputDex.readBytes()
        val oldDot = oldPackage.toByteArray(Charsets.UTF_8)
        val newDot = newPackage.toByteArray(Charsets.UTF_8)
        val oldSlash = oldPackage.replace('.', '/').toByteArray(Charsets.UTF_8)
        val newSlash = newPackage.replace('.', '/').toByteArray(Charsets.UTF_8)

        var count = 0
        count += replaceAllBytes(bytes, oldSlash, newSlash)
        count += replaceAllBytes(bytes, oldDot, newDot)

        if (count > 0) {
            fixDexChecksums(bytes)
            outputDex.writeBytes(bytes)
        } else {
            inputDex.copyTo(outputDex, overwrite = true)
        }
        android.util.Log.d("DexPatcher", "PATCH replaced=" + count)
        return count
    }

    private fun replaceAllBytes(data: ByteArray, from: ByteArray, to: ByteArray): Int {
        if (from.isEmpty() || from.size != to.size) return 0
        var count = 0
        var i = 0
        while (i <= data.size - from.size) {
            var match = true
            for (j in from.indices) {
                if (data[i + j] != from[j]) { match = false; break }
            }
            if (match) {
                System.arraycopy(to, 0, data, i, to.size)
                count++
                i += from.size
            } else {
                i++
            }
        }
        return count
    }

    private fun fixDexChecksums(data: ByteArray) {
        if (data.size < 32) return
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(data.copyOfRange(32, data.size))
        System.arraycopy(sha1, 0, data, 12, 20)
        val adler = java.util.zip.Adler32()
        adler.update(data, 12, data.size - 12)
        val v = adler.value.toInt()
        data[8] = (v and 0xFF).toByte()
        data[9] = ((v shr 8) and 0xFF).toByte()
        data[10] = ((v shr 16) and 0xFF).toByte()
        data[11] = ((v shr 24) and 0xFF).toByte()
    }

    private fun replacePackageName(s: String, oldPackage: String, newPackage: String): String {
        return s.replace(oldPackage, newPackage).replace(oldPackage.replace(".", "/"), newPackage.replace(".", "/"))
    }

    private fun patchPackageField(field: Field, oldPackage: String, newPackage: String, onPatch: () -> Unit): Field {
        val value = field.initialValue
        if (value is StringEncodedValue && (value.value.contains(oldPackage) || value.value.contains(oldPackage.replace(".", "/")))) {
            onPatch()
            return ImmutableField(field.definingClass, field.name, field.type, field.accessFlags,
                ImmutableStringEncodedValue(replacePackageName(value.value, oldPackage, newPackage)),
                field.annotations, field.hiddenApiRestrictions)
        }
        return field
    }

    private fun patchPackageMethod(method: org.jf.dexlib2.iface.Method, oldPackage: String, newPackage: String, onPatch: () -> Unit): org.jf.dexlib2.iface.Method {
        val impl = method.implementation ?: return method
        val instructions = impl.instructions.toList()
        val patched = mutableListOf<org.jf.dexlib2.iface.instruction.Instruction>()
        var changed = false
        for (instr in instructions) {
            if ((instr.opcode == Opcode.CONST_STRING || instr.opcode == Opcode.CONST_STRING_JUMBO) && instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is StringReference && (ref.string.contains(oldPackage) || ref.string.contains(oldPackage.replace(".", "/")))) {
                    onPatch()
                    changed = true
                    patched.add(createConstStringInstruction(instr, replacePackageName(ref.string, oldPackage, newPackage)))
                    continue
                }
            }
            patched.add(instr)
        }
        if (!changed) return method
        val newImpl = ImmutableMethodImplementation(impl.registerCount, patched, impl.tryBlocks, impl.debugItems)
        return ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl)
    }


    // ============================================================
    // ПАТЧИНГ МАНИФЕСТА
    // ============================================================
    fun patchManifest(inputApk: File, outputApk: File): Int {
        var count = 0
        try {
            val apkModule = ApkModule.loadApkFile(inputApk)
            val manifest = apkModule.androidManifest ?: throw IllegalStateException("AndroidManifest.xml не найден")
            // ВАЖНО: НЕ трогаем apkModule.tableBlock — на APK с огромным resources.arsc
            // его разбор требует сотни МБ памяти и валит приложение с OutOfMemoryError.
            // Удаляем узлы рекламно-аналитических SDK прямо в дереве XML-документа.
            val root = manifest.documentElement
                ?: throw IllegalStateException("Не удалось получить корень манифеста")
            count = removeAnalyticsManifestNodes(root)
            apkModule.writeApk(outputApk)
            apkModule.close()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "❌ Ошибка патчинга манифеста: ${t.message}", t)
            if (!outputApk.exists() || outputApk.length() == 0L) inputApk.copyTo(outputApk, overwrite = true)
            count = 0
        }
        android.util.Log.d("DexPatcher", "PATCH replaced=" + count)
        return count
    }

    private val MANIFEST_COMPONENT_TAGS = setOf("activity", "activity-alias", "receiver", "service", "provider")
    private val MANIFEST_CHILD_TAGS = setOf("intent-filter", "meta-data", "action", "data", "category", "property")

    // Удаляет рекламно-аналитические узлы манифеста, сверяясь с MANIFEST_PATTERN_1/2
    // по синтезированному фрагменту XML конкретного узла (без сериализации всего документа).
    private fun removeAnalyticsManifestNodes(root: ResXmlElement): Int {
        var removed = 0
        val children = ArrayList<ResXmlElement>()
        val it = root.elements
        while (it.hasNext()) {
            val n = it.next()
            if (n is ResXmlElement) children.add(n)
        }
        for (el in children) {
            val tag = el.name
            if (tag == "intent" && manifestIntentMatches(el)) {
                el.removeSelf(); removed++; continue
            }
            val value = if (tag != null) manifestNameAttr(el) else null
            if (tag != null && value != null) {
                var synth: String? = null
                if (tag in MANIFEST_COMPONENT_TAGS) {
                    val ct = firstManifestListedChildTag(el)
                    synth = if (ct != null)
                        "<$tag android:name=\"$value\"> <$ct/> </$tag>"
                    else
                        "<$tag android:name=\"$value\"/>"
                } else if (tag == "meta-data" || tag == "uses-library" || tag == "property" || tag == "uses-permission") {
                    synth = "<$tag android:name=\"$value\"/>"
                } else if (tag == "package") {
                    synth = "<package android:name=\"$value\" />"
                }
                if (synth != null) {
                    val hit = MANIFEST_PATTERN_1?.containsMatchIn(synth) == true ||
                            MANIFEST_PATTERN_2?.containsMatchIn(synth) == true
                    if (hit) { el.removeSelf(); removed++; continue }
                }
            }
            removed += removeAnalyticsManifestNodes(el)
        }
        return removed
    }

    private fun manifestIntentMatches(intent: ResXmlElement): Boolean {
        return try {
            val it = intent.elements
            while (it.hasNext()) {
                val n = it.next()
                if (n !is ResXmlElement) continue
                val t = n.name ?: continue
                if (t != "package" && t != "action") continue
                val v = manifestNameAttr(n) ?: continue
                val synth = "<intent> <$t android:name=\"$v\"/> </intent>"
                if (MANIFEST_PATTERN_1?.containsMatchIn(synth) == true ||
                    MANIFEST_PATTERN_2?.containsMatchIn(synth) == true) return true
            }
            false
        } catch (t: Throwable) { false }
    }

    private fun manifestNameAttr(el: ResXmlElement): String? {
        return try {
            var v: String? = null
            val at = el.attributes
            while (at.hasNext()) {
                val a = at.next()
                if (a.name == "name") v = a.valueString
            }
            v
        } catch (t: Throwable) { null }
    }

    private fun firstManifestListedChildTag(el: ResXmlElement): String? {
        return try {
            val it = el.elements
            while (it.hasNext()) {
                val n = it.next()
                if (n is ResXmlElement) {
                    val t = n.name
                    if (t != null && t in MANIFEST_CHILD_TAGS) return t
                }
            }
            null
        } catch (t: Throwable) { null }
    }

    // ============================================================
    // ЛОКАЛИЗАЦИИ — потоковое чтение resources.arsc (без ARSCLib-таблицы)
    // ============================================================

    fun readLocalesFromApk(apkFile: File): List<String> {
        // ВАЖНО: НЕ используем ApkModule.tableBlock — на APK с огромным resources.arsc
        // (36+ МБ) его разбор требует сотни МБ и приводит к OutOfMemoryError на устройстве.
        // Читаем чанки resources.arsc потоково, конфиги разбираем лёгким ResConfig.
        // ARSCLib может бросить Error (CoderMalfunctionError) — ловим Throwable.
        return try {
            val localesSet = linkedSetOf<String>()
            ZipFile(apkFile).use { zf ->
                val entry = zf.getEntry("resources.arsc")
                if (entry != null) {
                    zf.getInputStream(entry).use { raw ->
                        BufferedInputStream(raw, 1 shl 16).use { input ->
                            walkArscChunks(input, entry.size, localesSet)
                        }
                    }
                }
            }
            localesSet.toList().sorted()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private const val ARSC_TABLE = 0x0002
    private const val ARSC_PACKAGE = 0x0200
    private const val ARSC_TYPE = 0x0201

    private fun walkArscChunks(input: InputStream, available: Long, out: MutableSet<String>) {
        var pos = 0L
        val header = ByteArray(8)
        while (pos + 8 <= available) {
            readFullyExact(input, header)
            val type = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
            val headerSize = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
            val size = (header[4].toLong() and 0xFF) or ((header[5].toLong() and 0xFF) shl 8) or
                    ((header[6].toLong() and 0xFF) shl 16) or ((header[7].toLong() and 0xFF) shl 24)
            if (size < 8 || headerSize < 8 || headerSize > size || size > available - pos) return
            when (type) {
                ARSC_TABLE, ARSC_PACKAGE -> {
                    skipFullyExact(input, (headerSize - 8).toLong())
                    walkArscChunks(input, size - headerSize, out)
                }
                ARSC_TYPE -> {
                    val rest = ByteArray(16)
                    readFullyExact(input, rest)
                    val configSize = (rest[12].toLong() and 0xFF) or ((rest[13].toLong() and 0xFF) shl 8) or
                            ((rest[14].toLong() and 0xFF) shl 16) or ((rest[15].toLong() and 0xFF) shl 24)
                    if (configSize >= 4 && configSize <= 4096 && 20 + configSize <= size) {
                        val cb = ByteArray(configSize.toInt())
                        System.arraycopy(rest, 12, cb, 0, 4)
                        readFullyExact(input, cb, 4, cb.size - 4)
                        try {
                            val cfg = ResConfig()
                            cfg.readBytes(BlockReader(cb))
                            val q = cfg.qualifiers
                            if (isLanguageQualifier(q)) out.add(q)
                        } catch (_: Throwable) { }
                        skipFullyExact(input, size - 20 - configSize)
                    } else {
                        skipFullyExact(input, size - 20)
                    }
                }
                else -> skipFullyExact(input, size - 8)
            }
            pos += size
        }
    }

    private fun readFullyExact(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var o = off
        var l = len
        while (l > 0) {
            val r = input.read(buf, o, l)
            if (r < 0) throw EOFException()
            o += r
            l -= r
        }
    }

    private fun skipFullyExact(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException()
            left -= r
        }
    }

    fun removeLocalesFromApk(inputApk: File, outputApk: File, localesToRemove: List<String>? = null): Int {
        // Потоковое удаление локалей: пересобираем resources.arsc без выбранных языков
        // (без загрузки ARSCLib-таблицы — на больших файлах она даёт OutOfMemoryError).
        val safe = localesToRemove
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        val parent = outputApk.parentFile ?: inputApk.parentFile ?: File(".")
        val tmpArsc = File(parent, outputApk.name + ".arsc.tmp")
        try {
            val removed = buildLocaleStrippedArsc(inputApk, tmpArsc, safe)
            if (removed <= 0) return 0
            val apkModule = ApkModule.loadApkFile(inputApk)
            try {
                apkModule.removeInputSource("resources.arsc")
                val src = ByteInputSource(tmpArsc.readBytes(), "resources.arsc")
                src.setMethod(java.util.zip.ZipEntry.STORED)
                apkModule.add(src)
                apkModule.writeApk(outputApk)
            } finally {
                apkModule.close()
            }
            android.util.Log.d("DexPatcher", "PATCH removed_locales=" + removed)
            return removed
        } catch (t: Throwable) {
            throw IllegalStateException("Не удалось удалить локализации: ${t.message}")
        } finally {
            if (tmpArsc.exists()) tmpArsc.delete()
        }
    }

    private fun buildLocaleStrippedArsc(inputApk: File, outArsc: File, safe: Set<String>?): Int {
        val pkgRemovals = ArrayList<Long>()
        var count = 0
        var totalRemoved = 0L
        var tableSize = 0L
        ZipFile(inputApk).use { zf ->
            val entry = zf.getEntry("resources.arsc") ?: return 0
            tableSize = entry.size
            zf.getInputStream(entry).use { raw ->
                BufferedInputStream(raw, 1 shl 16).use { inn ->
                    val res = measureArscRemovals(inn, entry.size, safe, pkgRemovals)
                    count = res.first
                    totalRemoved = res.second
                }
            }
        }
        if (count <= 0 || totalRemoved <= 0L) return 0
        ZipFile(inputApk).use { zf ->
            val entry = zf.getEntry("resources.arsc") ?: return 0
            zf.getInputStream(entry).use { raw ->
                BufferedInputStream(raw, 1 shl 16).use { inn ->
                    BufferedOutputStream(FileOutputStream(outArsc), 1 shl 16).use { out ->
                        copyArscWithoutLocales(inn, tableSize, safe, pkgRemovals, totalRemoved, out)
                    }
                }
            }
        }
        return count
    }

    private fun measureArscRemovals(input: InputStream, tableSize: Long, safe: Set<String>?, pkgRemovals: MutableList<Long>): Pair<Int, Long> {
        var count = 0
        var total = 0L
        val h = ByteArray(8)
        readFullyExact(input, h)
        val ths = readU16(h, 2)
        skipFullyExact(input, (ths - 8).toLong())
        var pos = ths.toLong()
        while (pos + 8 <= tableSize) {
            readFullyExact(input, h)
            val type = readU16(h, 0)
            val hs = readU16(h, 2)
            val size = readU32(h, 4)
            if (size < 8 || hs < 8 || hs > size || size > tableSize - pos) break
            if (type == ARSC_PACKAGE) {
                var pkgRemoved = 0L
                skipFullyExact(input, (hs - 8).toLong())
                var pPos = hs.toLong()
                while (pPos + 8 <= size) {
                    readFullyExact(input, h)
                    val pt = readU16(h, 0)
                    val phs = readU16(h, 2)
                    val psz = readU32(h, 4)
                    if (psz < 8 || phs < 8 || phs > psz || psz > size - pPos) break
                    if (pt == ARSC_TYPE) {
                        if (readTypeChunkDecision(input, psz, safe)) {
                            count++
                            pkgRemoved += psz
                        }
                    } else {
                        skipFullyExact(input, psz - 8)
                    }
                    pPos += psz
                }
                pkgRemovals.add(pkgRemoved)
                total += pkgRemoved
            } else {
                skipFullyExact(input, size - 8)
            }
            pos += size
        }
        return Pair(count, total)
    }

    /** Читает type-чанк целиком; true = язык подходит под удаление. */
    private fun readTypeChunkDecision(input: InputStream, size: Long, safe: Set<String>?): Boolean {
        val rb = ByteArray(16)
        readFullyExact(input, rb)
        val configSize = readU32(rb, 12)
        if (configSize < 4 || configSize > 4096 || 20 + configSize > size) {
            skipFullyExact(input, maxOf(0L, size - 24))
            return false
        }
        val cb = ByteArray(configSize.toInt())
        System.arraycopy(rb, 12, cb, 0, 4)
        readFullyExact(input, cb, 4, cb.size - 4)
        skipFullyExact(input, size - 20 - configSize)
        return try {
            val cfg = ResConfig()
            cfg.readBytes(BlockReader(cb))
            val q = cfg.qualifiers
            isLanguageQualifier(q) && (safe == null || q in safe)
        } catch (t: Throwable) {
            false
        }
    }

    private fun copyArscWithoutLocales(
        input: InputStream, tableSize: Long, safe: Set<String>?, pkgRemovals: List<Long>, totalRemoved: Long, out: OutputStream
    ) {
        val h = ByteArray(8)
        readFullyExact(input, h)
        val ths = readU16(h, 2)
        writeChunkHeaderWithSize(out, h, tableSize - totalRemoved)
        copyExact(input, out, (ths - 8).toLong())
        var pos = ths.toLong()
        var pi = 0
        while (pos + 8 <= tableSize) {
            readFullyExact(input, h)
            val type = readU16(h, 0)
            val hs = readU16(h, 2)
            val size = readU32(h, 4)
            if (size < 8 || hs < 8 || hs > size || size > tableSize - pos) return
            if (type == ARSC_PACKAGE) {
                val pkgRem = if (pi < pkgRemovals.size) pkgRemovals[pi] else 0L
                pi++
                writeChunkHeaderWithSize(out, h, size - pkgRem)
                copyExact(input, out, (hs - 8).toLong())
                var pPos = hs.toLong()
                while (pPos + 8 <= size) {
                    readFullyExact(input, h)
                    val pt = readU16(h, 0)
                    val phs = readU16(h, 2)
                    val psz = readU32(h, 4)
                    if (psz < 8 || phs < 8 || phs > psz || psz > size - pPos) break
                    if (pt == ARSC_TYPE) {
                        copyTypeChunk(input, out, h, psz, safe)
                    } else {
                        out.write(h)
                        copyExact(input, out, psz - 8)
                    }
                    pPos += psz
                }
            } else {
                out.write(h)
                copyExact(input, out, size - 8)
            }
            pos += size
        }
    }

    private fun copyTypeChunk(input: InputStream, out: OutputStream, h: ByteArray, size: Long, safe: Set<String>?) {
        val rb = ByteArray(16)
        readFullyExact(input, rb)
        val configSize = readU32(rb, 12)
        val valid = configSize >= 4 && configSize <= 4096 && 20 + configSize <= size
        if (!valid) {
            out.write(h)
            out.write(rb)
            copyExact(input, out, maxOf(0L, size - 24))
            return
        }
        val cb = ByteArray(configSize.toInt())
        System.arraycopy(rb, 12, cb, 0, 4)
        readFullyExact(input, cb, 4, cb.size - 4)
        val remove = try {
            val cfg = ResConfig()
            cfg.readBytes(BlockReader(cb))
            val q = cfg.qualifiers
            isLanguageQualifier(q) && (safe == null || q in safe)
        } catch (t: Throwable) {
            false
        }
        if (remove) {
            skipFullyExact(input, size - 20 - configSize)
        } else {
            out.write(h)
            out.write(rb)
            out.write(cb, 4, cb.size - 4)
            copyExact(input, out, size - 20 - configSize)
        }
    }

    private fun writeChunkHeaderWithSize(out: OutputStream, header: ByteArray, newSize: Long) {
        val x = header.copyOf()
        x[4] = (newSize and 0xFF).toByte()
        x[5] = ((newSize ushr 8) and 0xFF).toByte()
        x[6] = ((newSize ushr 16) and 0xFF).toByte()
        x[7] = ((newSize ushr 24) and 0xFF).toByte()
        out.write(x)
    }

    private fun copyExact(input: InputStream, out: OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException()
            out.write(buf, 0, r)
            left -= r
        }
    }
    private const val ARSC_STRING_POOL = 0x0001

    private class RawStringPool(
        val data: ByteArray,
        val headerSize: Int,
        val stringCount: Int,
        val stringsStart: Int,
        val utf8: Boolean
    ) {
        fun string(index: Int): String? {
            if (index < 0 || index >= stringCount || index >= 1_000_000) return null
            if (headerSize + 4 * index + 4 > data.size) return null
            return try {
                val off = readU32(data, headerSize + 4 * index).toInt()
                val s = stringsStart + off
                if (s < 0 || s + 2 > data.size) return null
                if (utf8) stringUtf8(s) else stringUtf16(s)
            } catch (t: Throwable) {
                null
            }
        }

        private fun stringUtf8(s: Int): String {
            val v1 = readLen8(s)
            val n1 = if ((data[s].toInt() and 0x80) != 0) 2 else 1
            if (s + n1 + v1 < data.size && data[s + n1 + v1].toInt() == 0) {
                return String(data, s + n1, v1, Charsets.UTF_8)
            }
            val v2 = readLen8(s + n1)
            val n2 = if ((data[s + n1].toInt() and 0x80) != 0) 2 else 1
            val start = s + n1 + n2
            var cnt = v2
            if (start + cnt >= data.size || data[start + cnt].toInt() != 0) {
                var z = start
                val lim = minOf(data.size, start + 512)
                while (z < lim && data[z].toInt() != 0) z++
                cnt = z - start
            }
            if (cnt < 0 || start + cnt > data.size) return ""
            return String(data, start, cnt, Charsets.UTF_8)
        }

        private fun stringUtf16(s: Int): String {
            val v0 = readU16(data, s)
            var len: Int
            var pos: Int
            if ((v0 and 0x8000) != 0) {
                len = ((v0 and 0x7FFF) shl 16) or readU16(data, s + 2)
                pos = s + 4
            } else {
                len = v0
                pos = s + 2
            }
            if (len < 0 || pos + len * 2 > data.size) return ""
            return String(data, pos, len * 2, Charsets.UTF_16LE)
        }

        private fun readLen8(pos: Int): Int {
            val b0 = data[pos].toInt() and 0xFF
            return if ((b0 and 0x80) != 0) ((b0 and 0x7F) shl 8) or (data[pos + 1].toInt() and 0xFF) else b0
        }
    }
    private fun readU16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readU32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    fun readDefaultStringValues(apkFile: File, needles: List<String>, maxCount: Int): List<String> {
        val found = ArrayList<String>()
        val seen = HashSet<String>()
        try {
            ZipFile(apkFile).use { zf ->
                val entry = zf.getEntry("resources.arsc") ?: return emptyList()
                zf.getInputStream(entry).use { raw ->
                    BufferedInputStream(raw, 1 shl 16).use { input ->
                        val gpHolder = arrayOfNulls<RawStringPool>(1)
                        walkArscStrings(input, 0L, entry.size, -1L, needles, found, seen, gpHolder, maxCount)
                    }
                }
            }
        } catch (t: Throwable) {
            // возвращаем частичный результат
        }
        return found
    }

    private fun walkArscStrings(
        input: InputStream, startAbs: Long, available: Long, pkgStart: Long,
        needles: List<String>, found: MutableList<String>, seen: MutableSet<String>,
        gpHolder: Array<RawStringPool?>, maxCount: Int
    ) {
        var pos = 0L
        val header = ByteArray(8)
        while (pos + 8 <= available && found.size < maxCount) {
            val chunkStart = startAbs + pos
            readFullyExact(input, header)
            val type = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
            val headerSize = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
            val size = (header[4].toLong() and 0xFF) or ((header[5].toLong() and 0xFF) shl 8) or
                    ((header[6].toLong() and 0xFF) shl 16) or ((header[7].toLong() and 0xFF) shl 24)
            if (size < 8 || headerSize < 8 || headerSize > size || size > available - pos) return
            when (type) {
                ARSC_TABLE -> {
                    skipFullyExact(input, (headerSize - 8).toLong())
                    walkArscStrings(input, chunkStart + headerSize, size - headerSize, -1L, needles, found, seen, gpHolder, maxCount)
                }
                ARSC_PACKAGE -> {
                    val toRead = minOf(headerSize - 8, 280)
                    val ph = ByteArray(toRead)
                    readFullyExact(input, ph)
                    if (headerSize - 8 > toRead) skipFullyExact(input, (headerSize - 8 - toRead).toLong())
                    if (toRead >= 264) {
                        val typeStringsOff = readU32(ph, 260)
                        val typeIdOffset = if (headerSize >= 288) readU32(ph, 276) else 0L
                        walkArscPackageStrings(
                            input, chunkStart + headerSize, size - headerSize, chunkStart,
                            typeStringsOff, typeIdOffset, needles, found, seen, gpHolder, maxCount
                        )
                    } else {
                        skipFullyExact(input, (size - 8 - toRead).toLong())
                    }
                }
                ARSC_STRING_POOL -> {
                    if (pkgStart < 0 && gpHolder[0] == null) {
                        gpHolder[0] = readPoolFully(input, size, header)
                    } else {
                        skipFullyExact(input, size - 8)
                    }
                }
                else -> skipFullyExact(input, size - 8)
            }
            pos += size
        }
    }

    private fun readPoolFully(input: InputStream, chunkSize: Long, h8: ByteArray): RawStringPool? {
        val restLen = chunkSize - 8
        if (restLen < 0 || restLen > 64L * 1024 * 1024) {
            if (restLen > 0) skipFullyExact(input, restLen)
            return null
        }
        val data = ByteArray(chunkSize.toInt())
        System.arraycopy(h8, 0, data, 0, 8)
        readFullyExact(input, data, 8, data.size - 8)
        val headerSize = readU16(data, 2)
        val stringCount = readU32(data, 8).toInt()
        val flags = readU32(data, 16).toInt()
        val stringsStart = readU32(data, 20).toInt()
        val utf8 = (flags and 0x100) != 0
        return RawStringPool(data, headerSize, stringCount, stringsStart, utf8)
    }

    private fun walkArscPackageStrings(
        input: InputStream, startAbs: Long, available: Long, pkgStart: Long,
        typeStringsOff: Long, typeIdOffset: Long,
        needles: List<String>, found: MutableList<String>, seen: MutableSet<String>,
        gpHolder: Array<RawStringPool?>, maxCount: Int
    ) {
        var pos = 0L
        var stringTypeId = 0L
        val header = ByteArray(8)
        while (pos + 8 <= available) {
            val chunkStart = startAbs + pos
            readFullyExact(input, header)
            val type = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
            val headerSize = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
            val size = (header[4].toLong() and 0xFF) or ((header[5].toLong() and 0xFF) shl 8) or
                    ((header[6].toLong() and 0xFF) shl 16) or ((header[7].toLong() and 0xFF) shl 24)
            if (size < 8 || headerSize < 8 || headerSize > size || size > available - pos) return
            when {
                type == ARSC_STRING_POOL && chunkStart == pkgStart + typeStringsOff -> {
                    val tp = readPoolFully(input, size, header)
                    if (tp != null) {
                        var i = 0
                        while (i < tp.stringCount) {
                            if (tp.string(i) == "string") {
                                stringTypeId = i + 1 + typeIdOffset
                                break
                            }
                            i++
                        }
                    }
                }
                type == ARSC_TYPE -> {
                    val rb = ByteArray(16)
                    readFullyExact(input, rb)
                    val id = (rb[0].toInt() and 0xFF).toLong()
                    val flags0 = rb[1].toInt() and 0xFF
                    val configSize = readU32(rb, 12)
                    val validConfig = configSize >= 4 && configSize <= 4096 && 20 + configSize <= size
                    if (stringTypeId > 0 && id == stringTypeId && (flags0 and 0x03) == 0 && validConfig) {
                        val cb = ByteArray(configSize.toInt())
                        System.arraycopy(rb, 12, cb, 0, 4)
                        readFullyExact(input, cb, 4, cb.size - 4)
                        var isDefault = true
                        var k = 4
                        while (k < cb.size) {
                            if (cb[k].toInt() != 0) { isDefault = false; break }
                            k++
                        }
                        val entryCount = readU32(rb, 4)
                        val entriesStart = readU32(rb, 8)
                        if (isDefault && entryCount <= 2_000_000) {
                            val offBytes = entryCount * 4
                            val rem = size - (20 + configSize + offBytes)
                            if (rem >= 0 && rem <= 32L * 1024 * 1024) {
                                val offs = ByteArray(offBytes.toInt())
                                readFullyExact(input, offs)
                                val body = ByteArray(rem.toInt())
                                readFullyExact(input, body)
                                val bodyAbsStart = chunkStart + 20 + configSize + offBytes
                                val gp = gpHolder[0]
                                var i = 0L
                                while (i < entryCount && found.size < maxCount) {
                                    val o = readU32(offs, (4 * i).toInt())
                                    i++
                                    if (o == 0xFFFFFFFFL) continue
                                    val bi = (chunkStart + entriesStart + o - bodyAbsStart).toInt()
                                    if (bi < 0 || bi + 16 > body.size) continue
                                    val eflags = readU16(body, bi + 2)
                                    if ((eflags and 0x0001) != 0) continue
                                    val vtype = body[bi + 11].toInt() and 0xFF
                                    if (vtype != 0x03) continue
                                    val sidx = readU32(body, bi + 12).toInt()
                                    val v = gp?.string(sidx) ?: continue
                                    if (v.length in 2..60) {
                                        val low = v.lowercase()
                                        for (n in needles) {
                                            if (low.contains(n)) {
                                                if (seen.add(v)) found.add(v)
                                                break
                                            }
                                        }
                                    }
                                }
                            } else {
                                skipFullyExact(input, size - 20 - configSize)
                            }
                        } else {
                            skipFullyExact(input, size - 20 - configSize)
                        }
                    } else {
                        skipFullyExact(input, maxOf(0L, size - 24))
                    }
                }
                else -> skipFullyExact(input, size - 8)
            }
            pos += size
        }
    }
    private fun isLanguageQualifier(qualifiers: String): Boolean {
        if (qualifiers.isEmpty()) return false
        val clean = qualifiers.trimStart('-')

        if (clean.startsWith("b+")) {
            val parts = clean.split('+')
            if (parts.size >= 2) {
                val lang = parts[1]
                return lang.matches(Regex("^[a-z]{2,3}$")) && lang in LANGUAGE_CODES
            }
            return false
        }

        val firstPart = clean.substringBefore('-').lowercase()
        if (!firstPart.matches(Regex("^[a-z]{2,3}$"))) return false
        return firstPart in LANGUAGE_CODES
    }

    private val LANGUAGE_CODES: Set<String> = setOf(
        "aa","ab","ae","af","ak","am","an","ar","as","av","ay","az",
        "ba","be","bg","bh","bi","bm","bn","bo","br","bs",
        "ca","ce","ch","co","cr","cs","cu","cv","cy",
        "da","de","dv","dz",
        "ee","el","en","eo","es","et","eu",
        "fa","ff","fi","fj","fo","fr","fy",
        "ga","gd","gl","gn","gu","gv",
        "ha","he","hi","ho","hr","ht","hu","hy","hz",
        "ia","id","ie","ig","ii","ik","io","is","it","iu",
        "ja","jv",
        "ka","kg","ki","kj","kk","kl","km","kn","ko","kr","ks","ku","kv","kw","ky",
        "la","lb","lg","li","ln","lo","lt","lu","lv",
        "mg","mh","mi","mk","ml","mn","mr","ms","mt","my",
        "na","nb","nd","ne","ng","nl","nn","no","nr","nv","ny",
        "oc","oj","om","or","os",
        "pa","pi","pl","ps","pt",
        "qu",
        "rm","rn","ro","ru","rw",
        "sa","sc","sd","se","sg","si","sk","sl","sm","sn","so","sq","sr","ss","st","su","sv","sw",
        "ta","te","tg","th","ti","tk","tl","tn","to","tr","ts","tt","tw","ty",
        "ug","uk","ur","uz",
        "ve","vi","vo",
        "wa","wo",
        "xh",
        "yi","yo",
        "za","zh","zu",
        "in","iw","ji",
        "bho","ceb","ckb","doi","fil","haw","hmn","ilo","jw","kri","lus","mai","nso","phi"
    )

    // ============================================================
    // ПАТЧИНГ ПОЛЕЙ И МЕТОДОВ
    // ============================================================
    private fun patchStaticField(field: Field, onPatch: () -> Unit): Field {
        val initialValue = field.initialValue
        if (initialValue is StringEncodedValue && isAnalyticsFieldString(initialValue.value)) {
            onPatch(); return ImmutableField(field.definingClass, field.name, field.type, field.accessFlags, ImmutableStringEncodedValue(ANALYTICS_REPLACEMENT), field.annotations, field.hiddenApiRestrictions)
        }
        return field
    }

    private fun patchMethod(
        method: org.jf.dexlib2.iface.Method,
        patchGooglePlay: Boolean, patchRemoveAds: Boolean, patchRemoveAnalytics: Boolean, patchRemoveGPServices: Boolean,
        patchRemoveVpn: Boolean, patchRemoveInstallerCheck: Boolean, patchRemoveDebug: Boolean, patchRemoveSignature: Boolean,
        patchRemoveUpdate: Boolean,
        onGooglePlayPatch: () -> Unit, onAdsPatch: () -> Unit, onAdsBooleanPatch: () -> Unit,
        onUrlPatch: () -> Unit, onCaAppPubPatch: () -> Unit, onAnalyticsUrlPatch: () -> Unit,
        onGpServicesPatch: () -> Unit, onVpnPatch: () -> Unit, onInstallerCheckPatch: () -> Unit, onDebugItemRemove: () -> Unit,
        onSignaturePatch: () -> Unit, onUpdateCheckPatch: () -> Unit
    ): ImmutableMethod {

        // ✅ НОВОЕ: подход APK ToolM — патчим ЦЕЛЫЙ метод, содержащий проверку подписи
        if (patchRemoveSignature && isAppClass(method.definingClass)) {
            val impl = method.implementation
            if (impl != null && containsSignatureCheck(impl)) {
                onSignaturePatch()
                android.util.Log.d(TAG, "🔓 Патч метода проверки подписи: ${method.definingClass}->${method.name}${method.parameterTypes} ${method.returnType}")
                val newImpl = createSignatureBypassImplementation(method)
                return ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
                    method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl)
            }
        }

        // ✅ Убрать проверку обновления (Google Play In-App Updates):
        // 1) сам метод показа диалога -> false («не запущено»);
        // 2) updateAvailability -> UPDATE_NOT_AVAILABLE (случай, когда приложение
        //    показывает СВОЙ диалог на основании наличия обновления).
        if (patchRemoveUpdate && method.implementation != null
            && method.definingClass.startsWith("Lcom/google/android/play/core/appupdate")) {
            if (method.name == "startUpdateFlowForResult" && method.returnType == "Z") {
                onUpdateCheckPatch()
                return buildReturnFalseMethod(method)
            }
            if (method.name == "updateAvailability" && method.returnType == "I") {
                onUpdateCheckPatch()
                return buildReturnIntMethod(method, 1)   // UPDATE_NOT_AVAILABLE
            }
        }

        // ✅ RuStore SDK (ru.rustore.sdk.appupdate): «обновление недоступно» + не форсить
        if (patchRemoveUpdate && method.implementation != null
            && method.definingClass.startsWith("Lru/rustore/sdk/appupdate/")) {
            if (method.name == "getUpdateAvailability" && method.returnType == "I") {
                onUpdateCheckPatch()
                return buildReturnIntMethod(method, 1)   // UPDATE_NOT_AVAILABLE
            }
            if (method.name == "getForceUpdateAvailable" && method.returnType == "Z") {
                onUpdateCheckPatch()
                return buildReturnFalseMethod(method)
            }
        }

        // Остальной патчинг (реклама, аналитика, VPN и т.д.)
        val patchedImpl = method.implementation?.let { impl ->
            val instructionsList = impl.instructions.toList()
            val patchedInstructions = mutableListOf<org.jf.dexlib2.iface.instruction.Instruction>()
            var skipNext = false

            for (index in instructionsList.indices) {
                if (skipNext) { skipNext = false; continue }

                val currentInstruction = instructionsList[index]
                var wasHandled = false

                // Google Play Licensing
                if (patchGooglePlay && currentInstruction.opcode.name == "const/4" && currentInstruction is Instruction11n && currentInstruction.narrowLiteral == 1) {
                    if (index > 0) {
                        val prev = instructionsList[index - 1]
                        if (prev is Instruction21c && prev.opcode.name == "const-string") {
                            val ref = prev.reference
                            if (ref is StringReference && ref.string.contains("com.android.vending.licensing")) {
                                onGooglePlayPatch()
                                patchedInstructions.add(createConstZeroInstruction(currentInstruction.registerA))
                                wasHandled = true
                            }
                        }
                    }
                }

                                // Ads Void (remove call)
                if (!wasHandled && patchRemoveAds && currentInstruction.opcode.name.startsWith("invoke-")) {
                    val methodRef = (currentInstruction as? ReferenceInstruction)?.reference
                    if (methodRef is MethodReference && methodRef.returnType == "V" && AD_CLASS_PREFILTER.containsMatchIn(methodRef.definingClass)) {
                        val sig = buildSmaliInvokeString(currentInstruction.opcode, methodRef)
                        if (AD_PATTERN != null && AD_PATTERN!!.containsMatchIn(sig)) {
                            var hasMoveResult = false
                            if (index + 1 < instructionsList.size) {
                                val next = instructionsList[index + 1]
                                if (next.opcode.name.startsWith("move-result")) hasMoveResult = true
                            }
                            if (!hasMoveResult) {
                                onAdsPatch()
                                patchedInstructions.add(ImmutableInstruction10x(Opcode.NOP))
                                patchedInstructions.add(ImmutableInstruction10x(Opcode.NOP))
                                patchedInstructions.add(ImmutableInstruction10x(Opcode.NOP))
                                wasHandled = true
                            }
                        }
                    }
                }

                // Ad URLs & ca-app-pub
                if (!wasHandled && patchRemoveAds && isConstString(currentInstruction)) {
                    val ref = (currentInstruction as ReferenceInstruction).reference
                    if (ref is StringReference) {
                        val s = ref.string
                        if (CA_APP_PUB_PATTERN != null && CA_APP_PUB_PATTERN!!.containsMatchIn(s)) {
                            val newS = CA_APP_PUB_PATTERN!!.replace(s, CA_APP_PUB_REPLACEMENT)
                            onCaAppPubPatch()
                            patchedInstructions.add(createConstStringInstruction(currentInstruction, newS))
                            wasHandled = true
                        } else if (isAdUrlString(s)) {
                            onUrlPatch()
                            patchedInstructions.add(createConstStringInstruction(currentInstruction, BLOCKED_HOST))
                            wasHandled = true
                        }
                    }
                }

                // Analytics URLs
                if (!wasHandled && patchRemoveAnalytics && isConstString(currentInstruction)) {
                    val ref = (currentInstruction as ReferenceInstruction).reference
                    if (ref is StringReference && isAnalyticsString(ref.string)) {
                        onAnalyticsUrlPatch()
                        patchedInstructions.add(createConstStringInstruction(currentInstruction, ANALYTICS_REPLACEMENT))
                        wasHandled = true
                    }
                }

                // Analytics: split URLs (два подряд const-string "hts/"/"tp:")
                if (!wasHandled && patchRemoveAnalytics && isConstString(currentInstruction)) {
                    val ref = (currentInstruction as ReferenceInstruction).reference
                    if (ref is StringReference && isSplitUrlFragment(ref.string) && index + 1 < instructionsList.size) {
                        val next = instructionsList[index + 1]
                        if (isConstString(next)) {
                            val nextRef = (next as ReferenceInstruction).reference
                            if (nextRef is StringReference && isSplitUrlFragment(nextRef.string) && nextRef.string != ref.string) {
                                onAnalyticsUrlPatch()
                                patchedInstructions.add(createConstStringInstruction(currentInstruction, ANALYTICS_REPLACEMENT))
                                patchedInstructions.add(createConstStringInstruction(next, ANALYTICS_REPLACEMENT))
                                skipNext = true
                                wasHandled = true
                            }
                        }
                    }
                }

                // Google Play Services (return 0)
                if (!wasHandled && patchRemoveGPServices && currentInstruction.opcode.name.startsWith("invoke-")) {
                    val ref = (currentInstruction as? ReferenceInstruction)?.reference
                    if (ref is MethodReference && ref.returnType == "I") {
                        val isGpService = GP_SERVICE_CLASSES.any { ref.definingClass.startsWith(it) }
                        if (isGpService && index + 1 < instructionsList.size) {
                            val next = instructionsList[index + 1]
                            if (next.opcode.name == "move-result" && next is OneRegisterInstruction) {
                                onGpServicesPatch()
                                patchedInstructions.add(currentInstruction)
                                patchedInstructions.add(createConstZeroInstruction(next.registerA))
                                skipNext = true
                                wasHandled = true
                            }
                        }
                    }
                }

                // VPN Detection (return false)
                if (!wasHandled && patchRemoveVpn && currentInstruction.opcode.name.startsWith("invoke-")) {
                    val methodRef = (currentInstruction as? ReferenceInstruction)?.reference
                    if (methodRef is MethodReference && methodRef.definingClass == "Landroid/net/NetworkCapabilities;" && methodRef.name == "hasTransport" && methodRef.returnType == "Z") {
                        if (index + 1 < instructionsList.size) {
                            val next = instructionsList[index + 1]
                            if (next.opcode.name == "move-result" && next is OneRegisterInstruction) {
                                onVpnPatch()
                                patchedInstructions.add(currentInstruction)
                                patchedInstructions.add(createConstZeroInstruction(next.registerA))
                                skipNext = true
                                wasHandled = true
                            }
                        }
                    }
                }

                // Installer check (force com.android.vending)
                if (!wasHandled && patchRemoveInstallerCheck && currentInstruction.opcode.name.startsWith("invoke-")) {
                    val installerRef = (currentInstruction as? ReferenceInstruction)?.reference
                    val isInstallerCall = installerRef is MethodReference &&
                        installerRef.returnType == "Ljava/lang/String;" &&
                        (installerRef.definingClass == "Landroid/content/pm/PackageManager;" || installerRef.definingClass == "Landroid/content/pm/InstallSourceInfo;") &&
                        (installerRef.name == "getInstallerPackageName" || installerRef.name == "getInstallingPackageName")
                    if (isInstallerCall && index + 1 < instructionsList.size) {
                        val next = instructionsList[index + 1]
                        if (next.opcode.name == "move-result-object" && next is OneRegisterInstruction) {
                            onInstallerCheckPatch()
                            patchedInstructions.add(createConstStringForRegister(next.registerA, "com.android.vending"))
                            patchedInstructions.add(ImmutableInstruction10x(Opcode.NOP))
                            patchedInstructions.add(ImmutableInstruction10x(Opcode.NOP))
                            skipNext = true
                            wasHandled = true
                        }
                    }
                }

                if (!wasHandled) patchedInstructions.add(currentInstruction)
            }

            val newDebugItems = if (patchRemoveDebug && impl.debugItems.any()) { onDebugItemRemove(); emptyList() } else impl.debugItems
            ImmutableMethodImplementation(impl.registerCount, patchedInstructions, impl.tryBlocks, newDebugItems)
        }

        return ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions, patchedImpl)
    }

    private fun createConstStringForRegister(register: Int, newString: String): Instruction {
        return ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(newString))
    }
    private fun isConstString(instruction: org.jf.dexlib2.iface.instruction.Instruction): Boolean = instruction.opcode.name == "const-string" || instruction.opcode.name == "const-string/jumbo"

    private fun createConstStringInstruction(original: org.jf.dexlib2.iface.instruction.Instruction, newString: String): org.jf.dexlib2.iface.instruction.Instruction {
        val newRef = ImmutableStringReference(newString)
        return when (original) {
            is Instruction21c -> ImmutableInstruction21c(Opcode.CONST_STRING, original.registerA, newRef)
            is Instruction31c -> ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, original.registerA, newRef)
            else -> original
        }
    }

    private fun buildSmaliInvokeString(opcode: Opcode, methodRef: MethodReference): String = "${opcode.name} {v0}, ${methodRef.definingClass}->${methodRef.name}(${methodRef.parameterTypes.joinToString("")})${methodRef.returnType}"

    // ============================================================
    // ✅ УДАЛЕНИЕ ПРОВЕРКИ ПОДПИСИ (подход APK ToolM)
    // ============================================================

    private fun isAppClass(className: String): Boolean {
        return !className.startsWith("Landroid/") &&
                !className.startsWith("Landroidx/") &&
                !className.startsWith("Ljava/") &&
                !className.startsWith("Ljavax/") &&
                !className.startsWith("Lkotlin/") &&
                !className.startsWith("Lkotlinx/") &&
                !className.startsWith("Lorg/") &&
                !className.startsWith("Ldalvik/") &&
                !className.startsWith("Llibcore/") &&
                !className.startsWith("Lsun/") &&
                !className.startsWith("Lcom/google/") &&
                !className.startsWith("Lcom/android/")
    }

    private fun containsSignatureCheck(impl: org.jf.dexlib2.iface.MethodImplementation): Boolean {
        var hasSignatureApi = false
        var hasGetPackageInfo = false
        var hasHashString = false
        var hasCheckSignature = false
        var hasPackageInfoSignatures = false

        for (instruction in impl.instructions) {
            if (instruction is ReferenceInstruction) {
                val ref = instruction.reference

                if (ref is MethodReference) {
                    val cls = ref.definingClass
                    val name = ref.name

                    if (cls == "Landroid/content/pm/Signature;" &&
                        (name == "equals" || name == "toCharsString" || name == "toByteArray" || name == "hashCode")) {
                        hasSignatureApi = true
                    }

                    if (cls == "Landroid/content/pm/PackageManager;" && name == "getPackageInfo") {
                        hasGetPackageInfo = true
                    }

                    if (cls == "Landroid/content/pm/PackageManager;" &&
                        (name == "checkSignature" || name == "checkSignatures")) {
                        hasCheckSignature = true
                    }
                }

                if (ref is FieldReference) {
                    if (ref.definingClass == "Landroid/content/pm/PackageInfo;" && ref.name == "signatures") {
                        hasPackageInfoSignatures = true
                    }
                }

                if (ref is StringReference) {
                    val s = ref.string.trim()
                    if ((s.length == 32 || s.length == 40 || s.length == 64) &&
                        s.matches(Regex("^[0-9a-fA-F]+$"))) {
                        hasHashString = true
                    }
                    if (s.matches(Regex("^([0-9a-fA-F]{2}:)+[0-9a-fA-F]{2}$"))) {
                        hasHashString = true
                    }
                }
            }
        }

        return hasCheckSignature ||
                (hasGetPackageInfo && hasSignatureApi) ||
                (hasGetPackageInfo && hasPackageInfoSignatures) ||
                (hasSignatureApi && hasHashString) ||
                (hasGetPackageInfo && hasHashString)
    }

    /** Метод-заглушка: возвращает заданное число. */
    private fun buildReturnIntMethod(method: org.jf.dexlib2.iface.Method, value: Int): ImmutableMethod {
        var words = if (method.accessFlags and 0x8 != 0) 0 else 1
        for (pt in method.parameterTypes) {
            val s = pt.toString()
            words += if (s == "J" || s == "D") 2 else 1
        }
        val impl = ImmutableMethodImplementation(
            words + 1,
            listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, value),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            ),
            emptyList(),
            emptyList()
        )
        return ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, impl)
    }

    /** Метод-заглушка: `const/4 v0, 0; return v0` — для отключения показа диалога обновления. */
    private fun buildReturnFalseMethod(method: org.jf.dexlib2.iface.Method): ImmutableMethod {
        var words = if (method.accessFlags and 0x8 != 0) 0 else 1
        for (pt in method.parameterTypes) {
            val s = pt.toString()
            words += if (s == "J" || s == "D") 2 else 1
        }
        val impl = ImmutableMethodImplementation(
            words + 1,
            listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            ),
            emptyList(),
            emptyList()
        )
        return ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, impl)
    }

    private fun createSignatureBypassImplementation(method: org.jf.dexlib2.iface.Method): ImmutableMethodImplementation {
        val returnType = method.returnType
        val instructions = mutableListOf<org.jf.dexlib2.iface.instruction.Instruction>()

        when {
            returnType == "Z" -> {
                instructions.add(ImmutableInstruction11n(Opcode.CONST_4, 0, 1))
                instructions.add(ImmutableInstruction11x(Opcode.RETURN, 0))
            }
            returnType == "I" || returnType == "S" || returnType == "B" || returnType == "C" -> {
                instructions.add(ImmutableInstruction11n(Opcode.CONST_4, 0, 0))
                instructions.add(ImmutableInstruction11x(Opcode.RETURN, 0))
            }
            returnType == "J" -> {
                instructions.add(ImmutableInstruction11n(Opcode.CONST_4, 0, 0))
                instructions.add(ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))
            }
            returnType == "V" -> {
                instructions.add(ImmutableInstruction10x(Opcode.RETURN_VOID))
            }
            returnType == "F" || returnType == "D" -> {
                instructions.add(ImmutableInstruction11n(Opcode.CONST_4, 0, 0))
                instructions.add(ImmutableInstruction11x(if (returnType == "F") Opcode.RETURN else Opcode.RETURN_WIDE, 0))
            }
            else -> {
                instructions.add(ImmutableInstruction11n(Opcode.CONST_4, 0, 0))
                instructions.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            }
        }

        return ImmutableMethodImplementation(1, instructions, emptyList(), emptyList())
    }
}
