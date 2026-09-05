package com.falseenvironment.jmapjolt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.Patterns
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.graphics.PorterDuff
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : AppCompatActivity() {

    internal enum class SwipeAction {
        DELETE,
        ARCHIVE,
        MARK_READ,
        MARK_SPAM
    }
    internal enum class SettingsSection {
        ROOT,
        GENERAL,
        SWIPE,
        UNIFIED_PUSH,
        THEME
    }

    private data class TestNotificationResult(val success: Boolean, val httpCode: Int?)

    internal lateinit var drawerLayout: DrawerLayout
    internal lateinit var navigationView: NavigationView
    internal lateinit var toolbar: Toolbar
    internal lateinit var drawerToggle: ActionBarDrawerToggle
    internal lateinit var onboardingContainer: LinearLayout
    internal lateinit var onboardingBottomBar: android.widget.RelativeLayout
    internal lateinit var onboardingPager: androidx.viewpager2.widget.ViewPager2
    internal lateinit var onboardingNextFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    internal lateinit var onboardingDots: LinearLayout
    internal var loginFromOnboarding = false
    internal var onboardingPermRefresh: (() -> Unit)? = null
    internal var pendingMailboxShow = false
    internal lateinit var loginContainer: LinearLayout
    internal lateinit var loginBackBtn: ImageView
    internal lateinit var loadingOverlay: FrameLayout
    internal lateinit var settingsContainer: ScrollView
    internal lateinit var settingsMenuContainer: LinearLayout
    internal lateinit var settingsGeneralContainer: LinearLayout
    internal lateinit var settingsGeneralHeader: LinearLayout
    internal lateinit var settingsGeneralContent: LinearLayout
    internal lateinit var settingsGeneralChevron: ImageView
    internal lateinit var settingsLabelsContainer: LinearLayout
    internal lateinit var settingsLabelsHeader: LinearLayout
    internal lateinit var settingsLabelsContent: LinearLayout
    internal lateinit var settingsLabelsChevron: ImageView
    internal lateinit var settingsSwipeContainer: LinearLayout
    internal lateinit var settingsUnifiedPushContainer: LinearLayout
    internal lateinit var settingsUnifiedPushHeader: LinearLayout
    internal lateinit var settingsUnifiedPushContent: LinearLayout
    internal lateinit var settingsUnifiedPushChevron: ImageView

    internal lateinit var settingsThemeContainer: LinearLayout
    internal lateinit var settingsThemeHeader: LinearLayout
    internal lateinit var settingsThemeContent: LinearLayout
    internal lateinit var settingsThemeChevron: ImageView
    internal lateinit var settingsCalendarContainer: LinearLayout
    internal lateinit var settingsCalendarChevron: ImageView
    internal lateinit var settingsImportIcsRow: TextView
    internal lateinit var settingsExportIcsRow: TextView
    internal lateinit var settingsContactsContainer: LinearLayout
    internal lateinit var settingsContactsOptions: LinearLayout
    internal lateinit var contactsEnabledSwitch: SwitchCompat
    internal lateinit var settingsImportVcfRow: TextView
    internal lateinit var settingsExportVcfRow: TextView
    internal lateinit var settingsInfoRow: LinearLayout
    internal lateinit var settingsInfoIcon: ImageView
    internal lateinit var settingsInfoArrow: ImageView
    internal lateinit var loadImagesSwitch: SwitchCompat
    internal lateinit var loadFaviconsSwitch: SwitchCompat
    // Guards the favicon switch listener against re-entry while we toggle it programmatically.
    internal var suppressFaviconToggle = false
    internal var themeIdx: Int = 0
    internal lateinit var themeDropdown: LinearLayout
    internal lateinit var themeDropdownText: TextView
    internal lateinit var emailInput: EditText
    internal lateinit var passwordInput: EditText
    internal lateinit var serverUrlInput: EditText
    internal lateinit var emailInputLayout: com.google.android.material.textfield.TextInputLayout
    internal lateinit var passwordInputLayout: com.google.android.material.textfield.TextInputLayout
    internal lateinit var serverUrlInputLayout: com.google.android.material.textfield.TextInputLayout
    internal lateinit var loginButton: Button
    internal lateinit var mailboxContainer: FrameLayout
    internal lateinit var emailsRecyclerView: RecyclerView
    internal lateinit var emailDetailContainer: EmailDetailContainer
    internal lateinit var detailFrom: TextView
    internal lateinit var detailHeaderRow: LinearLayout
    internal lateinit var detailSubject: TextView
    internal lateinit var detailDate: TextView
    internal lateinit var detailToText: TextView
    internal lateinit var detailMoreButton: ImageView
    internal lateinit var searchChipsScroll: android.widget.HorizontalScrollView
    internal lateinit var searchChipsRow: LinearLayout
    internal var searchScope: Int? = null
    internal lateinit var detailBody: LinearLayout
    internal lateinit var detailScroll: androidx.core.widget.NestedScrollView
    internal var detailBarHidden = false
    internal var detailBarHeight = 0
    // Scroll-linked header collapse: current upward offset in px (0 = fully shown).
    internal var detailBarOffset = 0f
    internal var detailSwipeAnimating = false
    internal val prefetchingIds = mutableSetOf<String>()
    internal lateinit var detailWebView: android.webkit.WebView
    // Preview panel that slides in with the finger during detail swipes,
    // showing the adjacent email's content instead of an empty gap.
    internal var detailPreviewPanel: LinearLayout? = null
    internal var detailPreviewWebView: android.webkit.WebView? = null
    internal var detailPreviewKey: String? = null
    internal lateinit var mailSwipeRefresh: SwipeRefreshLayout
    internal lateinit var unifiedPushSwitch: SwitchCompat
    internal lateinit var sseSwitch: SwitchCompat
    internal lateinit var emptyStateView: TextView
    internal lateinit var status: TextView
    internal lateinit var customTopBar: LinearLayout
    internal lateinit var topBarAccentArea: LinearLayout
    internal lateinit var settingsEditLabelsButton: TextView
    internal lateinit var settingsEditFoldersButton: TextView
    internal lateinit var folderLabel: TextView
    internal lateinit var searchBarMenuIcon: ImageView
    internal lateinit var searchBarTitle: TextView
    internal lateinit var searchBarContainer: LinearLayout
    internal var swipeRightActionIdx: Int = 0
    internal var swipeLeftActionIdx: Int = 0
    internal lateinit var swipeRightDropdown: LinearLayout
    internal lateinit var swipeLeftDropdown: LinearLayout
    internal lateinit var swipeRightDropdownText: TextView
    internal lateinit var swipeLeftDropdownText: TextView
    /** Seconds an email must stay open before it's marked seen; 0 = instant (default). */
    internal var markReadDelaySeconds: Int = 0
    internal lateinit var markReadDelayDropdown: LinearLayout
    internal lateinit var markReadDelayDropdownText: TextView
    internal lateinit var settingsCalProviderDropdown: LinearLayout
    internal lateinit var settingsCalProviderText: TextView
    internal lateinit var settingsCalTimeFormatDropdown: LinearLayout
    internal lateinit var settingsCalTimeFormatText: TextView
    internal lateinit var settingsCalTimeZoneDropdown: LinearLayout
    internal lateinit var settingsCalTimeZoneText: TextView
    internal lateinit var settingsContactsShowDropdown: LinearLayout
    internal lateinit var settingsContactsShowText: TextView
    internal lateinit var settingsAccountContainer: LinearLayout
    internal lateinit var settingsAccountProfileRow: LinearLayout
    internal lateinit var settingsAccountAddRow: TextView
    internal lateinit var calendarEnabledSwitch: SwitchCompat
    internal lateinit var settingsCalAddProviderButton: TextView
    internal lateinit var topBarSendButton: ImageView
    internal lateinit var detailReplyButton: ImageView
    internal lateinit var detailForwardButton: ImageView
    internal lateinit var detailArchiveButton: ImageView
    internal lateinit var detailTrashButton: ImageView
    internal lateinit var detailMoveButton: ImageView
    internal lateinit var detailStarButton: ImageView
    internal var currentDetailEmail: DisplayEmail? = null
    internal lateinit var quoteIndicatorRow: LinearLayout
    internal lateinit var quoteIndicatorLabel: TextView
    internal lateinit var quoteIndicatorRemove: ImageView
    internal lateinit var quoteIndicatorDivider: View
    // Faithful HTML of the original message, appended verbatim at send time (reply/forward).
    internal var pendingQuoteHtml: String? = null
    internal lateinit var fabCompose: com.google.android.material.floatingactionbutton.FloatingActionButton
    internal lateinit var composeContainer: LinearLayout
    internal lateinit var composeSendButton: ImageView
    internal lateinit var composeFromLabel: LinearLayout
    internal lateinit var composeFromText: TextView
    internal lateinit var composeToChipsGroup: com.google.android.material.chip.ChipGroup
    internal lateinit var composeCcChipsGroup: com.google.android.material.chip.ChipGroup
    internal lateinit var composeBccChipsGroup: com.google.android.material.chip.ChipGroup
    internal lateinit var composeCategoryTabs: LinearLayout
    internal val recipientEmails = mutableListOf<String>()
    internal val ccEmails = mutableListOf<String>()
    internal val bccEmails = mutableListOf<String>()
    // Active recipient category: 0 = To, 1 = Cc, 2 = Bcc.
    internal var composeCategory = 0
    internal var selectedFromEmail = ""
    internal lateinit var composeToInput: EditText
    internal lateinit var composeContactsButton: ImageView
    internal lateinit var composeSubjectInput: EditText
    internal lateinit var composeBodyInput: EditText
    internal lateinit var formatToolbar: LinearLayout
    internal lateinit var formatToolbarRow: LinearLayout
    internal lateinit var composeAttachButton: ImageView
    internal lateinit var attachmentChipScroll: HorizontalScrollView
    internal lateinit var attachmentChipContainer: LinearLayout
    internal lateinit var attachmentChipDivider: View
    internal val activeFormats = mutableSetOf<String>()
    internal val formatButtons = mutableMapOf<String, View>()
    // List mode for the compose editor: 0 = none, 1 = bullet, 2 = numbered.
    internal var composeListMode = 0
    internal var composeListNextNumber = 1
    // Guards the body TextWatcher against re-entrancy during programmatic list edits.
    internal var composeSelfEdit = false

    data class AttachmentData(val uri: Uri, val name: String, val mimeType: String, val size: Long)
    internal val pendingAttachments = mutableListOf<AttachmentData>()
    /** Attachments carried over from a draft opened for editing — already on the server,
     * so save/send reuses their blobId instead of re-uploading. Not shown as removable
     * chips (those need a local Uri); cleared whenever the compose screen is reset. */
    internal val carriedAttachments = mutableListOf<EmailAttachmentInfo>()

    internal val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { addAttachment(it) } }

    internal val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { addAttachment(it) } }

    internal val requestStoragePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions resolved; user can try picking again */ }

    /** Import a .ics file picked via the Storage Access Framework. */
    internal val importIcsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doImportIcs(it) } }

    /** Export all local events to a .ics file created via the Storage Access Framework. */
    internal val exportIcsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri -> uri?.let { doExportIcs(it) } }

    /** Import a .vcf address book picked via the Storage Access Framework. */
    internal val importVcfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doImportVcf(it) } }

    /** Export the address book to a .vcf file created via the Storage Access Framework. */
    internal val exportVcfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri -> uri?.let { doExportVcf(it) } }
    internal lateinit var drawerAccountName: TextView
    internal lateinit var drawerAccountEmail: TextView
    internal lateinit var drawerAccountAvatar: ImageView
    internal lateinit var drawerAccountRow: LinearLayout
    internal lateinit var drawerAccountArrow: ImageView
    internal lateinit var drawerAccountsList: LinearLayout

    /** Email whose avatar is being changed by the picker; refresh hook for the open dialog. */
    internal var editingAvatarEmail: String? = null
    internal var editProfileAvatarRefresh: (() -> Unit)? = null

    internal val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val email = editingAvatarEmail ?: return@registerForActivityResult
        if (uri != null) showAvatarCropDialog(uri, email)
    }
    /** Set by [ContactEditor] while its avatar picker is open; cleared once the pick resolves. */
    internal var pendingContactPhoto: ((Uri) -> Unit)? = null

    internal val pickContactPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val callback = pendingContactPhoto
        pendingContactPhoto = null
        if (uri != null) callback?.invoke(uri)
    }

    internal lateinit var accentColorPreview: View
    internal lateinit var accentColorRow: LinearLayout
    internal var currentAccentColor: String = "#3D8BFD"

    /** User labels (ordered) + drawer menu ids assigned to each label keyword. */
    internal val labels = mutableListOf<EmailLabel>()
    internal val accountLabelsCache = mutableMapOf<String, List<EmailLabel>>()
    internal val folderMeta = mutableListOf<FolderMeta>()
    internal val accountFolderMetaCache = mutableMapOf<String, List<FolderMeta>>()
    internal val labelNavIds = linkedMapOf<Int, String>()
    /** navId → mailbox server ID for user-defined subfolders (no JMAP role). */
    internal val subfolderNavIds = linkedMapOf<Int, String>()
    internal lateinit var detailLabelRowView: LinearLayout
    internal val isDetailLabelRowViewInit: Boolean get() = ::detailLabelRowView.isInitialized
    internal fun debugTs(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    internal var labelDragHelper: ItemTouchHelper? = null
    /** mailboxIds in user-defined display order for the Folders section of the drawer. */
    internal var subfolderDisplayOrder: MutableList<String> = mutableListOf()

    internal val categoryOrder =
            mutableListOf(
                    R.id.nav_inbox,
                    R.id.nav_favourite,
                    R.id.nav_archive,
                    R.id.nav_sent,
                    R.id.nav_drafts,
                    R.id.nav_spam,
                    R.id.nav_trash
            )
    internal val categoryNames = mutableMapOf<Int, String>()
    internal val emails = mutableListOf<DisplayEmail>()
    // Current page size for the visible folder. Grows by PAGE_SIZE on scroll-to-bottom.
    internal var emailLimit = JMapClient.DEFAULT_EMAIL_LIMIT
    // True while a "load more" fetch is in flight, to avoid stacking requests.
    internal var isLoadingMore = false
    // Set when a page came back shorter than requested: the folder has no more rows.
    internal var reachedFolderEnd = false
    // Rows fetched from the folder's own query so far. Not the same as emails.size:
    // inbox threading adds members that live in other mailboxes, and counting those
    // would skip rows when asking for the next page's position.
    internal var folderQueryCount = 0
    internal lateinit var emailAdapter: EmailAdapter
    internal lateinit var jmapClient: JMapClient
    internal var connectedAccount: JMapClient.ConnectedAccount? = null
    internal val savedAccounts = mutableListOf<AccountEntry>()
    internal var currentAccountEmail: String? = null
    internal var selectedFolder: Int = R.id.nav_inbox
    internal var prevUpdateFolder: Int = -1
    internal val folderCache = FolderCache()
    private var syncJob: Job? = null
    internal var cacheSaveJob: Job? = null
    @Volatile private var lastSseRefreshAt = 0L
    internal var searchHintJob: Job? = null
    internal var currentSettingsSection: SettingsSection = SettingsSection.ROOT
    internal var currentTheme: String = "gray"
    internal val selectedEmails = mutableSetOf<String>()
    internal val baseEmails = mutableListOf<DisplayEmail>() // unfiltered list for search

    // --- Conversation threading (chat-style) ---
    // threadKey -> all member emails (newest first). Built by buildThreadedView().
    internal val threadMembers = LinkedHashMap<String, List<DisplayEmail>>()
    // Expanded thread keys: their child messages are shown indented under the head row.
    internal val expandedThreads = mutableSetOf<String>()
    // Per-thread cap on how many child messages are currently revealed. Grows by
    // THREAD_PAGE each time the user taps the "+N more" row.
    internal val threadChildLimit = HashMap<String, Int>()

    /**
     * Stable grouping key. Conversations are grouped by normalized subject (Re:/Fwd:
     * prefixes stripped) so a forwarded/replied chain collapses into one chat even when
     * the server hands out a fresh threadId per message. Falls back to threadId, then to
     * a per-id singleton for blank subjects.
     */

    internal val replyForwardPrefix =
        Regex("^\\s*(re|fwd|fw|r|i|aw|sv|antw)\\s*(\\[\\d+])?\\s*:\\s*", RegexOption.IGNORE_CASE)

    /** Lowercased subject with leading reply/forward markers and surrounding noise removed. */

    /**
     * Collapses [full] (newest-first) into a chat-style threaded list: one head row per
     * conversation, with the other messages emitted right after it only when expanded.
     * Bakes per-row thread state (count/head/child/key) onto each DisplayEmail so DiffUtil
     * rebinds rows when threading changes, and rebuilds [threadMembers] as a side effect.
     */

    /** Toggles a conversation's expanded state and rebuilds the visible list. */

    /** Reveals the next page of hidden messages in an expanded conversation. */
    // Pending request from a widget tap: open this email once its account's data is loaded.
    internal var pendingWidgetEmailId: String? = null
    internal var pendingWidgetAccount: String? = null
    internal var widgetSwitchAttempted = false
    // Pending request from the calendar widget: open calendar (WEEK) once the UI/session is ready.
    internal var pendingOpenCalendar = false
    internal var pendingCalendarNewEvent = false
    internal var pendingCalendarEventStart = 0L
    internal var isSearchActive = false
    internal lateinit var selectionBarContainer: LinearLayout
    internal lateinit var selectionCountText: TextView
    internal lateinit var selectionCloseBtn: ImageView
    internal lateinit var selectionArchiveBtn: ImageView
    internal lateinit var selectionDeleteBtn: ImageView
    internal lateinit var selectionReadBtn: ImageView
    internal lateinit var selectionMoreBtn: ImageView
    internal lateinit var searchInput: EditText
    internal lateinit var searchClearBtn: ImageView
    private val pushMessageReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == UnifiedPushService.ACTION_PUSH_MESSAGE_RECEIVED &&
                                    connectedAccount != null
                    ) {
                        refreshInboxNow()
                    }
                }
            }

    internal val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted: Boolean ->
                if (isGranted) {
                    Log.d(TAG, "Notification permission granted")
                } else {
                    Log.w(TAG, "Notification permission denied")
                }
                onboardingPermRefresh?.invoke()
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Creating the EncryptedSharedPreferences (Tink keyset + Keystore) costs
        // tens of ms. Start it here so it runs alongside layout inflation and the
        // later loadAccounts()/loadUnifiedPushPreferences() hit the cached instance.
        // SecureStorage.prefs stays synchronized, so a main-thread call that wins
        // the race simply builds it itself — no behaviour depends on this landing.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { SecureStorage.prefs(this@MainActivity) }
        }
        // Android 15 (API 35) enforces edge-to-edge by default; opt out to keep
        // the existing layout which does not handle system bar insets manually.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        FaviconRepository.init(cacheDir)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        toolbar = findViewById(R.id.toolbar)
        onboardingContainer = findViewById(R.id.onboardingContainer)
        onboardingBottomBar = findViewById(R.id.onboardingBottomBar)
        onboardingPager = findViewById(R.id.onboardingPager)
        onboardingNextFab = findViewById(R.id.onboardingNextFab)
        onboardingDots = findViewById(R.id.onboardingDots)
        loginContainer = findViewById(R.id.loginContainer)
        loginBackBtn = findViewById(R.id.loginBackBtn)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        settingsContainer = findViewById(R.id.settingsContainer)
        settingsMenuContainer = findViewById(R.id.settingsMenuContainer)
        settingsGeneralContainer = findViewById(R.id.settingsGeneralContainer)
        settingsGeneralHeader = findViewById(R.id.settingsGeneralHeader)
        settingsGeneralContent = findViewById(R.id.settingsGeneralContent)
        settingsGeneralChevron = findViewById(R.id.settingsGeneralChevron)
        settingsLabelsContainer = findViewById(R.id.settingsLabelsContainer)
        settingsLabelsHeader = findViewById(R.id.settingsLabelsHeader)
        settingsLabelsContent = findViewById(R.id.settingsLabelsContent)
        settingsLabelsChevron = findViewById(R.id.settingsLabelsChevron)
        settingsSwipeContainer = findViewById(R.id.settingsSwipeContainer)
        settingsUnifiedPushContainer = findViewById(R.id.settingsUnifiedPushContainer)
        settingsUnifiedPushHeader = findViewById(R.id.settingsUnifiedPushHeader)
        settingsUnifiedPushContent = findViewById(R.id.settingsUnifiedPushContent)
        settingsUnifiedPushChevron = findViewById(R.id.settingsUnifiedPushChevron)

        settingsThemeContainer = findViewById(R.id.settingsThemeContainer)
        settingsThemeHeader = findViewById(R.id.settingsThemeHeader)
        settingsThemeContent = findViewById(R.id.settingsThemeContent)
        settingsThemeChevron = findViewById(R.id.settingsThemeChevron)
        settingsCalendarContainer = findViewById(R.id.settingsCalendarContainer)
        settingsInfoRow = findViewById(R.id.settingsInfoRow)
        settingsInfoIcon = findViewById(R.id.settingsInfoIcon)
        settingsInfoArrow = findViewById(R.id.settingsInfoArrow)
        loadImagesSwitch = findViewById(R.id.loadImagesSwitch)
        loadFaviconsSwitch = findViewById(R.id.loadFaviconsSwitch)
        themeDropdown = findViewById(R.id.themeDropdown)
        themeDropdownText = findViewById(R.id.themeDropdownText)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        emailInputLayout = findViewById(R.id.emailInputLayout)
        passwordInputLayout = findViewById(R.id.passwordInputLayout)
        serverUrlInputLayout = findViewById(R.id.serverUrlInputLayout)
        loginButton = findViewById(R.id.loginButton)
        mailboxContainer = findViewById(R.id.mailboxContainer)
        emailsRecyclerView = findViewById(R.id.emailsRecyclerView)
        mailSwipeRefresh = findViewById(R.id.mailSwipeRefresh)
        fabCompose = findViewById(R.id.fabCompose)
        composeContainer = findViewById(R.id.composeContainer)
        composeSendButton = findViewById(R.id.composeSendButton)
        composeFromLabel = findViewById(R.id.composeFromLabel)
        composeFromText = findViewById(R.id.composeFromText)
        composeToChipsGroup = findViewById(R.id.composeToChipsGroup)
        composeCcChipsGroup = findViewById(R.id.composeCcChipsGroup)
        composeBccChipsGroup = findViewById(R.id.composeBccChipsGroup)
        composeCategoryTabs = findViewById(R.id.composeCategoryTabs)
        composeToInput = findViewById(R.id.composeToInput)
        composeContactsButton = findViewById(R.id.composeContactsButton)
        composeSubjectInput = findViewById(R.id.composeSubjectInput)
        composeBodyInput = findViewById(R.id.composeBodyInput)
        formatToolbar = findViewById(R.id.formatToolbar)
        formatToolbarRow = findViewById(R.id.formatToolbarRow)
        composeAttachButton = findViewById(R.id.composeAttachButton)
        attachmentChipScroll = findViewById(R.id.attachmentChipScroll)
        attachmentChipContainer = findViewById(R.id.attachmentChipContainer)
        attachmentChipDivider = findViewById(R.id.attachmentChipDivider)
        unifiedPushSwitch = findViewById(R.id.unifiedPushSwitch)
        sseSwitch = findViewById(R.id.sseSwitch)
        val frameLayout =
                FrameLayout(this).apply {
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                }
        mailSwipeRefresh.removeView(emailsRecyclerView)
        frameLayout.addView(emailsRecyclerView)

        emptyStateView =
                TextView(this).apply {
                    // Text and colour are set per context by updateEmptyState/applyTheme.
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.4f)
                    visibility = View.GONE
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER
                            )
                }
        frameLayout.addView(emptyStateView)
        mailSwipeRefresh.addView(frameLayout)

        status =
                findViewById<TextView>(R.id.status).apply {
                    text = getString(R.string.status_initial)
                }
        customTopBar = findViewById(R.id.customTopBar)
        topBarAccentArea = findViewById(R.id.topBarAccentArea)
        folderLabel = findViewById(R.id.folderLabel)
        searchBarMenuIcon = findViewById(R.id.searchBarMenuIcon)
        searchBarTitle = findViewById(R.id.searchBarTitle)
        searchBarContainer = findViewById(R.id.searchBarContainer)
        selectionBarContainer = findViewById(R.id.selectionBarContainer)
        selectionCountText = findViewById(R.id.selectionCountText)
        selectionCloseBtn = findViewById(R.id.selectionCloseBtn)
        selectionArchiveBtn = findViewById(R.id.selectionArchiveBtn)
        selectionDeleteBtn = findViewById(R.id.selectionDeleteBtn)
        selectionReadBtn = findViewById(R.id.selectionReadBtn)
        selectionMoreBtn = findViewById(R.id.selectionMoreBtn)
        searchInput = findViewById(R.id.searchInput)
        searchChipsScroll = findViewById(R.id.searchChipsScroll)
        searchChipsRow = findViewById(R.id.searchChipsRow)
        searchClearBtn = findViewById(R.id.searchClearBtn)
        swipeRightDropdown = findViewById(R.id.swipeRightDropdown)
        swipeLeftDropdown = findViewById(R.id.swipeLeftDropdown)
        swipeRightDropdownText = findViewById(R.id.swipeRightDropdownText)
        swipeLeftDropdownText = findViewById(R.id.swipeLeftDropdownText)
        markReadDelayDropdown = findViewById(R.id.markReadDelayDropdown)
        markReadDelayDropdownText = findViewById(R.id.markReadDelayDropdownText)
        settingsCalProviderDropdown = findViewById(R.id.settingsCalProviderDropdown)
        settingsCalProviderText = findViewById(R.id.settingsCalProviderText)
        settingsCalTimeFormatDropdown = findViewById(R.id.settingsCalTimeFormatDropdown)
        settingsCalTimeFormatText = findViewById(R.id.settingsCalTimeFormatText)
        settingsCalTimeZoneDropdown = findViewById(R.id.settingsCalTimeZoneDropdown)
        settingsCalTimeZoneText = findViewById(R.id.settingsCalTimeZoneText)
        settingsContactsShowDropdown = findViewById(R.id.settingsContactsShowDropdown)
        settingsContactsShowText = findViewById(R.id.settingsContactsShowText)
        settingsAccountContainer = findViewById(R.id.settingsAccountContainer)
        settingsAccountProfileRow = findViewById(R.id.settingsAccountProfileRow)
        settingsAccountAddRow = findViewById(R.id.settingsAccountAddRow)
        calendarEnabledSwitch = findViewById(R.id.calendarEnabledSwitch)
        settingsCalAddProviderButton = findViewById(R.id.settingsCalAddProviderRow)
        settingsContactsContainer = findViewById(R.id.settingsContactsContainer)
        settingsContactsOptions = findViewById(R.id.settingsContactsOptions)
        contactsEnabledSwitch = findViewById(R.id.contactsEnabledSwitch)
        settingsImportVcfRow = findViewById(R.id.settingsImportVcfRow)
        settingsExportVcfRow = findViewById(R.id.settingsExportVcfRow)
        topBarSendButton = findViewById(R.id.topBarSendButton)
        quoteIndicatorRow = findViewById(R.id.quoteIndicatorRow)
        quoteIndicatorLabel = findViewById(R.id.quoteIndicatorLabel)
        quoteIndicatorRemove = findViewById(R.id.quoteIndicatorRemove)
        quoteIndicatorDivider = findViewById(R.id.quoteIndicatorDivider)
        settingsEditLabelsButton = findViewById(R.id.settingsEditLabelsButton)
        settingsEditFoldersButton = findViewById(R.id.settingsEditFoldersButton)
        quoteIndicatorRemove.setOnClickListener { clearPendingQuote() }
        val drawerHeader = navigationView.getHeaderView(0)
        drawerAccountName = drawerHeader.findViewById(R.id.drawerAccountName)
        drawerAccountEmail = drawerHeader.findViewById(R.id.drawerAccountEmail)
        drawerAccountAvatar = drawerHeader.findViewById(R.id.drawerAccountAvatar)
        drawerAccountRow = drawerHeader.findViewById(R.id.drawerAccountRow)
        drawerAccountArrow = drawerHeader.findViewById(R.id.drawerAccountArrow)
        drawerAccountsList = drawerHeader.findViewById(R.id.drawerAccountsList)
        val drawerVersionText = findViewById<TextView>(R.id.drawerVersionText)
        drawerVersionText.text = "JMAPJolt v${BuildConfig.VERSION_NAME}"
        accentColorPreview = findViewById(R.id.accentColorPreview)
        accentColorRow = findViewById(R.id.accentColorRow)

        jmapClient = JMapClient(this)
        // Purge the WebView disk cache: detail views run with LOAD_NO_CACHE, but
        // caches accumulated before that (or by other WebView writes) linger forever.
        // Instantiating a WebView starts Chromium (100-300 ms), so this runs once
        // ever, and after the first frame rather than inline in onCreate.
        purgeWebViewCacheOnce()
        // One-time cleanup of the legacy flat JSON cache (replaced by the Room store), plus
        // the attachments staged for other apps, which are only needed while the share or
        // open is in flight.
        lifecycleScope.launch(Dispatchers.IO) {
            filesDir.listFiles()
                ?.filter { it.name.startsWith("cache_") && it.name.endsWith(".json") }
                ?.forEach { it.delete() }
            runCatching { AttachmentCache.purgeExpired(this@MainActivity) }
        }

        // Warm the address book once so the email list can draw contact photos for known senders
        // (and the compose picker opens instantly) without waiting for the contacts tab.
        // The book lands after the first rows are already bound, so repaint the list once the
        // photo index exists (and again whenever the address book is reloaded or edited).
        ContactAvatars.onIndexed = { runOnUiThread { rebindVisibleAvatars() } }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ContactsRepository(this@MainActivity).warmCache() }
        }

        setSupportActionBar(toolbar)
        drawerToggle =
                ActionBarDrawerToggle(
                        this,
                        drawerLayout,
                        toolbar,
                        R.string.drawer_open,
                        R.string.drawer_close
                )
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.setToolbarNavigationClickListener { handleNavigationClick() }
        searchBarMenuIcon.setOnClickListener {
            if (drawerToggle.isDrawerIndicatorEnabled) drawerLayout.openDrawer(GravityCompat.START)
            else handleNavigationClick()
        }

        setupEmailDetailView()
        setupComposeView()
        loadCategoryPreferences()
        loadLabels()
        loadSubfolderOrder()
        loadFolderMeta()
        setupAdapters()
        setupSwipeSpinners()
        setupThemeSpinner()
        setupMarkReadDelaySpinner()
        loadThemePreference()
        // Calendar views read the zone override from a static cache (no Context there).
        CalendarPrefs.warmTimeZone(this)
        loadUnifiedPushPreferences()
        loadGeneralPreferences()
        rebuildDrawerMenu()
        bindSettingsActions()
        bindDrawerNavigation()
        bindSettingsMenuNavigation()
        bindPullToRefresh()
        loadAccounts()
        applyTheme()
        handleMailtoIntent(intent)
        handleWidgetIntent(intent)
        handleCalendarIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    composeContainer.visibility == View.VISIBLE -> attemptLeaveCompose()
                    drawerLayout.isDrawerOpen(GravityCompat.START) ->
                        drawerLayout.closeDrawer(GravityCompat.START)
                    calendarPanelView?.visibility == View.VISIBLE ->
                        if (calendarPanelView?.onBackPressed() != true) showMailboxScreen()
                    contactsPanelView?.visibility == View.VISIBLE ->
                        if (contactsPanelView?.onBackPressed() != true) showMailboxScreen()
                    selectedEmails.isNotEmpty() -> clearSelection()
                    isSearchActive -> {
                        // First back press only dismisses the keyboard so results stay
                        // visible; a second press (or the back-arrow icon) exits search.
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(drawerLayout)
                        val imeVisible = insets
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        if (imeVisible) hideKeyboard() else deactivateSearch()
                    }
                    isShowingEmailDetail -> closeEmailDetail()
                    settingsContainer.visibility == View.VISIBLE -> {
                        if (currentSettingsSection != SettingsSection.ROOT) attemptLeaveSettingsSubmenu()
                        else showMailboxScreen()
                    }
                    loginContainer.visibility == View.VISIBLE -> showOnboarding()
                    onboardingContainer.visibility == View.VISIBLE && onboardingPager.currentItem > 0 ->
                        onboardingPager.setCurrentItem(onboardingPager.currentItem - 1, true)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        setupOnboardingPager()
        drawerAccountRow.setOnClickListener {
            val open = drawerAccountsList.visibility != View.VISIBLE
            drawerAccountArrow.animate().rotation(if (open) 180f else 0f).setDuration(200).start()
            if (open) {
                // Expand like the settings accordions: grow + fade.
                drawerAccountsList.visibility = View.VISIBLE
                drawerAccountsList.measure(
                    View.MeasureSpec.makeMeasureSpec(navigationView.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val target = drawerAccountsList.measuredHeight
                drawerAccountsList.layoutParams.height = 0
                drawerAccountsList.alpha = 0f
                android.animation.ValueAnimator.ofInt(0, target).apply {
                    duration = 300
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener {
                        drawerAccountsList.layoutParams.height = it.animatedValue as Int
                        // Fade in faster than the height grows so content lands settled.
                        drawerAccountsList.alpha = kotlin.math.min(1f, it.animatedFraction * 1.6f)
                        drawerAccountsList.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            drawerAccountsList.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            drawerAccountsList.alpha = 1f
                            drawerAccountsList.requestLayout()
                        }
                    })
                    start()
                }
            } else {
                val start = drawerAccountsList.height
                android.animation.ValueAnimator.ofInt(start, 0).apply {
                    duration = 240
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener {
                        drawerAccountsList.layoutParams.height = it.animatedValue as Int
                        drawerAccountsList.alpha = 1f - it.animatedFraction
                        drawerAccountsList.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            drawerAccountsList.visibility = View.GONE
                            drawerAccountsList.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            drawerAccountsList.alpha = 1f
                            drawerAccountsList.requestLayout()
                        }
                    })
                    start()
                }
            }
        }
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                drawerAccountsList.visibility = View.GONE
                drawerAccountArrow.rotation = 0f
            }
        })

        if (!restoreLastAccountSession()) {
            if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_WELCOME_SHOWN, false)) {
                showLoginScreen()
            } else {
                showOnboarding()
            }
        } else if (unifiedPushSwitch.isChecked) {
            // Restored a saved session: ensure the periodic fallback worker is
            // active (it may have been cancelled by a past push registration).
            EmailSyncWorker.schedule(this)
        }
        // After the session restore decided the initial screen, honor a pending calendar
        // widget tap so it lands on the calendar (WEEK) instead of the inbox.
        applyPendingCalendarIntent()
        emailInput.addTextChangedListener(simpleWatcher)
        passwordInput.addTextChangedListener(simpleWatcher)
        serverUrlInput.addTextChangedListener(simpleWatcher)
        loginButton.setOnClickListener { connectAndOpenMailbox() }
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                pushMessageReceiver,
                IntentFilter(UnifiedPushService.ACTION_PUSH_MESSAGE_RECEIVED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startForegroundSse()
        updateFormState()
        // Notification permission is only requested from the onboarding permission screen,
        // never automatically on launch.
        // Re-arm calendar reminders on every launch: the reschedule chain only advances when
        // a reminder fires or the calendar screen is touched, so it can stall silently.
        if (CalendarPrefs.isEnabled(this)) CalendarReminderScheduler.reschedule(this)
        // Same self-healing idea for the widgets' midnight rollover alarm.
        WidgetDayRollReceiver.schedule(applicationContext)
    }

    private val simpleWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                ) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                        updateFormState()
                override fun afterTextChanged(s: Editable?) = Unit
            }

    internal fun completeOnboardingToLogin() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WELCOME_SHOWN, true)
                .apply()
        loginFromOnboarding = true
        showLoginScreen()
    }

    internal var isShowingEmailDetail = false

    /** Pending "mark as read" coroutine honoring [markReadDelaySeconds]; cancelled on navigation away. */
    internal var markSeenJob: Job? = null

    /** Calendar UI hosted in the content area so the app drawer stays available over it. */
    internal var calendarPanelView: CalendarPanel? = null

    /** Opens the app navigation drawer (used by the calendar panel's hamburger). */
    internal fun openMainDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /** Launches the .ics import picker and refreshes the calendar panel on return. */
    internal fun launchCalendarIcsImport() {
        runCatching { importIcsLauncher.launch(arrayOf("text/calendar", "*/*")) }
    }

    /** Shows the calendar inside MainActivity (keeps the real drawer). */
    internal fun showCalendarScreen() {
        if (composeContainer.visibility == View.VISIBLE) hideCompose()
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        loginBackBtn.visibility = View.GONE
        mailboxContainer.visibility = View.GONE
        settingsContainer.visibility = View.GONE
        emailDetailContainer.visibility = View.GONE
        hideContactsScreen()
        fabCompose.visibility = View.GONE
        customTopBar.visibility = View.GONE
        isShowingEmailDetail = false
        val panel = calendarPanelView ?: CalendarPanel(this).also { p ->
            calendarPanelView = p
            val parent = mailboxContainer.parent as android.view.ViewGroup
            parent.addView(p, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        panel.refresh()
        panel.onShown()
        navigationView.post { rebuildDrawerMenu() }
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    internal fun hideCalendarScreen() {
        calendarPanelView?.visibility = View.GONE
    }

    /** Address book UI, hosted the same way as the calendar so the drawer stays available. */
    internal var contactsPanelView: ContactsPanel? = null

    internal fun showContactsScreen() {
        if (composeContainer.visibility == View.VISIBLE) hideCompose()
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        loginBackBtn.visibility = View.GONE
        mailboxContainer.visibility = View.GONE
        settingsContainer.visibility = View.GONE
        emailDetailContainer.visibility = View.GONE
        calendarPanelView?.visibility = View.GONE
        fabCompose.visibility = View.GONE
        customTopBar.visibility = View.GONE
        isShowingEmailDetail = false
        val panel = contactsPanelView ?: ContactsPanel(this).also { p ->
            contactsPanelView = p
            val parent = mailboxContainer.parent as android.view.ViewGroup
            parent.addView(p, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        panel.onShown()
        navigationView.post { rebuildDrawerMenu() }
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    internal fun hideContactsScreen() {
        contactsPanelView?.visibility = View.GONE
    }

    /** Id of the draft currently being edited, so it can be replaced (destroyed) on save/send. */
    internal var editingDraftId: String? = null

    /** Optimistic favorite state per email id, kept until the server sync reflects it. */
    internal val optimisticFavorite = mutableMapOf<String, Boolean>()

    /** Mailboxes cached so the "Move to" sheet opens instantly without a network round-trip. */
    internal var mailboxCache: List<JMapClient.MailboxInfo>? = null

    internal fun showMailboxScreen(skipRefresh: Boolean = false) {
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        loginBackBtn.visibility = View.GONE
        hideCalendarScreen()
        hideContactsScreen()
        mailboxContainer.visibility = View.VISIBLE
        mailboxContainer.animateScreenInBack()
        emailDetailContainer.visibility = View.GONE
        mailSwipeRefresh.visibility = View.VISIBLE
        fabCompose.animateFabIn()
        settingsContainer.visibility = View.GONE
        customTopBar.visibility = View.VISIBLE
        isShowingEmailDetail = false
        currentSettingsSection = SettingsSection.ROOT
        invalidateOptionsMenu()
        setDrawerIndicator(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerToggle.syncState()
        applyNavIconTint(getOnAccentColor())
        updateTopBarState()
        rebuildDrawerMenu()
        if (!skipRefresh) applyFolderFilterAndRefresh()

    }




    /** Lazily builds the sliding preview panel (a second WebView) used during detail swipes. */

    /** Loads [target]'s content into the preview (cached body, or the shimmer skeleton). */

    /** Content follows the finger; the adjacent email slides in alongside it (no empty gap). */

    /** Overflow menu (3 dots) on the detail header: actions that used to be inline icons. */

    /** Moves an archived email back to the inbox (detail-view counterpart of swipe unarchive). */

    /** Re-syncs body inset and the swipe zone with the (content-dependent) header height. */

    /** "to me ▾" tap: floating popup card with full addresses; tap anywhere outside to dismiss. */

    /** Heuristic: true when the body carries real HTML markup (full document or fragment). */

    /**
     * Collapses deeply-nested quote/forward chains behind a no-JS <details> toggle.
     * Once the nesting of quote containers (`.quoted-html-island` divs or <blockquote>)
     * exceeds [threshold], the container that crosses the threshold — and everything inside
     * it — is wrapped in a collapsible <details class="jj-quote-collapse"> element so long
     * forward chains don't flood the view. Pure HTML/CSS, no JavaScript required.
     *
     * Only `div` and `blockquote` elements are balanced (they form quote nesting); void and
     * other tags are ignored for depth tracking. Insertions are applied right-to-left so
     * earlier indices stay valid.
     */

    /** Captures a widget tap so the target email opens once its data is available. */

    /** Navigates to the inbox the inbox-widget header represents (single account or unified). */

    /** Opens a pending widget email when its account is active and the message is loaded. */

    internal fun showEmailDetail(email: DisplayEmail, fromSwipe: Boolean = false) {
        if (!fromSwipe) {
            mailSwipeRefresh.visibility = View.GONE
            fabCompose.animateFabOut()
            emailDetailContainer.visibility = View.VISIBLE
            emailDetailContainer.animateScreenIn()
        }
        isShowingEmailDetail = true
        currentDetailEmail = email
        // The theme may have changed while the detail was hidden; the container is the
        // surface uncovered by the next/previous swipe, so keep it on the current theme.
        emailDetailContainer.setBackgroundColor(getThemeBackgroundColor())
        detailHeaderRow.setBackgroundColor(getThemeToolbarColor())
        updateDetailStarIcon(email.isFavorite)
        // Reset the auto-hide action row to fully visible on open.
        detailBarHidden = false
        detailBarOffset = 0f
        detailHeaderRow.animate().cancel()
        detailHeaderRow.visibility = View.VISIBLE
        detailHeaderRow.translationY = 0f
        detailHeaderRow.alpha = 1f
        detailBody.setPadding(0, detailBarHeight, 0, 0)
        setDrawerIndicator(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawerToggle.syncState()
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
        applyNavIconTint(getOnAccentColor())
        val (toolbarColor, textColor, secondaryTextColor) =
                when (currentTheme) {
                    "light" -> Triple("#F5F5F5", "#212121", "#757575")
                    "oled" -> Triple("#000000", "#FFFFFF", "#BDBDBD")
                    "violet" -> Triple("#140B22", "#FFFFFF", "#BDBDBD")
                    else -> Triple("#2A2A2A", "#FFFFFF", "#BDBDBD")
                }

        detailHeaderRow.setBackgroundColor(toolbarColor.toColorInt())
        detailSubject.text = email.subject.ifBlank { "(no subject)" }
        detailSubject.setTextColor(textColor.toColorInt())
        detailFrom.setTextColor(textColor.toColorInt())
        detailFrom.text = email.from.ifBlank { email.fromEmail }
        detailDate.text = if (email.receivedAt > 0) formatRelativeDate(email.receivedAt) else ""
        detailDate.setTextColor(secondaryTextColor.toColorInt())
        val toLabel = when {
            email.toEmail.isBlank() -> "to me"
            email.toEmail.equals(email.accountEmail, ignoreCase = true) -> "to me"
            else -> "to ${email.toEmail}"
        }
        detailToText.text = "$toLabel  ▾"
        detailToText.setTextColor(secondaryTextColor.toColorInt())

        // Tint the pinned action icons to contrast the header; star reflects favourite state.
        val actionTint = ColorStateList.valueOf(textColor.toColorInt())
        listOf(detailReplyButton, detailForwardButton, detailArchiveButton,
               detailTrashButton, detailMoveButton, detailMoreButton).forEach { it.imageTintList = actionTint }
        updateDetailStarIcon(email.isFavorite)
        updateDetailLabelIcon()

        // Header height is content-dependent now (subject wraps): sync the body
        // inset and the swipe-from-header zone once it is laid out.
        syncDetailHeaderHeight()

        // Remove previous attachment footer if present (detailBody: 0=WebView, 1=spacer, 2=attRow)
        if (detailBody.childCount > 2) detailBody.removeViewAt(2)

        val account = resolveAccountFor(email)
        if (email.attachments.isNotEmpty() && account != null) {
            val attRow = buildEmailAttachmentRow(email.attachments, account)
            detailBody.addView(attRow)
        }

        // Email view background = the home/inbox background per theme, so the whole screen matches.
        val wvBgInt = tokens.background
        // Paint the whole scroll area (webview + spacer + attachment footer) with one colour so
        // the screen reads as a single email view. The webview itself is transparent so its own
        // (possibly skeleton) backdrop never shows a different shade.
        detailWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        detailScroll.setBackgroundColor(wvBgInt)
        detailBody.setBackgroundColor(wvBgInt)
        // Show cached body immediately (zero latency) or a shimmer skeleton while fetching.
        val bodyAvailableNow = email.fullBody.isNotBlank()
        detailWebView.loadEmailHtml(if (bodyAvailableNow) buildHtmlContent(email.fullBody) else buildSkeletonHtml())
        detailWebView.settings.blockNetworkImage = !EmailWebView.isImageLoadingEnabled(this)

        lifecycleScope.launch {
            try {
                val account = resolveAccountFor(email)
                val needsFetch = email.fullBody.isBlank() || email.attachments.isEmpty()
                var displayEmail = email
                if (needsFetch && account != null) {
                    val fresh = jmapClient.fetchEmailsById(account, listOf(email.id)).firstOrNull()
                    if (fresh != null) {
                        val updated = email.copy(
                            fullBody = if (fresh.fullBody.isNotBlank()) fresh.fullBody else email.fullBody,
                            attachments = fresh.attachments
                        )
                        displayEmail = updated
                        val idx = emails.indexOfFirst { it.id == email.id }
                        if (idx >= 0) {
                            emails[idx] = updated
                            val bi = baseEmails.indexOfFirst { it.id == email.id }
                            if (bi >= 0) baseEmails[bi] = updated
                            saveEmailCache()
                        }
                        // Refresh attachment footer
                        if (detailBody.childCount > 2) detailBody.removeViewAt(2)
                        if (updated.attachments.isNotEmpty()) {
                            val attRow = buildEmailAttachmentRow(updated.attachments, account)
                            detailBody.addView(attRow)
                        }
                    }
                }
                currentDetailEmail = displayEmail
                // Only render if body was skeleton (not already rendered synchronously above).
                if (!bodyAvailableNow) {
                    detailWebView.loadEmailHtml(buildHtmlContent(displayEmail.fullBody))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load email HTML", e)
            }
        }

        // No sender label in the top bar: the pinned header already shows it.
        updateCustomTopBar("", inMailbox = false)
        searchChipsScroll.visibility = View.GONE


        markSeenJob?.cancel()
        if (!email.seen) {
            val account = connectedAccount
            if (account != null) {
                markSeenJob = lifecycleScope.launch {
                    if (markReadDelaySeconds > 0) delay(markReadDelaySeconds * 1000L)
                    // The user may have swiped away or closed the detail while waiting.
                    if (currentDetailEmail?.id != email.id) return@launch

                    // 1. Optimistic local UI update
                    email.seen = true
                    PendingMutations.markSeen(email.id, true)
                    emailAdapter.notifyItemsChangedByIds(listOf(email.id))
                    saveEmailCache()

                    // 2. Asynchronous JMAP server update
                    try {
                        jmapClient.setSeen(account, email.id, true)
                    } catch (e: Exception) {
                        PendingMutations.forget(email.id)
                        Log.e(TAG, "Failed to mark email seen on server", e)
                    }
                }
            }
        }

        // Prefetch adjacent emails so swipe navigation loads instantly.
        val curIdx = emails.indexOfFirst { it.id == email.id }
        if (curIdx >= 0) {
            emails.getOrNull(curIdx - 1)?.let { prefetchEmailBody(it) }
            emails.getOrNull(curIdx + 1)?.let { prefetchEmailBody(it) }
        }
    }



    /** Requests READ/WRITE_CALENDAR; invokes [onResult] once the user responds. */

    internal var calendarPermissionCallback: (() -> Unit)? = null
    internal val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> calendarPermissionCallback?.invoke(); calendarPermissionCallback = null }

    /** Requests READ/WRITE_CONTACTS; invokes [onResult] once the user responds. */
    internal var contactsPermissionCallback: (() -> Unit)? = null
    internal val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> contactsPermissionCallback?.invoke(); contactsPermissionCallback = null }

    internal fun requestContactsPermissions(onResult: () -> Unit) {
        contactsPermissionCallback = onResult
        contactsPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS
        ))
    }

    /** Reflects the selected calendar provider in the dropdown text, hint, add button + account. */

    /** Handles a provider switch: warn here (not in the calendar tab) when the choice can't sync. */

    /** App-styled bottom in-app message (matches the snackbars used elsewhere). */




    /**
     * Theme-aware snackbar with an optional action button that can show a leading icon.
     * Background and text colours follow the active theme; the action uses the accent colour.
     */

    /** Extension-visible wrapper (label helpers live in LabelHelper.kt). */


    /**
     * Grows the page size when the user scrolls near the bottom, so more emails
     * load on demand instead of being capped at the first page. The periodic sync
     * loop refetches the folder with the larger [emailLimit].
     */

    /** Long-press drag to reorder label rows inside the drawer's internal RecyclerView. */



    /** Cache bucket for a folder, scoped per account (or "unified" for the merged inbox). */

    /** Display the persisted snapshot for a folder before the network responds. */

    /** Persist a freshly fetched folder snapshot for offline viewing. */



    /** Collapses the scope chips bar height + fades it, so it retracts up behind the top bar. */

    // Search scope chips: label -> drawer folder id (null = search everywhere).
    internal val searchScopes = listOf<Pair<String, Int?>>(
        "All" to null,
        "Inbox" to R.id.nav_inbox,
        "Favorite" to R.id.nav_favourite,
        "Archive" to R.id.nav_archive,
        "Sent" to R.id.nav_sent,
        "Trash" to R.id.nav_trash
    )


    /** Emails to search through, based on the selected scope chip. */


    /**
     * Removes the given emails from the visible list (and the search base list) with a
     * per-row removal animation. Call any clearSelection()/ActionMode.finish() BEFORE this,
     * since those trigger a full notifyDataSetChanged that would cancel the animation.
     */

    /** Overlays pending favorite toggles on freshly synced data until the server reflects them. */

    /** Inserts a just-saved draft into the Drafts list immediately, before the next sync. */
    internal fun insertOptimisticDraft(
        to: String,
        subject: String,
        bodyHtml: String,
        accountEmail: String,
        removeId: String?,
        cc: String = "",
        bcc: String = "",
        attachments: List<EmailAttachmentInfo> = emptyList()
    ): String {
        @Suppress("DEPRECATION")
        val plain = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY)
        else
            Html.fromHtml(bodyHtml)).toString().trim()

        val localId = "local-draft-" + System.currentTimeMillis()
        val draft = DisplayEmail(
            id = localId,
            subject = if (subject.isBlank()) "(No Subject)" else subject,
            from = accountEmail,
            fromEmail = accountEmail,
            preview = plain.take(140),
            fullBody = bodyHtml,
            seen = true,
            isFavorite = false,
            receivedAt = System.currentTimeMillis(),
            toEmail = to,
            ccEmail = cc,
            bccEmail = bcc,
            attachments = attachments
        )
        val current = (folderCache[R.id.nav_drafts] ?: emptyList()).toMutableList()
        if (removeId != null) current.removeAll { it.id == removeId }
        current.add(0, draft)
        folderCache[R.id.nav_drafts] = current
        if (selectedFolder == R.id.nav_drafts) updateEmailsList(current)
        return localId
    }

    /**
     * Swaps a just-saved draft's local placeholder id for its real server id once
     * [JMapClient.saveDraft] returns it, so opening the draft right after saving
     * fetches the body/attachments by an id the server actually recognises instead
     * of silently failing on the throwaway local-draft-<timestamp> id.
     */
    internal fun replaceOptimisticDraftId(localId: String, realId: String) {
        val current = folderCache[R.id.nav_drafts] ?: return
        folderCache[R.id.nav_drafts] = current.map { if (it.id == localId) it.copy(id = realId) else it }
        if (selectedFolder == R.id.nav_drafts) {
            val idx = emails.indexOfFirst { it.id == localId }
            if (idx >= 0) {
                emails[idx] = emails[idx].copy(id = realId)
                emailAdapter.notifyItemChanged(idx)
            }
        }
        saveEmailCache()
    }

    /**
     * True when deleting this email has to be permanent because it already sits in Trash.
     * Search results mix folders, so there the row's own origin decides, not [selectedFolder].
     */
    internal fun isTrashedEmail(email: DisplayEmail): Boolean =
        selectedFolder == R.id.nav_trash ||
            (isSearchActive && email.originFolderId == R.id.nav_trash)

    /**
     * True when the top-bar action for this email is "move back to Inbox" instead of
     * "archive". In search the list mixes folders, so the row's own origin decides:
     * an archived or trashed hit gets restored, everything else gets archived.
     */
    internal fun isRestorableEmail(email: DisplayEmail): Boolean =
        selectedFolder == R.id.nav_archive ||
            (isSearchActive &&
                (email.originFolderId == R.id.nav_archive ||
                    email.originFolderId == R.id.nav_trash))

    /** True when the email already sits in Spam — the overflow entry then reads "Not spam". */
    internal fun isSpamEmail(email: DisplayEmail): Boolean =
        selectedFolder == R.id.nav_spam ||
            (isSearchActive && email.originFolderId == R.id.nav_spam)

    /** True when the email already sits in Archive — used to disable a redundant swipe/action. */
    internal fun isArchivedEmail(email: DisplayEmail): Boolean =
        selectedFolder == R.id.nav_archive ||
            (isSearchActive && email.originFolderId == R.id.nav_archive)

    /** Asks for confirmation, then permanently destroys emails (used in Trash). */
    internal fun confirmPermanentDelete(account: JMapClient.ConnectedAccount, ids: List<String>) {
        showThemedConfirmDialog(
            title = "Delete permanently",
            message = if (ids.size == 1)
                "Permanently delete this email? This can't be undone."
            else
                "Permanently delete ${ids.size} emails? This can't be undone.",
            confirmLabel = "Delete",
            isDangerous = true
        ) {
            clearSelection()
            removeEmailsAnimated(ids)
            PendingMutations.markDestroyed(ids)
            // Outside Trash (e.g. deleting a trashed hit from search results) the visible
            // list isn't the Trash folder, so drop the ids from its cache instead.
            folderCache[R.id.nav_trash] =
                if (selectedFolder == R.id.nav_trash) emails.toList()
                else (folderCache[R.id.nav_trash] ?: emptyList()).filterNot { it.id in ids }
            saveEmailCache()
            lifecycleScope.launch {
                ids.forEach {
                    try { jmapClient.destroyEmail(account, it) }
                    catch (e: Exception) { Log.e(TAG, "destroyEmail failed", e) }
                }
            }
        }
    }

    internal fun performAction(action: String) {
        val account = connectedAccount ?: return
        var ids = selectedEmails.toList()
        if (ids.isEmpty()) return

        if (selectedFolder == R.id.nav_drafts && (action == "archive" || action == "toggleRead")) {
            showThemedSnackbar("Not available for drafts")
            clearSelection()
            return
        }

        if (action == "archive" || action == "unarchive") {
            // The action follows each email's own folder, not the visible one: in search
            // a trashed/archived hit must go back to the Inbox even from the inbox view.
            val restorableIds = emails.filter { it.id in ids && isRestorableEmail(it) }
                .map { it.id }
                .toSet()
            val archivableIds = ids.filterNot { it in restorableIds }
            if (restorableIds.isNotEmpty() && archivableIds.isNotEmpty()) {
                selectedEmails.clear()
                selectedEmails.addAll(restorableIds)
                performAction("unarchive")
                selectedEmails.clear()
                selectedEmails.addAll(archivableIds)
                performAction("archive")
                return
            }
            val resolved = if (restorableIds.isNotEmpty()) "unarchive" else "archive"
            if (resolved != action) {
                performAction(resolved)
                return
            }
        }

        if (action == "delete") {
            // A search selection can mix trashed and non-trashed hits: the trashed ones
            // need the permanent-delete confirmation, the rest just move to Trash.
            val trashedIds = emails.filter { it.id in ids && isTrashedEmail(it) }.map { it.id }
            if (trashedIds.size == ids.size) {
                confirmPermanentDelete(account, ids)
                return
            }
            if (trashedIds.isNotEmpty()) {
                confirmPermanentDelete(account, trashedIds)
                selectedEmails.removeAll(trashedIds.toSet())
                ids = ids - trashedIds.toSet()
            }
        }

        when (action) {
            "archive", "delete" -> {
                // Archiving from Favourites keeps the email flagged, so it must stay
                // visible there (an email can be both favourited and archived).
                if (action == "archive" && selectedFolder == R.id.nav_favourite) {
                    clearSelection()
                    emailAdapter.notifyItemsChangedByIds(ids)
                    showThemedSnackbar("Moved to Archive")
                    lifecycleScope.launch {
                        try {
                            ids.forEach { id ->
                                val acc = resolveAccountForId(id) ?: return@forEach
                                val archiveId = resolveOrCreateArchive(acc) ?: return@forEach
                                jmapClient.setMailbox(acc, id, archiveId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Archive from favourites failed", e)
                        }
                    }
                    return
                }
                val movedEmails = emails.filter { it.id in ids }
                // Resolve accounts before removeEmailsAnimated wipes baseEmails
                val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: connectedAccount) }
                clearSelection()
                removeEmailsAnimated(ids)
                val targetNavId = if (action == "archive") R.id.nav_archive else R.id.nav_trash
                movedEmails.forEach { updateFolderCachesForMove(it, targetNavId) }
                saveEmailCache()

                lifecycleScope.launch {
                    try {
                        when (action) {
                            "archive" -> ids.forEach { id ->
                                val acc = accountsById[id] ?: return@forEach
                                val archiveId = resolveOrCreateArchive(acc) ?: return@forEach
                                jmapClient.setMailbox(acc, id, archiveId)
                            }
                            "delete" -> ids.forEach { id ->
                                val acc = accountsById[id] ?: return@forEach
                                val trashId = resolveMailboxIdByRole(acc, "trash") ?: return@forEach
                                jmapClient.setMailbox(acc, id, trashId)
                            }
                        }
                    } catch (e: Exception) {
                        PendingMutations.forget(ids)
                        Log.e(TAG, "Action failed", e)
                    }
                }
            }
            "unarchive" -> {
                val movedEmails = emails.filter { it.id in ids }
                // Resolve accounts before removeEmailsAnimated wipes baseEmails
                val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: connectedAccount) }
                clearSelection()
                removeEmailsAnimated(ids)
                movedEmails.forEach { updateFolderCachesForInbox(it) }
                saveEmailCache()
                lifecycleScope.launch {
                    try {
                        ids.forEach { id ->
                            val acc = accountsById[id] ?: return@forEach
                            val inboxId = resolveMailboxIdByRole(acc, "inbox") ?: return@forEach
                            jmapClient.setMailbox(acc, id, inboxId)
                            BackgroundEmailSyncReceiver.addToBaseline(this@MainActivity, acc.email, listOf(id))
                        }
                    } catch (e: Exception) {
                        PendingMutations.forget(ids)
                        Log.e(TAG, "Unarchive failed", e)
                    }
                }
            }
            "toggleRead" -> {
                val allSeen = ids.all { id -> baseEmails.find { it.id == id }?.seen == true }
                val newState = !allSeen
                emails.forEach { if (it.id in ids) it.seen = newState }
                baseEmails.forEach { if (it.id in ids) it.seen = newState }
                PendingMutations.markSeen(ids, newState)
                clearSelection()
                emailAdapter.notifyItemsChangedByIds(ids)
                saveEmailCache()
                lifecycleScope.launch {
                    try {
                        ids.forEach { id ->
                            val acc = resolveAccountForId(id) ?: return@forEach
                            jmapClient.setSeen(acc, id, newState)
                        }
                    }
                    catch (e: Exception) {
                        PendingMutations.forget(ids)
                        Log.e(TAG, "toggleRead failed", e)
                    }
                }
            }
            "more" -> showMoreOptionsPopup(null)
        }
    }

    /**
     * Inserts an email into a receivedAt-descending list at its correct chronological
     * position, so optimistic updates match the order the server sync will produce
     * (no visible "jump" once the background sync lands).
     */
    internal fun insertSortedByDate(
        list: List<DisplayEmail>,
        email: DisplayEmail
    ): List<DisplayEmail> {
        val result = ArrayList<DisplayEmail>(list.size + 1)
        var inserted = false
        for (e in list) {
            if (!inserted && email.receivedAt >= e.receivedAt) {
                result.add(email)
                inserted = true
            }
            result.add(e)
        }
        if (!inserted) result.add(email)
        return result
    }

    internal fun updateFolderCachesForFavorite(email: DisplayEmail, isFavorite: Boolean) {
        val favKey = R.id.nav_favourite
        if (isFavorite) {
            // Only add to favourites cache when the email is not from Trash
            if (selectedFolder != R.id.nav_trash) {
                val current = folderCache[favKey]
                if (current != null && current.none { it.id == email.id }) {
                    folderCache[favKey] = insertSortedByDate(current, email.copy(isFavorite = true))
                }
            }
        } else {
            val current = folderCache[favKey]
            if (current != null) {
                folderCache[favKey] = current.filter { it.id != email.id }
            }
        }
    }

    /** Moves an email back from Archive to the Inbox cache, keeping date order. */
    internal fun updateFolderCachesForInbox(email: DisplayEmail) {
        // Hold the move until the server confirms it, so a sync landing meanwhile
        // doesn't push the email back into Archive/Trash.
        PendingMutations.markMoved(email.id, R.id.nav_inbox)
        val archiveCurrent = folderCache[R.id.nav_archive]
        if (archiveCurrent != null) {
            folderCache[R.id.nav_archive] = archiveCurrent.filter { it.id != email.id }
        }
        // Restoring also happens from Trash (e.g. a trashed hit picked in search).
        val trashCurrent = folderCache[R.id.nav_trash]
        if (trashCurrent != null) {
            folderCache[R.id.nav_trash] = trashCurrent.filter { it.id != email.id }
        }
        val inboxCurrent = folderCache[R.id.nav_inbox]
        if (inboxCurrent != null && inboxCurrent.none { it.id == email.id }) {
            folderCache[R.id.nav_inbox] = insertSortedByDate(inboxCurrent, email)
        }
    }

    /**
     * Drawer nav id that renders [mbox], used to record where an optimistic move landed.
     * Custom folders resolve through the subfolder map; 0 for a folder with no drawer entry
     * (the rows then simply stay hidden everywhere until the server confirms).
     */
    internal fun navIdForMailbox(mbox: JMapClient.MailboxInfo): Int =
        when (mbox.role?.lowercase()) {
            "inbox" -> R.id.nav_inbox
            "archive" -> R.id.nav_archive
            "trash" -> R.id.nav_trash
            "junk", "spam" -> R.id.nav_spam
            "sent" -> R.id.nav_sent
            "drafts" -> R.id.nav_drafts
            else -> subfolderNavIds.entries.firstOrNull { it.value == mbox.id }?.key ?: 0
        }

    internal fun updateFolderCachesForMove(email: DisplayEmail, targetNavId: Int) {
        // The server call runs in the background: keep the move authoritative until it
        // lands, otherwise a sync in flight re-adds the row to the folder it just left.
        PendingMutations.markMoved(email.id, targetNavId)
        // Insert into target cache at top (if already loaded and not already present)
        if (targetNavId == R.id.nav_archive || targetNavId == R.id.nav_trash) {
            val current = folderCache[targetNavId]
            if (current != null && current.none { it.id == email.id }) {
                folderCache[targetNavId] = insertSortedByDate(current, email)
            }
        }
        // Remove from inbox and archive on delete/trash
        if (targetNavId == R.id.nav_trash) {
            val archiveCurrent = folderCache[R.id.nav_archive]
            if (archiveCurrent != null) {
                folderCache[R.id.nav_archive] = archiveCurrent.filter { it.id != email.id }
            }
            val favCurrent = folderCache[R.id.nav_favourite]
            if (favCurrent != null) {
                folderCache[R.id.nav_favourite] = favCurrent.filter { it.id != email.id }
            }
        }
        // Remove from inbox always
        val inboxCurrent = folderCache[R.id.nav_inbox]
        if (inboxCurrent != null) {
            folderCache[R.id.nav_inbox] = inboxCurrent.filter { it.id != email.id }
        }
        // Spam: also remove from archive and favourite
        if (targetNavId != R.id.nav_archive && targetNavId != R.id.nav_trash) {
            val archiveCurrent = folderCache[R.id.nav_archive]
            if (archiveCurrent != null) {
                folderCache[R.id.nav_archive] = archiveCurrent.filter { it.id != email.id }
            }
            val favCurrent = folderCache[R.id.nav_favourite]
            if (favCurrent != null) {
                folderCache[R.id.nav_favourite] = favCurrent.filter { it.id != email.id }
            }
        }
    }

    private val actionModeCallback =
            object : androidx.appcompat.view.ActionMode.Callback {
                override fun onCreateActionMode(
                        mode: androidx.appcompat.view.ActionMode,
                        menu: Menu
                ): Boolean {
                    menu.add(0, 1, 0, getString(R.string.swipe_action_archive))
                            .setIcon(R.drawable.ic_lucide_archive)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, 2, 0, getString(R.string.swipe_action_delete))
                            .setIcon(R.drawable.ic_lucide_trash)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, 3, 0, "Mark Unread")
                            .setIcon(R.drawable.ic_lucide_eye)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, 6, 0, "More")
                            .setIcon(R.drawable.ic_lucide_more_vertical)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    val barTint = getOnAccentColor()
                    for (i in 0 until menu.size()) {
                        menu.getItem(i).icon?.mutate()?.setTint(barTint)
                    }
                    tintActionModeBar()
                    return true
                }

                override fun onPrepareActionMode(
                        mode: androidx.appcompat.view.ActionMode,
                        menu: Menu
                ): Boolean {
                    val allSeen =
                            selectedEmails.isNotEmpty() &&
                                    selectedEmails.all { id ->
                                        emails.find { it.id == id }?.seen == true
                                    }
                    menu.findItem(3)?.title = if (allSeen) "Mark Unread" else "Mark Read"
                    return true
                }

                override fun onActionItemClicked(
                        mode: androidx.appcompat.view.ActionMode,
                        item: MenuItem
                ): Boolean {
                    val account = connectedAccount ?: return false
                    val ids = selectedEmails.toList()

                    if (item.itemId == 6) {
                        showMoreOptionsPopup(mode)
                        return true
                    }

                    mode.finish()
                    val movedEmails = emails.filter { it.id in ids }
                    val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: account) }
                    when (item.itemId) {
                        1 -> { // Archive
                            // Archiving from Favourites keeps the email flagged, so it
                            // must stay visible there instead of being removed.
                            val keepVisible = selectedFolder == R.id.nav_favourite
                            if (!keepVisible) {
                                removeEmailsAnimated(ids)
                                PendingMutations.markMoved(ids, R.id.nav_archive)
                                saveEmailCache()
                            }

                            lifecycleScope.launch {
                                try {
                                    ids.forEach { id ->
                                        val acc = accountsById[id] ?: return@forEach
                                        val archiveId = resolveOrCreateArchive(acc) ?: return@forEach
                                        jmapClient.setMailbox(acc, id, archiveId)
                                    }
                                } catch (e: Exception) {
                                    PendingMutations.forget(ids)
                                    Log.e(TAG, "Failed to archive selection", e)
                                }
                            }
                        }
                        2 -> { // Delete
                            removeEmailsAnimated(ids)
                            PendingMutations.markMoved(ids, R.id.nav_trash)
                            saveEmailCache()

                            lifecycleScope.launch {
                                try {
                                    ids.forEach { id ->
                                        val acc = accountsById[id] ?: return@forEach
                                        val trashId = resolveMailboxIdByRole(acc, "trash") ?: return@forEach
                                        jmapClient.setMailbox(acc, id, trashId)
                                    }
                                } catch (e: Exception) {
                                    PendingMutations.forget(ids)
                                    Log.e(TAG, "Failed to delete selection", e)
                                }
                            }
                        }
                        3 -> { // Toggle Read/Unread
                            val allSeen = ids.all { id -> emails.find { e -> e.id == id }?.seen == true }
                            val newState = !allSeen
                            emails.forEach { if (it.id in ids) it.seen = newState }
                            PendingMutations.markSeen(ids, newState)
                            emailAdapter.notifyItemsChangedByIds(ids)
                            saveEmailCache()

                            lifecycleScope.launch {
                                try {
                                    ids.forEach { id ->
                                        val acc = resolveAccountForId(id) ?: return@forEach
                                        jmapClient.setSeen(acc, id, newState)
                                    }
                                } catch (e: Exception) {
                                    PendingMutations.forget(ids)
                                    Log.e(TAG, "Failed to toggle seen state for selection", e)
                                }
                            }
                        }
                    }
                    return true
                }

                override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
                    val wasSelected = selectedEmails.toList()
                    selectedEmails.clear()
                    emailAdapter.notifyItemsChangedByIds(wasSelected)
                }
            }

    internal suspend fun resolveMailboxIdByRole(
            account: JMapClient.ConnectedAccount,
            role: String
    ): String? {
        val fromQuery = jmapClient.resolveMailboxIdByRole(account, role)
        if (fromQuery != null) return fromQuery
        // Some servers don't assign the IMAP special-use role, so the query returns
        // nothing. Fall back to fetching all mailboxes and matching by role, then by name.
        return try {
            val mailboxes = jmapClient.fetchMailboxes(account)
            mailboxes.firstOrNull { it.role?.lowercase() == role.lowercase() }?.id
                ?: mailboxes.firstOrNull { mbox ->
                    mailboxNameMatchesRole(mbox.name, role)
                }?.id
                ?: run {
                    Log.w(TAG, "resolveMailboxIdByRole: no '$role' mailbox for ${LogRedact.email(account.email)}; " +
                            "available=${mailboxes.joinToString { "${it.name}/${it.role}" }}")
                    null
                }
        } catch (e: Exception) {
            Log.w(TAG, "resolveMailboxIdByRole fallback failed for $role", e)
            null
        }
    }

    /**
     * Resolves the archive mailbox for an account, creating it if the server doesn't ship
     * one. Stalwart (and some other servers) don't provision an Archive folder by default
     * but recognise the "archive" special-use role, so we create it on first use.
     */
    internal suspend fun resolveOrCreateArchive(account: JMapClient.ConnectedAccount): String? {
        resolveMailboxIdByRole(account, "archive")?.let { return it }
        val created = jmapClient.createMailbox(account, "Archive", "archive")
        if (created != null) {
            Log.i(TAG, "Created Archive mailbox for ${account.email}")
            // Invalidate cached mailbox list so the new folder shows up in pickers.
            mailboxCache = null
        } else {
            Log.w(TAG, "Could not resolve or create Archive mailbox for ${LogRedact.email(account.email)}")
        }
        return created
    }

    /** Heuristic name match for servers that don't expose IMAP special-use roles. */
    private fun mailboxNameMatchesRole(name: String, role: String): Boolean {
        val n = name.trim().lowercase()
        val candidates = when (role.lowercase()) {
            "archive" -> listOf("archive", "archived", "all mail", "archivio")
            "junk", "spam" -> listOf("junk", "spam", "junk e-mail", "junk email", "posta indesiderata")
            "trash" -> listOf("trash", "deleted", "deleted items", "bin", "cestino")
            "sent" -> listOf("sent", "sent items", "sent mail", "posta inviata", "inviata")
            "drafts" -> listOf("drafts", "draft", "bozze")
            "inbox" -> listOf("inbox", "posta in arrivo")
            else -> listOf(role.lowercase())
        }
        return candidates.any { n == it }
    }

    /**
     * One-shot WebView disk cache purge, posted past the first frame. The flag is
     * versioned so a future cleanup can re-run by bumping the key.
     */
    private fun purgeWebViewCacheOnce() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_WEBVIEW_CACHE_CLEARED, false)) return
        window.decorView.post {
            try {
                android.webkit.WebView(this).apply { clearCache(true); destroy() }
                prefs.edit().putBoolean(KEY_WEBVIEW_CACHE_CLEARED, true).apply()
            } catch (_: Exception) {
                // Leave the flag unset so the purge is retried on the next launch.
            }
        }
    }

    /**
     * Repaints the avatars of the rows currently on screen. The contact photo index
     * lands after the first rows are bound; off-screen rows pick the photo up when
     * they are bound, so only the visible range needs the nudge.
     */
    internal fun rebindVisibleAvatars() {
        if (!::emailAdapter.isInitialized || !::emailsRecyclerView.isInitialized) return
        // A notify during a layout pass corrupts RecyclerView child state; defer past it.
        emailsRecyclerView.post {
            val lm = emailsRecyclerView.layoutManager as? LinearLayoutManager ?: return@post
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION || last < first) return@post
            emailAdapter.notifyItemRangeChanged(first, last - first + 1, EmailAdapter.PAYLOAD_AVATAR)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {}
    }

    internal fun startPeriodicSync() {
        syncJob?.cancel()
        requestBatteryOptimizationExemption()
        val account = connectedAccount ?: return
        val currentFolderId = selectedFolder
        val role = getFolderRole(selectedFolder)
        val isFav = selectedFolder == R.id.nav_favourite
        val isInbox = selectedFolder == R.id.nav_inbox
        val isUnifiedInbox = selectedFolder == R.id.nav_unified_inbox
        val labelKeyword = labelNavIds[selectedFolder]
        val subfolderMailboxId = subfolderNavIds[selectedFolder]
        val folderTitle = getCurrentMailboxTitle()

        syncJob =
                lifecycleScope.launch {
                    // Refresh mailbox list each sync cycle so new/deleted folders appear promptly.
                    // Compare by id set, not list equality: the server may return mailboxes
                    // in a different order each call, and a list-equality check would then
                    // force a menu rebuild every cycle — fighting the user's local drag
                    // reorder (subfolderDisplayOrder) with a visible flicker/reset.
                    runCatching {
                        val fresh = jmapClient.fetchMailboxes(account)
                        val freshIds = fresh.map { it.id to it.name to it.parentId to it.role }.toSet()
                        val cachedIds = mailboxCache?.map { it.id to it.name to it.parentId to it.role }?.toSet()
                        mailboxCache = fresh
                        if (freshIds != cachedIds) {
                            navigationView.post { rebuildDrawerMenu() }
                        }
                    }
                    // Account-wide Email state seen at the last full fetch; when the
                    // cheap probe matches, the heavy refetch is skipped entirely.
                    val lastEmailStates = HashMap<String, String>()
                    while (true) {
                        try {
                            if (folderCache[currentFolderId] == null) {
                                status.text =
                                        getString(R.string.status_sync_contacting, folderTitle, debugTs())
                            }

                            if (isUnifiedInbox) {
                                val allAccounts = BackgroundEmailSyncReceiver.readAllAccounts(this@MainActivity)
                                val freshStates = fetchEmailStates(allAccounts)
                                if (folderCache[currentFolderId] != null &&
                                            emailStatesUnchanged(allAccounts, freshStates, lastEmailStates)
                                ) {
                                    delay(SYNC_POLL_INTERVAL_MS)
                                    continue
                                }
                                lastEmailStates.putAll(freshStates)
                                val merged = allAccounts.flatMap { acc ->
                                    try {
                                        val base = jmapClient.fetchEmails(acc, limit = emailLimit).map { e ->
                                            DisplayEmail(
                                                e.id, e.subject, e.from, e.fromEmail,
                                                e.preview, e.fullBody, e.seen, e.isStarred,
                                                e.receivedAt, e.toEmail,
                                                ccEmail = e.ccEmail, bccEmail = e.bccEmail,
                                                attachments = e.attachments,
                                                accountEmail = acc.email,
                                                labels = e.keywords.toList(),
                                                threadId = e.threadId
                                            )
                                        }
                                        // Chat-style threading: pull replies from other mailboxes
                                        // (e.g. Sent) so a conversation groups under one head, same
                                        // as the single-account inbox does.
                                        val threadIds = base.mapNotNull { it.threadId.ifBlank { null } }.toSet()
                                        val haveIds = base.map { it.id }.toSet()
                                        val extra = try {
                                            jmapClient.fetchThreadMembers(acc, threadIds, haveIds).map {
                                                DisplayEmail(
                                                    it.id, it.subject, it.from, it.fromEmail, it.preview,
                                                    it.fullBody, it.seen, it.isStarred, it.receivedAt, it.toEmail,
                                                    ccEmail = it.ccEmail, bccEmail = it.bccEmail,
                                                    attachments = it.attachments, accountEmail = acc.email,
                                                    labels = it.keywords.toList(), threadId = it.threadId
                                                )
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (_: Exception) { emptyList() }
                                        base + extra
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }.sortedByDescending { it.receivedAt }
                                    .let { PendingMutations.apply(it, currentFolderId) }
                                folderCache[currentFolderId] = merged
                                updateEmailsList(merged)
                                persistOfflineCache(currentFolderId, merged)
                                status.text = if (merged.isEmpty())
                                    getString(R.string.status_sync_ok_empty, folderTitle, debugTs())
                                else getString(R.string.status_sync_ok, merged.size, debugTs(), folderTitle)
                                delay(SYNC_POLL_INTERVAL_MS)
                                continue
                            }

                            val freshStates = fetchEmailStates(listOf(account))
                            if (folderCache[currentFolderId] != null &&
                                        emailStatesUnchanged(listOf(account), freshStates, lastEmailStates)
                            ) {
                                delay(SYNC_POLL_INTERVAL_MS)
                                continue
                            }
                            lastEmailStates.putAll(freshStates)

                            val mailboxId =
                                    if (role != null) resolveMailboxIdByRole(account, role)
                                    else null
                            val fresh =
                                    if (labelKeyword != null) {
                                        jmapClient.fetchEmailsByKeyword(account, labelKeyword, emailLimit)
                                    } else if (subfolderMailboxId != null) {
                                        jmapClient.fetchEmails(account, subfolderMailboxId, emailLimit)
                                    } else if (isFav) {
                                        jmapClient.fetchStarredEmails(account, emailLimit)
                                    } else if (isInbox) {
                                        jmapClient.fetchEmails(account, limit = emailLimit)
                                    } else if (role != null && mailboxId == null) {
                                        emptyList()
                                    } else {
                                        jmapClient.fetchEmails(account, mailboxId, emailLimit)
                                    }

                            val newEmailsList =
                                    fresh.map {
                                        DisplayEmail(
                                                it.id,
                                                it.subject,
                                                it.from,
                                                it.fromEmail,
                                                it.preview,
                                                it.fullBody,
                                                it.seen,
                                                it.isStarred,
                                                it.receivedAt,
                                                it.toEmail,
                                                ccEmail = it.ccEmail,
                                                bccEmail = it.bccEmail,
                                                attachments = it.attachments,
                                                accountEmail = account.email,
                                                labels = it.keywords.toList(),
                                                threadId = it.threadId
                                        )
                                    }
                            // Chat-style threading: pull in replies that live in other
                            // mailboxes (e.g. Sent) so a conversation groups under one head.
                            val threadedList = if (isInbox) {
                                val threadIds = newEmailsList.mapNotNull { it.threadId.ifBlank { null } }.toSet()
                                val haveIds = newEmailsList.map { it.id }.toSet()
                                val extra = try {
                                    jmapClient.fetchThreadMembers(account, threadIds, haveIds).map {
                                        DisplayEmail(
                                            it.id, it.subject, it.from, it.fromEmail, it.preview,
                                            it.fullBody, it.seen, it.isStarred, it.receivedAt, it.toEmail,
                                            ccEmail = it.ccEmail, bccEmail = it.bccEmail,
                                            attachments = it.attachments, accountEmail = account.email,
                                            labels = it.keywords.toList(), threadId = it.threadId
                                        )
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) { emptyList() }
                                (newEmailsList + extra).sortedByDescending { it.receivedAt }
                            } else newEmailsList
                            // A window shorter than requested is the whole folder.
                            reachedFolderEnd = fresh.size < emailLimit
                            folderQueryCount = fresh.size
                            val mergedList = PendingMutations.apply(
                                applyOptimisticFavorite(threadedList, isFav), currentFolderId
                            )
                            folderCache[currentFolderId] = mergedList
                            updateEmailsList(mergedList)
                            persistOfflineCache(currentFolderId, mergedList)

                            status.text =
                                    if (fresh.isEmpty())
                                            getString(R.string.status_sync_ok_empty, folderTitle, debugTs())
                                    else getString(R.string.status_sync_ok, fresh.size, debugTs(), folderTitle)
                        } catch (_: CancellationException) {
                            return@launch
                        } catch (e: Throwable) {
                            Log.e(TAG, "Sync failed", e)
                            status.text =
                                    getString(
                                            R.string.status_sync_failed,
                                            AuthError.describe(this@MainActivity, e),
                                            debugTs()
                                    )
                            if (pendingMailboxShow) {
                                pendingMailboxShow = false
                                showMailboxScreen(skipRefresh = true)
                                loadingOverlay.animate().alpha(0f).setDuration(350).withEndAction {
                                    loadingOverlay.visibility = View.GONE
                                    loadingOverlay.alpha = 1f
                                }.start()
                            }
                        }
                        delay(SYNC_POLL_INTERVAL_MS)
                    }
                }
    }

    /** Cheap per-account Email state probe; a missing entry means the probe failed. */
    private suspend fun fetchEmailStates(
        accounts: List<JMapClient.ConnectedAccount>
    ): Map<String, String> =
            accounts.mapNotNull { acc ->
                try {
                    jmapClient.fetchEmailState(acc)?.let { acc.email to it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }.toMap()

    /** True when every account's probed state matches the one from the last full fetch. */
    private fun emailStatesUnchanged(
        accounts: List<JMapClient.ConnectedAccount>,
        fresh: Map<String, String>,
        last: Map<String, String>
    ): Boolean =
            accounts.isNotEmpty() &&
                    fresh.size == accounts.size &&
                    accounts.all { fresh[it.email] != null && fresh[it.email] == last[it.email] }

    // Foreground SSE: while the activity is STARTED, hold a direct EventSource
    // connection per account. No foreground service involved, so the Android 15
    // dataSync FGS time budget never applies here. A StateChange triggers an
    // immediate refresh; the periodic state-gated poll remains as fallback.
    private fun startForegroundSse() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val accounts = try {
                        BackgroundEmailSyncReceiver.readAllAccounts(this@MainActivity)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (accounts.isEmpty()) {
                        delay(SSE_NO_ACCOUNT_RETRY_MS)
                        continue
                    }
                    // Suspends until STOP cancels the scope; one listener per account.
                    coroutineScope {
                        accounts.forEach { acc ->
                            launch(Dispatchers.IO) { foregroundSseLoop(acc) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun foregroundSseLoop(account: JMapClient.ConnectedAccount) {
        var backoffMs = SSE_BACKOFF_INITIAL_MS
        while (true) {
            try {
                val url = JmapSse.resolveEventSourceUrl(account)
                if (url == null) {
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, SSE_BACKOFF_MAX_MS)
                    continue
                }
                backoffMs = SSE_BACKOFF_INITIAL_MS
                JmapSse.connectAndListen(account, url) { _, data ->
                    if (JmapSse.isRelevantStateChange(data)) onSseStateChange()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Foreground SSE error for ${account.email}, retry in ${backoffMs}ms", e)
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, SSE_BACKOFF_MAX_MS)
            }
        }
    }

    private fun onSseStateChange() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSseRefreshAt < SSE_REFRESH_DEBOUNCE_MS) return
        lastSseRefreshAt = now
        runOnUiThread { if (connectedAccount != null) refreshInboxNow() }
    }

    private fun loadUnifiedPushPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        unifiedPushSwitch.isChecked = prefs.getBoolean(KEY_UP_ENABLED, false)
        sseSwitch.isChecked = JmapEventSourceService.isEnabled(this)
    }

    internal fun saveUnifiedPushEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_UP_ENABLED, enabled)
                .apply()
    }

    private fun normalizeUnifiedPushLink(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val withScheme =
                if (trimmed.startsWith("http://", ignoreCase = true) ||
                                trimmed.startsWith("https://", ignoreCase = true)
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
        return try {
            val url = URL(withScheme)
            if (url.protocol != "https" || url.host.isNullOrBlank()) return null
            val topic = url.path.trim('/').ifBlank { getOrCreateUnifiedPushTopic() }
            URL("https", url.host, url.port, "/$topic").toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateUnifiedPushTopic(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_UP_AUTO_TOPIC, null)
        if (!saved.isNullOrBlank()) return saved
        val generated = "jmapjolt-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_UP_AUTO_TOPIC, generated).apply()
        return generated
    }

    private fun sendUnifiedPushTestNotification() {
        lifecycleScope.launch {
            // Registration is asynchronous: when the switch is toggled on, the fresh
            // endpoint arrives via onNewEndpoint a moment later. Poll the pref for a
            // short window so the test isn't sent to a stale/missing endpoint.
            val endpoint = withContext(Dispatchers.IO) {
                var attempt = 0
                var found: String? = null
                while (attempt < 24) { // ~12s at 500ms
                    found = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(KEY_LAST_UP_ENDPOINT, null)
                        ?.takeIf { it.isNotBlank() }
                    if (found != null) break
                    Thread.sleep(500)
                    attempt++
                }
                found
            }
            if (endpoint == null) {
                showThemedSnackbar(getString(R.string.settings_unifiedpush_waiting_endpoint))
                return@launch
            }
            // The test push travels unencrypted, so onMessage would otherwise drop it:
            // open the short window that lets exactly this payload be shown.
            UnifiedPushService.markPendingTest(this@MainActivity)
            val result =
                    withContext(Dispatchers.IO) {
                        try {
                            val connection = URL(endpoint).openConnection() as HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                            connection.outputStream.use {
                                it.write(getString(R.string.settings_unifiedpush_test_body).toByteArray())
                            }
                            val code = connection.responseCode
                            connection.disconnect()
                            TestNotificationResult(code in 200..299, code)
                        } catch (e: Throwable) {
                            Log.e(TAG, "UnifiedPush test notification failed", e)
                            TestNotificationResult(false, null)
                        }
                    }

            showThemedSnackbar(
                if (result.success) {
                    getString(R.string.settings_unifiedpush_test_sent)
                } else if (result.httpCode != null) {
                    getString(R.string.settings_unifiedpush_error_with_code, result.httpCode)
                } else {
                    getString(R.string.settings_unifiedpush_error)
                }
            )
        }
    }

    internal fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        // currentFocus is already null when the focused view was hidden first
        // (e.g. searchInput on back-arrow); the decorView token always works.
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
        currentFocus?.clearFocus()
    }

    internal fun getCategoryDisplayName(id: Int): String =
        categoryNames[id]?.takeIf { it.isNotBlank() } ?: getDefaultCategoryTitle(id)

    internal fun getDefaultCategoryTitle(id: Int): String {
        return when (id) {
            R.id.nav_unified_inbox -> "Unified Inbox"
            R.id.nav_inbox -> "Inbox"
            R.id.nav_favourite -> "Favorite"
            R.id.nav_archive -> "Archive"
            R.id.nav_sent -> "Sent"
            R.id.nav_drafts -> "Drafts"
            R.id.nav_spam -> "Spam"
            R.id.nav_trash -> "Trash"
            else -> "Folder"
        }
    }

    internal fun getCategoryIcon(id: Int): Int {
        return when (id) {
            R.id.nav_unified_inbox -> R.drawable.ic_lucide_inbox
            R.id.nav_inbox -> R.drawable.ic_lucide_inbox
            R.id.nav_favourite -> R.drawable.ic_lucide_star
            R.id.nav_archive -> R.drawable.ic_lucide_archive
            R.id.nav_sent -> R.drawable.ic_lucide_send
            R.id.nav_drafts -> R.drawable.ic_lucide_file_text
            R.id.nav_spam -> R.drawable.ic_lucide_ban
            R.id.nav_trash -> R.drawable.ic_lucide_trash
            else -> R.drawable.ic_lucide_inbox
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Only intercept home/up when drawer indicator is NOT showing (i.e. we are in a sub-view)
        if (item.itemId == android.R.id.home && !drawerToggle.isDrawerIndicatorEnabled) {
            if (settingsContainer.visibility == View.VISIBLE) {
                if (currentSettingsSection != SettingsSection.ROOT) {
                    attemptLeaveSettingsSubmenu()
                } else {
                    showMailboxScreen()
                }
                return true
            }
            if (isShowingEmailDetail) {
                closeEmailDetail()
                return true
            }
        }

        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        syncJob?.cancel()
        unregisterReceiver(pushMessageReceiver)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (settingsContainer.visibility == View.VISIBLE &&
                        currentSettingsSection != SettingsSection.ROOT
        ) {
            attemptLeaveSettingsSubmenu()
            return true
        } else if (settingsContainer.visibility == View.VISIBLE &&
                        currentSettingsSection == SettingsSection.ROOT
        ) {
            showMailboxScreen()
            return true
        } else if (isShowingEmailDetail) {
            closeEmailDetail()
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMailtoIntent(intent)
        handleWidgetIntent(intent)
        handleCalendarIntent(intent)
        applyPendingCalendarIntent()
    }

    override fun onResume() {
        super.onResume()
        onboardingPermRefresh?.invoke()
        if (JmapEventSourceService.isEnabled(this) &&
            BackgroundEmailSyncReceiver.readCurrentAccount(this) != null) {
            JmapEventSourceService.start(this)
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen: write now instead of waiting out the debounce.
        saveEmailCache(immediate = true)
    }

    override fun onStop() {
        super.onStop()
        saveEmailCache(immediate = true)
    }

    internal fun getCurrentMailboxTitle(): String {
        labelNavIds[selectedFolder]?.let { kw ->
            labelByKeyword(kw)?.let { return it.name }
        }
        subfolderNavIds[selectedFolder]?.let { mailboxId ->
            mailboxCache?.find { it.id == mailboxId }?.let { return folderDisplayName(it) }
        }
        return categoryNames[selectedFolder]?.takeIf { it.isNotBlank() }
                ?: getDefaultCategoryTitle(selectedFolder)
    }

    private class SimpleSelectionListener(private val onSelected: () -> Unit) :
            AdapterView.OnItemSelectedListener {
        private var firstEvent = true

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (firstEvent) {
                firstEvent = false
                return
            }
            onSelected()
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        internal const val TAG = "MainActivity"
        internal const val KEY_LAST_SELECTED_FOLDER = "last_selected_folder"
        private const val KEY_WEBVIEW_CACHE_CLEARED = "webview_cache_cleared_v1"
        // Fallback poll cadence; each tick is only a cheap Email-state probe,
        // the full refetch runs just when the state actually moved.
        private const val SYNC_POLL_INTERVAL_MS = 10_000L
        private const val SSE_REFRESH_DEBOUNCE_MS = 3_000L
        // A burst of swipes/stars lands within this window and produces one write.
        internal const val CACHE_SAVE_DEBOUNCE_MS = 500L
        private const val SSE_BACKOFF_INITIAL_MS = 5_000L
        private const val SSE_BACKOFF_MAX_MS = 60_000L
        private const val SSE_NO_ACCOUNT_RETRY_MS = 30_000L
        // Chat-thread expansion reveals messages this many at a time.
        internal const val THREAD_PAGE = 5
        internal const val PREFS_NAME = "mail_prefs"
        internal const val EXTRA_OPEN_CALENDAR = "open_calendar"
        internal const val EXTRA_NEW_EVENT = "open_calendar_new_event"
        internal const val EXTRA_OPEN_EVENT_START = "open_calendar_event_start"
        internal const val EXTRA_OPEN_DRAWER = "open_drawer"

        // Pull-to-refresh trigger distance (default is ~64dp; raised to avoid
        // accidental refreshes while swiping the top email row horizontally).
        internal const val PULL_TO_REFRESH_TRIGGER_DP = 160
        // Trigger the next page when within this many rows of the bottom.
        internal const val LOAD_MORE_THRESHOLD = 10

        // Detects common HTML elements so HTML fragments (no <html> root) are rendered as
        // markup instead of being escaped and shown as raw text.
        internal val HTML_MARKUP_REGEX = Regex(
            "</?(?:p|div|br|span|a|table|tr|td|th|tbody|thead|ul|ol|li|blockquote|" +
                "h[1-6]|strong|em|b|i|u|img|pre|code|font|hr|center|dl|dt|dd|figure|" +
                "article|section|head|body|html)\\b",
            RegexOption.IGNORE_CASE
        )

        private const val KEY_WELCOME_SHOWN = "welcome_shown"
        internal const val KEY_CATEGORY_ORDER = "category_order"
        private const val KEY_UP_ENABLED = "up_enabled"
        private const val KEY_UP_MANUAL_DISTRIBUTOR = "up_manual_distributor"
        private const val KEY_UP_AUTO_TOPIC = "up_auto_topic"
        private const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"
        internal const val KEY_SWIPE_RIGHT_ACTION = "swipe_right_action"
        internal const val KEY_SWIPE_LEFT_ACTION = "swipe_left_action"
        internal const val KEY_MARK_READ_DELAY_SECONDS = "mark_read_delay_seconds"
        internal const val MARK_READ_DELAY_MAX_SECONDS = 60
        internal const val KEY_ACCOUNTS_JSON = "accounts_json"
        internal const val KEY_LAST_SYNC_APP_VERSION = "last_sync_app_version"
        internal const val KEY_ACCENT_COLOR = "accent_color"
        internal const val KEY_LABELS_JSON = "labels_json"
        // Refined accents: same hue families as before, shifted to brighter,
        // slightly desaturated tones that read well on dark and light surfaces.
        val ACCENT_COLORS = listOf(
            "#3D8BFD", "#3FA65C", "#9C5BD1",
            "#E8593C", "#0FA3B1", "#D84A7F", "#F2A33C"
        )

        // Old palette -> refined palette, for migrating saved preferences.
        val LEGACY_ACCENT_MAP = mapOf(
            "#1976D2" to "#3D8BFD", "#2E7D32" to "#3FA65C", "#7B1FA2" to "#9C5BD1",
            "#D84315" to "#E8593C", "#00838F" to "#0FA3B1", "#AD1457" to "#D84A7F",
            "#F57F17" to "#F2A33C"
        )
    }

}

/** InputFilter that rejects Arabic-script characters (used for account names and labels). */
internal fun noArabicFilter(): android.text.InputFilter = android.text.InputFilter { source, start, end, _, _, _ ->
    val arabic = source.subSequence(start, end).any { ch ->
        ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' || ch in 'ࢠ'..'ࣿ' ||
            ch in 'ﭐ'..'﷿' || ch in 'ﹰ'..'﻿'
    }
    if (arabic) "" else null
}
