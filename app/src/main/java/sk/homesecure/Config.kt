package sk.homesecure

/**
 * Zariadenie, ktoreho pritomnost na Wi-Fi znamena "som doma".
 *
 * IP je povinna a mala by byt na routeri rezervovana cez DHCP, inak sa po case
 * zmeni a strazenie by sa zaplo aj ked si doma. MAC je len informativna - citat
 * ARP tabulku Android od verzie 10 aplikaciam zakazuje, takze detekcia bezi
 * cez IP.
 */
data class TrustedDevice(val name: String, val ip: String, val mac: String = "")

/** Parametre detekcie pritomnosti, pohybu a ochranneho okna. */
data class DetectConfig(
    /** Ako casto skenujeme siet, ked sa nic nedeje. */
    val pollIdleMs: Long = 15_000,
    /** Zrychlene skenovanie, kym su fotky v karantene (aby sa navrat chytil vcas). */
    val pollFastMs: Long = 3_000,
    /**
     * Kolko kol po sebe musia byt vsetky zariadenia nedostupne, nez vyhlasime
     * odchod. Telefon v spanku obcas ping zahodi, takze jedno zmeskane kolo
     * nesmie stacit.
     */
    val awayRounds: Int = 4,
    /** Ochranne okno: fotka sa zahodi, ak sa do tohto casu pripojis na Wi-Fi. */
    val graceMs: Long = 30_000,
    /** Kolko fotiek spravi jeden poplach. */
    val burstCount: Int = 3,
    val burstSpacingMs: Long = 1_500,
    /** Po davke sa chvilu nespusta dalsia, nech nemas 200 fotiek zo zaclony. */
    val cooldownMs: Long = 20_000,
    /** 1..100; vyssie = citlivejsie na pohyb. */
    val sensitivity: Int = 45,
    /**
     * Pozadovana sirka fotky v pixeloch; 0 = maximum, co kamera vie.
     * Kamera vyberie najblizsie vyssie podporovane rozlisenie.
     */
    val photoWidth: Int = 1920,
    /** Kompresia JPEG, 60..100. Nizsie = mensi mail, viac artefaktov. */
    val jpegQuality: Int = 92,
    val useFrontCamera: Boolean = false,
    /**
     * Brat odmietnute TCP spojenie ako dokaz, ze zariadenie je na sieti.
     * Standardne vypnute - na niektorych sietach odpoveda router aj za adresy,
     * na ktorych nic nie je, a appka potom tvrdi "si doma", aj ked nie si.
     */
    val allowTcpKnock: Boolean = false,
    /**
     * Prisvietenie bleskom pri foteni. Bez neho su nocne zabery cierne, ale
     * zabliknutie prezradi, ze v miestnosti nieco je - preto je to volba.
     */
    val useFlash: Boolean = false
)

/**
 * Kam a cez co sa posielaju fotky.
 *
 * Predvolby plni build zo spolocneho nas-credentials.local — ten isty
 * ucet, z ktoreho posielaju aj skenery akcii. Nastavenia appky ich prepisu.
 */
data class MailConfig(
    val smtpHost: String = BuildConfig.MAIL_HOST.ifBlank { "smtp.gmail.com" },
    val smtpPort: Int = BuildConfig.MAIL_PORT.toIntOrNull() ?: 587,
    /** Odosielajuci ucet (samostatny Gmail, nie osobny). */
    val username: String = BuildConfig.MAIL_USER,
    /** Gmail "app password", nie bezne heslo k uctu. */
    val password: String = BuildConfig.MAIL_PASS,
    /** Prijemca upozorneni - lubovolna adresa, meni sa v nastaveniach. */
    val to: String = BuildConfig.MAIL_TO
) {
    val isUsable: Boolean
        get() = smtpHost.isNotBlank() && username.isNotBlank() &&
            password.isNotBlank() && to.isNotBlank()
}

/**
 * Self-update z NAS-u cez SMB - rovnaka konvencia ako WakeNB: kazda appka ma
 * v share-i "Android" vlastny podpriecinok a v nom jeden subor s pevnym menom,
 * ktory sa pri novom vydani len prepise.
 */
data class UpdateConfig(
    val host: String = BuildConfig.NAS_HOST.ifBlank { "nas.local" },
    val shareName: String = BuildConfig.NAS_SHARE.ifBlank { "Android" },
    /** Presne tak, ako sa priecinok vola na NAS-e. */
    val remotePath: String = "Homesecure",
    val apkFileName: String = "homesecure.apk",
    /** Predvolby z `BuildConfig` plni build zo spolocneho nas-credentials.local. */
    val username: String = BuildConfig.NAS_USER,
    val password: String = BuildConfig.NAS_PASS,
    /** Vacsinou prazdne; Synology workgroup ak ho mas nastaveny. */
    val domain: String = "",
    val autoCheck: Boolean = true,
    val checkIntervalHours: Int = 6
)
