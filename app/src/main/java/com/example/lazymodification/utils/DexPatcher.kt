package com.example.lazymodification.utils

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.xml.XMLFactory
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
    val signaturePatched: Int = 0  // ✅ НОВОЕ
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
        patchRemoveSignature: Boolean = false  // ✅ НОВОЕ
    ): PatchResult {
        val opcodes = Opcodes.forApi(28)
        val dex = DexFileFactory.loadDexFile(inputDex, opcodes)

        var googlePlayCount = 0; var adsCount = 0; var adsBooleanCount = 0; var urlsCount = 0
        var caAppPubCount = 0; var analyticsUrlCount = 0; var analyticsFieldCount = 0
        var gpServicesCount = 0; var vpnCount = 0; var installerCheckCount = 0; var debugItemsCount = 0
        var signatureCount = 0  // ✅ НОВОЕ

        val startTime = System.currentTimeMillis()
        android.util.Log.d(TAG, "🔍 DEX: ${inputDex.name}, GP=$patchGooglePlay, Ads=$patchRemoveAds, Analytics=$patchRemoveAnalytics, GP_Svc=$patchRemoveGPServices, VPN=$patchRemoveVpn, Debug=$patchRemoveDebug, Sign=$patchRemoveSignature")

        try {
            val patchedClasses = dex.classes.map { classDef ->
                val patchedStaticFields = if (patchRemoveAnalytics) {
                    classDef.staticFields.map { field -> patchStaticField(field) { analyticsFieldCount++ } }
                } else classDef.staticFields

                val directMethods = classDef.directMethods.map { method ->
                    patchMethod(method, patchGooglePlay, patchRemoveAds, patchRemoveAnalytics, patchRemoveGPServices, patchRemoveVpn, patchRemoveInstallerCheck, patchRemoveDebug, patchRemoveSignature,
                        { googlePlayCount++ }, { adsCount++ }, { adsBooleanCount++ }, { urlsCount++ }, { caAppPubCount++ },
                        { analyticsUrlCount++ }, { gpServicesCount++ }, { vpnCount++ }, { installerCheckCount++ }, { debugItemsCount++ }, { signatureCount++ })
                }

                val virtualMethods = classDef.virtualMethods.map { method ->
                    patchMethod(method, patchGooglePlay, patchRemoveAds, patchRemoveAnalytics, patchRemoveGPServices, patchRemoveVpn, patchRemoveInstallerCheck, patchRemoveDebug, patchRemoveSignature,
                        { googlePlayCount++ }, { adsCount++ }, { adsBooleanCount++ }, { urlsCount++ }, { caAppPubCount++ },
                        { analyticsUrlCount++ }, { gpServicesCount++ }, { vpnCount++ }, { installerCheckCount++ }, { debugItemsCount++ }, { signatureCount++ })
                }

                ImmutableClassDef(classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile, classDef.annotations,
                    patchedStaticFields, classDef.instanceFields, directMethods, virtualMethods)
            }

            DexFileFactory.writeDexFile(outputDex.absolutePath, ImmutableDexFile(opcodes, patchedClasses))
            android.util.Log.d(TAG, "📊 GP=$googlePlayCount, Ads(V)=$adsCount, Ads(Z)=$adsBooleanCount, URL=$urlsCount, ca-app-pub=$caAppPubCount, Analytics(URL)=$analyticsUrlCount, Analytics(Field)=$analyticsFieldCount, GP_Svc=$gpServicesCount, VPN=$vpnCount, Sign=$signatureCount, Debug=$debugItemsCount, Time=${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) { android.util.Log.e(TAG, "❌ Ошибка: ${e.message}", e); throw e }

        return PatchResult(googlePlayCount, adsCount + adsBooleanCount, adsBooleanCount, urlsCount, caAppPubCount,
            analyticsUrlCount, analyticsFieldCount, gpServicesCount, vpnCount, installerCheckCount, debugItemsCount, 0, signatureCount)
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
            val packageBlock = apkModule.tableBlock.pickOne()
            if (packageBlock != null) manifest.setPackageBlock(packageBlock)
            var xml = manifest.serializeToXml()

            MANIFEST_PATTERN_1?.let { xml = it.replace(xml) { count++; MANIFEST_COMMENT } }
            MANIFEST_PATTERN_2?.let { xml = it.replace(xml) { count++; MANIFEST_COMMENT } }

            try { manifest.javaClass.getMethod("clear").invoke(manifest) } catch (_: Exception) { try { manifest.javaClass.getMethod("reset").invoke(manifest) } catch (_: Exception) {} }
            val parser = XMLFactory.newPullParser(StringReader(xml)); parser.setInput(StringReader(xml)); manifest.parse(parser)
            apkModule.writeApk(outputApk)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Ошибка патчинга манифеста: ${e.message}", e)
            if (!outputApk.exists() || outputApk.length() == 0L) inputApk.copyTo(outputApk, overwrite = true)
        }
        android.util.Log.d("DexPatcher", "PATCH replaced=" + count)
        return count
    }

    // ============================================================
    // ЛОКАЛИЗАЦИИ
    // ============================================================
    fun readLocalesFromApk(apkFile: File): List<String> {
        val apkModule = ApkModule.loadApkFile(apkFile)
        val tableBlock: TableBlock = apkModule.tableBlock
            ?: throw IllegalStateException("В APK нет resources.arsc")

        val localesSet = linkedSetOf<String>()

        for (packageBlock in tableBlock.listPackages()) {
            for (specTypePair in packageBlock.listSpecTypePairs()) {
                for (typeBlock in specTypePair) {
                    val qualifiers = typeBlock.resConfig.qualifiers
                    if (isLanguageQualifier(qualifiers)) {
                        localesSet.add(qualifiers)
                    }
                }
            }
        }

        apkModule.close()
        return localesSet.toList().sorted()
    }

    fun removeLocalesFromApk(inputApk: File, outputApk: File, localesToRemove: List<String>? = null): Int {
        val apkModule = ApkModule.loadApkFile(inputApk)
        val tableBlock: TableBlock = apkModule.tableBlock
            ?: throw IllegalStateException("В APK нет resources.arsc")

        val safeLocalesToRemove = localesToRemove
            ?.filter { it.isNotEmpty() }
            ?.toSet()

        var count = 0

        for (packageBlock in tableBlock.listPackages()) {
            for (specTypePair in packageBlock.listSpecTypePairs()) {
                val typeBlocksToRemove = mutableListOf<TypeBlock>()

                for (typeBlock in specTypePair) {
                    val qualifiers = typeBlock.resConfig.qualifiers
                    if (qualifiers.isEmpty()) continue
                    if (!isLanguageQualifier(qualifiers)) continue

                    val shouldRemove = if (safeLocalesToRemove != null) {
                        qualifiers in safeLocalesToRemove
                    } else {
                        true
                    }

                    if (shouldRemove) {
                        typeBlocksToRemove.add(typeBlock)
                    }
                }

                for (typeBlock in typeBlocksToRemove) {
                    typeBlock.destroy()
                    count++
                }
            }
        }

        tableBlock.refresh()
        apkModule.writeApk(outputApk)
        apkModule.close()
        android.util.Log.d("DexPatcher", "PATCH replaced=" + count)
        return count
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
        "in","iw","ji"
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
        onGooglePlayPatch: () -> Unit, onAdsPatch: () -> Unit, onAdsBooleanPatch: () -> Unit,
        onUrlPatch: () -> Unit, onCaAppPubPatch: () -> Unit, onAnalyticsUrlPatch: () -> Unit,
        onGpServicesPatch: () -> Unit, onVpnPatch: () -> Unit, onInstallerCheckPatch: () -> Unit, onDebugItemRemove: () -> Unit,
        onSignaturePatch: () -> Unit
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
