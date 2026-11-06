package dev.nekuz.trackerlens

enum class TrackerCategory(val label: Int, val description: Int) {
    ADVERTISING(R.string.category_advertising, R.string.category_advertising_description),
    ATTRIBUTION(R.string.category_attribution, R.string.category_attribution_description),
    ANALYTICS(R.string.category_analytics, R.string.category_analytics_description),
    ENGAGEMENT(R.string.category_engagement, R.string.category_engagement_description),
    LOCATION(R.string.category_location, R.string.category_location_description),
    DIAGNOSTICS(R.string.category_diagnostics, R.string.category_diagnostics_description),
}

data class Tracker(
    val id: String,
    val name: String,
    val owner: String,
    val category: TrackerCategory,
    val packages: List<String>,
    val excluded: List<String> = emptyList(),
    val manifestKeys: List<String> = emptyList(),
)

object Catalog {
    private fun tracker(
        id: String,
        name: String,
        owner: String,
        category: TrackerCategory,
        vararg packages: String,
        excluded: List<String> = emptyList(),
        manifestKeys: List<String> = emptyList(),
    ) = Tracker(id, name, owner, category, packages.toList(), excluded, manifestKeys)

    private val ads = TrackerCategory.ADVERTISING
    private val attribution = TrackerCategory.ATTRIBUTION
    private val analytics = TrackerCategory.ANALYTICS
    private val engagement = TrackerCategory.ENGAGEMENT
    private val location = TrackerCategory.LOCATION
    private val diagnostics = TrackerCategory.DIAGNOSTICS

    val trackers: List<Tracker> = listOf(
        tracker(
            "admob", "Google AdMob and Ad Manager", "Google", ads,
            "com.google.android.gms.ads", "com.google.android.gms.internal.ads", "com.google.ads",
            excluded = listOf("com.google.android.gms.ads.identifier"),
            manifestKeys = listOf(
                "com.google.android.gms.ads.APPLICATION_ID",
                "com.google.android.gms.ads.AD_MANAGER_APP",
            ),
        ),
        tracker("meta_audience", "Meta Audience Network", "Meta", ads, "com.facebook.ads"),
        tracker("applovin", "AppLovin", "AppLovin", ads, "com.applovin"),
        tracker("unity_ads", "Unity Ads", "Unity", ads, "com.unity3d.ads", "com.unity3d.services"),
        tracker("ironsource", "ironSource", "Unity", ads, "com.ironsource", "com.unity3d.ironsourceads"),
        tracker("liftoff", "Liftoff Monetize (Vungle)", "Liftoff", ads, "com.vungle"),
        tracker("chartboost", "Chartboost", "Chartboost", ads, "com.chartboost"),
        tracker("inmobi", "InMobi", "InMobi", ads, "com.inmobi"),
        tracker("mintegral", "Mintegral", "Mintegral", ads, "com.mbridge.msdk", "com.mintegral.msdk"),
        tracker("pangle", "Pangle", "ByteDance", ads, "com.bytedance.sdk.openadsdk", "com.pgl.sys"),
        tracker("adcolony", "AdColony", "Digital Turbine", ads, "com.adcolony"),
        tracker("tapjoy", "Tapjoy", "Unity", ads, "com.tapjoy"),
        tracker("smaato", "Smaato", "Verve Group", ads, "com.smaato"),
        tracker("fyber", "Digital Turbine Fairbid (Fyber)", "Digital Turbine", ads, "com.fyber", "com.inneractive"),
        tracker("mopub", "MoPub", "AppLovin", ads, "com.mopub"),
        tracker("amazon_ads", "Amazon Publisher Services", "Amazon", ads, "com.amazon.device.ads", "com.amazon.aps"),
        tracker("yandex_ads", "Yandex Ads", "Yandex", ads, "com.yandex.mobile.ads"),
        tracker("criteo", "Criteo", "Criteo", ads, "com.criteo.publisher"),
        tracker("ogury", "Ogury", "Ogury", ads, "com.ogury", "io.presage"),
        tracker("yahoo_ads", "Yahoo Ads", "Yahoo", ads, "com.yahoo.ads", "com.verizon.ads"),
        tracker("startio", "Start.io", "Start.io", ads, "com.startapp"),
        tracker("hyprmx", "HyprMX", "HyprMX", ads, "com.hyprmx"),
        tracker("bidmachine", "BidMachine", "Explorer Advertising", ads, "io.bidmachine"),
        tracker("appodeal", "Appodeal", "Appodeal", ads, "com.appodeal"),
        tracker("pubmatic", "PubMatic", "PubMatic", ads, "com.pubmatic.sdk"),
        tracker("moloco", "Moloco", "Moloco", ads, "com.moloco.sdk"),
        tracker("huawei_ads", "Huawei Ads", "Huawei", ads, "com.huawei.openalliance.ad", "com.huawei.hms.ads"),
        tracker("taboola", "Taboola", "Taboola", ads, "com.taboola"),
        tracker("outbrain", "Outbrain", "Teads", ads, "com.outbrain"),
        tracker("teads", "Teads", "Teads", ads, "tv.teads"),
        tracker("prebid", "Prebid Mobile", "Prebid", ads, "org.prebid.mobile"),
        tracker("xiaomi_ads", "Xiaomi Ads", "Xiaomi", ads, "com.miui.zeus.mimo"),
        tracker("tencent_ads", "Tencent Ads", "Tencent", ads, "com.qq.e.ads"),
        tracker("bigo_ads", "BIGO Ads", "BIGO", ads, "sg.bigo.ads"),
        tracker("adjoe", "adjoe", "adjoe", ads, "io.adjoe.sdk"),
        tracker("maio", "maio", "i-mobile", ads, "jp.maio.sdk"),
        tracker("mytarget", "myTarget", "VK", ads, "com.my.target"),
        tracker("pubnative", "PubNative", "Verve Group", ads, "net.pubnative"),
        tracker("superawesome", "SuperAwesome", "Epic Games", ads, "tv.superawesome"),
        tracker("open_measurement", "IAB Open Measurement", "IAB Tech Lab", ads, "com.iab.omid"),

        tracker("adjust", "Adjust", "AppLovin", attribution, "com.adjust.sdk"),
        tracker("appsflyer", "AppsFlyer", "AppsFlyer", attribution, "com.appsflyer"),
        tracker("branch", "Branch", "Branch Metrics", attribution, "io.branch.referral", "io.branch.sdk"),
        tracker("kochava", "Kochava", "Kochava", attribution, "com.kochava"),
        tracker("singular", "Singular", "Singular", attribution, "com.singular.sdk"),
        tracker("tenjin", "Tenjin", "Tenjin", attribution, "com.tenjin.android"),
        tracker("airbridge", "Airbridge", "AB180", attribution, "io.airbridge"),
        tracker("tiktok_events", "TikTok Business SDK", "TikTok", attribution, "com.tiktok.appevents", "com.tiktok.business"),
        tracker("snap_ads_kit", "Snap App Ads Kit", "Snap", attribution, "com.snap.appadskit"),
        tracker("google_conversion", "Google Ads Conversion Tracking", "Google", attribution, "com.google.ads.conversiontracking"),

        tracker(
            "firebase_analytics", "Google Analytics for Firebase", "Google", analytics,
            "com.google.firebase.analytics", "com.google.android.gms.measurement",
            excluded = listOf("com.google.firebase.analytics.connector"),
        ),
        tracker("google_analytics", "Google Analytics", "Google", analytics, "com.google.android.gms.analytics", "com.google.analytics"),
        tracker("google_tag_manager", "Google Tag Manager", "Google", analytics, "com.google.android.gms.tagmanager", "com.google.tagmanager"),
        tracker(
            "facebook_sdk", "Facebook SDK", "Meta", analytics,
            "com.facebook.appevents", "com.facebook.login", "com.facebook.share",
            manifestKeys = listOf("com.facebook.sdk.ApplicationId"),
        ),
        tracker("mixpanel", "Mixpanel", "Mixpanel", analytics, "com.mixpanel"),
        tracker("amplitude", "Amplitude", "Amplitude", analytics, "com.amplitude"),
        tracker("segment", "Segment", "Twilio", analytics, "com.segment.analytics"),
        tracker("flurry", "Flurry", "Yahoo", analytics, "com.flurry"),
        tracker("appmetrica", "AppMetrica", "Yandex", analytics, "com.yandex.metrica", "io.appmetrica"),
        tracker("app_center", "Visual Studio App Center", "Microsoft", analytics, "com.microsoft.appcenter"),
        tracker("clarity", "Microsoft Clarity", "Microsoft", analytics, "com.microsoft.clarity"),
        tracker("smartlook", "Smartlook", "Cisco", analytics, "com.smartlook"),
        tracker("uxcam", "UXCam", "UXCam", analytics, "com.uxcam"),
        tracker("contentsquare", "Contentsquare", "Contentsquare", analytics, "com.contentsquare"),
        tracker("fullstory", "Fullstory", "Fullstory", analytics, "com.fullstory"),
        tracker("comscore", "Comscore", "Comscore", analytics, "com.comscore"),
        tracker("nielsen", "Nielsen", "Nielsen", analytics, "com.nielsen.app.sdk"),
        tracker("matomo", "Matomo", "Matomo", analytics, "org.matomo.sdk", "org.piwik.sdk"),
        tracker("posthog", "PostHog", "PostHog", analytics, "com.posthog"),
        tracker("heap", "Heap", "Contentsquare", analytics, "com.heapanalytics"),
        tracker("countly", "Countly", "Countly", analytics, "ly.count.android.sdk"),
        tracker("pendo", "Pendo", "Pendo", analytics, "sdk.pendo.io"),
        tracker("quantcast", "Quantcast", "Quantcast", analytics, "com.quantcast.measurement"),
        tracker("umeng", "Umeng", "Alibaba", analytics, "com.umeng"),
        tracker("baidu_stats", "Baidu Mobile Statistics", "Baidu", analytics, "com.baidu.mobstat"),
        tracker("huawei_analytics", "Huawei Analytics", "Huawei", analytics, "com.huawei.hms.analytics"),

        tracker("onesignal", "OneSignal", "OneSignal", engagement, "com.onesignal"),
        tracker("braze", "Braze", "Braze", engagement, "com.braze", "com.appboy"),
        tracker("clevertap", "CleverTap", "CleverTap", engagement, "com.clevertap"),
        tracker("leanplum", "Leanplum", "CleverTap", engagement, "com.leanplum"),
        tracker("airship", "Airship", "Airship", engagement, "com.urbanairship"),
        tracker("iterable", "Iterable", "Iterable", engagement, "com.iterable"),
        tracker("intercom", "Intercom", "Intercom", engagement, "io.intercom"),
        tracker("salesforce_marketing", "Salesforce Marketing Cloud", "Salesforce", engagement, "com.salesforce.marketingcloud"),
        tracker("customerio", "Customer.io", "Customer.io", engagement, "io.customer.sdk"),
        tracker("moengage", "MoEngage", "MoEngage", engagement, "com.moengage"),
        tracker("batch", "Batch", "Batch", engagement, "com.batch.android"),
        tracker("webengage", "WebEngage", "WebEngage", engagement, "com.webengage"),
        tracker("pushwoosh", "Pushwoosh", "Pushwoosh", engagement, "com.pushwoosh"),

        tracker("foursquare", "Foursquare Movement", "Foursquare", location, "com.foursquare.pilgrim", "com.foursquare.movement"),
        tracker("radar", "Radar", "Radar Labs", location, "io.radar.sdk"),
        tracker("cuebiq", "Cuebiq", "Cuebiq", location, "com.cuebiq"),
        tracker("placed", "Placed", "Foursquare", location, "com.placed.client"),
        tracker("xmode", "X-Mode", "Outlogic", location, "io.xmode"),
        tracker("huq", "Huq", "Huq Industries", location, "io.huq"),
        tracker("tutela", "Tutela", "Comlinkdata", location, "com.tutelatechnologies"),
        tracker("sense360", "Sense360", "Medallia", location, "com.sense360"),

        tracker("crashlytics", "Firebase Crashlytics", "Google", diagnostics, "com.google.firebase.crashlytics", "com.crashlytics", "io.fabric.sdk"),
        tracker("firebase_performance", "Firebase Performance", "Google", diagnostics, "com.google.firebase.perf"),
        tracker("sentry", "Sentry", "Sentry", diagnostics, "io.sentry"),
        tracker("bugsnag", "Bugsnag", "SmartBear", diagnostics, "com.bugsnag"),
        tracker("instabug", "Instabug", "Instabug", diagnostics, "com.instabug"),
        tracker("datadog", "Datadog", "Datadog", diagnostics, "com.datadog.android"),
        tracker("new_relic", "New Relic", "New Relic", diagnostics, "com.newrelic.agent"),
        tracker("dynatrace", "Dynatrace", "Dynatrace", diagnostics, "com.dynatrace.android"),
        tracker("appdynamics", "AppDynamics", "Cisco", diagnostics, "com.appdynamics.eumagent"),
        tracker("embrace", "Embrace", "Embrace", diagnostics, "io.embrace.android"),
        tracker("bugly", "Tencent Bugly", "Tencent", diagnostics, "com.tencent.bugly"),
        tracker("backtrace", "Backtrace", "Sauce Labs", diagnostics, "backtraceio.library"),
    )

    val byId: Map<String, Tracker> = trackers.associateBy { it.id }

    val version: String = trackers
        .joinToString("|") { "${it.id}:${it.packages}:${it.excluded}:${it.manifestKeys}" }
        .hashCode()
        .toString(16)
}
